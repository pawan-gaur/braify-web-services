package com.braify.controller;

import com.braify.dto.esign.CreateDocumentRequest;
import com.braify.dto.esign.DocumentResponse;
import com.braify.dto.esign.FieldPlacementRequest;
import com.braify.model.ESignAuditEvent;
import com.braify.security.UserDetailsImpl;
import com.braify.service.ESignAuditService;
import com.braify.service.ESignDocumentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Authenticated creator endpoints.
 * All routes require a valid user JWT (handled by JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/esign/documents")
@RequiredArgsConstructor
public class ESignCreatorController {

    private final ESignDocumentService documentService;
    private final ESignAuditService    auditService;

    /** POST /api/esign/documents — create new document (DRAFT) */
    @PostMapping
    public ResponseEntity<DocumentResponse> create(
            @RequestBody CreateDocumentRequest req,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        DocumentResponse doc = documentService.createDocument(
                req, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    /** GET /api/esign/documents — list my documents */
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(documentService.listMyDocuments(principal));
    }

    /** GET /api/esign/documents/{id} — full detail (includes PDF) */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(documentService.getDocument(id, principal));
    }

    /** PUT /api/esign/documents/{id}/fields — replace all field placements */
    @PutMapping("/{id}/fields")
    public ResponseEntity<DocumentResponse> saveFields(
            @PathVariable String id,
            @RequestBody List<FieldPlacementRequest> fields,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        DocumentResponse doc = documentService.saveFields(
                id, fields, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    /** POST /api/esign/documents/{id}/send — send signing invitation */
    @PostMapping("/{id}/send")
    public ResponseEntity<DocumentResponse> send(
            @PathVariable String id,
            @RequestParam(defaultValue = "7") int tokenValidDays,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        DocumentResponse doc = documentService.sendDocument(
                id, tokenValidDays, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    /** POST /api/esign/documents/{id}/resend — resend signing invitation */
    @PostMapping("/{id}/resend")
    public ResponseEntity<DocumentResponse> resend(
            @PathVariable String id,
            @RequestParam(defaultValue = "7") int tokenValidDays,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        DocumentResponse doc = documentService.resendDocument(
                id, tokenValidDays, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    /** POST /api/esign/documents/{id}/cancel — cancel document */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<DocumentResponse> cancel(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        DocumentResponse doc = documentService.cancelDocument(
                id, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    /** GET /api/esign/documents/{id}/audit — full audit trail */
    @GetMapping("/{id}/audit")
    public ResponseEntity<List<ESignAuditEvent>> audit(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(documentService.getAuditTrail(id, principal));
    }

    /** GET /api/esign/documents/{id}/signed-pdf — download signed PDF */
    @GetMapping("/{id}/signed-pdf")
    public ResponseEntity<byte[]> downloadSignedPdf(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

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
