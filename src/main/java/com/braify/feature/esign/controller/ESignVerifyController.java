package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.service.ESignClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Tag(name = "E-Sign — Verification", description = "Public endpoint to verify a completed e-sign document's authenticity. No authentication required — anyone with the document ID can verify.")
@RestController
@RequestMapping("/api/esign/verify")
@RequiredArgsConstructor
public class ESignVerifyController {

    private final ESignClientService clientService;

    @Operation(summary = "Verify signed document",
               description = "Returns a public summary of a completed e-sign document for third-party integrity verification. " +
                             "The server re-hashes the stored signed PDF and compares it to the hash recorded at signing. " +
                             "Includes: `status`, `clientName`, `clientEmail`, `completedAt`, " +
                             "`signedPdfHash` (SHA-256 of the whole signed PDF file, recorded at signing), " +
                             "`computedPdfHash` (SHA-256 re-derived now from the stored file), " +
                             "`integrityVerified` (boolean — true only when the two hashes match), and " +
                             "`verified` (boolean — true only when status is COMPLETED). " +
                             "Does **not** return the PDF bytes — use the creator's download endpoint for that.")
    @SecurityRequirements   // marks as requiring NO auth in the spec
    @ApiResponse(responseCode = "200", description = "Verification summary")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> verify(
            @Parameter(description = "E-sign document ID") @PathVariable String id) {
        log.info("GET /api/esign/verify/{}", id);
        var v = clientService.verifyDocumentIntegrity(id);
        DocumentResponse doc = v.document();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("documentId",        doc.getId());
        result.put("title",             doc.getTitle());
        result.put("status",            doc.getStatus());
        result.put("clientName",        doc.getClientName() != null ? doc.getClientName() : "");
        result.put("clientEmail",       doc.getClientEmail() != null ? doc.getClientEmail() : "");
        result.put("completedAt",       doc.getCompletedAt() != null ? doc.getCompletedAt().toString() : "");
        result.put("signedPdfHash",     v.storedHash() != null ? v.storedHash() : "");
        result.put("computedPdfHash",   v.computedHash() != null ? v.computedHash() : "");
        result.put("integrityVerified", v.integrityVerified());
        result.put("pdfAvailable",      v.pdfAvailable());
        result.put("verified",          "COMPLETED".equals(doc.getStatus()));

        return ResponseEntity.ok(result);
    }
}
