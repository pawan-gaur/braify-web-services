package com.braify.feature.fileupload.cloud;

import lombok.Builder;
import lombok.Data;

/**
 * Value object passed to a {@link CloudUploader} describing a single upload.
 */
@Data
@Builder
public class CloudUploadRequest {

    /** Target bucket / container / GCS bucket. */
    private String bucket;

    /** Object key / blob name / GCS object name (e.g. {@code uploads/org123/F20260520/report.pdf}). */
    private String storageKey;

    /** Raw file bytes. */
    private byte[] data;

    /** MIME type (e.g. {@code application/pdf}). */
    private String contentType;

    /** Original filename — stored as cloud object metadata. */
    private String originalFilename;

    // ── AWS-specific ──────────────────────────────────────────────────────────

    /** AWS region (e.g. {@code us-east-1}).  Ignored for Azure / GCP. */
    private String awsRegion;

    /** AWS access key ID for explicit credential provider. */
    private String awsAccessKeyId;

    /** AWS secret access key for explicit credential provider. */
    private String awsSecretAccessKey;

    // ── Azure-specific ────────────────────────────────────────────────────────

    /**
     * Azure Storage connection string.
     * {@code bucket} is treated as the container name.
     * Ignored for AWS / GCP.
     */
    private String azureConnectionString;

    // ── GCP-specific ──────────────────────────────────────────────────────────

    /**
     * GCP service-account JSON key (full JSON string).
     * {@code bucket} is treated as the GCS bucket name.
     * Ignored for AWS / Azure.
     */
    private String gcpServiceAccountJson;
}
