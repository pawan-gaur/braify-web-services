package com.braify.feature.cloudconfig.dto;

import lombok.Data;

import java.util.List;

/**
 * Request body for creating or replacing an organisation's cloud storage configuration.
 *
 * <p>All credential fields are optional — omit or pass {@code null} to clear them.
 * {@code cloud} must be one of {@code AWS}, {@code AZURE}, or {@code GCP} when provided.
 */
@Data
public class OrgCloudConfigRequest {

    /** Cloud provider: AWS | AZURE | GCP */
    private String cloud;

    /** Bucket or container name. */
    private String bucket;

    /** Path prefix within the bucket. */
    private String path;

    /** Domain grouping, e.g. "claims", "kyc". */
    private String module;

    /** Cloud access key / client ID. */
    private String accessKey;

    /** Cloud secret key / client secret. */
    private String secretKey;

    /** AWS region, e.g. "ap-south-1". Only relevant for AWS. */
    private String awsRegion;

    /**
     * Allowed upload file extensions, without dots.
     * Example: ["pdf", "jpg", "png"]
     */
    private List<String> allowedFileTypes;

    /** Maximum upload file size in megabytes (must be > 0 for uploads to work). */
    private Integer maxUploadSizeMb;

    /** File retention in days (informational). */
    private Integer retentionDays;

    /** Pre-signed URL expiry window in minutes. */
    private Integer presignedUrlExpiration;
}
