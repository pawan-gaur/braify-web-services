package com.braify.feature.fileupload.cloud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
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

/**
 * {@link CloudUploader} implementation backed by AWS S3 (SDK v2).
 *
 * <p>A fresh {@link S3Client} is built per call using the credentials supplied in
 * {@link CloudUploadRequest} / {@link CloudDownloadRequest}.  This is intentionally
 * stateless so that each organisation can use its own IAM credentials.
 */
@Slf4j
@Component
public class AwsS3Uploader implements CloudUploader {

    // ── Upload ────────────────────────────────────────────────────────────────

    @Override
    public CloudUploadResult upload(CloudUploadRequest req) {
        try (S3Client s3 = buildClient(req.getAwsRegion(), req.getAwsAccessKeyId(), req.getAwsSecretAccessKey())) {

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
        try (S3Presigner presigner = buildPresigner(req.getAwsRegion(),
                                                    req.getAwsAccessKeyId(),
                                                    req.getAwsSecretAccessKey())) {

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

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public void delete(CloudDownloadRequest req) {
        try (S3Client s3 = buildClient(req.getAwsRegion(), req.getAwsAccessKeyId(), req.getAwsSecretAccessKey())) {
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

    private S3Client buildClient(String region, String accessKeyId, String secretKey) {
        return S3Client.builder()
                .region(Region.of(region != null ? region : "us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretKey)))
                .build();
    }

    private S3Presigner buildPresigner(String region, String accessKeyId, String secretKey) {
        return S3Presigner.builder()
                .region(Region.of(region != null ? region : "us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretKey)))
                .build();
    }

    /** Strips ASCII control characters and non-ASCII from metadata header values. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\x20-\\x7E]", "_");
    }
}
