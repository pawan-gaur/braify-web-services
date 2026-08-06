package com.braify.feature.branding.service;

import com.braify.config.EncryptionService;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.cloudconfig.service.CloudConfigResolver;
import com.braify.feature.fileupload.cloud.CloudDownloadRequest;
import com.braify.feature.fileupload.cloud.CloudUploadRequest;
import com.braify.feature.fileupload.cloud.CloudUploaderFactory;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stores an organisation's branding logo in its configured cloud bucket and reads it back
 * server-side (for the public logo endpoint). Mirrors {@code ESignStorageService} but for the
 * single, overwritable {@code branding/logo} object per org.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandingLogoStorage {

    private final OrganizationRepository orgRepository;
    private final CloudUploaderFactory   uploaderFactory;
    private final EncryptionService      encryptionService;
    private final CloudConfigResolver    cloudConfigResolver;

    /** Cloud reference for a stored logo. */
    public record StoredLogo(String bucket, String key, String provider, String contentType) {}

    public boolean isCloudConfigured(String orgId) {
        try { requireConfig(orgId); return true; } catch (RuntimeException e) { return false; }
    }

    /** Uploads the logo bytes to the org bucket at a stable key and returns its reference. */
    public StoredLogo upload(String orgId, byte[] bytes, String contentType, String ext) {
        OrgCloudConfig cfg = requireConfig(orgId);
        String key = keyBase(cfg, orgId) + "/branding/logo" + (ext != null && !ext.isBlank() ? "." + ext : "");
        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());

        CloudUploadRequest req = CloudUploadRequest.builder()
                .bucket(cfg.getBucket())
                .storageKey(key)
                .data(bytes)
                .contentType(contentType != null ? contentType : "image/png")
                .originalFilename("logo" + (ext != null ? "." + ext : ""))
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                .azureConnectionString(accessKey)   // Azure: accessKey = connection string
                .gcpServiceAccountJson(secretKey)   // GCP:   secretKey = service-account JSON
                .build();

        uploaderFactory.get(cfg.getCloud()).upload(req);
        log.info("Branding logo uploaded: org={} key={} size={}B cloud={}", orgId, key, bytes.length, cfg.getCloud());
        return new StoredLogo(cfg.getBucket(), key, cfg.getCloud().name(),
                contentType != null ? contentType : "image/png");
    }

    /** Downloads the logo bytes (server-side) for the public streaming endpoint. */
    public byte[] download(String orgId, String bucket, String key) {
        OrgCloudConfig cfg = requireConfig(orgId);
        return uploaderFactory.get(cfg.getCloud()).download(downloadRequest(cfg, bucket, key));
    }

    /** Best-effort delete of a previously stored logo; never throws. */
    public void deleteQuietly(String orgId, String bucket, String key) {
        if (bucket == null || key == null) return;
        try {
            OrgCloudConfig cfg = requireConfig(orgId);
            uploaderFactory.get(cfg.getCloud()).delete(downloadRequest(cfg, bucket, key));
        } catch (Exception e) {
            log.warn("Branding logo delete failed (key={}): {}", key, e.getMessage());
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private CloudDownloadRequest downloadRequest(OrgCloudConfig cfg, String bucket, String key) {
        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());
        return CloudDownloadRequest.builder()
                .bucket(bucket != null ? bucket : cfg.getBucket())
                .storageKey(key)
                .expirationSeconds(0)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                .azureConnectionString(accessKey)
                .gcpServiceAccountJson(secretKey)
                .build();
    }

    private String keyBase(OrgCloudConfig cfg, String orgId) {
        return (cfg.getPath() != null && !cfg.getPath().isBlank())
                ? cfg.getPath().replaceAll("/$", "") + "/" + orgId
                : orgId;
    }

    private OrgCloudConfig requireConfig(String orgId) {
        // Falls back to the platform-admin default when the org has no usable config.
        return cloudConfigResolver.resolveByOrgId(orgId);
    }
}
