package com.braify.service;

import com.braify.dto.esign.DocumentResponse;
import com.braify.dto.esign.SignFieldRequest;
import com.braify.model.ESignAuditEvent;
import com.braify.model.ESignDocument;
import com.braify.model.ESignSignatureField;
import com.braify.model.ESignSigningToken;
import com.braify.repository.AppUserRepository;
import com.braify.repository.ESignDocumentRepository;
import com.braify.repository.ESignSignatureFieldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        return DocumentResponse.from(doc, fields, true);  // include sourcePdf for signing UI
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
            // 1. Stamp signatures onto PDF
            byte[] signedBytes = pdfService.stampSignatures(doc, fields);
            String hash = pdfService.sha256Hex(signedBytes);

            doc.setSignedPdfData(signedBytes);
            doc.setSignedPdfHash(hash);
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
