package com.braify.feature.esign.service;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.SignFieldRequest;
import com.braify.feature.esign.exception.SigningLinkException;
import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSignatureField;
import com.braify.feature.esign.model.ESignSigningToken;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import com.braify.feature.esign.repository.ESignSignatureFieldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignClientService {

    private final ESignDocumentRepository      docRepo;
    private final ESignSignatureFieldRepository fieldRepo;
    private final AppUserRepository            userRepo;
    private final ESignTokenService            tokenService;
    private final ESignPdfService              pdfService;
    private final ESignEmailService            emailService;
    private final ESignAuditService            auditService;
    private final ESignStorageService          esignStorage;

    // ── Open document (validate token, return doc + fields) ─────────────────

    public DocumentResponse openDocument(String rawJwt, String ip, String ua) {
        ESignSigningToken token = validateToken(rawJwt);
        ESignDocument doc = fetchDoc(token.getDocumentId());
        ESignDocument.Signatory signatory = resolveSignatory(doc, token);

        boolean dirty = false;

        // Mark this signatory as having viewed the document
        if (signatory != null && signatory.getStatus() == ESignDocument.SignatoryStatus.PENDING) {
            signatory.setStatus(ESignDocument.SignatoryStatus.VIEWED);
            signatory.setViewedAt(LocalDateTime.now());
            dirty = true;
        }

        // Doc-level: first open transitions PENDING → IN_REVIEW
        if (doc.getStatus() == ESignDocument.Status.PENDING) {
            doc.setStatus(ESignDocument.Status.IN_REVIEW);
            doc.setViewedAt(LocalDateTime.now());
            dirty = true;
        }

        if (dirty) {
            docRepo.save(doc);
            auditService.log(doc.getId(), token.getClientEmail(),
                    ESignAuditEvent.ActorType.CLIENT,
                    ESignAuditEvent.EventType.DOCUMENT_VIEWED, ip, ua, null);
        }

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(doc.getId());
        DocumentResponse resp = DocumentResponse.from(doc, fields, true);  // include sourcePdf for signing UI
        resp.setCurrentSignatoryId(signatory != null ? signatory.getId() : null);
        // Cloud-stored docs: give the signing client a pre-signed URL for the source PDF.
        resp.setSourcePdfUrl(esignStorage.sourcePresignedUrl(doc));
        return resp;
    }

    // ── Electronic-records-&-signatures consent (ESIGN Act §101(c) / UETA §5) ──

    /**
     * Records the signer's affirmative consent to use electronic records and signatures
     * BEFORE they sign. This is the legal prerequisite under the U.S. ESIGN Act / UETA
     * (and good practice for eIDAS): an immutable {@code CONSENT_ACCEPTED} audit event
     * (with IP / user-agent / timestamp) plus a {@code consentedAt} stamp on the signatory
     * so the consent is part of the tamper-evident record and can be reproduced.
     */
    public DocumentResponse recordConsent(String rawJwt, String ip, String ua) {
        ESignSigningToken token = validateToken(rawJwt);
        ESignDocument doc = fetchDoc(token.getDocumentId());
        ESignDocument.Signatory signatory = resolveSignatory(doc, token);

        LocalDateTime now = LocalDateTime.now();
        if (signatory != null && signatory.getConsentedAt() == null) {
            signatory.setConsentedAt(now);
            docRepo.save(doc);
        }
        auditService.log(doc.getId(), token.getClientEmail(),
                ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.CONSENT_ACCEPTED, ip, ua,
                Map.of("consent", "Agreed to use electronic records and signatures",
                       "signatory", signatory != null ? signatory.getEmail() : doc.getClientEmail()));

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(doc.getId());
        DocumentResponse resp = DocumentResponse.from(doc, fields, true);
        resp.setCurrentSignatoryId(signatory != null ? signatory.getId() : null);
        resp.setSourcePdfUrl(esignStorage.sourcePresignedUrl(doc));
        return resp;
    }

    // ── Source PDF bytes (same-origin, token-authorized) ─────────────────────

    /**
     * Returns the source (unsigned) PDF bytes for the signing UI, authorized by the signing token.
     * Served same-origin so the signer's browser can render cloud-stored PDFs page-by-page without
     * needing bucket CORS (a cross-origin pre-signed URL fails to load in pdf.js otherwise).
     */
    public byte[] getSourcePdfBytes(String rawJwt) {
        ESignSigningToken token = validateToken(rawJwt);
        ESignDocument doc = fetchDoc(token.getDocumentId());
        return esignStorage.resolveSourceBytes(doc);
    }

    // ── Read-only view (CC recipients) ───────────────────────────────────────

    /**
     * Returns document metadata + fields for a read-only viewer, authorized by a view token.
     * No signing is possible with a view token. Used by CC recipients' "view document" links.
     */
    public DocumentResponse openForView(String rawViewJwt) {
        String docId = tokenService.validateViewToken(rawViewJwt)
                .orElseThrow(() -> new SecurityException("Invalid or expired view link"));
        ESignDocument doc = fetchDoc(docId);
        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    /**
     * Returns the PDF bytes for the read-only viewer: the signed PDF once COMPLETED, otherwise the
     * source PDF. Served same-origin so cloud-stored PDFs render without bucket CORS.
     */
    public byte[] getViewPdfBytes(String rawViewJwt) {
        String docId = tokenService.validateViewToken(rawViewJwt)
                .orElseThrow(() -> new SecurityException("Invalid or expired view link"));
        ESignDocument doc = fetchDoc(docId);
        return doc.getStatus() == ESignDocument.Status.COMPLETED
                ? esignStorage.resolveSignedBytes(doc)
                : esignStorage.resolveSourceBytes(doc);
    }

    // ── Sign a single field ──────────────────────────────────────────────────

    public DocumentResponse.FieldResponse signField(String rawJwt,
                                                     String fieldId,
                                                     SignFieldRequest req,
                                                     String ip, String ua) {
        ESignSigningToken token = validateToken(rawJwt);

        ESignSignatureField field = fieldRepo.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("Field not found: " + fieldId));

        if (!field.getDocumentId().equals(token.getDocumentId()))
            throw new SecurityException("Field does not belong to the document in this token");

        // Creator pre-filled fields are read-only to signers.
        if (field.getFilledBy() == ESignSignatureField.FilledBy.CREATOR)
            throw new SecurityException("This field is pre-filled by the sender and cannot be signed");

        // A signatory may only sign fields assigned to them (legacy tokens / unassigned fields are unrestricted).
        if (token.getSignatoryId() != null && field.getSignatoryId() != null
                && !token.getSignatoryId().equals(field.getSignatoryId()))
            throw new SecurityException("This field belongs to a different signatory");

        field.setValue(req.getValue());
        field.setSigningMethod(ESignSignatureField.SigningMethod.valueOf(
                req.getSigningMethod().toUpperCase()));
        field.setSignedAt(LocalDateTime.now());
        field.setSignedTimeZone(req.getTimeZone());   // signer's browser timezone, for display
        if (req.getFontSize() != null && req.getFontSize() > 0)
            field.setFontSize(req.getFontSize());     // signer's font-size override for TEXT/DATE
        fieldRepo.save(field);

        auditService.logAsync(token.getDocumentId(), token.getClientEmail(),
                ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.FIELD_SIGNED, ip, ua,
                Map.of("fieldId", fieldId, "method", req.getSigningMethod()));

        // Resolve the field owner's display name for the signature caption.
        ESignDocument doc = fetchDoc(token.getDocumentId());
        ESignDocument.Signatory signatory = resolveSignatory(doc, token);
        String signerName = signatory != null ? signatory.getName() : doc.getClientName();

        return DocumentResponse.FieldResponse.from(field, signerName);
    }

    // ── Submit all signatures ────────────────────────────────────────────────

    public DocumentResponse submitDocument(String rawJwt, String ip, String ua) {
        ESignSigningToken token = validateToken(rawJwt);
        ESignDocument doc = fetchDoc(token.getDocumentId());
        ESignDocument.Signatory signatory = resolveSignatory(doc, token);

        List<ESignSignatureField> allFields =
                fieldRepo.findByDocumentIdOrderByPageAscYAsc(doc.getId());

        // Validate THIS signatory's required fields are signed (others may still be pending)
        List<ESignSignatureField> myFields = fieldsForSignatory(doc, allFields, signatory);
        List<String> unsigned = myFields.stream()
                .filter(f -> f.isRequired() && (f.getValue() == null || f.getValue().isBlank()))
                .map(f -> f.getLabel() != null ? f.getLabel() : f.getId())
                .toList();

        if (!unsigned.isEmpty())
            throw new IllegalStateException("Required fields not signed: " + unsigned);

        // Consume this signatory's token + mark them signed
        tokenService.markUsed(token.getJti());
        if (signatory != null) {
            signatory.setStatus(ESignDocument.SignatoryStatus.SIGNED);
            signatory.setSignedAt(LocalDateTime.now());
        }

        auditService.log(doc.getId(), token.getClientEmail(),
                ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.DOCUMENT_SUBMITTED, ip, ua,
                Map.of("fieldsSigned", myFields.stream().filter(f -> f.getValue() != null).count()));

        boolean allSigned = doc.getSignatories() == null || doc.getSignatories().isEmpty()
                || doc.getSignatories().stream()
                       .allMatch(s -> s.getStatus() == ESignDocument.SignatoryStatus.SIGNED);

        if (allSigned) {
            doc.setStatus(ESignDocument.Status.SIGNED);
            doc.setSubmittedAt(LocalDateTime.now());
            docRepo.save(doc);
            // Stamp ALL signatories' fields into the final PDF + send completion emails
            finalizeDocumentAsync(doc, allFields, ip, ua);
        } else {
            doc.setStatus(ESignDocument.Status.PARTIALLY_SIGNED);
            docRepo.save(doc);
            // Sequential mode: hand the document to the next signatory in order
            if (doc.getSigningMode() == ESignDocument.SigningMode.SEQUENTIAL) {
                inviteNextSignatory(doc, ip, ua);
            }
        }

        return DocumentResponse.from(doc, allFields, false);
    }

    // ── Async finalization ───────────────────────────────────────────────────

    /** Max automatic finalization retries before the scheduler gives up (manual retry still works). */
    public static final int MAX_FINALIZE_ATTEMPTS = 5;

    @Async
    public void finalizeDocumentAsync(ESignDocument doc,
                                       List<ESignSignatureField> fields,
                                       String ip, String ua) {
        doFinalize(doc, fields, ip, ua);
    }

    /**
     * Re-runs finalization for a document that submitted but never reached COMPLETED (e.g. the async
     * finalize threw on a transient cloud-storage error). Safe to call repeatedly — a no-op once the
     * document is COMPLETED. Used by the manual "Finalize now" action and the retry scheduler.
     *
     * @param manual when true, resets the attempt counter so a human retry is never blocked by the cap
     * @return true if the document is COMPLETED after this call
     */
    public boolean retryFinalization(ESignDocument doc, String ip, String ua, boolean manual) {
        if (doc.getStatus() == ESignDocument.Status.COMPLETED) return true;
        if (doc.getStatus() != ESignDocument.Status.SIGNED) {
            throw new IllegalStateException(
                    "Only a fully-signed document can be finalized (this one is " + doc.getStatus() + ").");
        }
        if (manual) { doc.setFinalizeAttempts(0); docRepo.save(doc); }
        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(doc.getId());
        return doFinalize(doc, fields, ip, ua);
    }

    /** Runs the finalize body; on failure records the error + attempt count so the doc isn't silently stranded. */
    private boolean doFinalize(ESignDocument doc,
                               List<ESignSignatureField> fields,
                               String ip, String ua) {
        try {
            // Resolve the creator (sender) for the audit report + completion emails.
            var creatorOpt = userRepo.findById(doc.getCreatedBy());
            String creatorEmail = creatorOpt.map(u -> u.getEmail()).orElse(null);
            String creatorName  = creatorOpt
                    .map(u -> ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                             + (u.getLastName()  != null ? u.getLastName()  : "")).trim())
                    .filter(s -> !s.isBlank())
                    .orElse(null);

            // 1. Stamp signatures onto the source PDF (fetched from cloud or legacy bytes)
            byte[] sourceBytes = esignStorage.resolveSourceBytes(doc);
            byte[] signedBytes = pdfService.stampSignatures(doc, sourceBytes, fields, creatorName, creatorEmail);
            String hash = pdfService.sha256Hex(signedBytes);

            // 2. Upload the signed PDF to cloud storage; keep only the reference.
            ESignStorageService.StoredPdf ref = esignStorage.uploadSignedPdf(doc.getOrgId(), doc.getId(), signedBytes);
            doc.setSignedPdfKey(ref.storageKey());
            doc.setSignedPdfHash(hash);
            if (doc.getPdfBucket() == null)        doc.setPdfBucket(ref.bucket());
            if (doc.getPdfCloudProvider() == null) doc.setPdfCloudProvider(ref.provider());
            doc.setStatus(ESignDocument.Status.COMPLETED);
            doc.setCompletedAt(LocalDateTime.now());
            doc.setFinalizeError(null);   // clear any prior failure
            docRepo.save(doc);

            auditService.log(doc.getId(), "SYSTEM",
                    ESignAuditEvent.ActorType.SYSTEM,
                    ESignAuditEvent.EventType.PDF_GENERATED, ip, ua,
                    Map.of("signedPdfHash", hash));

            // 2. Send completion emails and record who was notified (per-recipient status).
            var notifications = emailService.sendCompletionEmails(doc, creatorEmail, creatorName, signedBytes);
            doc.setCompletionNotifications(notifications);
            docRepo.save(doc);
            auditService.log(doc.getId(), "SYSTEM",
                    ESignAuditEvent.ActorType.SYSTEM,
                    ESignAuditEvent.EventType.COMPLETION_EMAIL_SENT, ip, ua,
                    Map.of("recipients", notifications.size()));
            return true;

        } catch (Exception e) {
            log.error("PDF finalization failed for doc {}: {}", doc.getId(), e.getMessage(), e);
            // Record the failure so the document isn't silently stranded at SIGNED — it stays
            // retryable (manual "Finalize now" + the retry scheduler) and the error is visible.
            try {
                ESignDocument fresh = docRepo.findById(doc.getId()).orElse(doc);
                if (fresh.getStatus() == ESignDocument.Status.SIGNED) {
                    fresh.setFinalizeAttempts(fresh.getFinalizeAttempts() + 1);
                    fresh.setFinalizeError(e.getMessage() != null
                            ? (e.getMessage().length() > 500 ? e.getMessage().substring(0, 500) : e.getMessage())
                            : e.getClass().getSimpleName());
                    docRepo.save(fresh);
                }
            } catch (Exception ignore) { /* best-effort */ }
            return false;
        }
    }

    // ── Post-submission: client attachment upload ───────────────────────────

    private static final int  MAX_ATTACHMENTS = 5;
    private static final long MAX_FILE_BYTES  = 10 * 1024 * 1024L; // 10 MB

    /**
     * Allows the client to upload a supporting document after signing.
     * The signing JWT must still be cryptographically valid (not expired), but it is
     * allowed to have been already "used" (i.e. after submission).
     *
     * @return the metadata of the stored attachment (without file bytes)
     */
    public ESignDocument.ClientAttachment uploadAttachment(String rawJwt,
                                                           MultipartFile file,
                                                           String ip) throws IOException {
        // Resolve document ID from token (skips "used" check)
        String docId = tokenService.extractDocumentIdFromToken(rawJwt)
                .orElseThrow(() -> new SecurityException("Invalid or expired signing token"));

        ESignDocument doc = fetchDoc(docId);

        // Creator must have enabled client uploads for this document
        if (!doc.isAllowClientUpload()) {
            throw new IllegalStateException("Client document upload is not enabled for this document");
        }

        // Document must have been submitted
        if (doc.getStatus() == ESignDocument.Status.DRAFT
         || doc.getStatus() == ESignDocument.Status.PENDING
         || doc.getStatus() == ESignDocument.Status.IN_REVIEW) {
            throw new IllegalStateException("Attachments can only be added after the document has been submitted");
        }
        if (doc.getStatus() == ESignDocument.Status.CANCELLED) {
            throw new IllegalStateException("Cannot add attachments to a cancelled document");
        }

        // Validate limits
        List<ESignDocument.ClientAttachment> existing = doc.getClientAttachments();
        if (existing == null) existing = new ArrayList<>();
        if (existing.size() >= MAX_ATTACHMENTS)
            throw new IllegalStateException("Maximum of " + MAX_ATTACHMENTS + " attachments already reached");

        if (file.isEmpty())
            throw new IllegalArgumentException("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_BYTES)
            throw new IllegalArgumentException("File exceeds the 10 MB size limit");

        // Validate file type against the allowed-types list (if configured for this document)
        List<String> allowedTypes = doc.getAllowedClientUploadFileTypes();
        if (allowedTypes != null && !allowedTypes.isEmpty()) {
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
            int dotIdx = fileName.lastIndexOf('.');
            String ext = dotIdx >= 0 ? fileName.substring(dotIdx + 1).toLowerCase() : "";
            boolean typeAllowed = allowedTypes.stream()
                    .anyMatch(t -> t.equalsIgnoreCase(ext));
            if (!typeAllowed) {
                String allowedList = String.join(", ", allowedTypes);
                throw new IllegalArgumentException(
                        "File type '" + (ext.isEmpty() ? "(unknown)" : "." + ext) +
                        "' is not supported. Allowed types: " + allowedList);
            }
        }

        // Build and persist attachment
        ESignDocument.ClientAttachment attachment = ESignDocument.ClientAttachment.builder()
                .id(UUID.randomUUID().toString())
                .fileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .data(file.getBytes())
                .fileSize(file.getSize())
                .uploadedAt(LocalDateTime.now())
                .uploadedFromIp(ip)
                .build();

        existing.add(attachment);
        doc.setClientAttachments(existing);
        docRepo.save(doc);

        auditService.logAsync(docId, doc.getClientEmail(),
                ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.CLIENT_ATTACHMENT_UPLOADED, ip, null,
                Map.of("fileName",    attachment.getFileName(),
                       "fileSize",    attachment.getFileSize(),
                       "contentType", attachment.getContentType() != null ? attachment.getContentType() : "unknown",
                       "attachmentId", attachment.getId()));

        log.info("Client attachment '{}' ({} bytes) uploaded for doc '{}'",
                attachment.getFileName(), attachment.getFileSize(), docId);

        // Return metadata only (strip bytes to avoid sending full payload back)
        return ESignDocument.ClientAttachment.builder()
                .id(attachment.getId())
                .fileName(attachment.getFileName())
                .contentType(attachment.getContentType())
                .fileSize(attachment.getFileSize())
                .uploadedAt(attachment.getUploadedAt())
                .build();
    }

    /**
     * Returns the raw bytes of a client attachment for download.
     * Accessible by the client (via signing JWT) and by the creator (via the creator service).
     */
    public ESignDocument.ClientAttachment getClientAttachment(String rawJwt, String attachmentId) {
        String docId = tokenService.extractDocumentIdFromToken(rawJwt)
                .orElseThrow(() -> new SecurityException("Invalid or expired signing token"));

        ESignDocument doc = fetchDoc(docId);
        return doc.getClientAttachments().stream()
                .filter(a -> attachmentId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + attachmentId));
    }

    // ── Public verify ────────────────────────────────────────────────────────

    /**
     * Result of a public verification: the document metadata plus a live integrity check.
     * {@code storedHash} is the SHA-256 recorded when the PDF was finalized; {@code computedHash}
     * is re-derived here from the stored PDF bytes; {@code integrityVerified} is true only when the
     * two match (i.e. the file on storage has not been altered since signing).
     */
    public record VerificationResult(DocumentResponse document,
                                     String storedHash,
                                     String computedHash,
                                     boolean integrityVerified,
                                     boolean pdfAvailable) {}

    /**
     * Verifies a completed document for the public verification page. No authentication needed —
     * the document ID is the access key. Unlike a metadata-only lookup, this RE-HASHES the stored
     * signed PDF and compares it to the recorded hash, so the result reflects true intactness of the
     * file rather than merely echoing a stored value.
     */
    public VerificationResult verifyDocumentIntegrity(String docId) {
        ESignDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));

        if (doc.getStatus() != ESignDocument.Status.COMPLETED)
            throw new IllegalStateException("Document is not yet completed");

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        DocumentResponse dr = DocumentResponse.from(doc, fields, false);

        String storedHash = doc.getSignedPdfHash();
        String computedHash = null;
        boolean pdfAvailable = false;
        try {
            byte[] signedBytes = esignStorage.resolveSignedBytes(doc);
            if (signedBytes != null && signedBytes.length > 0) {
                computedHash = pdfService.sha256Hex(signedBytes);
                pdfAvailable = true;
            }
        } catch (Exception e) {
            // Storage unreachable / legacy doc with no signed bytes — report as "could not verify"
            // rather than failing the whole request; integrityVerified stays false.
            log.warn("Verify: could not resolve signed PDF for doc {}: {}", docId, e.getMessage());
        }

        boolean integrityVerified = pdfAvailable
                && storedHash != null && !storedHash.isBlank()
                && storedHash.equalsIgnoreCase(computedHash);

        return new VerificationResult(dr, storedHash, computedHash, integrityVerified, pdfAvailable);
    }

    // ── Reminder subscription (signer-facing opt-out from the email) ───────────

    /** Result of an unsubscribe/resubscribe action, for the confirmation page. */
    public record ReminderSubscription(String documentTitle, String email, boolean optedOut) {}

    /**
     * Turns reminder emails on/off for the signer identified by the signing token (works even if the
     * token has expired or was already used — decoded via a signature-only peek). Affects only this
     * signer on this document; they can still open and sign, and can re-enable from the same link.
     */
    public ReminderSubscription setReminderSubscription(String rawJwt, boolean optOut, String ip, String ua) {
        var peek = tokenService.peekSigningToken(rawJwt)
                .orElseThrow(() -> new SigningLinkException(SigningLinkException.Reason.INVALID,
                        "This link isn't valid."));
        ESignDocument doc = docRepo.findById(peek.documentId())
                .orElseThrow(() -> new SigningLinkException(SigningLinkException.Reason.INVALID,
                        "This link isn't valid."));

        ESignDocument.Signatory sig = doc.getSignatories() == null ? null
                : doc.getSignatories().stream()
                      .filter(s -> s.getEmail() != null && s.getEmail().equalsIgnoreCase(peek.email()))
                      .findFirst().orElse(null);
        if (sig == null)
            throw new SigningLinkException(SigningLinkException.Reason.INVALID, "This link isn't valid.");

        if (sig.isRemindersOptedOut() != optOut) {
            sig.setRemindersOptedOut(optOut);
            docRepo.save(doc);
            auditService.log(doc.getId(), sig.getEmail(),
                    ESignAuditEvent.ActorType.CLIENT,
                    ESignAuditEvent.EventType.DOCUMENT_SENT, ip, ua,
                    Map.of("action", optOut ? "REMINDERS_UNSUBSCRIBED" : "REMINDERS_RESUBSCRIBED",
                           "signatory", sig.getEmail()));
        }
        return new ReminderSubscription(doc.getTitle(), sig.getEmail(), optOut);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ESignSigningToken validateToken(String rawJwt) {
        return tokenService.validateSigningToken(rawJwt)
                .orElseThrow(() -> classifySigningFailure(rawJwt));
    }

    /**
     * Turns a failed token validation into a specific, user-facing reason by decoding the
     * document from the (possibly expired) token and inspecting its state — so the signing
     * page can say "expired", "already signed", "cancelled" or "invalid" with a clear next step.
     */
    private SigningLinkException classifySigningFailure(String rawJwt) {
        var peek = tokenService.peekSigningToken(rawJwt).orElse(null);
        if (peek == null || peek.documentId() == null) {
            return new SigningLinkException(SigningLinkException.Reason.INVALID,
                    "This signing link isn't valid.");
        }
        ESignDocument doc = docRepo.findById(peek.documentId()).orElse(null);
        if (doc == null) {
            return new SigningLinkException(SigningLinkException.Reason.INVALID,
                    "This signing link isn't valid.");
        }

        switch (doc.getStatus()) {
            case CANCELLED:
                return new SigningLinkException(SigningLinkException.Reason.CANCELLED,
                        "This document was cancelled by the sender.");
            case SIGNED:
            case COMPLETED:
                return new SigningLinkException(SigningLinkException.Reason.ALREADY_SIGNED,
                        "This document has already been signed.");
            default:
                // Document is still awaiting signatures — did THIS signer already sign,
                // or did their link simply expire / get superseded?
                boolean alreadySigned = doc.getSignatories() != null && peek.email() != null
                        && doc.getSignatories().stream().anyMatch(s ->
                                peek.email().equalsIgnoreCase(s.getEmail())
                                && s.getStatus() == ESignDocument.SignatoryStatus.SIGNED);
                if (alreadySigned) {
                    return new SigningLinkException(SigningLinkException.Reason.ALREADY_SIGNED,
                            "You have already signed this document.");
                }
                return new SigningLinkException(SigningLinkException.Reason.EXPIRED,
                        "This signing link has expired.");
        }
    }

    /** Resolves the signatory a token belongs to. Returns null for legacy single-signer tokens. */
    private ESignDocument.Signatory resolveSignatory(ESignDocument doc, ESignSigningToken token) {
        if (token.getSignatoryId() == null || doc.getSignatories() == null) return null;
        return doc.getSignatories().stream()
                .filter(s -> token.getSignatoryId().equals(s.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the fields a given signatory is responsible for. Legacy documents (or a null
     * signatory) get all fields; otherwise fields are matched by signatoryId, with any
     * unassigned (null) field defaulting to the first signatory.
     */
    private List<ESignSignatureField> fieldsForSignatory(ESignDocument doc,
                                                         List<ESignSignatureField> allFields,
                                                         ESignDocument.Signatory signatory) {
        // Creator pre-filled fields are never a signer's responsibility.
        List<ESignSignatureField> signerFields = allFields.stream()
                .filter(f -> f.getFilledBy() != ESignSignatureField.FilledBy.CREATOR)
                .toList();
        if (signatory == null || signatory.getId() == null
                || doc.getSignatories() == null || doc.getSignatories().isEmpty()) {
            return signerFields;
        }
        String firstId = doc.getSignatories().get(0).getId();
        return signerFields.stream()
                .filter(f -> {
                    String owner = f.getSignatoryId() != null ? f.getSignatoryId() : firstId;
                    return signatory.getId().equals(owner);
                })
                .toList();
    }

    /** SEQUENTIAL mode: issue a token for the next not-yet-signed signatory and email them. */
    private void inviteNextSignatory(ESignDocument doc, String ip, String ua) {
        ESignDocument.Signatory next = doc.getSignatories().stream()
                .sorted(java.util.Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder))
                .filter(s -> s.getStatus() != ESignDocument.SignatoryStatus.SIGNED)
                .findFirst()
                .orElse(null);
        if (next == null) return;

        int validDays = doc.getTokenExpiresAt() != null
                ? (int) Math.max(1, java.time.Duration.between(LocalDateTime.now(), doc.getTokenExpiresAt()).toDays())
                : 7;

        String tokenJwt = tokenService.issueSigningToken(doc, next, validDays);
        if (next.getInvitedAt() == null) next.setInvitedAt(LocalDateTime.now());
        docRepo.save(doc);   // persist the new tokenJti on the signatory
        emailService.sendSigningInvitation(doc, next.getName(), next.getEmail(), false, tokenJwt);

        auditService.logAsync(doc.getId(), next.getEmail(),
                ESignAuditEvent.ActorType.SYSTEM,
                ESignAuditEvent.EventType.DOCUMENT_SENT, ip, ua,
                Map.of("sequentialNext", next.getEmail()));
    }

    private ESignDocument fetchDoc(String docId) {
        return docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
    }
}
