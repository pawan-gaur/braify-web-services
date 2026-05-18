package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.SignFieldRequest;
import com.braify.feature.esign.service.ESignClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

        DocumentResponse doc = clientService.openDocument(
                token, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
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
            @RequestBody SignFieldRequest req,
            HttpServletRequest http) {

        DocumentResponse.FieldResponse field = clientService.signField(
                token, fieldId, req, extractIp(http), http.getHeader("User-Agent"));
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

        DocumentResponse doc = clientService.submitDocument(
                token, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return request.getRemoteAddr();
    }
}
