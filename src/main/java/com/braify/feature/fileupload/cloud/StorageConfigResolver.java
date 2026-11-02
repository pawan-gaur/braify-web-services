package com.braify.feature.fileupload.cloud;

import com.braify.config.EncryptionService;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.organization.model.Organization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides which bucket an organisation's files, e-sign PDFs and logo live in.
 *
 * <ul>
 *   <li><b>Own storage</b> — an org that configured its own bucket (Settings → Cloud Storage)
 *       keeps using it, exactly as before.</li>
 *   <li><b>Managed storage</b> — every other org uses the platform bucket, under a per-org key
 *       prefix. The platform bucket is the Platform-Admin default ({@code platform_provider_defaults},
 *       any provider, with keys) when one is set, else the env-configured S3 bucket
 *       ({@code app.platform-storage.*}, may use the instance role). With neither, behaviour is
 *       unchanged ("cloud storage is not configured").</li>
 * </ul>
 *
 * <p>Reads are routed by the bucket recorded on the stored object, so objects stay readable
 * when an org later switches between managed and own storage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageConfigResolver {

    public static final String NOT_CONFIGURED =
            "Cloud storage is not configured for this organisation. "
            + "Configure it under Settings → Cloud Storage.";

    private final EncryptionService encryptionService;
    private final com.braify.feature.platform.repository.PlatformProviderDefaultsRepository platformDefaultsRepository;

    @Value("${app.platform-storage.bucket:}")
    private String bucket;

    @Value("${app.platform-storage.region:us-east-1}")
    private String region;

    /** Optional — blank uses the AWS default credential chain (e.g. the EC2 instance role). */
    @Value("${app.platform-storage.access-key:}")
    private String accessKey;

    @Value("${app.platform-storage.secret-key:}")
    private String secretKey;

    @Value("${app.platform-storage.path:tenants}")
    private String path;

    @Value("${app.platform-storage.max-upload-mb:10}")
    private int maxUploadMb;

    @Value("${app.platform-storage.url-expiry-minutes:15}")
    private int urlExpiryMinutes;

    /** True when {@code name} is a platform bucket (orgs may not configure it as their own). */
    public boolean isPlatformBucket(String name) {
        if (name == null || name.isBlank()) return false;
        OrgCloudConfig admin = adminDefault();
        return (admin != null && admin.getBucket().trim().equalsIgnoreCase(name.trim()))
            || (envEnabled() && bucket.trim().equalsIgnoreCase(name.trim()));
    }

    /** True when managed storage is available (admin default or env bucket). */
    public boolean platformEnabled() {
        return adminDefault() != null || envEnabled();
    }

    private boolean envEnabled() {
        return bucket != null && !bucket.isBlank();
    }

    /** The Platform-Admin default cloud config, when complete (provider, bucket and keys). */
    private OrgCloudConfig adminDefault() {
        OrgCloudConfig c = platformDefaultsRepository
                .findById(com.braify.feature.platform.model.PlatformProviderDefaults.SINGLETON_ID)
                .map(com.braify.feature.platform.model.PlatformProviderDefaults::getCloud)
                .orElse(null);
        return c != null && c.getCloud() != null && !blank(c.getBucket()) && !blank(c.getAccessKey()) ? c : null;
    }

    /** The platform config used for new objects: admin default first, else the env bucket. */
    private OrgCloudConfig platformConfig() {
        OrgCloudConfig admin = adminDefault();
        return admin != null ? admin : envConfig();
    }

    /** True when the org configured its own bucket (same test the services always used). */
    public static boolean hasOwnStorage(Organization org) {
        OrgCloudConfig c = org.getCloudConfig();
        return c != null && c.getCloud() != null && c.getBucket() != null && !c.getBucket().isBlank();
    }

    /** True when the org can store files at all (own bucket or managed storage). */
    public boolean isAvailable(Organization org) {
        return hasOwnStorage(org) || platformEnabled();
    }

    /** True when the org is using the platform bucket (no own storage). */
    public boolean usesManagedStorage(Organization org) {
        return !hasOwnStorage(org) && platformEnabled();
    }

    /** Config to WRITE new objects with: the org's own bucket, else managed storage. */
    public OrgCloudConfig forWrite(Organization org) {
        if (hasOwnStorage(org)) return org.getCloudConfig();
        if (platformEnabled())  return platformConfig();
        throw new IllegalStateException(NOT_CONFIGURED);
    }

    /**
     * Config to READ/DELETE an existing object recorded in {@code objectBucket}: the platform
     * config when the object lives in the platform bucket (and that isn't also the org's own
     * bucket), otherwise the org's own config — the pre-existing behaviour.
     */
    public OrgCloudConfig forRead(Organization org, String objectBucket) {
        // Which platform bucket (if any) holds the object: admin default or env bucket.
        OrgCloudConfig admin = adminDefault();
        OrgCloudConfig platformForObject =
                admin != null && admin.getBucket().equals(objectBucket) ? admin
              : envEnabled() && bucket.equals(objectBucket)            ? envConfig()
              : null;
        if (hasOwnStorage(org)) {
            boolean ownIsThatBucket = platformForObject != null
                    && platformForObject.getBucket().equals(org.getCloudConfig().getBucket());
            return platformForObject != null && !ownIsThatBucket ? platformForObject : org.getCloudConfig();
        }
        if (platformForObject != null) return platformForObject;
        if (platformEnabled()) return platformConfig();
        throw new IllegalStateException(NOT_CONFIGURED);
    }

    /** Config to WRITE objects that belong to no organisation (e.g. a Platform Admin's avatar). */
    public OrgCloudConfig forPlatformWrite() {
        if (platformEnabled()) return platformConfig();
        throw new IllegalStateException("Platform storage is not configured.");
    }

    /** Config to READ/DELETE a no-organisation object recorded in {@code objectBucket}. */
    public OrgCloudConfig forPlatformRead(String objectBucket) {
        OrgCloudConfig admin = adminDefault();
        if (admin != null && admin.getBucket().equals(objectBucket)) return admin;
        if (envEnabled() && bucket.equals(objectBucket))           return envConfig();
        return forPlatformWrite();
    }

    /**
     * The managed-storage config, shaped like an org config so every existing code path
     * (key building, upload, presign, size/type checks) works unchanged. Credentials are
     * stored encrypted like org credentials, because callers decrypt with {@code decryptSafe}.
     */
    private OrgCloudConfig envConfig() {
        return OrgCloudConfig.builder()
                .cloud(OrgCloudConfig.CloudProvider.AWS)
                .bucket(bucket.trim())
                .awsRegion(region)
                .path(path)
                .accessKey(blank(accessKey) ? null : encryptionService.encrypt(accessKey.trim()))
                .secretKey(blank(secretKey) ? null : encryptionService.encrypt(secretKey.trim()))
                .maxUploadSizeMb(maxUploadMb)
                .presignedUrlExpiration(urlExpiryMinutes)
                .status(OrgCloudConfig.ConfigStatus.ACTIVE)
                .platformManaged(true)
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
