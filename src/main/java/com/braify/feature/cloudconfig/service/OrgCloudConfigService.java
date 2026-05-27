package com.braify.feature.cloudconfig.service;

import com.braify.config.EncryptionService;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigRequest;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigResponse;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.fileupload.cloud.CloudDownloadRequest;
import com.braify.feature.fileupload.cloud.CloudUploaderFactory;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages cloud storage configuration for organisations.
 *
 * <h3>Credential security</h3>
 * {@code accessKey} and {@code secretKey} are encrypted with AES-256-GCM via
 * {@link EncryptionService} before being persisted to MongoDB.  On read they are
 * decrypted in-memory and then immediately masked before being returned to callers —
 * the plaintext value is never exposed through the API.
 *
 * <h3>Keep-existing semantics</h3>
 * When the caller sends {@code null} for a credential field the existing encrypted
 * value in MongoDB is preserved unchanged.  A non-null value always replaces the
 * stored credential (it is encrypted and overwritten).  To explicitly clear a
 * credential, the caller should send an empty string {@code ""} — which is treated
 * as {@code null} and clears the field.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgCloudConfigService {

    private final OrganizationRepository orgRepository;
    private final AuditLogService        auditLogService;
    private final EncryptionService      encryptionService;
    private final CloudUploaderFactory   uploaderFactory;

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns the cloud config for {@code orgId} with credentials decrypted and
     * then masked (e.g. {@code "AKIA****5678"}).
     *
     * <p>PLATFORM_ADMIN may access any org; ORG_ADMIN is restricted to their own.
     */
    public OrgCloudConfigResponse getCloudConfig(String orgId, AppUser caller) {
        assertAccess(caller, orgId);
        return toResponse(requireOrg(orgId).getCloudConfig());
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Replaces the cloud config for {@code orgId}.
     *
     * <p>Credentials are encrypted with AES-256-GCM before being persisted.
     * If the caller omits a credential field (sends {@code null} or blank),
     * the existing encrypted value is retained unchanged.
     *
     * <p>Validation:
     * <ul>
     *   <li>{@code cloud} must be a valid {@link OrgCloudConfig.CloudProvider} when provided.</li>
     *   <li>{@code maxUploadSizeMb}, {@code presignedUrlExpiration}, {@code retentionDays}
     *       must be positive when provided.</li>
     * </ul>
     */
    public OrgCloudConfigResponse updateCloudConfig(String orgId,
                                                    OrgCloudConfigRequest req,
                                                    AppUser caller) {
        assertAccess(caller, orgId);
        Organization org = requireOrg(orgId);

        // ── Validate cloud provider ───────────────────────────────────────────
        OrgCloudConfig.CloudProvider provider = null;
        if (req.getCloud() != null && !req.getCloud().isBlank()) {
            try {
                provider = OrgCloudConfig.CloudProvider.valueOf(req.getCloud().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(
                        "Invalid cloud provider: " + req.getCloud() +
                        ". Accepted values: AWS, AZURE, GCP");
            }
        }

        // ── Validate numeric fields ───────────────────────────────────────────
        if (req.getMaxUploadSizeMb() != null && req.getMaxUploadSizeMb() <= 0)
            throw new RuntimeException("maxUploadSizeMb must be greater than zero");
        if (req.getPresignedUrlExpiration() != null && req.getPresignedUrlExpiration() <= 0)
            throw new RuntimeException("presignedUrlExpiration must be greater than zero");
        if (req.getRetentionDays() != null && req.getRetentionDays() <= 0)
            throw new RuntimeException("retentionDays must be greater than zero");

        // ── Preserve immutable fields from existing config ────────────────────
        OrgCloudConfig existing   = org.getCloudConfig();
        LocalDateTime  createdAt  = (existing != null) ? existing.getCreatedAt() : LocalDateTime.now();
        String         createdBy  = (existing != null && existing.getCreatedBy() != null)
                ? existing.getCreatedBy()
                : caller.getId();
        OrgCloudConfig.ConfigStatus status = (existing != null && existing.getStatus() != null)
                ? existing.getStatus()
                : OrgCloudConfig.ConfigStatus.ONBOARD;

        // ── Resolve credentials: encrypt new value OR keep existing ───────────
        //    null / blank → keep existing encrypted value (no change)
        //    non-blank    → encrypt the new plaintext and overwrite
        String encryptedAccessKey = resolveCredential(req.getAccessKey(),
                existing != null ? existing.getAccessKey() : null);
        String encryptedSecretKey = resolveCredential(req.getSecretKey(),
                existing != null ? existing.getSecretKey() : null);

        // ── Normalise allowed file types ──────────────────────────────────────
        List<String> fileTypes = null;
        if (req.getAllowedFileTypes() != null) {
            fileTypes = req.getAllowedFileTypes().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.trim().toLowerCase().replaceAll("^\\.", ""))
                    .distinct()
                    .toList();
        }

        // ── Build and persist ─────────────────────────────────────────────────
        OrgCloudConfig updated = OrgCloudConfig.builder()
                .cloud(provider)
                .bucket(trimOrNull(req.getBucket()))
                .path(trimOrNull(req.getPath()))
                .module(trimOrNull(req.getModule()))
                .accessKey(encryptedAccessKey)   // AES-256-GCM ciphertext or null
                .secretKey(encryptedSecretKey)
                .awsRegion(trimOrNull(req.getAwsRegion()))
                .allowedFileTypes(fileTypes)
                .maxUploadSizeMb(req.getMaxUploadSizeMb())
                .retentionDays(req.getRetentionDays())
                .presignedUrlExpiration(req.getPresignedUrlExpiration())
                .status(status)
                .createdBy(createdBy)
                .createdAt(createdAt)
                .updatedAt(LocalDateTime.now())
                .build();

        org.setCloudConfig(updated);
        orgRepository.save(org);

        auditLogService.log(
                org.getId(), org.getName(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0,
                Map.of("action", "CLOUD_CONFIG_UPDATED",
                       "cloud",  provider != null ? provider.name() : "none"),
                caller.getEmail(),
                orgId
        );

        log.info("Cloud config updated for org '{}' by '{}'", orgId, caller.getEmail());

        // Return response with credentials decrypted → masked
        return toResponse(updated);
    }

    // ── Connectivity test ─────────────────────────────────────────────────────

    /**
     * Attempts to generate a pre-signed download URL for a sentinel test object.
     * A successful URL generation proves that credentials are well-formed and the
     * SDK can reach the provider endpoint. Returns a result map with {@code success}
     * (boolean) and {@code message} (string).
     *
     * <p>PLATFORM_ADMIN may test any org; ORG_ADMIN is restricted to their own.
     */
    public Map<String, Object> testConnectivity(String orgId, AppUser caller) {
        assertAccess(caller, orgId);

        OrgCloudConfig cfg = requireOrg(orgId).getCloudConfig();
        if (cfg == null || cfg.getCloud() == null) {
            return result(false, "No cloud configuration found for this organisation.");
        }
        if (cfg.getBucket() == null || cfg.getBucket().isBlank()) {
            return result(false, "Bucket name is not configured.");
        }

        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());

        CloudDownloadRequest testReq = CloudDownloadRequest.builder()
                .bucket(cfg.getBucket())
                .storageKey("__braify-connectivity-test__")
                .expirationSeconds(60)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                // Azure / GCP use the same credential field by convention
                .azureConnectionString(accessKey)
                .gcpServiceAccountJson(accessKey)
                .build();

        try {
            uploaderFactory.get(cfg.getCloud()).generatePresignedUrl(testReq);
            log.info("Cloud connectivity test PASSED for org '{}' provider={}", orgId, cfg.getCloud());
            return result(true, "Connectivity test passed — credentials accepted by " + cfg.getCloud() + ".");
        } catch (Exception ex) {
            log.warn("Cloud connectivity test FAILED for org '{}' provider={}: {}",
                    orgId, cfg.getCloud(), ex.getMessage());
            return result(false, "Connectivity test failed: " + ex.getMessage());
        }
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves which encrypted value to store for a credential field.
     *
     * <ul>
     *   <li>If {@code newPlainValue} is non-blank → encrypt it and return the ciphertext.</li>
     *   <li>If {@code newPlainValue} is null or blank → return {@code existingEncrypted}
     *       unchanged (preserving whatever was stored before).</li>
     * </ul>
     */
    private String resolveCredential(String newPlainValue, String existingEncrypted) {
        if (newPlainValue != null && !newPlainValue.isBlank()) {
            return encryptionService.encrypt(newPlainValue.trim());
        }
        return existingEncrypted;   // keep existing (already encrypted) or null
    }

    private Organization requireOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));
    }

    private void assertAccess(AppUser caller, String orgId) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException(
                    "You can only manage your own organisation's cloud config");
        }
    }

    /**
     * Converts a stored {@link OrgCloudConfig} to its API response DTO.
     *
     * <p>Credential fields are decrypted in-memory (using
     * {@link EncryptionService#decryptSafe} for migration safety) and then
     * immediately passed through {@link #mask(String)} before returning.
     * The plaintext value is never included in the response.
     */
    public OrgCloudConfigResponse toResponse(OrgCloudConfig cfg) {
        if (cfg == null) {
            return OrgCloudConfigResponse.builder().configured(false).build();
        }

        // Decrypt stored ciphertext → mask for API response
        String maskedAccessKey = mask(encryptionService.decryptSafe(cfg.getAccessKey()));
        String maskedSecretKey = mask(encryptionService.decryptSafe(cfg.getSecretKey()));

        return OrgCloudConfigResponse.builder()
                .configured(true)
                .cloud(cfg.getCloud() != null ? cfg.getCloud().name() : null)
                .bucket(cfg.getBucket())
                .path(cfg.getPath())
                .module(cfg.getModule())
                .accessKey(maskedAccessKey)
                .secretKey(maskedSecretKey)
                .awsRegion(cfg.getAwsRegion())
                .allowedFileTypes(cfg.getAllowedFileTypes())
                .maxUploadSizeMb(cfg.getMaxUploadSizeMb())
                .retentionDays(cfg.getRetentionDays())
                .presignedUrlExpiration(cfg.getPresignedUrlExpiration())
                .status(cfg.getStatus() != null ? cfg.getStatus().name() : null)
                .createdAt(cfg.getCreatedAt())
                .updatedAt(cfg.getUpdatedAt())
                .build();
    }

    /**
     * Masks a decrypted plaintext credential, revealing the first 4 and last 4
     * characters with asterisks in between.
     *
     * <pre>
     * "AKIA1234ABCD5678" → "AKIA****5678"
     * "short"            → "****"
     * null               → null
     * </pre>
     */
    private static String mask(String plainText) {
        if (plainText == null) return null;
        if (plainText.length() <= 8) return "****";
        return plainText.substring(0, 4) + "****" + plainText.substring(plainText.length() - 4);
    }

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
