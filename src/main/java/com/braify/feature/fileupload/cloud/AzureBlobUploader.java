package com.braify.feature.fileupload.cloud;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;

/**
 * {@link CloudUploader} implementation backed by Azure Blob Storage.
 *
 * <p>The {@code bucket} field of the request is treated as the Azure container name.
 * The {@code azureConnectionString} credential is used to authenticate — it should be
 * a full Azure Storage connection string, e.g.:
 * {@code DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...;EndpointSuffix=core.windows.net}
 *
 * <p>Pre-signed URLs are generated using Blob SAS (Service-level Shared Access Signature).
 */
@Slf4j
@Component
public class AzureBlobUploader implements CloudUploader {

    // ── Upload ────────────────────────────────────────────────────────────────

    @Override
    public CloudUploadResult upload(CloudUploadRequest req) {
        try {
            BlobClient blobClient = buildBlobClient(
                    req.getAzureConnectionString(), req.getBucket(), req.getStorageKey());

            BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(req.getContentType());
            blobClient.uploadWithResponse(
                    new ByteArrayInputStream(req.getData()),
                    req.getData().length,
                    null,       // ParallelTransferOptions
                    headers,
                    null,       // metadata
                    null,       // tier
                    null,       // requestConditions
                    null,       // context
                    null        // timeout
            );

            log.info("Azure Blob upload OK: container={} blob={} size={}B",
                    req.getBucket(), req.getStorageKey(), req.getData().length);

            return CloudUploadResult.builder()
                    .bucket(req.getBucket())
                    .storageKey(req.getStorageKey())
                    .publicUrl(blobClient.getBlobUrl())
                    .build();

        } catch (Exception e) {
            log.error("Azure Blob upload failed: container={} blob={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("Azure Blob upload failed: " + e.getMessage(), e);
        }
    }

    // ── Pre-signed URL ────────────────────────────────────────────────────────

    @Override
    public String generatePresignedUrl(CloudDownloadRequest req) {
        try {
            BlobClient blobClient = buildBlobClient(
                    req.getAzureConnectionString(), req.getBucket(), req.getStorageKey());

            BlobSasPermission perms = new BlobSasPermission().setReadPermission(true);
            BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                    OffsetDateTime.now().plusSeconds(req.getExpirationSeconds()), perms);

            String sasToken = blobClient.generateSas(values);
            String url = blobClient.getBlobUrl() + "?" + sasToken;

            log.debug("Azure SAS URL generated: container={} blob={} ttl={}s",
                    req.getBucket(), req.getStorageKey(), req.getExpirationSeconds());
            return url;

        } catch (Exception e) {
            log.error("Azure SAS URL generation failed: container={} blob={}",
                    req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("Azure Blob presigned URL generation failed: " + e.getMessage(), e);
        }
    }

    // ── Download (server-side bytes) ────────────────────────────────────────────

    @Override
    public byte[] download(CloudDownloadRequest req) {
        try {
            BlobClient blobClient = buildBlobClient(
                    req.getAzureConnectionString(), req.getBucket(), req.getStorageKey());
            byte[] bytes = blobClient.downloadContent().toBytes();
            log.info("Azure Blob download OK: container={} blob={} size={}B",
                    req.getBucket(), req.getStorageKey(), bytes.length);
            return bytes;
        } catch (Exception e) {
            log.error("Azure Blob download failed: container={} blob={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("Azure Blob download failed: " + e.getMessage(), e);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public void delete(CloudDownloadRequest req) {
        try {
            BlobClient blobClient = buildBlobClient(
                    req.getAzureConnectionString(), req.getBucket(), req.getStorageKey());
            blobClient.delete();
            log.info("Azure Blob delete OK: container={} blob={}", req.getBucket(), req.getStorageKey());
        } catch (Exception e) {
            log.error("Azure Blob delete failed: container={} blob={}", req.getBucket(), req.getStorageKey(), e);
            throw new RuntimeException("Azure Blob delete failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BlobClient buildBlobClient(String connectionString, String container, String blobName) {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(container);
        return containerClient.getBlobClient(blobName);
    }
}
