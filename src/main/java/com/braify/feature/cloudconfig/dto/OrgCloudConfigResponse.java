package com.braify.feature.cloudconfig.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only view of an organisation's cloud storage configuration.
 *
 * <p>Sensitive credential fields ({@code accessKey}, {@code secretKey}) are always
 * returned in masked form — e.g. {@code "AKIA****5678"} — regardless of the caller's role.
 * The full values are never returned through the API.
 *
 * <p>The {@code configured} flag is {@code true} when any cloud config has been saved
 * for the organisation. When {@code false}, all other fields will be {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgCloudConfigResponse {

    /** {@code true} if a cloud config record exists for this organisation. */
    private boolean configured;

    /** Cloud provider: AWS | AZURE | GCP */
    private String cloud;

    private String bucket;
    private String path;
    private String module;

    /** Access key with the middle portion masked: {@code "AKIA****5678"}. */
    private String accessKey;

    /** Secret key with the middle portion masked: {@code "secr****cret"}. */
    private String secretKey;

    private String awsRegion;

    private List<String> allowedFileTypes;
    private Integer maxUploadSizeMb;
    private Integer retentionDays;
    private Integer presignedUrlExpiration;

    /** Config lifecycle status: ONBOARD | ACTIVE | INACTIVE */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
