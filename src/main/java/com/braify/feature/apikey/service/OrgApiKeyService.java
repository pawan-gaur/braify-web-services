package com.braify.feature.apikey.service;

import com.braify.feature.apikey.model.ApiKeyUsageLog;
import com.braify.feature.apikey.model.OrgApiKey;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.apikey.repository.ApiKeyUsageLogRepository;
import com.braify.feature.apikey.repository.OrgApiKeyRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.quota.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrgApiKeyService {

    private final OrgApiKeyRepository       orgApiKeyRepo;
    private final ApiKeyUsageLogRepository  usageLogRepo;
    private final OrganizationRepository    orgRepo;
    private final AppUserRepository         appUserRepo;
    private final QuotaService              quotaService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Record returned on key creation (plain key shown only once) ───────────

    public record KeyCreatedResponse(String plainKey, OrgApiKey keyMeta) {}

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Computes SHA-256 hex digest of the given plain-text string.
     */
    private String hashKey(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Generates a cryptographically random API key with prefix "brfy_"
     * followed by 32 random lowercase hex characters.
     *
     * Example: brfy_3a9f1c2d4e5b6a7f8c9d0e1f2a3b4c5d
     */
    private String generatePlainKey() {
        byte[] bytes = new byte[16]; // 16 bytes = 32 hex chars
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("brfy_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new API key for an organisation.
     *
     * @param orgId           Target organisation ID
     * @param name            Human-readable label
     * @param allowedFeatures Subset of the org's enabled features
     * @param expiresAt       Optional expiry; null means never expires
     * @param createdBy       userId of the creating user (the platform-standard actor id;
     *                        also enforced on insert by @CreatedBy auditing)
     * @return KeyCreatedResponse containing the plain key (shown ONCE) and saved metadata
     * @throws ResponseStatusException 404 if org not found or not active
     * @throws IllegalArgumentException if allowedFeatures contains features not enabled for the org
     */
    public KeyCreatedResponse createKey(String orgId,
                                        String name,
                                        Set<String> allowedFeatures,
                                        LocalDateTime expiresAt,
                                        String createdBy) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Organisation not found: " + orgId));

        if (!org.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organisation is not active");
        }

        // Validate allowedFeatures ⊆ org.features
        if (allowedFeatures != null && !org.getFeatures().containsAll(allowedFeatures)) {
            Set<String> invalid = new java.util.HashSet<>(allowedFeatures);
            invalid.removeAll(org.getFeatures());
            throw new IllegalArgumentException(
                    "The following features are not enabled for this organisation: " + invalid);
        }

        String plainKey  = generatePlainKey();
        String keyHash   = hashKey(plainKey);
        String keyPrefix = plainKey.substring(0, Math.min(12, plainKey.length()));

        OrgApiKey key = OrgApiKey.builder()
                .orgId(orgId)
                .name(name)
                .keyPrefix(keyPrefix)
                .keyHash(keyHash)
                .allowedFeatures(allowedFeatures != null ? allowedFeatures : new java.util.HashSet<>())
                .active(true)
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .totalCalls(0L)
                .build();

        OrgApiKey saved = orgApiKeyRepo.save(key);
        log.info("API key '{}' created for org '{}' by '{}'", saved.getId(), orgId, createdBy);

        return new KeyCreatedResponse(plainKey, saved);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Returns all API keys for the given organisation, newest first.
     * The keyHash field is excluded from serialization via @JsonIgnore on the model.
     */
    public List<OrgApiKey> listKeys(String orgId) {
        return orgApiKeyRepo.findByOrgIdOrderByCreatedAtDesc(orgId);
    }

    /**
     * Returns ALL API keys across all organisations, newest first.
     * Each entry is enriched with the organisation's name so the caller
     * does not need a separate lookup.
     *
     * <p>Intended for Platform Admin use only — the controller enforces this via
     * {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")}.
     *
     * @return List of maps, each containing all OrgApiKey fields plus "orgName"
     */
    public List<Map<String, Object>> listAllKeysWithOrgName() {
        List<OrgApiKey> allKeys = orgApiKeyRepo.findAllByOrderByCreatedAtDesc();

        // Build caches of orgId → orgName and userId → email to avoid N queries
        Map<String, String> orgNameCache   = new HashMap<>();
        Map<String, String> creatorCache   = new HashMap<>();

        return allKeys.stream().map(k -> {
            String orgName = orgNameCache.computeIfAbsent(k.getOrgId(), id ->
                orgRepo.findById(id).map(Organization::getName).orElse("Unknown")
            );
            String creator = k.getCreatedBy() == null ? null
                : creatorCache.computeIfAbsent(k.getCreatedBy(), id ->
                    appUserRepo.findById(id).map(AppUser::getEmail).orElse(id));
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id",              k.getId());
            m.put("orgId",           k.getOrgId());
            m.put("orgName",         orgName);
            m.put("name",            k.getName());
            m.put("keyPrefix",       k.getKeyPrefix());
            m.put("allowedFeatures", k.getAllowedFeatures());
            m.put("active",          k.isActive());
            m.put("createdAt",       k.getCreatedAt());
            m.put("createdBy",       creator);
            m.put("lastUsedAt",      k.getLastUsedAt());
            m.put("expiresAt",       k.getExpiresAt());
            m.put("totalCalls",      k.getTotalCalls());
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    /**
     * Permanently deactivates a key (sets active=false).
     * Once revoked a key cannot be re-activated via this method; use toggleKey for that.
     */
    public OrgApiKey revokeKey(String orgId, String keyId) {
        OrgApiKey key = orgApiKeyRepo.findById(keyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "API key not found: " + keyId));

        if (!orgId.equals(key.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "API key does not belong to this organisation");
        }

        key.setActive(false);
        OrgApiKey saved = orgApiKeyRepo.save(key);
        log.info("API key '{}' revoked for org '{}'", keyId, orgId);
        return saved;
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    /**
     * Toggles the active state of a key (active ↔ inactive).
     */
    public OrgApiKey toggleKey(String orgId, String keyId) {
        OrgApiKey key = orgApiKeyRepo.findById(keyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "API key not found: " + keyId));

        if (!orgId.equals(key.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "API key does not belong to this organisation");
        }

        key.setActive(!key.isActive());
        OrgApiKey saved = orgApiKeyRepo.save(key);
        log.info("API key '{}' toggled to active={} for org '{}'", keyId, saved.isActive(), orgId);
        return saved;
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    /**
     * Validates a plain API key supplied in a request header.
     *
     * <ol>
     *   <li>Hashes the plain key and looks it up in the database.</li>
     *   <li>Verifies the key is active.</li>
     *   <li>Checks the optional expiry date.</li>
     *   <li>Verifies the owning organisation is active.</li>
     *   <li>Updates lastUsedAt and increments totalCalls.</li>
     * </ol>
     *
     * @param plainKey The raw value from the X-API-Key header
     * @return The validated OrgApiKey
     * @throws ResponseStatusException 401 if not found, inactive, or expired; 403 if org inactive
     */
    public OrgApiKey validateKey(String plainKey) {
        String hash = hashKey(plainKey);

        OrgApiKey key = orgApiKeyRepo.findByKeyHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid API key"));

        if (!key.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key is inactive");
        }

        if (key.getExpiresAt() != null && LocalDateTime.now().isAfter(key.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key expired");
        }

        Organization org = orgRepo.findById(key.getOrgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Organisation not found for this API key"));

        if (!org.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Organisation is not active");
        }

        // Update usage metadata
        key.setLastUsedAt(LocalDateTime.now());
        key.setTotalCalls(key.getTotalCalls() + 1);
        orgApiKeyRepo.save(key);

        return key;
    }

    // ── Track usage ───────────────────────────────────────────────────────────

    /**
     * Asynchronously records a usage log entry and increments the quota counter.
     * Runs in a separate thread to avoid blocking the response.
     */
    @Async
    public void trackUsage(String orgId,
                           String apiKeyId,
                           String keyPrefix,
                           String feature,
                           String endpoint,
                           String method,
                           int statusCode) {
        try {
            ApiKeyUsageLog log = ApiKeyUsageLog.builder()
                    .orgId(orgId)
                    .apiKeyId(apiKeyId)
                    .keyPrefix(keyPrefix)
                    .feature(feature)
                    .endpoint(endpoint)
                    .method(method)
                    .statusCode(statusCode)
                    .success(statusCode >= 200 && statusCode < 300)
                    .calledAt(LocalDateTime.now())
                    .build();

            usageLogRepo.save(log);
            quotaService.incrementApiCall(orgId);
        } catch (Exception e) {
            log.warn("Failed to track API key usage for apiKeyId={}: {}", apiKeyId, e.getMessage());
        }
    }

    // ── Usage queries ─────────────────────────────────────────────────────────

    /**
     * Returns usage log entries for the given organisation from the last {@code days} days.
     */
    public List<ApiKeyUsageLog> getRecentUsage(String orgId, int days) {
        LocalDateTime after = LocalDateTime.now().minusDays(days);
        return usageLogRepo.findByOrgIdAndCalledAtAfterOrderByCalledAtDesc(orgId, after);
    }

    /**
     * Returns a map of { keyId → call count } for the last 30 days,
     * covering all keys that belong to the given organisation.
     */
    public Map<String, Long> getUsageSummaryByKey(String orgId) {
        LocalDateTime after = LocalDateTime.now().minusDays(30);
        List<OrgApiKey> keys = orgApiKeyRepo.findByOrgId(orgId);

        Map<String, Long> summary = new HashMap<>();
        for (OrgApiKey key : keys) {
            long count = usageLogRepo.countByApiKeyIdAndCalledAtAfter(key.getId(), after);
            summary.put(key.getId(), count);
        }
        return summary;
    }
}
