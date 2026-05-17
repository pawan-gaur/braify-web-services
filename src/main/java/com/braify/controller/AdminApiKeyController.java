package com.braify.controller;

import com.braify.service.OrgApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Platform-Admin–only endpoints for managing API keys across all organisations.
 *
 * <p>These endpoints are separate from {@link OrgApiKeyController} (which is scoped
 * to a single org) because the admin view needs a flat, cross-org list enriched
 * with organisation names.
 *
 * <p>Base path: {@code /api/admin/api-keys}
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
@Tag(name = "Admin API Keys", description = "Platform-Admin endpoints for cross-org API key management")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminApiKeyController {

    private final OrgApiKeyService orgApiKeyService;

    /**
     * Returns every API key across all organisations, newest first.
     * Each entry includes an {@code orgName} field so the frontend can group or
     * filter without a separate org lookup.
     *
     * <p>GET /api/admin/api-keys
     */
    @GetMapping
    @Operation(summary = "List all API keys (all orgs)",
               description = "Returns all API keys across every organisation, enriched with orgName. Platform Admin only.")
    public List<Map<String, Object>> listAllKeys() {
        return orgApiKeyService.listAllKeysWithOrgName();
    }
}
