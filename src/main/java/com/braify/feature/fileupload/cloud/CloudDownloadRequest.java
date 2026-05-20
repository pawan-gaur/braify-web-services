package com.braify.feature.fileupload.cloud;

import lombok.Builder;
import lombok.Data;

/**
 * Value object passed to a {@link CloudUploader} to generate a pre-signed download URL.
 */
@Data
@Builder
public class CloudDownloadRequest {

    private String bucket;
    private String storageKey;

    /** How many seconds the pre-signed URL should remain valid. */
    private int expirationSeconds;

    // ── Provider credentials ──────────────────────────────────────────────────

    private String awsRegion;
    private String awsAccessKeyId;
    private String awsSecretAccessKey;

    private String azureConnectionString;

    private String gcpServiceAccountJson;
}
