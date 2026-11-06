package com.braify.feature.fileupload.cloud;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CloudUploader} implementation backed by AWS S3 (SDK v2).
 *
 * <p>Clients are built from the credentials supplied in {@link CloudUploadRequest} /
 * {@link CloudDownloadRequest} (so each organisation uses its own IAM credentials) and
 * cached per (region, credentials) — see {@code clients} below.
 */
@Slf4j
@Component
public class AwsS3Uploader implements CloudUploader {

    // ── Upload ────────────────────────────────────────────────────────────────

    @Override
    public CloudUploadResult upload(CloudUploadRequest req) {
        try {
            S3Client s3 = buildClient(req.getAwsRegion(), req.getAwsAccessKeyId(), req.getAwsSecretAccessKey(), req.isUseDefaultCredentials());   // cached, never closed per call

            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(req.getBucket())
                    .key(req.getStorageKey())
                    .contentType(req.getContentType())
                    .metadata(java.util.Map.of(
                            "original-filename", sanitize(req.getOriginalFilename())
                    ))
                    .build();

            s3.putObject(putReq, RequestBody.fromBytes(req.getData()));

            log.info("S3 upload OK: bucket={} key={} size={}B",
                    req.getBucket(), req.getStorageKey(), req.getData().length);

            return CloudUploadResult.builder()
                    .bucket(req.getBucket())
                    .storageKey(req.getStorageKey())
                    .build();

        } catch (Exception e) {
            log.error("S3 upload failed: bucket={} key={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }
    }

    // ── Pre-signed URL ────────────────────────────────────────────────────────

    @Override
    public String generatePresignedUrl(CloudDownloadRequest req) {
        try {
            S3Presigner presigner = buildPresigner(req.getAwsRegion(),
                                                    req.getAwsAccessKeyId(),
                                                    req.getAwsSecretAccessKey(),
                                                    req.isUseDefaultCredentials());   // cached, never closed per call

            GetObjectRequest getReq = GetObjectRequest.builder()
                    .bucket(req.getBucket())
                    .key(req.getStorageKey())
                    .build();

            GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(req.getExpirationSeconds()))
                    .getObjectRequest(getReq)
                    .build();

            String url = presigner.presignGetObject(presignReq).url().toString();
            log.debug("S3 presigned URL generated: bucket={} key={} ttl={}s",
                    req.getBucket(), req.getStorageKey(), req.getExpirationSeconds());
            return url;

        } catch (Exception e) {
            log.error("S3 presign failed: bucket={} key={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("S3 presigned URL generation failed: " + e.getMessage(), e);
        }
    }

    // ── Download (server-side bytes) ────────────────────────────────────────────

    @Override
    public byte[] download(CloudDownloadRequest req) {
        try {
            S3Client s3 = buildClient(req.getAwsRegion(), req.getAwsAccessKeyId(), req.getAwsSecretAccessKey(), req.isUseDefaultCredentials());   // cached, never closed per call
            GetObjectRequest getReq = GetObjectRequest.builder()
                    .bucket(req.getBucket())
                    .key(req.getStorageKey())
                    .build();
            byte[] bytes = s3.getObjectAsBytes(getReq).asByteArray();
            log.info("S3 download OK: bucket={} key={} size={}B", req.getBucket(), req.getStorageKey(), bytes.length);
            return bytes;
        } catch (Exception e) {
            log.error("S3 download failed: bucket={} key={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("S3 download failed: " + e.getMessage(), e);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public void delete(CloudDownloadRequest req) {
        try {
            S3Client s3 = buildClient(req.getAwsRegion(), req.getAwsAccessKeyId(), req.getAwsSecretAccessKey(), req.isUseDefaultCredentials());   // cached, never closed per call
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(req.getBucket())
                    .key(req.getStorageKey())
                    .build());
            log.info("S3 delete OK: bucket={} key={}", req.getBucket(), req.getStorageKey());
        } catch (Exception e) {
            log.error("S3 delete failed: bucket={} key={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("S3 delete failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /*
     * S3Client / S3Presigner are thread-safe and expensive to build (HTTP pool, credential
     * resolution — for the default chain an IMDS round trip), so they are cached per
     * (region, credentials) instead of being built and closed on every call. The key holds a
     * SHA-256 of the keys (or "default-chain"), never the secret itself. Rotated keys simply
     * produce a new entry; all cached clients are closed on shutdown.
     */
    private final ConcurrentHashMap<String, S3Client>    clients    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, S3Presigner> presigners = new ConcurrentHashMap<>();

    private S3Client buildClient(String region, String accessKeyId, String secretKey, boolean useDefault) {
        String r = region != null ? region : "us-east-1";
        return clients.computeIfAbsent(cacheKey("s3", r, accessKeyId, secretKey, useDefault), k -> S3Client.builder()
                .region(Region.of(r))
                .credentialsProvider(credentials(accessKeyId, secretKey, useDefault))
                .build());
    }

    private S3Presigner buildPresigner(String region, String accessKeyId, String secretKey, boolean useDefault) {
        String r = region != null ? region : "us-east-1";
        return presigners.computeIfAbsent(cacheKey("s3-presign", r, accessKeyId, secretKey, useDefault), k -> S3Presigner.builder()
                .region(Region.of(r))
                .credentialsProvider(credentials(accessKeyId, secretKey, useDefault))
                .build());
    }

    /** Mirrors {@link #credentials}: the default chain gets its own key, static keys are hashed. */
    private static String cacheKey(String kind, String region, String accessKeyId, String secretKey, boolean useDefault) {
        boolean noKeys = accessKeyId == null || accessKeyId.isBlank() || secretKey == null || secretKey.isBlank();
        return (useDefault && noKeys)
                ? kind + "|" + region + "|" + CloudClientKeys.DEFAULT_CHAIN
                : CloudClientKeys.of(kind, region, accessKeyId, secretKey);
    }

    @PreDestroy
    void closeCachedClients() {
        clients.values().forEach(c -> { try { c.close(); } catch (Exception ignored) { } });
        presigners.values().forEach(p -> { try { p.close(); } catch (Exception ignored) { } });
        clients.clear();
        presigners.clear();
    }

    /**
     * The AWS default credential chain (env vars / EC2 instance role) is used ONLY for the
     * platform's managed-storage bucket ({@code useDefault}), and only when it has no explicit
     * keys. Org-configured buckets always use their own keys — a keyless org config fails, so an
     * org can never borrow the platform's identity to reach other buckets.
     */
    private static AwsCredentialsProvider credentials(String accessKeyId, String secretKey, boolean useDefault) {
        boolean noKeys = accessKeyId == null || accessKeyId.isBlank() || secretKey == null || secretKey.isBlank();
        if (useDefault && noKeys) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretKey));
    }

    /** Strips ASCII control characters and non-ASCII from metadata header values. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\x20-\\x7E]", "_");
    }
}
