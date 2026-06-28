package com.braify.feature.apikey.controller;

import com.braify.feature.apikey.dto.ApiKeyCreateRequest;
import com.braify.feature.apikey.model.ApiKeyUsageLog;
import com.braify.feature.apikey.model.OrgApiKey;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.apikey.service.OrgApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/organizations/{orgId}/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Keys", description = "Manage per-organisation API keys")
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN')")
public class OrgApiKeyController {

    private final OrgApiKeyService orgApiKeyService;
    private final AuditLogService  auditLogService;
    private final AppUserRepository appUserRepo;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserDetailsImpl principal(Authentication auth) {
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private String performedBy(Authentication auth) {
        return principal(auth).getUsername();
    }

    /**
     * Resolves a {@code createdBy} value (the platform-standard actor userId) to a
     * human-readable email for display. Gracefully passes through legacy values that
     * are not user ids (e.g. an email stored before standardisation, or "system").
     */
    private String resolveCreator(String createdBy) {
        if (createdBy == null || createdBy.isBlank()) return createdBy;
        return appUserRepo.findById(createdBy)
                .map(AppUser::getEmail)
                .orElse(createdBy);
    }

    /**
     * Enforces org isolation — non-PLATFORM_ADMIN callers can only manage their own org's keys.
     */
    private void assertOrgAccess(Authentication auth, String orgId) {
        UserDetailsImpl p = principal(auth);
        if (p.getAppUser().getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(p.getOrgId()))
            throw new AccessDeniedException("You can only manage API keys for your own organisation");
    }

    /**
     * Returns a safe view of an OrgApiKey — the keyHash field is stripped.
     * Although @JsonIgnore on the model already excludes it from serialization,
     * this explicit map gives us full control over the response shape.
     */
    private Map<String, Object> sanitize(OrgApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              k.getId());
        m.put("orgId",           k.getOrgId());
        m.put("name",            k.getName());
        m.put("keyPrefix",       k.getKeyPrefix());
        m.put("allowedFeatures", k.getAllowedFeatures());
        m.put("active",          k.isActive());
        m.put("createdAt",       k.getCreatedAt());
        m.put("createdBy",       resolveCreator(k.getCreatedBy()));
        m.put("lastUsedAt",      k.getLastUsedAt());
        m.put("expiresAt",       k.getExpiresAt());
        m.put("totalCalls",      k.getTotalCalls());
        return m;
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * Lists all API keys for the organisation, newest first.
     * The keyHash field is never included in the response.
     *
     * GET /api/organizations/{orgId}/api-keys
     */
    @GetMapping
    @Operation(summary = "List API keys", description = "Returns all API keys for the organisation, keyHash excluded.")
    public List<Map<String, Object>> listKeys(@PathVariable String orgId, Authentication auth) {
        assertOrgAccess(auth, orgId);
        log.debug("GET /api/organizations/{}/api-keys", orgId);
        return orgApiKeyService.listKeys(orgId)
                .stream()
                .map(this::sanitize)
                .collect(Collectors.toList());
    }

    /**
     * Creates a new API key for the organisation.
     * The plain key is returned ONCE in this response and cannot be retrieved again.
     *
     * POST /api/organizations/{orgId}/api-keys
     */
    @PostMapping
    @Operation(summary = "Create API key",
               description = "Generates a new API key. The plain key is returned once and cannot be retrieved again.")
    public ResponseEntity<Map<String, Object>> createKey(
            @PathVariable String orgId,
            @Valid @RequestBody ApiKeyCreateRequest request,
            Authentication auth) {

        assertOrgAccess(auth, orgId);
        String performer = performedBy(auth);          // email — for the audit trail & logs
        String creatorId = principal(auth).getId();    // userId — stored as the key's createdBy (also enforced by @CreatedBy auditing)
        log.info("POST /api/organizations/{}/api-keys name='{}' by '{}'", orgId, request.getName(), performer);

        OrgApiKeyService.KeyCreatedResponse result = orgApiKeyService.createKey(
                orgId,
                request.getName(),
                request.getAllowedFeatures(),
                request.getExpiresAt(),
                creatorId
        );

        // Audit the creation event
        auditLogService.log(
                result.keyMeta().getId(),
                result.keyMeta().getName(),
                AuditLog.Action.API_KEY_CREATED,
                AuditLog.ResourceType.API_KEY,
                0,
                Map.of("allowedFeatures", result.keyMeta().getAllowedFeatures()),
                performer,
                orgId
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plainKey", result.plainKey());
        body.put("keyMeta",  sanitize(result.keyMeta()));

        log.info("API key created: id='{}' for org '{}'", result.keyMeta().getId(), orgId);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Revokes (deactivates) an API key permanently.
     *
     * DELETE /api/organizations/{orgId}/api-keys/{keyId}
     */
    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke API key", description = "Deactivates an API key. The key will immediately stop working.")
    public void revokeKey(@PathVariable String orgId,
                          @PathVariable String keyId,
                          Authentication auth) {
        assertOrgAccess(auth, orgId);
        String performer = performedBy(auth);
        log.info("DELETE /api/organizations/{}/api-keys/{} by '{}'", orgId, keyId, performer);
        OrgApiKey revoked = orgApiKeyService.revokeKey(orgId, keyId);

        auditLogService.log(
                revoked.getId(),
                revoked.getName(),
                AuditLog.Action.API_KEY_REVOKED,
                AuditLog.ResourceType.API_KEY,
                0,
                null,
                performer,
                orgId
        );
        log.info("API key '{}' revoked for org '{}'", keyId, orgId);
    }

    /**
     * Toggles an API key between active and inactive states.
     *
     * PATCH /api/organizations/{orgId}/api-keys/{keyId}/toggle
     */
    @PatchMapping("/{keyId}/toggle")
    @Operation(summary = "Toggle API key", description = "Flips the active state of an API key.")
    public Map<String, Object> toggleKey(@PathVariable String orgId,
                                         @PathVariable String keyId,
                                         Authentication auth) {
        assertOrgAccess(auth, orgId);
        String performer = performedBy(auth);
        log.info("PATCH /api/organizations/{}/api-keys/{}/toggle by '{}'", orgId, keyId, performer);
        OrgApiKey toggled = orgApiKeyService.toggleKey(orgId, keyId);

        auditLogService.log(
                toggled.getId(),
                toggled.getName(),
                AuditLog.Action.API_KEY_TOGGLED,
                AuditLog.ResourceType.API_KEY,
                0,
                Map.of("active", toggled.isActive()),
                performer,
                orgId
        );
        log.info("API key '{}' toggled to active={} for org '{}'", keyId, toggled.isActive(), orgId);
        return sanitize(toggled);
    }

    /**
     * Returns recent usage logs and a per-key summary for the last 30 days.
     *
     * GET /api/organizations/{orgId}/api-keys/usage
     */
    @GetMapping("/usage")
    @Operation(summary = "Get API key usage",
               description = "Returns recent usage logs (last 30 days) and a per-key call-count summary.")
    public Map<String, Object> getUsage(@PathVariable String orgId, Authentication auth) {
        assertOrgAccess(auth, orgId);
        log.debug("GET /api/organizations/{}/api-keys/usage", orgId);
        List<ApiKeyUsageLog> logs  = orgApiKeyService.getRecentUsage(orgId, 30);
        Map<String, Long>  summary = orgApiKeyService.getUsageSummaryByKey(orgId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recentLogs",    logs);
        result.put("summaryByKey",  summary);
        return result;
    }
}
