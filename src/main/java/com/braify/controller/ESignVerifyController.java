package com.braify.controller;

import com.braify.dto.esign.DocumentResponse;
import com.braify.service.ESignClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public verification endpoint — no authentication needed.
 * Returns completion metadata so anyone can verify a signed document's integrity.
 *
 * GET /api/esign/verify/{id}
 */
@RestController
@RequestMapping("/api/esign/verify")
@RequiredArgsConstructor
public class ESignVerifyController {

    private final ESignClientService clientService;

    /**
     * Returns a summary of the completed document:
     * status, clientName, completedAt, signedPdfHash (for integrity verification).
     * Does NOT return the signed PDF bytes — use the creator's download endpoint.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String id) {
        DocumentResponse doc = clientService.verifyDocument(id);

        Map<String, Object> result = Map.of(
                "documentId",    doc.getId(),
                "title",         doc.getTitle(),
                "status",        doc.getStatus(),
                "clientName",    doc.getClientName() != null ? doc.getClientName() : "",
                "clientEmail",   doc.getClientEmail() != null ? doc.getClientEmail() : "",
                "completedAt",   doc.getCompletedAt() != null ? doc.getCompletedAt().toString() : "",
                "signedPdfHash", doc.getSignedPdfHash() != null ? doc.getSignedPdfHash() : "",
                "verified",      "COMPLETED".equals(doc.getStatus())
        );

        return ResponseEntity.ok(result);
    }
}
