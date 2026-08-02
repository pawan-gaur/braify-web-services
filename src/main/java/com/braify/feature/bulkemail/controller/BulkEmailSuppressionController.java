package com.braify.feature.bulkemail.controller;

import com.braify.feature.bulkemail.model.EmailSuppression;
import com.braify.feature.bulkemail.service.BulkEmailService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Manage the org's email suppression (unsubscribe) list. Addresses here are excluded from
 * every future bulk-email campaign. Entries are added automatically when a recipient clicks
 * the unsubscribe link, or manually here.
 */
@Slf4j
@Tag(name = "Bulk Email Suppressions", description = "Per-organisation unsubscribe / do-not-email list.")
@RestController
@RequestMapping("/api/bulk-email/suppressions")
@RequiredArgsConstructor
public class BulkEmailSuppressionController {

    private final BulkEmailService bulkEmailService;

    @Operation(summary = "List suppressed addresses for the caller's organisation")
    @GetMapping
    public ResponseEntity<List<EmailSuppression>> list(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(bulkEmailService.listSuppressions(principal));
    }

    @Operation(summary = "Manually add an address to the suppression list (idempotent)")
    @PostMapping
    public ResponseEntity<EmailSuppression> add(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(bulkEmailService.addSuppression(body.get("email"), principal));
    }

    @Operation(summary = "Remove an address from the suppression list (re-allows emailing it)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        bulkEmailService.removeSuppression(id, principal);
        return ResponseEntity.noContent().build();
    }
}
