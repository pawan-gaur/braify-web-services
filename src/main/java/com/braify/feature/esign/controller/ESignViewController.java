package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.service.ESignClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public, read-only document viewer for CC ("keep in the loop") recipients. Access is granted by a
 * short-lived VIEW token (type ESIGN_VIEW) embedded in the emailed link. A view token can only read
 * the document — it can never sign it (signing requires an ESIGN token handled by
 * {@link ESignClientController}).
 */
@Slf4j
@Tag(name = "E-Sign — Read-only View", description = "Public view-only access for CC recipients (VIEW token). No signing, no download endpoints.")
@RestController
@RequestMapping("/api/esign/view")
@RequiredArgsConstructor
public class ESignViewController {

    private final ESignClientService clientService;

    @Operation(summary = "Open document for viewing",
               description = "Returns document metadata + fields for a read-only viewer, authorized by a view token.")
    @ApiResponse(responseCode = "200", description = "Document metadata")
    @ApiResponse(responseCode = "401", description = "Invalid or expired view link")
    @GetMapping("/{token}")
    public ResponseEntity<DocumentResponse> open(
            @Parameter(description = "ESIGN view token from the emailed link") @PathVariable String token) {
        log.debug("GET /api/esign/view/{} (open read-only)", token.length() > 12 ? token.substring(0, 12) + "…" : token);
        return ResponseEntity.ok(clientService.openForView(token));
    }

    @Operation(summary = "Get PDF for viewing",
               description = "Returns the signed PDF once completed, otherwise the source PDF, as `application/pdf` " +
                             "(served same-origin so cloud PDFs render without bucket CORS).")
    @ApiResponse(responseCode = "200", description = "PDF bytes")
    @ApiResponse(responseCode = "401", description = "Invalid or expired view link")
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> pdf(
            @Parameter(description = "ESIGN view token") @PathVariable String token) {
        byte[] bytes = clientService.getViewPdfBytes(token);
        if (bytes == null || bytes.length == 0) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline")
                .body(bytes);
    }
}
