package com.braify.feature.esign.service;

import com.braify.config.EncryptionService;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.cloudconfig.service.CloudConfigResolver;
import com.braify.feature.fileupload.cloud.CloudDownloadRequest;
import com.braify.feature.fileupload.cloud.CloudUploadRequest;
import com.braify.feature.fileupload.cloud.CloudUploader;
import com.braify.feature.fileupload.cloud.CloudUploaderFactory;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stores e-sign PDFs (source + signed) in the organisation's configured cloud bucket —
 * the same mechanism the File Storage feature uses — instead of embedding the bytes in
 * MongoDB. The {@code esign_documents} record keeps only a storage reference
 * (bucket + key + provider); content is fetched via {@link #download} (server-side, e.g.
 * for signature stamping / email attachment) or {@link #presignedUrl} (for client viewing).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ESignStorageService {

    private final OrganizationRepository orgRepository;
    private final CloudUploaderFactory   uploaderFactory;
    private final EncryptionService      encryptionService;
    private final CloudConfigResolver    cloudConfigResolver;

    /** Storage reference persisted on the e-sign document. */
    public record StoredPdf(String bucket, String storageKey, String provider) {}

    public boolean isCloudConfigured(String orgId) {
        try { requireConfig(orgId); return true; } catch (RuntimeException e) { return false; }
    }

    // ── Document-aware resolvers (cloud key preferred, legacy embedded bytes fallback) ──

    /** Source PDF bytes for a document — from cloud if stored there, else legacy embedded bytes. */
    public byte[] resolveSourceBytes(ESignDocument doc) {
        if (doc.getSourcePdfKey() != null)
            return download(doc.getOrgId(), doc.getPdfBucket(), doc.getSourcePdfKey());
        return doc.getSourcePdfData();
    }

    /** Signed PDF bytes for a document — from cloud if stored there, else legacy embedded bytes. */
    public byte[] resolveSignedBytes(ESignDocument doc) {
        if (doc.getSignedPdfKey() != null)
            return download(doc.getOrgId(), doc.getPdfBucket(), doc.getSignedPdfKey());
        return doc.getSignedPdfData();
    }

    /** Pre-signed URL for the source PDF, or {@code null} for legacy (embedded) documents. */
    public String sourcePresignedUrl(ESignDocument doc) {
        return doc.getSourcePdfKey() != null
                ? presignedUrl(doc.getOrgId(), doc.getPdfBucket(), doc.getSourcePdfKey()) : null;
    }

    /** Pre-signed URL for the signed PDF, or {@code null} for legacy (embedded) documents. */
    public String signedPresignedUrl(ESignDocument doc) {
        return doc.getSignedPdfKey() != null
                ? presignedUrl(doc.getOrgId(), doc.getPdfBucket(), doc.getSignedPdfKey()) : null;
    }

    /** Uploads the source PDF and returns its storage reference. */
    public StoredPdf uploadSourcePdf(String orgId, String docId, byte[] bytes) {
        return upload(orgId, key(orgId, docId, "source.pdf"), bytes);
    }

    /** Uploads the signed PDF and returns its storage reference. */
    public StoredPdf uploadSignedPdf(String orgId, String docId, byte[] bytes) {
        return upload(orgId, key(orgId, docId, "signed.pdf"), bytes);
    }

    /** Downloads object bytes (server-side) — for signature stamping or email attachment. */
    public byte[] download(String orgId, String bucket, String storageKey) {
        OrgCloudConfig cfg = requireConfig(orgId);
        CloudUploader uploader = uploaderFactory.get(cfg.getCloud());
        return uploader.download(downloadRequest(cfg, bucket, storageKey, 0));
    }

    /** Generates a time-limited pre-signed URL for client-side viewing/download. */
    public String presignedUrl(String orgId, String bucket, String storageKey) {
        OrgCloudConfig cfg = requireConfig(orgId);
        int ttl = cfg.getPresignedUrlExpiration() != null
                ? cfg.getPresignedUrlExpiration() * 60   // stored in minutes
                : 3600;
        CloudUploader uploader = uploaderFactory.get(cfg.getCloud());
        return uploader.generatePresignedUrl(downloadRequest(cfg, bucket, storageKey, ttl));
    }

    /** Best-effort delete (e.g. when a document is hard-deleted). Never throws. */
    public void deleteQuietly(String orgId, String bucket, String storageKey) {
        if (bucket == null || storageKey == null) return;
        try {
            OrgCloudConfig cfg = requireConfig(orgId);
            uploaderFactory.get(cfg.getCloud()).delete(downloadRequest(cfg, bucket, storageKey, 0));
        } catch (Exception e) {
            log.warn("E-sign PDF delete failed (key={}): {}", storageKey, e.getMessage());
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private StoredPdf upload(String orgId, String storageKey, byte[] bytes) {
        OrgCloudConfig cfg = requireConfig(orgId);
        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());

        CloudUploadRequest req = CloudUploadRequest.builder()
                .bucket(cfg.getBucket())
                .storageKey(storageKey)
                .data(bytes)
                .contentType("application/pdf")
                .originalFilename(storageKey.substring(storageKey.lastIndexOf('/') + 1))
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                .azureConnectionString(accessKey)   // Azure: accessKey = connection string
                .gcpServiceAccountJson(secretKey)   // GCP:   secretKey = service-account JSON
                .build();

        uploaderFactory.get(cfg.getCloud()).upload(req);
        log.info("E-sign PDF uploaded: org={} key={} size={}B cloud={}",
                orgId, storageKey, bytes.length, cfg.getCloud());
        return new StoredPdf(cfg.getBucket(), storageKey, cfg.getCloud().name());
    }

    private CloudDownloadRequest downloadRequest(OrgCloudConfig cfg, String bucket,
                                                 String storageKey, int ttlSeconds) {
        String accessKey = encryptionService.decryptSafe(cfg.getAccessKey());
        String secretKey = encryptionService.decryptSafe(cfg.getSecretKey());
        return CloudDownloadRequest.builder()
                .bucket(bucket != null ? bucket : cfg.getBucket())
                .storageKey(storageKey)
                .expirationSeconds(ttlSeconds)
                .awsRegion(cfg.getAwsRegion())
                .awsAccessKeyId(accessKey)
                .awsSecretAccessKey(secretKey)
                .azureConnectionString(accessKey)
                .gcpServiceAccountJson(secretKey)
                .build();
    }

    /** Object key: {@code <cfg.path>/<orgId>/esign/<docId>/<name>}. */
    private String key(String orgId, String docId, String name) {
        OrgCloudConfig cfg = requireConfig(orgId);
        String base = (cfg.getPath() != null && !cfg.getPath().isBlank())
                ? cfg.getPath().replaceAll("/$", "") + "/" + orgId
                : orgId;
        return base + "/esign/" + docId + "/" + name;
    }

    private OrgCloudConfig requireConfig(String orgId) {
        // Falls back to the platform-admin default when the org has no usable config.
        return cloudConfigResolver.resolveByOrgId(orgId);
    }
}
