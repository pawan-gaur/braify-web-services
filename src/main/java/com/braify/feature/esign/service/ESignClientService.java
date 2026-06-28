package com.braify.feature.esign.service;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.SignFieldRequest;
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

        // Record view / IN_REVIEW transition
        if (doc.getStatus() == ESignDocument.Status.PENDING) {
            doc.setStatus(ESignDocument.Status.IN_REVIEW);
            doc.setViewedAt(LocalDateTime.now());
            docRepo.save(doc);
            auditService.log(doc.getId(), doc.getClientEmail(),
                    ESignAuditEvent.ActorType.CLIENT,
                    ESignAuditEvent.EventType.DOCUMENT_VIEWED, ip, ua, null);
        }

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(doc.getId());
        DocumentResponse resp = DocumentResponse.from(doc, fields, true);  // include sourcePdf for signing UI
        // Cloud-stored docs: give the signing client a pre-signed URL for the source PDF.
        resp.setSourcePdfUrl(esignStorage.sourcePresignedUrl(doc));
        return resp;
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

        field.setValue(req.getValue());
        field.setSigningMethod(ESignSignatureField.SigningMethod.valueOf(
                req.getSigningMethod().toUpperCase()));
        field.setSignedAt(LocalDateTime.now());
        fieldRepo.save(field);

        auditService.logAsync(token.getDocumentId(), token.getClientEmail(),
                ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.FIELD_SIGNED, ip, ua,
                Map.of("fieldId", fieldId, "method", req.getSigningMethod()));

        return DocumentResponse.FieldResponse.from(field);
    }

    // ── Submit all signatures ────────────────────────────────────────────────

    public DocumentResponse submitDocument(String rawJwt, String ip, String ua) {
        ESignSigningToken token = validateToken(rawJwt);
        ESignDocument doc = fetchDoc(token.getDocumentId());

        List<ESignSignatureField> fields =
                fieldRepo.findByDocumentIdOrderByPageAscYAsc(doc.getId());

        // Validate all required fields are signed
        List<String> unsigned = fields.stream()
                .filter(f -> f.isRequired() && (f.getValue() == null || f.getValue().isBlank()))
                .map(f -> f.getLabel() != null ? f.getLabel() : f.getId())
                .toList();

        if (!unsigned.isEmpty())
            throw new IllegalStateException("Required fields not signed: " + unsigned);

        // Mark token used
        tokenService.markUsed(token.getJti());

        doc.setStatus(ESignDocument.Status.SIGNED);
        doc.setSubmittedAt(LocalDateTime.now());
        docRepo.save(doc);

        auditService.log(doc.getId(), doc.getClientEmail(),
                ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.DOCUMENT_SUBMITTED, ip, ua,
                Map.of("fieldsSigned", fields.stream().filter(f -> f.getValue() != null).count()));

        // Kick off async PDF stamping + emails
        finalizeDocumentAsync(doc, fields, ip, ua);

        return DocumentResponse.from(doc, fields, false);
    }

    // ── Async finalization ───────────────────────────────────────────────────

    @Async
    public void finalizeDocumentAsync(ESignDocument doc,
                                       List<ESignSignatureField> fields,
                                       String ip, String ua) {
        try {
            // 1. Stamp signatures onto the source PDF (fetched from cloud or legacy bytes)
            byte[] sourceBytes = esignStorage.resolveSourceBytes(doc);
            byte[] signedBytes = pdfService.stampSignatures(doc, sourceBytes, fields);
            String hash = pdfService.sha256Hex(signedBytes);

            // 2. Upload the signed PDF to cloud storage; keep only the reference.
            ESignStorageService.StoredPdf ref = esignStorage.uploadSignedPdf(doc.getOrgId(), doc.getId(), signedBytes);
            doc.setSignedPdfKey(ref.storageKey());
            doc.setSignedPdfHash(hash);
            if (doc.getPdfBucket() == null)        doc.setPdfBucket(ref.bucket());
            if (doc.getPdfCloudProvider() == null) doc.setPdfCloudProvider(ref.provider());
            doc.setStatus(ESignDocument.Status.COMPLETED);
            doc.setCompletedAt(LocalDateTime.now());
            docRepo.save(doc);

            auditService.log(doc.getId(), "SYSTEM",
                    ESignAuditEvent.ActorType.SYSTEM,
                    ESignAuditEvent.EventType.PDF_GENERATED, ip, ua,
                    Map.of("signedPdfHash", hash));

            // 2. Send completion emails
            String creatorEmail = userRepo.findById(doc.getCreatedBy())
                    .map(u -> u.getEmail())
                    .orElse(null);

            if (creatorEmail != null) {
                emailService.sendCompletionEmails(doc, creatorEmail, null, signedBytes);
                auditService.log(doc.getId(), "SYSTEM",
                        ESignAuditEvent.ActorType.SYSTEM,
                        ESignAuditEvent.EventType.COMPLETION_EMAIL_SENT, ip, ua, null);
            }

        } catch (Exception e) {
            log.error("Async PDF finalization failed for doc {}: {}", doc.getId(), e.getMessage(), e);
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
     * Returns public document metadata for the verification page.
     * No authentication needed — the document ID is the access key.
     */
    public DocumentResponse verifyDocument(String docId) {
        ESignDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));

        if (doc.getStatus() != ESignDocument.Status.COMPLETED)
            throw new IllegalStateException("Document is not yet completed");

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ESignSigningToken validateToken(String rawJwt) {
        return tokenService.validateSigningToken(rawJwt)
                .orElseThrow(() -> new SecurityException("Invalid or expired signing token"));
    }

    private ESignDocument fetchDoc(String docId) {
        return docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
    }
}
