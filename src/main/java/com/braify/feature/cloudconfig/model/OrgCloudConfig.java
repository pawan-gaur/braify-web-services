package com.braify.feature.cloudconfig.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cloud storage configuration embedded inside the {@link Organization} document.
 * Holds provider credentials, bucket/path metadata, and upload policy settings.
 *
 * <p>Sensitive fields ({@code accessKey}, {@code secretKey}) are masked when
 * returned through {@link com.braify.feature.cloudconfig.dto.OrgCloudConfigResponse}.
 *
 * <p>Supported cloud providers: {@link CloudProvider#AWS}, {@link CloudProvider#AZURE},
 * {@link CloudProvider#GCP}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgCloudConfig {

    // ── Cloud provider ────────────────────────────────────────────────────────

    /**
     * Cloud storage provider.
     * Determines which uploader implementation is used at runtime.
     */
    private CloudProvider cloud;

    // ── Storage coordinates ───────────────────────────────────────────────────

    /** Bucket or container name in the cloud provider. */
    private String bucket;

    /** Path prefix within the bucket (e.g. {@code "acme/docs"}). */
    private String path;

    /**
     * Domain grouping for the stored files, e.g. {@code "claims"}, {@code "kyc"},
     * {@code "underwriting"}. Used for organisation/routing logic.
     */
    private String module;

    // ── Credentials ───────────────────────────────────────────────────────────

    /** Cloud access key / client ID. Masked in all API responses. */
    private String accessKey;

    /** Cloud secret key / client secret. Masked in all API responses. */
    private String secretKey;

    /** AWS-specific region, e.g. {@code "ap-south-1"}. Ignored for Azure / GCP. */
    private String awsRegion;

    // ── Upload policy ─────────────────────────────────────────────────────────

    /**
     * Allowed upload file extensions without dots, e.g. {@code ["pdf", "jpg", "png"]}.
     * An empty or null list means all file types are accepted.
     */
    private List<String> allowedFileTypes;

    /**
     * Maximum individual upload size in megabytes.
     * Must be greater than zero for the upload flow to function.
     */
    private Integer maxUploadSizeMb;

    /**
     * File retention policy in days.
     * Informational metadata — enforcement depends on the cloud provider lifecycle rules.
     */
    private Integer retentionDays;

    /**
     * Expiry window in minutes for generated pre-signed URLs.
     * Confirm units match the cloud uploader implementation before displaying in UI labels.
     */
    private Integer presignedUrlExpiration;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Configuration lifecycle status.
     * Starts as {@link ConfigStatus#ONBOARD} when first saved and transitions to
     * {@link ConfigStatus#ACTIVE} once credentials are verified.
     */
    @Builder.Default
    private ConfigStatus status = ConfigStatus.ONBOARD;

    /** ID of the AppUser who first saved this config; preserved across subsequent updates. */
    private String createdBy;

    /** Timestamp when this config was first persisted. */
    private LocalDateTime createdAt;

    /** Timestamp of the last update. */
    private LocalDateTime updatedAt;

    // ── Nested enums ─────────────────────────────────────────────────────────

    public enum CloudProvider { AWS, AZURE, GCP }

    public enum ConfigStatus  { ONBOARD, ACTIVE, INACTIVE }
}
