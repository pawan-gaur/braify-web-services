package com.braify.feature.fileupload.cloud;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link CloudUploader} implementation backed by Google Cloud Storage.
 *
 * <p>Authenticates using a GCP service-account JSON key supplied as a string in
 * {@link CloudUploadRequest#getGcpServiceAccountJson()}.
 *
 * <p>Pre-signed URLs use V4 signing with the service account credentials.
 */
@Slf4j
@Component
public class GcpStorageUploader implements CloudUploader {

    // ── Upload ────────────────────────────────────────────────────────────────

    @Override
    public CloudUploadResult upload(CloudUploadRequest req) {
        try {
            Storage storage = buildStorage(req.getGcpServiceAccountJson());

            BlobId   blobId   = BlobId.of(req.getBucket(), req.getStorageKey());
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(req.getContentType())
                    .setMetadata(Map.of("original-filename",
                            req.getOriginalFilename() != null ? req.getOriginalFilename() : ""))
                    .build();

            storage.create(blobInfo, req.getData());

            log.info("GCS upload OK: bucket={} object={} size={}B",
                    req.getBucket(), req.getStorageKey(), req.getData().length);

            return CloudUploadResult.builder()
                    .bucket(req.getBucket())
                    .storageKey(req.getStorageKey())
                    .build();

        } catch (Exception e) {
            log.error("GCS upload failed: bucket={} object={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("GCS upload failed: " + e.getMessage(), e);
        }
    }

    // ── Pre-signed URL ────────────────────────────────────────────────────────

    @Override
    public String generatePresignedUrl(CloudDownloadRequest req) {
        try {
            Storage storage = buildStorage(req.getGcpServiceAccountJson());

            BlobId   blobId   = BlobId.of(req.getBucket(), req.getStorageKey());
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

            String url = storage.signUrl(
                    blobInfo,
                    req.getExpirationSeconds(),
                    TimeUnit.SECONDS,
                    Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                    Storage.SignUrlOption.withV4Signature()
            ).toString();

            log.debug("GCS signed URL generated: bucket={} object={} ttl={}s",
                    req.getBucket(), req.getStorageKey(), req.getExpirationSeconds());
            return url;

        } catch (Exception e) {
            log.error("GCS presign failed: bucket={} object={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("GCS presigned URL generation failed: " + e.getMessage(), e);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public void delete(CloudDownloadRequest req) {
        try {
            Storage storage = buildStorage(req.getGcpServiceAccountJson());
            storage.delete(BlobId.of(req.getBucket(), req.getStorageKey()));
            log.info("GCS delete OK: bucket={} object={}", req.getBucket(), req.getStorageKey());
        } catch (Exception e) {
            log.error("GCS delete failed: bucket={} object={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("GCS delete failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Storage buildStorage(String serviceAccountJson) throws IOException {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes()))
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        return StorageOptions.newBuilder()
                .setCredentials(credentials)
                .build()
                .getService();
    }
}
