package com.braify.feature.esign.service;

import com.braify.feature.esign.dto.BulkBatchResponse;
import com.braify.feature.esign.dto.BulkCreateDocumentRequest;
import com.braify.feature.esign.dto.BulkCreateDocumentResponse;
import com.braify.feature.esign.dto.BulkDocumentResult;
import com.braify.feature.esign.dto.CreateDocumentRequest;
import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.FieldPlacementRequest;
import com.braify.feature.esign.dto.PageResponse;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.quota.service.QuotaService;
import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.model.ESignBulkBatch;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSignatureField;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.esign.repository.ESignBulkBatchRepository;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import com.braify.feature.esign.repository.ESignSignatureFieldRepository;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignDocumentService {

    private final ESignDocumentRepository       docRepo;
    private final ESignBulkBatchRepository      batchRepo;
    private final ESignSignatureFieldRepository fieldRepo;
    private final TemplateRepository            templateRepo;
    private final ESignTokenService             tokenService;
    private final ESignEmailService             emailService;
    private final ESignAuditService             auditService;
    private final AuditLogService               auditLogService;
    private final QuotaService                  quotaService;
    private final ESignStorageService           esignStorage;

    // ── Create ──────────────────────────────────────────────────────────────

    /** Public entry point for single-sign document creation (no batch link unless req.bulkBatchId is set). */
    public DocumentResponse createDocument(CreateDocumentRequest req,
                                           UserDetailsImpl principal,
                                           String ip,
                                           String ua) {
        return createDocument(req, req.getBulkBatchId(), principal, ip, ua);
    }

    /** Internal entry point used by bulk processing to attach a batch ID. */
    private DocumentResponse createDocument(CreateDocumentRequest req,
                                            String bulkBatchId,
                                            UserDetailsImpl principal,
                                            String ip,
                                            String ua) {
        byte[] pdfBytes;

        if ("UPLOAD".equalsIgnoreCase(req.getSourceType())) {
            if (req.getPdfBase64() == null || req.getPdfBase64().isBlank())
                throw new IllegalArgumentException("pdfBase64 is required for UPLOAD source type");
            pdfBytes = decodeBase64Pdf(req.getPdfBase64());

        } else if ("TEMPLATE".equalsIgnoreCase(req.getSourceType())) {
            if (req.getTemplateId() == null || req.getTemplateId().isBlank())
                throw new IllegalArgumentException("templateId is required for TEMPLATE source type");
            Template tpl = templateRepo.findById(req.getTemplateId())
                    .orElseThrow(() -> new IllegalArgumentException("Template not found: " + req.getTemplateId()));
            // For now, use the template's htmlContent as a placeholder (integrate PdfGenerationService if needed)
            pdfBytes = tpl.getHtmlContent() != null ? tpl.getHtmlContent().getBytes() : new byte[0];
        } else {
            throw new IllegalArgumentException("sourceType must be UPLOAD or TEMPLATE");
        }

        // Inherit allowClientUpload and allowedClientUploadFileTypes from the parent batch
        boolean allowClientUpload = false;
        List<String> allowedClientUploadFileTypes = List.of();
        if (bulkBatchId != null && !bulkBatchId.isBlank()) {
            ESignBulkBatch parentBatch = batchRepo.findById(bulkBatchId).orElse(null);
            if (parentBatch != null) {
                allowClientUpload = parentBatch.isAllowClientUpload();
                allowedClientUploadFileTypes = parentBatch.getAllowedClientUploadFileTypes() != null
                        ? parentBatch.getAllowedClientUploadFileTypes() : List.of();
            }
        }

        // E-sign PDFs are stored in the org's cloud bucket (not embedded in Mongo).
        // Fail fast if the org hasn't configured cloud storage.
        if (!esignStorage.isCloudConfigured(principal.getOrgId())) {
            throw new RuntimeException("Cloud storage is not configured for this organisation. " +
                    "Configure it under Settings → Cloud Storage before creating e-sign documents.");
        }

        // Build the signatory list (always ≥1). Single-signer/bulk requests derive one
        // signatory from the client fields; multi-party requests use req.signatories.
        List<ESignDocument.Signatory> signatories = buildSignatories(req);
        ESignDocument.Signatory primary = signatories.get(0);   // mirrored into clientEmail/clientName
        ESignDocument.SigningMode signingMode = parseSigningMode(req.getSigningMode());

        ESignDocument doc = ESignDocument.builder()
                .createdBy(principal.getId())
                .orgId(principal.getOrgId())
                .title(req.getTitle())
                .sourceType(ESignDocument.SourceType.valueOf(req.getSourceType().toUpperCase()))
                .templateId(req.getTemplateId())
                .sourcePdfHash(sha256Hex(pdfBytes))
                .clientEmail(primary.getEmail())
                .clientName(primary.getName())
                .signatories(signatories)
                .signingMode(signingMode)
                .ccEmails(req.getCcEmails())
                .completionCcEmails(req.getCompletionCcEmails())
                .bulkBatchId(bulkBatchId)
                .emailTemplateId(req.getEmailTemplateId())
                .allowClientUpload(allowClientUpload)
                .allowedClientUploadFileTypes(allowedClientUploadFileTypes)
                .status(ESignDocument.Status.DRAFT)
                .build();

        doc = docRepo.save(doc);

        // Upload the source PDF to cloud storage; keep only the reference on the document.
        ESignStorageService.StoredPdf ref = esignStorage.uploadSourcePdf(principal.getOrgId(), doc.getId(), pdfBytes);
        doc.setSourcePdfKey(ref.storageKey());
        doc.setPdfBucket(ref.bucket());
        doc.setPdfCloudProvider(ref.provider());
        doc = docRepo.save(doc);

        auditService.log(doc.getId(), principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.DOCUMENT_CREATED, ip, ua,
                Map.of("title", doc.getTitle(), "sourceType", doc.getSourceType().name()));

        // Unified audit log entry (visible in the main Audit Log page)
        auditLogService.log(
                doc.getId(), doc.getTitle(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.E_SIGN,
                0, Map.of("sourceType", doc.getSourceType().name()),
                principal.getUsername(), principal.getOrgId());

        return DocumentResponse.from(doc, List.of(), false);
    }

    // ── Bulk Create + Send ───────────────────────────────────────────────────

    /**
     * Creates (and optionally sends) multiple e-sign documents in a single call.
     *
     * <p>Processing is sequential and best-effort: a failure on one row is
     * recorded in the result but does not abort the remaining rows.  If
     * {@code sendImmediately} is {@code true} a quota pre-flight check is run
     * first so the whole batch fails fast before any documents are created.</p>
     *
     * @param bulkReq   list of document requests + send flag
     * @param principal authenticated creator
     * @param ip        client IP (for audit trail)
     * @param ua        User-Agent header (for audit trail)
     * @return per-row results + aggregate counters
     */
    public BulkCreateDocumentResponse createAndSendBulk(
            BulkCreateDocumentRequest bulkReq,
            UserDetailsImpl principal,
            String ip, String ua) {

        List<CreateDocumentRequest> items         = bulkReq.getDocuments();
        int                         totalRequested = items.size();

        // Pre-flight: verify the org has enough quota for the whole batch before
        // creating anything.  This avoids partially-committed state where some
        // documents are created but cannot be sent due to quota exhaustion.
        if (bulkReq.isSendImmediately()) {
            quotaService.checkEsignBulkCapacity(principal.getOrgId(), totalRequested);
        }

        // ── Create a batch record to group the documents ───────────────────
        String batchLabel = (bulkReq.getLabel() != null && !bulkReq.getLabel().isBlank())
                ? bulkReq.getLabel()
                : "Bulk Send – " + LocalDateTime.now().toString().substring(0, 16).replace('T', ' ');

        ESignBulkBatch batch = batchRepo.save(ESignBulkBatch.builder()
                .createdBy(principal.getId())
                .orgId(principal.getOrgId())
                .label(batchLabel)
                .totalRequested(totalRequested)
                .allowClientUpload(bulkReq.isAllowClientUpload())
                .allowedClientUploadFileTypes(bulkReq.getAllowedClientUploadFileTypes() != null
                        ? bulkReq.getAllowedClientUploadFileTypes() : List.of())
                .status(ESignBulkBatch.Status.PROCESSING)
                .build());
        String batchId = batch.getId();

        List<BulkDocumentResult> results = new ArrayList<>(totalRequested);
        int created = 0, sent = 0, failed = 0;

        for (int i = 0; i < items.size(); i++) {
            CreateDocumentRequest item  = items.get(i);
            String                docId = null;

            // ── 1. Create document ─────────────────────────────────────────
            try {
                DocumentResponse doc = createDocument(item, batchId, principal, ip, ua);
                docId = doc.getId();
                created++;
            } catch (Exception e) {
                failed++;
                log.warn("Bulk esign row {}: create failed for '{}' — {}", i, item.getTitle(), e.getMessage());
                results.add(BulkDocumentResult.builder()
                        .rowIndex(i)
                        .title(item.getTitle())
                        .clientEmail(item.getClientEmail())
                        .success(false)
                        .error("Create failed: " + e.getMessage())
                        .build());
                continue;
            }

            // ── 2. Draft-only mode ─────────────────────────────────────────
            if (!bulkReq.isSendImmediately()) {
                results.add(BulkDocumentResult.builder()
                        .rowIndex(i)
                        .title(item.getTitle())
                        .clientEmail(item.getClientEmail())
                        .success(true)
                        .documentId(docId)
                        .status("DRAFT")
                        .build());
                continue;
            }

            // ── 3. Send document ───────────────────────────────────────────
            try {
                sendDocument(docId, item.getTokenValidDays(), principal, ip, ua);
                sent++;
                results.add(BulkDocumentResult.builder()
                        .rowIndex(i)
                        .title(item.getTitle())
                        .clientEmail(item.getClientEmail())
                        .success(true)
                        .documentId(docId)
                        .status("PENDING")
                        .build());
            } catch (Exception e) {
                // Document was created (DRAFT) but send failed — record docId so
                // the caller can retry the send individually.
                failed++;
                log.warn("Bulk esign row {}: send failed for doc '{}' — {}", i, docId, e.getMessage());
                results.add(BulkDocumentResult.builder()
                        .rowIndex(i)
                        .title(item.getTitle())
                        .clientEmail(item.getClientEmail())
                        .success(false)
                        .documentId(docId)
                        .status("DRAFT")
                        .error("Send failed: " + e.getMessage())
                        .build());
            }
        }

        log.info("Bulk e-sign for '{}': requested={} created={} sent={} failed={}",
                principal.getUsername(), totalRequested, created, sent, failed);

        // ── Update batch record with final counters ────────────────────────
        ESignBulkBatch.Status batchStatus = failed == 0 ? ESignBulkBatch.Status.COMPLETED
                : created == 0              ? ESignBulkBatch.Status.FAILED
                : ESignBulkBatch.Status.PARTIAL;

        batch.setTotalCreated(created);
        batch.setTotalSent(sent);
        batch.setTotalFailed(failed);
        batch.setStatus(batchStatus);
        batchRepo.save(batch);

        return BulkCreateDocumentResponse.builder()
                .batchId(batchId)
                .totalRequested(totalRequested)
                .totalCreated(created)
                .totalSent(sent)
                .totalFailed(failed)
                .results(results)
                .build();
    }

    // ── Field Placement ─────────────────────────────────────────────────────

    public DocumentResponse saveFields(String docId,
                                       List<FieldPlacementRequest> requests,
                                       UserDetailsImpl principal,
                                       String ip, String ua) {
        ESignDocument doc = getAccessibleDoc(docId, principal);

        // Editing is only allowed before anyone signs. Once a signatory has submitted
        // (PARTIALLY_SIGNED / SIGNED / COMPLETED) or the doc is no longer active
        // (EXPIRED / CANCELLED), the fields are frozen.
        if (doc.getStatus() == ESignDocument.Status.PARTIALLY_SIGNED ||
            doc.getStatus() == ESignDocument.Status.SIGNED ||
            doc.getStatus() == ESignDocument.Status.COMPLETED ||
            doc.getStatus() == ESignDocument.Status.EXPIRED ||
            doc.getStatus() == ESignDocument.Status.CANCELLED) {
            throw new IllegalStateException(
                    "This document can no longer be edited (status: " + doc.getStatus() + ").");
        }

        // Default unassigned fields to the first/only signatory.
        List<ESignDocument.Signatory> sigs = doc.getSignatories();
        String defaultSignatoryId = (sigs != null && !sigs.isEmpty()) ? sigs.get(0).getId() : null;

        // Replace all existing fields
        fieldRepo.deleteByDocumentId(docId);

        List<ESignSignatureField> fields = requests.stream()
                .map(r -> ESignSignatureField.builder()
                        .documentId(docId)
                        .createdBy(principal.getId())
                        .signatoryId(r.getSignatoryId() != null && !r.getSignatoryId().isBlank()
                                ? r.getSignatoryId() : defaultSignatoryId)
                        .page(r.getPage())
                        .x(r.getX()).y(r.getY())
                        .width(r.getWidth()).height(r.getHeight())
                        .fieldType(ESignSignatureField.FieldType.valueOf(r.getFieldType().toUpperCase()))
                        .label(r.getLabel())
                        .required(r.isRequired())
                        .build())
                .toList();

        List<ESignSignatureField> saved = fieldRepo.saveAll(fields);

        auditService.log(docId, principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.FIELDS_SAVED, ip, ua,
                Map.of("fieldCount", saved.size()));

        return DocumentResponse.from(doc, saved, false);
    }

    // ── Send to client ──────────────────────────────────────────────────────

    public DocumentResponse sendDocument(String docId,
                                         int tokenValidDays,
                                         UserDetailsImpl principal,
                                         String ip, String ua) {
        ESignDocument doc = getAccessibleDoc(docId, principal);

        if (doc.getStatus() == ESignDocument.Status.COMPLETED ||
            doc.getStatus() == ESignDocument.Status.CANCELLED) {
            throw new IllegalStateException("Document is already " + doc.getStatus());
        }

        // Enforce monthly e-sign quota before sending (one document = one quota unit,
        // regardless of how many signatories it has).
        quotaService.checkAndIncrementEsign(principal.getOrgId());

        doc.setStatus(ESignDocument.Status.PENDING);
        doc.setSentAt(java.time.LocalDateTime.now());
        doc.setTokenExpiresAt(java.time.LocalDateTime.now().plusDays(tokenValidDays));

        // Issue tokens + send invitations. PARALLEL = everyone now; SEQUENTIAL = first only
        // (the next signatory is invited automatically as each one submits).
        List<ESignDocument.Signatory> sigs = effectiveSignatories(doc);
        List<String> emailFailures = new ArrayList<>();
        if (doc.getSigningMode() == ESignDocument.SigningMode.SEQUENTIAL) {
            ESignDocument.Signatory first = sigs.get(0);
            String token = tokenService.issueSigningToken(doc, first, tokenValidDays);
            if (first.getInvitedAt() == null) first.setInvitedAt(LocalDateTime.now());
            if (!emailService.sendSigningInvitation(doc, first.getName(), first.getEmail(), true, token))
                emailFailures.add(first.getEmail());
        } else {
            for (int i = 0; i < sigs.size(); i++) {
                ESignDocument.Signatory s = sigs.get(i);
                String token = tokenService.issueSigningToken(doc, s, tokenValidDays);
                if (s.getInvitedAt() == null) s.setInvitedAt(LocalDateTime.now());
                if (!emailService.sendSigningInvitation(doc, s.getName(), s.getEmail(), i == 0, token))
                    emailFailures.add(s.getEmail());
            }
        }
        doc = docRepo.save(doc);   // persists tokenJti set on each signatory

        auditService.log(docId, principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.DOCUMENT_SENT, ip, ua,
                Map.of("clientEmail", doc.getClientEmail(),
                       "signatories", sigs.size(),
                       "signingMode", doc.getSigningMode().name(),
                       "tokenValidDays", tokenValidDays));

        // Unified audit log entry
        auditLogService.log(
                doc.getId(), doc.getTitle(),
                AuditLog.Action.SENT, AuditLog.ResourceType.E_SIGN,
                0, Map.of("clientEmail", doc.getClientEmail()),
                principal.getUsername(), principal.getOrgId());

        // Surface any invitations the email provider rejected — otherwise a signatory silently
        // never receives their link (commonly an unverified sending domain on the mail provider).
        if (!emailFailures.isEmpty()) {
            throw new IllegalStateException(
                    "The document was sent, but the signing invitation could not be emailed to: "
                    + String.join(", ", emailFailures)
                    + ". Verify your email sending domain/provider, then use Resend on the document.");
        }

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    // ── Resend invitation ────────────────────────────────────────────────────

    public DocumentResponse resendDocument(String docId,
                                           int tokenValidDays,
                                           UserDetailsImpl principal,
                                           String ip, String ua) {
        ESignDocument doc = getAccessibleDoc(docId, principal);

        if (doc.getStatus() == ESignDocument.Status.COMPLETED ||
            doc.getStatus() == ESignDocument.Status.CANCELLED ||
            doc.getStatus() == ESignDocument.Status.EXPIRED) {
            throw new IllegalStateException("Cannot resend a " + doc.getStatus() + " document");
        }

        // Re-invite whoever still needs to sign. SEQUENTIAL → only the current (first
        // not-yet-signed) signatory; PARALLEL → all who haven't signed. Each gets a fresh
        // token (revoking their previous one).
        doc.setSentAt(java.time.LocalDateTime.now());
        doc.setTokenExpiresAt(java.time.LocalDateTime.now().plusDays(tokenValidDays));

        List<ESignDocument.Signatory> pending = effectiveSignatories(doc).stream()
                .filter(s -> s.getStatus() != ESignDocument.SignatoryStatus.SIGNED)
                .toList();
        boolean first = true;
        for (ESignDocument.Signatory s : pending) {
            String token = tokenService.issueSigningToken(doc, s, tokenValidDays);
            if (s.getInvitedAt() == null) s.setInvitedAt(LocalDateTime.now());
            emailService.sendSigningInvitation(doc, s.getName(), s.getEmail(), first, token);
            first = false;
            if (doc.getSigningMode() == ESignDocument.SigningMode.SEQUENTIAL) break; // only the active one
        }
        doc = docRepo.save(doc);

        auditService.log(docId, principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.DOCUMENT_SENT, ip, ua,
                Map.of("clientEmail", doc.getClientEmail(), "action", "RESEND",
                       "tokenValidDays", tokenValidDays));

        // Unified audit log entry (resend = SENT action)
        auditLogService.log(
                doc.getId(), doc.getTitle(),
                AuditLog.Action.SENT, AuditLog.ResourceType.E_SIGN,
                0, Map.of("clientEmail", doc.getClientEmail(), "action", "RESEND"),
                principal.getUsername(), principal.getOrgId());

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    /**
     * Resends the signing invitation to a single signatory (fresh token, revoking their previous one).
     * In SEQUENTIAL mode only the current (first not-yet-signed) signatory can be re-invited.
     */
    public DocumentResponse resendToSignatory(String docId,
                                              String signatoryId,
                                              int tokenValidDays,
                                              UserDetailsImpl principal,
                                              String ip, String ua) {
        ESignDocument doc = getAccessibleDoc(docId, principal);

        if (doc.getStatus() == ESignDocument.Status.COMPLETED ||
            doc.getStatus() == ESignDocument.Status.CANCELLED ||
            doc.getStatus() == ESignDocument.Status.EXPIRED) {
            throw new IllegalStateException("Cannot resend a " + doc.getStatus() + " document");
        }

        List<ESignDocument.Signatory> sigs = doc.getSignatories();
        if (sigs == null || sigs.isEmpty())
            throw new IllegalArgumentException("This document has no signatories to resend to");

        ESignDocument.Signatory target = sigs.stream()
                .filter(s -> signatoryId.equals(s.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Signatory not found: " + signatoryId));

        if (target.getStatus() == ESignDocument.SignatoryStatus.SIGNED)
            throw new IllegalStateException("This signatory has already signed the document");

        // In sequential order, only the current signatory holds an active turn.
        if (doc.getSigningMode() == ESignDocument.SigningMode.SEQUENTIAL) {
            ESignDocument.Signatory current = sigs.stream()
                    .sorted(java.util.Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder))
                    .filter(s -> s.getStatus() != ESignDocument.SignatoryStatus.SIGNED)
                    .findFirst().orElse(null);
            if (current == null || !current.getId().equals(target.getId()))
                throw new IllegalStateException(
                        "This document signs in order — you can only resend to the current signatory"
                        + (current != null ? " (" + current.getEmail() + ")" : "") + ".");
        }

        doc.setSentAt(java.time.LocalDateTime.now());
        doc.setTokenExpiresAt(java.time.LocalDateTime.now().plusDays(tokenValidDays));

        String token = tokenService.issueSigningToken(doc, target, tokenValidDays);
        if (target.getInvitedAt() == null) target.setInvitedAt(LocalDateTime.now());
        boolean ok = emailService.sendSigningInvitation(doc, target.getName(), target.getEmail(), false, token);
        doc = docRepo.save(doc);

        auditService.log(docId, principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.DOCUMENT_SENT, ip, ua,
                Map.of("signatory", target.getEmail(), "action", "RESEND_SIGNATORY",
                       "tokenValidDays", tokenValidDays));

        auditLogService.log(
                doc.getId(), doc.getTitle(),
                AuditLog.Action.SENT, AuditLog.ResourceType.E_SIGN,
                0, Map.of("signatory", target.getEmail(), "action", "RESEND_SIGNATORY"),
                principal.getUsername(), principal.getOrgId());

        if (!ok) {
            throw new IllegalStateException(
                    "Could not email the invitation to " + target.getEmail()
                    + ". Verify your email sending domain/provider and try again.");
        }

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    // ── List / Detail ────────────────────────────────────────────────────────

    public List<DocumentResponse> listMyDocuments(UserDetailsImpl principal) {
        List<ESignDocument> docs = docRepo.findByCreatedByOrderByCreatedAtDesc(principal.getId());
        return docs.stream()
                .map(d -> DocumentResponse.from(d, List.of(), false))
                .toList();
    }

    /**
     * Paginated list of single-sign documents (excludes bulk-batch documents).
     * Scope is determined by the caller's role:
     * <ul>
     *   <li>PLATFORM_ADMIN — all documents across all orgs</li>
     *   <li>ORG_ADMIN      — all documents in their org</li>
     *   <li>ADMIN / USER   — only documents they created</li>
     * </ul>
     *
     * @param status optional status filter; null means all statuses
     * @param page   zero-based page index
     * @param size   page size (max 100)
     */
    public PageResponse<DocumentResponse> listMyDocumentsPaged(
            UserDetailsImpl principal,
            ESignDocument.Status status,
            int page, int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        AppUser.Role role = principal.getAppUser().getRole();

        Page<ESignDocument> pageResult = switch (role) {
            case PLATFORM_ADMIN -> (status != null)
                    ? docRepo.findByBulkBatchIdIsNullAndStatusOrderByCreatedAtDesc(status, pageable)
                    : docRepo.findByBulkBatchIdIsNullOrderByCreatedAtDesc(pageable);
            case ORG_ADMIN -> (status != null)
                    ? docRepo.findByOrgIdAndBulkBatchIdIsNullAndStatusOrderByCreatedAtDesc(
                            principal.getOrgId(), status, pageable)
                    : docRepo.findByOrgIdAndBulkBatchIdIsNullOrderByCreatedAtDesc(
                            principal.getOrgId(), pageable);
            default -> (status != null)
                    ? docRepo.findByCreatedByAndBulkBatchIdIsNullAndStatusOrderByCreatedAtDesc(
                            principal.getId(), status, pageable)
                    : docRepo.findByCreatedByAndBulkBatchIdIsNullOrderByCreatedAtDesc(
                            principal.getId(), pageable);
        };

        List<DocumentResponse> content = pageResult.getContent().stream()
                .map(d -> DocumentResponse.from(d, List.of(), false))
                .toList();

        return PageResponse.of(pageResult, content);
    }

    /**
     * Creates an empty batch record (status = PROCESSING) so the frontend can obtain a
     * batch ID before starting its own individual-document loop.
     *
     * @param allowClientUpload when {@code true}, every document created under this batch
     *                          will show the client an optional post-signing file-upload section
     */
    public BulkBatchResponse initBatch(String label, int totalRequested,
                                       boolean allowClientUpload,
                                       List<String> allowedClientUploadFileTypes,
                                       UserDetailsImpl principal) {
        String resolvedLabel = (label != null && !label.isBlank())
                ? label
                : "Bulk Send – " + LocalDateTime.now().toString().substring(0, 16).replace('T', ' ');

        ESignBulkBatch batch = batchRepo.save(ESignBulkBatch.builder()
                .createdBy(principal.getId())
                .orgId(principal.getOrgId())
                .label(resolvedLabel)
                .totalRequested(totalRequested)
                .allowClientUpload(allowClientUpload)
                .allowedClientUploadFileTypes(allowedClientUploadFileTypes != null
                        ? allowedClientUploadFileTypes : List.of())
                .status(ESignBulkBatch.Status.PROCESSING)
                .build());

        return BulkBatchResponse.from(batch);
    }

    /**
     * Updates the batch with final counters after the frontend has finished processing.
     * Ownership is enforced.
     */
    public BulkBatchResponse finalizeBatch(String batchId, int totalCreated, int totalSent, int totalFailed,
                                           UserDetailsImpl principal) {
        // Reuse the role-aware getBatch() which already enforces access rules
        getBatch(batchId, principal); // throws AccessDeniedException if not allowed

        ESignBulkBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        ESignBulkBatch.Status status = totalFailed == 0     ? ESignBulkBatch.Status.COMPLETED
                                     : totalCreated == 0    ? ESignBulkBatch.Status.FAILED
                                     : ESignBulkBatch.Status.PARTIAL;

        batch.setTotalCreated(totalCreated);
        batch.setTotalSent(totalSent);
        batch.setTotalFailed(totalFailed);
        batch.setStatus(status);
        return BulkBatchResponse.from(batchRepo.save(batch));
    }

    /**
     * Paginated list of bulk batches, scoped by the caller's role:
     * <ul>
     *   <li>PLATFORM_ADMIN — all batches</li>
     *   <li>ORG_ADMIN      — all batches in their org</li>
     *   <li>ADMIN / USER   — only their own batches</li>
     * </ul>
     */
    public PageResponse<BulkBatchResponse> listMyBatches(UserDetailsImpl principal, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        AppUser.Role role = principal.getAppUser().getRole();

        Page<ESignBulkBatch> pageResult = switch (role) {
            case PLATFORM_ADMIN ->
                    batchRepo.findAllByOrderByCreatedAtDesc(pageable);
            case ORG_ADMIN ->
                    batchRepo.findByOrgIdOrderByCreatedAtDesc(principal.getOrgId(), pageable);
            default ->
                    batchRepo.findByCreatedByOrderByCreatedAtDesc(principal.getId(), pageable);
        };

        List<BulkBatchResponse> content = pageResult.getContent().stream()
                .map(BulkBatchResponse::from)
                .toList();

        return PageResponse.of(pageResult, content);
    }

    /**
     * Returns summary for a single batch.
     * PLATFORM_ADMIN sees any batch; ORG_ADMIN sees batches in their org;
     * others are restricted to their own.
     */
    public BulkBatchResponse getBatch(String batchId, UserDetailsImpl principal) {
        ESignBulkBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        AppUser.Role role = principal.getAppUser().getRole();
        if (role == AppUser.Role.PLATFORM_ADMIN) {
            return BulkBatchResponse.from(batch);
        }
        if (role == AppUser.Role.ORG_ADMIN) {
            if (!principal.getOrgId().equals(batch.getOrgId()))
                throw new AccessDeniedException("Access denied to batch: " + batchId);
            return BulkBatchResponse.from(batch);
        }
        if (!batch.getCreatedBy().equals(principal.getId()))
            throw new AccessDeniedException("Access denied to batch: " + batchId);
        return BulkBatchResponse.from(batch);
    }

    /**
     * Paginated list of documents belonging to a specific batch.
     * Access is verified via {@link #getBatch(String, UserDetailsImpl)} before listing.
     */
    public PageResponse<DocumentResponse> listBatchDocuments(
            String batchId, UserDetailsImpl principal, int page, int size) {

        // Verify access to the batch first (enforces role-based rules)
        getBatch(batchId, principal);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ESignDocument> pageResult =
                docRepo.findByBulkBatchIdOrderByCreatedAtDesc(batchId, pageable);

        List<DocumentResponse> content = pageResult.getContent().stream()
                .map(d -> DocumentResponse.from(d, List.of(), false))
                .toList();

        return PageResponse.of(pageResult, content);
    }

    public DocumentResponse getDocument(String docId, UserDetailsImpl principal) {
        ESignDocument doc = getAccessibleDoc(docId, principal);
        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        DocumentResponse resp = DocumentResponse.from(doc, fields, true);  // include PDF for detail view
        // Cloud-stored docs: hand the client pre-signed URLs instead of embedded bytes.
        resp.setSourcePdfUrl(esignStorage.sourcePresignedUrl(doc));
        resp.setSignedPdfUrl(esignStorage.signedPresignedUrl(doc));
        return resp;
    }

    /** Signed PDF bytes for download (cloud or legacy); null if not yet generated. */
    public byte[] getSignedPdfBytes(String docId, UserDetailsImpl principal) {
        ESignDocument doc = getAccessibleDoc(docId, principal);
        return esignStorage.resolveSignedBytes(doc);
    }

    /**
     * Source (unsigned) PDF bytes for the creator's editor — served same-origin so the
     * field-placement editor can render cloud-stored PDFs without needing bucket CORS.
     * Cloud or legacy embedded; null if not available.
     */
    public byte[] getSourcePdfBytes(String docId, UserDetailsImpl principal) {
        ESignDocument doc = getAccessibleDoc(docId, principal);
        return esignStorage.resolveSourceBytes(doc);
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    public DocumentResponse cancelDocument(String docId, UserDetailsImpl principal,
                                           String ip, String ua) {
        ESignDocument doc = getAccessibleDoc(docId, principal);
        doc.setStatus(ESignDocument.Status.CANCELLED);
        docRepo.save(doc);
        auditService.log(docId, principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.DOCUMENT_CANCELLED, ip, ua, null);

        // Unified audit log entry
        auditLogService.log(
                doc.getId(), doc.getTitle(),
                AuditLog.Action.CANCELLED, AuditLog.ResourceType.E_SIGN,
                0, null,
                principal.getUsername(), principal.getOrgId());

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    // ── Audit trail ──────────────────────────────────────────────────────────

    public List<ESignAuditEvent> getAuditTrail(String docId, UserDetailsImpl principal) {
        getAccessibleDoc(docId, principal); // access check
        return auditService.getAuditTrail(docId);
    }

    // ── Client attachments (creator access) ──────────────────────────────────

    /**
     * Returns attachment metadata (no file bytes) for the creator's document detail view.
     */
    public List<java.util.Map<String, Object>> listAttachments(String docId, UserDetailsImpl principal) {
        ESignDocument doc = getAccessibleDoc(docId, principal);
        if (doc.getClientAttachments() == null || doc.getClientAttachments().isEmpty())
            return java.util.List.of();
        return doc.getClientAttachments().stream()
                .map(a -> java.util.Map.<String, Object>of(
                        "id",          a.getId(),
                        "fileName",    a.getFileName()    != null ? a.getFileName()    : "",
                        "contentType", a.getContentType() != null ? a.getContentType() : "",
                        "fileSize",    a.getFileSize(),
                        "uploadedAt",  a.getUploadedAt()  != null ? a.getUploadedAt().toString() : ""
                ))
                .toList();
    }

    /**
     * Returns the full attachment (including bytes) so the creator can download it.
     */
    public ESignDocument.ClientAttachment getAttachment(String docId,
                                                        String attachmentId,
                                                        UserDetailsImpl principal) {
        ESignDocument doc = getAccessibleDoc(docId, principal);
        return doc.getClientAttachments().stream()
                .filter(a -> attachmentId.equals(a.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + attachmentId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Role-aware document access check.
     * <ul>
     *   <li>PLATFORM_ADMIN — any document</li>
     *   <li>ORG_ADMIN      — any document in their org</li>
     *   <li>ADMIN / USER   — only documents they created</li>
     * </ul>
     */
    private ESignDocument getAccessibleDoc(String docId, UserDetailsImpl principal) {
        ESignDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));

        AppUser.Role role = principal.getAppUser().getRole();

        if (role == AppUser.Role.PLATFORM_ADMIN) {
            return doc; // platform admin sees everything
        }
        if (role == AppUser.Role.ORG_ADMIN) {
            if (!principal.getOrgId().equals(doc.getOrgId()))
                throw new AccessDeniedException("Access denied to document: " + docId);
            return doc;
        }
        // ADMIN / USER — strict ownership
        if (!doc.getCreatedBy().equals(principal.getId()))
            throw new AccessDeniedException("Access denied to document: " + docId);
        return doc;
    }

    /**
     * Strict ownership check — kept for the internal bulk-create send flow
     * where the creator is the expected owner and no elevated-role bypass is needed.
     */
    private ESignDocument getOwnedDoc(String docId, String userId) {
        ESignDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
        if (!doc.getCreatedBy().equals(userId))
            throw new AccessDeniedException("Access denied to document: " + docId);
        return doc;
    }

    private ESignDocument.SigningMode parseSigningMode(String raw) {
        if (raw == null || raw.isBlank()) return ESignDocument.SigningMode.PARALLEL;
        try { return ESignDocument.SigningMode.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return ESignDocument.SigningMode.PARALLEL; }
    }

    /**
     * Builds the ordered signatory list for a new document. Multi-party requests use
     * {@code req.signatories}; otherwise a single signatory is derived from the client fields
     * (covers the legacy single-sign flow and bulk send). Always returns at least one entry.
     */
    private List<ESignDocument.Signatory> buildSignatories(CreateDocumentRequest req) {
        List<ESignDocument.Signatory> result = new ArrayList<>();
        if (req.getSignatories() != null && !req.getSignatories().isEmpty()) {
            int i = 0;
            for (CreateDocumentRequest.SignatoryRequest s : req.getSignatories()) {
                i++;
                if (s.getEmail() == null || s.getEmail().isBlank()
                        || s.getName() == null || s.getName().isBlank())
                    throw new IllegalArgumentException("Each signatory needs a name and an email");
                result.add(ESignDocument.Signatory.builder()
                        .id(java.util.UUID.randomUUID().toString())
                        .name(s.getName().trim())
                        .email(s.getEmail().trim())
                        .signingOrder(s.getSigningOrder() != null ? s.getSigningOrder() : i)
                        .status(ESignDocument.SignatoryStatus.PENDING)
                        .build());
            }
            // Normalise to a contiguous 1..n order
            result.sort(java.util.Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder));
            for (int k = 0; k < result.size(); k++) result.get(k).setSigningOrder(k + 1);
        } else {
            if (req.getClientEmail() == null || req.getClientEmail().isBlank()
                    || req.getClientName() == null || req.getClientName().isBlank())
                throw new IllegalArgumentException(
                        "clientName and clientEmail are required when no signatories are provided");
            result.add(ESignDocument.Signatory.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .name(req.getClientName().trim())
                    .email(req.getClientEmail().trim())
                    .signingOrder(1)
                    .status(ESignDocument.SignatoryStatus.PENDING)
                    .build());
        }
        return result;
    }

    /**
     * Returns the document's signatories, ordered. For legacy documents created before the
     * multi-signatory feature (empty list) a single synthetic signatory is derived from the
     * client fields so the send/sign flow can treat every document uniformly.
     */
    private List<ESignDocument.Signatory> effectiveSignatories(ESignDocument doc) {
        if (doc.getSignatories() != null && !doc.getSignatories().isEmpty()) {
            return doc.getSignatories().stream()
                    .sorted(java.util.Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder))
                    .toList();
        }
        return List.of(ESignDocument.Signatory.builder()
                .id(null)   // legacy: tokens carry a null signatoryId
                .name(doc.getClientName())
                .email(doc.getClientEmail())
                .signingOrder(1)
                .status(ESignDocument.SignatoryStatus.PENDING)
                .build());
    }

    private byte[] decodeBase64Pdf(String base64) {
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        return Base64.getDecoder().decode(data.trim());
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
