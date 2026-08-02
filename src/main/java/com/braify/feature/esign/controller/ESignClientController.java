package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.SignFieldRequest;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.service.ESignClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Tag(name = "E-Sign — Client Signing", description = "Public endpoints used by the signing recipient (no user account required). Access is controlled by a short-lived ESIGN signing JWT embedded in the emailed link. Pass the token as a Bearer token in the Authorization header.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/esign/sign")
@RequiredArgsConstructor
public class ESignClientController {

    private final ESignClientService clientService;

    @Operation(summary = "Open document for signing",
               description = "Validates the signing token, transitions the document to IN_REVIEW on first open, " +
                             "and returns the full document including the base PDF for rendering in the signing UI. " +
                             "The token is the value from the emailed signing link.")
    @ApiResponse(responseCode = "200", description = "Document opened")
    @ApiResponse(responseCode = "401", description = "Invalid or expired signing token")
    @GetMapping("/{token}")
    public ResponseEntity<DocumentResponse> openDocument(
            @Parameter(description = "ESIGN signing token from the emailed link") @PathVariable String token,
            HttpServletRequest http) {

        log.info("GET /api/esign/sign/{} (open document)", token.length() > 12 ? token.substring(0, 12) + "…" : token);
        DocumentResponse doc = clientService.openDocument(
                token, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Get source PDF for signing",
               description = "Returns the original PDF as `application/pdf`, served same-origin (authorized by the " +
                             "signing token) so the signing UI can render multi-page cloud PDFs without bucket CORS.")
    @ApiResponse(responseCode = "200", description = "Source PDF bytes")
    @ApiResponse(responseCode = "401", description = "Invalid or expired signing token")
    @GetMapping("/{token}/source-pdf")
    public ResponseEntity<byte[]> sourcePdf(
            @Parameter(description = "ESIGN signing token") @PathVariable String token) {
        byte[] bytes = clientService.getSourcePdfBytes(token);
        if (bytes == null || bytes.length == 0) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline")
                .body(bytes);
    }

    @Operation(summary = "Sign a single field",
               description = "Submits the client's signature for one field. Supported types:\n\n" +
                             "- `SIGNATURE` / `INITIALS` — body: `{ signatureData: \"data:image/png;base64,...\" }`\n" +
                             "- `TEXT` — body: `{ textValue: \"John Doe\" }`\n" +
                             "- `DATE` — body: `{ dateValue: \"2025-05-15\" }`")
    @ApiResponse(responseCode = "200", description = "Field signed")
    @ApiResponse(responseCode = "400", description = "Field already signed or invalid data")
    @PutMapping("/{token}/fields/{fieldId}")
    public ResponseEntity<DocumentResponse.FieldResponse> signField(
            @Parameter(description = "ESIGN signing token") @PathVariable String token,
            @Parameter(description = "Field ID to sign") @PathVariable String fieldId,
            @Valid @RequestBody SignFieldRequest req,
            HttpServletRequest http) {

        log.info("PUT /api/esign/sign/{token}/fields/{} (sign field)", fieldId);
        DocumentResponse.FieldResponse field = clientService.signField(
                token, fieldId, req, extractIp(http), http.getHeader("User-Agent"));
        log.info("Field '{}' signed", fieldId);
        return ResponseEntity.ok(field);
    }

    @Operation(summary = "Submit completed document",
               description = "Called after all required fields are signed. Triggers async PDF stamping (signatures are burned into the PDF), " +
                             "transitions the document to COMPLETED, and sends completion emails to both the creator and the client.")
    @ApiResponse(responseCode = "200", description = "Document submitted and completed")
    @ApiResponse(responseCode = "400", description = "Required fields are still unsigned")
    @PostMapping("/{token}/submit")
    public ResponseEntity<DocumentResponse> submitDocument(
            @Parameter(description = "ESIGN signing token") @PathVariable String token,
            HttpServletRequest http) {

        log.info("POST /api/esign/sign/{token}/submit (client submitting document)");
        DocumentResponse doc = clientService.submitDocument(
                token, extractIp(http), http.getHeader("User-Agent"));
        log.info("E-sign document '{}' submitted by client", doc.getId());
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Record consent to use electronic records & signatures",
               description = "Captures the signer's affirmative ESIGN Act / UETA consent before signing. " +
                             "Recorded as an immutable audit event plus a consent timestamp on the signatory.")
    @ApiResponse(responseCode = "200", description = "Consent recorded")
    @PostMapping("/{token}/consent")
    public ResponseEntity<DocumentResponse> recordConsent(
            @Parameter(description = "ESIGN signing token") @PathVariable String token,
            HttpServletRequest http) {

        log.info("POST /api/esign/sign/{token}/consent (client accepting electronic-signature consent)");
        DocumentResponse doc = clientService.recordConsent(
                token, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "Upload supporting document after signing",
               description = "Allows the client to optionally attach a supporting document (e.g. ID copy) " +
                             "after submitting their signature. Requires the original signing JWT (still within " +
                             "its expiry window). Up to 5 files, each max 10 MB. " +
                             "The upload is recorded in the document's audit trail.")
    @ApiResponse(responseCode = "200", description = "Attachment stored — returns metadata without the file bytes")
    @ApiResponse(responseCode = "400", description = "File too large, limit reached, or invalid state")
    @ApiResponse(responseCode = "401", description = "Invalid or expired signing token")
    @PostMapping(value = "/{token}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAttachment(
            @Parameter(description = "ESIGN signing token") @PathVariable String token,
            @Parameter(description = "File to upload (max 10 MB)") @RequestParam("file") MultipartFile file,
            HttpServletRequest http) throws IOException {

        log.info("POST /api/esign/sign/{token}/attachments file='{}' size={}",
                file.getOriginalFilename(), file.getSize());
        ESignDocument.ClientAttachment att = clientService.uploadAttachment(
                token, file, extractIp(http));
        return ResponseEntity.ok(Map.of(
                "id",          att.getId(),
                "fileName",    att.getFileName(),
                "contentType", att.getContentType() != null ? att.getContentType() : "",
                "fileSize",    att.getFileSize(),
                "uploadedAt",  att.getUploadedAt().toString()
        ));
    }

    @Operation(summary = "Download a client-uploaded attachment",
               description = "Returns the binary file content of a previously uploaded attachment. " +
                             "Requires the original signing JWT.")
    @ApiResponse(responseCode = "200", description = "File bytes")
    @ApiResponse(responseCode = "404", description = "Attachment not found")
    @GetMapping("/{token}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(
            @Parameter(description = "ESIGN signing token") @PathVariable String token,
            @Parameter(description = "Attachment ID") @PathVariable String attachmentId) {

        log.info("GET /api/esign/sign/{token}/attachments/{}", attachmentId);
        ESignDocument.ClientAttachment att = clientService.getClientAttachment(token, attachmentId);
        String ct = att.getContentType() != null ? att.getContentType() : "application/octet-stream";
        return ResponseEntity.ok()
                .header("Content-Type", ct)
                .header("Content-Disposition", "attachment; filename=\"" + sanitize(att.getFileName()) + "\"")
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
        return name == null ? "attachment" :
                name.replaceAll("[^a-zA-Z0-9._\\- ]", "").trim();
    }
}
