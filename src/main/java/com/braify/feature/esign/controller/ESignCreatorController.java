package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.CreateDocumentRequest;
import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.FieldPlacementRequest;
import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.esign.service.ESignAuditService;
import com.braify.feature.esign.service.ESignDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "E-Sign — Creator", description = "Authenticated creator endpoints for managing e-sign documents: create, place fields, send signing invitations, resend, cancel, view audit trail, and download the completed signed PDF. All routes require a valid user JWT.")
@RestController
@RequestMapping("/api/esign/documents")
@RequiredArgsConstructor
public class ESignCreatorController {

    private final ESignDocumentService documentService;
    private final ESignAuditService    auditService;

    @Operation(summary = "Create e-sign document",
               description = "Creates a new DRAFT document from a PDF template. The base PDF is rendered immediately and stored. " +
                             "Body: `{ templateId, title, clientName, clientEmail, data: { … } }`")
    @ApiResponse(responseCode = "200", description = "Document created in DRAFT status")
    @PostMapping
    public ResponseEntity<DocumentResponse> create(
            @Valid @RequestBody CreateDocumentRequest req,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents title='{}' by '{}'", req.getTitle(), principal.getUsername());
        DocumentResponse doc = documentService.createDocument(
                req, principal, extractIp(http), http.getHeader("User-Agent"));
        log.info("E-sign document created: id='{}'", doc.getId());
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "List my e-sign documents",
               description = "Returns all documents created by the authenticated user, newest first.")
    @ApiResponse(responseCode = "200", description = "List of documents")
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.debug("GET /api/esign/documents caller='{}'", principal.getUsername());
        return ResponseEntity.ok(documentService.listMyDocuments(principal));
    }

    @Operation(summary = "Get e-sign document detail",
               description = "Returns full document detail including base PDF (base64), field placements, and status.")
    @ApiResponse(responseCode = "200", description = "Document detail")
    @ApiResponse(responseCode = "404", description = "Not found or not owned by the caller")
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.debug("GET /api/esign/documents/{}", id);
        return ResponseEntity.ok(documentService.getDocument(id, principal));
    }

    @Operation(summary = "Save field placements",
               description = "Replaces all field placement definitions for the document. Each field specifies position, size, type (SIGNATURE / INITIALS / DATE / TEXT), and page number. " +
                             "Only DRAFT documents can have fields edited.")
    @ApiResponse(responseCode = "200", description = "Fields saved")
    @PutMapping("/{id}/fields")
    public ResponseEntity<DocumentResponse> saveFields(
            @Parameter(description = "Document ID") @PathVariable String id,
            @RequestBody List<FieldPlacementRequest> fields,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("PUT /api/esign/documents/{}/fields count={} by '{}'", id, fields.size(), principal.getUsername());
        DocumentResponse doc = documentService.saveFields(
                id, fields, principal, extractIp(http), http.getHeader("User-Agent"));
        log.info("Fields saved for e-sign document '{}'", id);
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Send signing invitation",
               description = "Generates a one-time signing token, sends the client an email invitation, and transitions the document to PENDING status. " +
                             "The `tokenValidDays` query param sets how long the link remains valid (default 7 days).")
    @ApiResponse(responseCode = "200", description = "Invitation sent — document now PENDING")
    @PostMapping("/{id}/send")
    public ResponseEntity<DocumentResponse> send(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "Token validity in days (default 7)") @RequestParam(defaultValue = "7") int tokenValidDays,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/send tokenValidDays={} by '{}'", id, tokenValidDays, principal.getUsername());
        DocumentResponse doc = documentService.sendDocument(
                id, tokenValidDays, principal, extractIp(http), http.getHeader("User-Agent"));
        log.info("E-sign document '{}' sent to client", id);
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Resend signing invitation",
               description = "Invalidates the previous signing token, issues a new one, and re-sends the invitation email. Resets the expiry window.")
    @ApiResponse(responseCode = "200", description = "Invitation resent")
    @PostMapping("/{id}/resend")
    public ResponseEntity<DocumentResponse> resend(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "New token validity in days (default 7)") @RequestParam(defaultValue = "7") int tokenValidDays,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/resend by '{}'", id, principal.getUsername());
        DocumentResponse doc = documentService.resendDocument(
                id, tokenValidDays, principal, extractIp(http), http.getHeader("User-Agent"));
        log.info("E-sign document '{}' invitation resent", id);
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Cancel document",
               description = "Transitions the document to CANCELLED status. The signing link is invalidated. Cannot cancel a COMPLETED document.")
    @ApiResponse(responseCode = "200", description = "Document cancelled")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<DocumentResponse> cancel(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/cancel by '{}'", id, principal.getUsername());
        DocumentResponse doc = documentService.cancelDocument(
                id, principal, extractIp(http), http.getHeader("User-Agent"));
        log.info("E-sign document '{}' cancelled", id);
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Get e-sign audit trail",
               description = "Returns chronological audit events for the document: CREATED, FIELDS_SAVED, SENT, OPENED, FIELD_SIGNED, SUBMITTED, COMPLETED, CANCELLED, etc.")
    @ApiResponse(responseCode = "200", description = "Audit events list")
    @GetMapping("/{id}/audit")
    public ResponseEntity<List<ESignAuditEvent>> audit(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.debug("GET /api/esign/documents/{}/audit", id);
        return ResponseEntity.ok(documentService.getAuditTrail(id, principal));
    }

    @Operation(summary = "Download signed PDF",
               description = "Returns the completed, signature-stamped PDF as a binary download (`application/pdf`). Only available when the document status is COMPLETED.")
    @ApiResponse(responseCode = "200", description = "Signed PDF bytes")
    @ApiResponse(responseCode = "204", description = "Document not yet completed — no signed PDF available")
    @GetMapping("/{id}/signed-pdf")
    public ResponseEntity<byte[]> downloadSignedPdf(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("GET /api/esign/documents/{}/signed-pdf by '{}'", id, principal.getUsername());

        DocumentResponse doc = documentService.getDocument(id, principal);
        if (doc.getSignedPdfBase64() == null)
            return ResponseEntity.noContent().build();

        byte[] pdfBytes = java.util.Base64.getDecoder().decode(doc.getSignedPdfBase64());
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"" + sanitize(doc.getTitle()) + "-signed.pdf\"")
                .body(pdfBytes);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return request.getRemoteAddr();
    }

    private String sanitize(String name) {
        return name == null ? "document" :
                name.replaceAll("[^a-zA-Z0-9._\\- ]", "").trim().replace(" ", "-");
    }
}
