package com.braify.service;

import com.braify.dto.esign.CreateDocumentRequest;
import com.braify.dto.esign.DocumentResponse;
import com.braify.dto.esign.FieldPlacementRequest;
import com.braify.model.AuditLog;
import com.braify.model.ESignAuditEvent;
import com.braify.model.ESignDocument;
import com.braify.model.ESignSignatureField;
import com.braify.model.Template;
import com.braify.repository.ESignDocumentRepository;
import com.braify.repository.ESignSignatureFieldRepository;
import com.braify.repository.TemplateRepository;
import com.braify.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignDocumentService {

    private final ESignDocumentRepository       docRepo;
    private final ESignSignatureFieldRepository fieldRepo;
    private final TemplateRepository            templateRepo;
    private final ESignTokenService             tokenService;
    private final ESignEmailService             emailService;
    private final ESignAuditService             auditService;
    private final AuditLogService               auditLogService;

    // ── Create ──────────────────────────────────────────────────────────────

    public DocumentResponse createDocument(CreateDocumentRequest req,
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

        ESignDocument doc = ESignDocument.builder()
                .createdBy(principal.getId())
                .orgId(principal.getOrgId())
                .title(req.getTitle())
                .sourceType(ESignDocument.SourceType.valueOf(req.getSourceType().toUpperCase()))
                .templateId(req.getTemplateId())
                .sourcePdfData(pdfBytes)
                .sourcePdfHash(sha256Hex(pdfBytes))
                .clientEmail(req.getClientEmail())
                .clientName(req.getClientName())
                .status(ESignDocument.Status.DRAFT)
                .build();

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

    // ── Field Placement ─────────────────────────────────────────────────────

    public DocumentResponse saveFields(String docId,
                                       List<FieldPlacementRequest> requests,
                                       UserDetailsImpl principal,
                                       String ip, String ua) {
        ESignDocument doc = getOwnedDoc(docId, principal.getId());

        // Replace all existing fields
        fieldRepo.deleteByDocumentId(docId);

        List<ESignSignatureField> fields = requests.stream()
                .map(r -> ESignSignatureField.builder()
                        .documentId(docId)
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
        ESignDocument doc = getOwnedDoc(docId, principal.getId());

        if (doc.getStatus() == ESignDocument.Status.COMPLETED ||
            doc.getStatus() == ESignDocument.Status.CANCELLED) {
            throw new IllegalStateException("Document is already " + doc.getStatus());
        }

        String signingToken = tokenService.issueSigningToken(doc, tokenValidDays);

        doc.setStatus(ESignDocument.Status.PENDING);
        doc.setSentAt(java.time.LocalDateTime.now());
        doc.setTokenExpiresAt(java.time.LocalDateTime.now().plusDays(tokenValidDays));
        doc = docRepo.save(doc);

        emailService.sendSigningInvitation(doc, signingToken);

        auditService.log(docId, principal.getUsername(),
                ESignAuditEvent.ActorType.CREATOR,
                ESignAuditEvent.EventType.DOCUMENT_SENT, ip, ua,
                Map.of("clientEmail", doc.getClientEmail(), "tokenValidDays", tokenValidDays));

        // Unified audit log entry
        auditLogService.log(
                doc.getId(), doc.getTitle(),
                AuditLog.Action.SENT, AuditLog.ResourceType.E_SIGN,
                0, Map.of("clientEmail", doc.getClientEmail()),
                principal.getUsername(), principal.getOrgId());

        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, false);
    }

    // ── Resend invitation ────────────────────────────────────────────────────

    public DocumentResponse resendDocument(String docId,
                                           int tokenValidDays,
                                           UserDetailsImpl principal,
                                           String ip, String ua) {
        ESignDocument doc = getOwnedDoc(docId, principal.getId());

        if (doc.getStatus() == ESignDocument.Status.COMPLETED ||
            doc.getStatus() == ESignDocument.Status.CANCELLED ||
            doc.getStatus() == ESignDocument.Status.EXPIRED) {
            throw new IllegalStateException("Cannot resend a " + doc.getStatus() + " document");
        }

        // Issue a fresh token (revokes the previous one)
        String signingToken = tokenService.issueSigningToken(doc, tokenValidDays);
        doc.setSentAt(java.time.LocalDateTime.now());
        doc.setTokenExpiresAt(java.time.LocalDateTime.now().plusDays(tokenValidDays));
        doc = docRepo.save(doc);

        emailService.sendSigningInvitation(doc, signingToken);

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

    // ── List / Detail ────────────────────────────────────────────────────────

    public List<DocumentResponse> listMyDocuments(UserDetailsImpl principal) {
        List<ESignDocument> docs = docRepo.findByCreatedByOrderByCreatedAtDesc(principal.getId());
        return docs.stream()
                .map(d -> DocumentResponse.from(d, List.of(), false))
                .toList();
    }

    public DocumentResponse getDocument(String docId, UserDetailsImpl principal) {
        ESignDocument doc = getOwnedDoc(docId, principal.getId());
        List<ESignSignatureField> fields = fieldRepo.findByDocumentIdOrderByPageAscYAsc(docId);
        return DocumentResponse.from(doc, fields, true);  // include PDF for detail view
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    public DocumentResponse cancelDocument(String docId, UserDetailsImpl principal,
                                           String ip, String ua) {
        ESignDocument doc = getOwnedDoc(docId, principal.getId());
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
        getOwnedDoc(docId, principal.getId()); // ownership check
        return auditService.getAuditTrail(docId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ESignDocument getOwnedDoc(String docId, String userId) {
        ESignDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));
        if (!doc.getCreatedBy().equals(userId))
            throw new AccessDeniedException("Access denied to document: " + docId);
        return doc;
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
