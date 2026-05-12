package com.braify.controller;

import com.braify.dto.esign.DocumentResponse;
import com.braify.dto.esign.SignFieldRequest;
import com.braify.service.ESignClientService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public client signing endpoints — no user auth required.
 * Access is controlled by the ESIGN signing JWT in the Authorization header.
 *
 * All routes: /api/esign/sign/{token}/**
 * SecurityConfig permits /api/esign/sign/** without user auth.
 * JwtAuthFilter skips ESIGN tokens, so we validate them manually here.
 */
@RestController
@RequestMapping("/api/esign/sign")
@RequiredArgsConstructor
public class ESignClientController {

    private final ESignClientService clientService;

    /**
     * GET /api/esign/sign/{token}
     * Validates the signing token, marks the document as IN_REVIEW on first open,
     * and returns the document with source PDF for rendering in the signing UI.
     */
    @GetMapping("/{token}")
    public ResponseEntity<DocumentResponse> openDocument(
            @PathVariable String token,
            HttpServletRequest http) {

        DocumentResponse doc = clientService.openDocument(
                token, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(doc);
    }

    /**
     * PUT /api/esign/sign/{token}/fields/{fieldId}
     * Signs a single field (DRAW / TYPE / UPLOAD).
     */
    @PutMapping("/{token}/fields/{fieldId}")
    public ResponseEntity<DocumentResponse.FieldResponse> signField(
            @PathVariable String token,
            @PathVariable String fieldId,
            @RequestBody SignFieldRequest req,
            HttpServletRequest http) {

        DocumentResponse.FieldResponse field = clientService.signField(
                token, fieldId, req, extractIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(field);
    }

    /**
     * POST /api/esign/sign/{token}/submit
     * Submits the document after all required fields are signed.
     * Triggers async PDF stamping and completion emails.
     */
    @PostMapping("/{token}/submit")
    public ResponseEntity<DocumentResponse> submitDocument(
            @PathVariable String token,
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
