package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.BulkCreateDocumentRequest;
import com.braify.feature.esign.dto.BulkCreateDocumentResponse;
import com.braify.feature.esign.dto.CreateDocumentRequest;
import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.FieldPlacementRequest;
import com.braify.feature.esign.dto.PageResponse;
import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.model.ESignDocument;
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

    @Operation(summary = "Bulk create and send e-sign documents",
               description = "Accepts up to 500 document creation requests and processes them sequentially. " +
                             "Each row is independently attempted — a failure on one row does not abort the rest. " +
                             "Set `sendImmediately: false` to create DRAFT documents only (no emails sent). " +
                             "When `sendImmediately` is true a quota pre-flight check runs first so the whole " +
                             "batch fails fast if the org lacks sufficient remaining quota.")
    @ApiResponse(responseCode = "200", description = "Per-row results with aggregate counters")
    @ApiResponse(responseCode = "400", description = "Validation error (empty list, item missing required field, etc.)")
    @ApiResponse(responseCode = "429", description = "Quota exceeded — not enough remaining capacity for the batch")
    @PostMapping("/bulk")
    public ResponseEntity<BulkCreateDocumentResponse> createBulk(
            @Valid @RequestBody BulkCreateDocumentRequest req,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/bulk count={} sendImmediately={} by '{}'",
                req.getDocuments().size(), req.isSendImmediately(), principal.getUsername());

        BulkCreateDocumentResponse result = documentService.createAndSendBulk(
                req, principal, extractIp(http), http.getHeader("User-Agent"));

        log.info("Bulk e-sign done: created={} sent={} failed={}",
                result.getTotalCreated(), result.getTotalSent(), result.getTotalFailed());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "List my e-sign documents (paginated)",
               description = "Returns single-sign documents created by the authenticated user (bulk-batch documents excluded), newest first. " +
                             "Supports optional status filter and page/size controls (default page 0, size 20).")
    @ApiResponse(responseCode = "200", description = "Paginated list of documents")
    @GetMapping
    public ResponseEntity<PageResponse<DocumentResponse>> list(
            @Parameter(description = "Filter by status (optional)") @RequestParam(required = false) String status,
            @Parameter(description = "Free-text search: title, client/signatory name or email, or a document id (optional)")
                @RequestParam(required = false) String search,
            @Parameter(description = "Created on/after this date, yyyy-MM-dd (optional)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Created on/before this date, yyyy-MM-dd (optional)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Zero-based page index (default 0)")  @RequestParam(defaultValue = "0")  int page,
            @Parameter(description = "Page size (default 20, max 100)")    @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        ESignDocument.Status statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = ESignDocument.Status.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException e) { /* ignore unknown — return all */ }
        }
        // Dates arrive as absolute UTC instants (the frontend converts the user's local
        // day-boundaries), or as plain yyyy-MM-dd (treated as UTC) for direct API callers.
        java.time.LocalDateTime fromTs = parseFrom(dateFrom);
        java.time.LocalDateTime toTs   = parseTo(dateTo);

        log.debug("GET /api/esign/documents caller='{}' status={} search='{}' from={} to={} page={} size={}",
                principal.getUsername(), status, search, dateFrom, dateTo, page, size);
        return ResponseEntity.ok(documentService.listMyDocumentsPaged(
                principal, statusEnum, search, fromTs, toTs, page, size));
    }

    /**
     * Lower bound of the created-date range as a UTC {@link java.time.LocalDateTime}.
     * Prefers an ISO-8601 instant (e.g. {@code 2026-07-02T18:30:00Z}); falls back to
     * {@code yyyy-MM-dd} interpreted as the start of that day in UTC. Null on blank/invalid.
     */
    private java.time.LocalDateTime parseFrom(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try { return java.time.LocalDateTime.ofInstant(java.time.Instant.parse(s), java.time.ZoneOffset.UTC); }
        catch (Exception ignore) { /* not an instant — try a plain date */ }
        try { return java.time.LocalDate.parse(s).atStartOfDay(); }
        catch (Exception e) { return null; }
    }

    /** Upper bound; like {@link #parseFrom} but the {@code yyyy-MM-dd} fallback maps to end-of-day (UTC). */
    private java.time.LocalDateTime parseTo(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try { return java.time.LocalDateTime.ofInstant(java.time.Instant.parse(s), java.time.ZoneOffset.UTC); }
        catch (Exception ignore) { /* not an instant — try a plain date */ }
        try { return java.time.LocalDate.parse(s).atTime(java.time.LocalTime.MAX); }
        catch (Exception e) { return null; }
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

    @Operation(summary = "Finalize a signed document",
               description = "Re-runs finalization (signed-PDF generation + completion emails) for a document that is " +
                             "fully SIGNED but never reached COMPLETED — e.g. the automatic finalize failed on a transient " +
                             "storage error. Safe to call repeatedly; a no-op once COMPLETED.")
    @ApiResponse(responseCode = "200", description = "Finalization attempted — returns the (hopefully COMPLETED) document")
    @PostMapping("/{id}/finalize")
    public ResponseEntity<DocumentResponse> finalize(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/finalize by '{}'", id, principal.getUsername());
        DocumentResponse doc = documentService.finalizeDocument(
                id, principal, extractIp(http), http.getHeader("User-Agent"));
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

    @Operation(summary = "Reactivate an expired document",
               description = "Revives an EXPIRED document: issues fresh signing tokens, resets the expiry window, " +
                             "and re-invites everyone who still needs to sign. Any signatures captured before expiry are kept.")
    @ApiResponse(responseCode = "200", description = "Document reactivated")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<DocumentResponse> reactivate(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "New token validity in days (default 7)") @RequestParam(defaultValue = "7") int tokenValidDays,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/reactivate by '{}'", id, principal.getUsername());
        DocumentResponse doc = documentService.reactivateDocument(
                id, tokenValidDays, principal, extractIp(http), http.getHeader("User-Agent"));
        log.info("E-sign document '{}' reactivated", id);
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Resend invitation to one signatory",
               description = "Re-issues a fresh signing link and re-sends the invitation to a single signatory. " +
                             "In sequential mode only the current (first not-yet-signed) signatory can be re-invited.")
    @ApiResponse(responseCode = "200", description = "Invitation resent to the signatory")
    @PostMapping("/{id}/signatories/{signatoryId}/resend")
    public ResponseEntity<DocumentResponse> resendSignatory(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "Signatory ID") @PathVariable String signatoryId,
            @Parameter(description = "Token validity in days (default 7)") @RequestParam(defaultValue = "7") int tokenValidDays,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/signatories/{}/resend by '{}'", id, signatoryId, principal.getUsername());
        DocumentResponse doc = documentService.resendToSignatory(
                id, signatoryId, tokenValidDays, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Send a signing reminder now",
               description = "Immediately emails a reminder to every signatory who still needs to sign. Ignores the " +
                             "automatic schedule and the per-document opt-out (a person explicitly asked), but only works " +
                             "while the document is still awaiting signatures and its signing window has not expired.")
    @ApiResponse(responseCode = "200", description = "Reminder(s) sent")
    @PostMapping("/{id}/remind")
    public ResponseEntity<DocumentResponse> remind(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/remind by '{}'", id, principal.getUsername());
        DocumentResponse doc = documentService.sendRemindersNow(
                id, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Toggle automatic reminders for a document",
               description = "Turns the automatic reminder schedule on or off for this document. Manual 'send reminder now' " +
                             "still works when off.")
    @ApiResponse(responseCode = "200", description = "Automatic-reminder setting updated")
    @PutMapping("/{id}/reminders")
    public ResponseEntity<DocumentResponse> setReminders(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "Whether automatic reminders are enabled") @RequestParam boolean enabled,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("PUT /api/esign/documents/{}/reminders enabled={} by '{}'", id, enabled, principal.getUsername());
        DocumentResponse doc = documentService.setRemindersEnabled(
                id, enabled, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Resend the final signed copy",
               description = "Re-sends the signed PDF to all signatories, the creator, and the 'send a copy' recipients " +
                             "(and the view-only notice to invitation-CC recipients), refreshing the per-recipient " +
                             "notification record. Only valid once the document is COMPLETED.")
    @ApiResponse(responseCode = "200", description = "Final copy resent")
    @PostMapping("/{id}/resend-copy")
    public ResponseEntity<DocumentResponse> resendCopy(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/resend-copy by '{}'", id, principal.getUsername());
        DocumentResponse doc = documentService.resendFinalCopy(
                id, principal, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Resend the final signed copy to one recipient",
               description = "Re-sends the signed PDF (or the view-only notice, for invitation-CC recipients) to a single " +
                             "recipient of the completed document, refreshing that recipient's notification record.")
    @ApiResponse(responseCode = "200", description = "Final copy resent to the recipient")
    @PostMapping("/{id}/resend-copy/recipient")
    public ResponseEntity<DocumentResponse> resendCopyToRecipient(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "Recipient email") @RequestParam String email,
            @AuthenticationPrincipal UserDetailsImpl principal,
            HttpServletRequest http) {

        log.info("POST /api/esign/documents/{}/resend-copy/recipient email='{}' by '{}'", id, email, principal.getUsername());
        DocumentResponse doc = documentService.resendFinalCopyToRecipient(
                id, email, principal, extractIp(http), http.getHeader("User-Agent"));
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

    @Operation(summary = "Get source PDF",
               description = "Returns the original (unsigned) PDF as `application/pdf`, served same-origin so the " +
                             "field-placement editor can render cloud-stored PDFs without bucket CORS.")
    @ApiResponse(responseCode = "200", description = "Source PDF bytes")
    @ApiResponse(responseCode = "204", description = "No source PDF available")
    @GetMapping("/{id}/source-pdf")
    public ResponseEntity<byte[]> getSourcePdf(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.debug("GET /api/esign/documents/{}/source-pdf by '{}'", id, principal.getUsername());

        byte[] pdfBytes = documentService.getSourcePdfBytes(id, principal);
        if (pdfBytes == null || pdfBytes.length == 0)
            return ResponseEntity.noContent().build();

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline")
                .body(pdfBytes);
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
        byte[] pdfBytes = documentService.getSignedPdfBytes(id, principal);  // cloud or legacy
        if (pdfBytes == null || pdfBytes.length == 0)
            return ResponseEntity.noContent().build();

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"" + sanitize(doc.getTitle()) + "-signed.pdf\"")
                .body(pdfBytes);
    }

    @Operation(summary = "List client-uploaded attachments",
               description = "Returns metadata (id, fileName, contentType, fileSize, uploadedAt) for all supporting documents " +
                             "uploaded by the signing client after submission. File bytes are not included.")
    @ApiResponse(responseCode = "200", description = "Attachment metadata list")
    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<Map<String, Object>>> listAttachments(
            @Parameter(description = "Document ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.debug("GET /api/esign/documents/{}/attachments by '{}'", id, principal.getUsername());
        return ResponseEntity.ok(documentService.listAttachments(id, principal));
    }

    @Operation(summary = "Download a client-uploaded attachment",
               description = "Returns the raw file bytes of a client-uploaded supporting document. " +
                             "Only accessible by the document owner.")
    @ApiResponse(responseCode = "200", description = "File bytes")
    @ApiResponse(responseCode = "404", description = "Attachment not found")
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(
            @Parameter(description = "Document ID") @PathVariable String id,
            @Parameter(description = "Attachment ID") @PathVariable String attachmentId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("GET /api/esign/documents/{}/attachments/{} by '{}'", id, attachmentId, principal.getUsername());
        ESignDocument.ClientAttachment att = documentService.getAttachment(id, attachmentId, principal);
        String ct = att.getContentType() != null ? att.getContentType() : "application/octet-stream";
        return ResponseEntity.ok()
                .header("Content-Type", ct)
                .header("Content-Disposition",
                        "attachment; filename=\"" + sanitize(att.getFileName()) + "\"")
                .body(att.getData());
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
