package com.braify.feature.cloudconfig.service;

import com.braify.config.EncryptionService;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigRequest;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigResponse;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Shared mapping between {@link OrgCloudConfig} and its request/response DTOs.
 * Used by the platform-default cloud config; the per-org {@code OrgCloudConfigService}
 * keeps its own equivalent inline logic.
 */
@Component
@RequiredArgsConstructor
public class CloudConfigMapper {

    private final EncryptionService encryptionService;

    public OrgCloudConfig applyRequest(OrgCloudConfigRequest req, OrgCloudConfig existing, String callerId) {
        OrgCloudConfig.CloudProvider provider = null;
        if (req.getCloud() != null && !req.getCloud().isBlank()) {
            try {
                provider = OrgCloudConfig.CloudProvider.valueOf(req.getCloud().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid cloud provider: " + req.getCloud() + ". Accepted values: AWS, AZURE, GCP");
            }
        }
        if (req.getMaxUploadSizeMb() != null && req.getMaxUploadSizeMb() <= 0)
            throw new IllegalArgumentException("maxUploadSizeMb must be greater than zero");
        if (req.getPresignedUrlExpiration() != null && req.getPresignedUrlExpiration() <= 0)
            throw new IllegalArgumentException("presignedUrlExpiration must be greater than zero");
        if (req.getRetentionDays() != null && req.getRetentionDays() <= 0)
            throw new IllegalArgumentException("retentionDays must be greater than zero");

        LocalDateTime createdAt = (existing != null && existing.getCreatedAt() != null)
                ? existing.getCreatedAt() : LocalDateTime.now();
        String createdBy = (existing != null && existing.getCreatedBy() != null)
                ? existing.getCreatedBy() : callerId;
        OrgCloudConfig.ConfigStatus status = (existing != null && existing.getStatus() != null)
                ? existing.getStatus() : OrgCloudConfig.ConfigStatus.ONBOARD;

        String encAccessKey = resolveSecret(req.getAccessKey(), existing != null ? existing.getAccessKey() : null);
        String encSecretKey = resolveSecret(req.getSecretKey(), existing != null ? existing.getSecretKey() : null);

        List<String> fileTypes = null;
        if (req.getAllowedFileTypes() != null) {
            fileTypes = req.getAllowedFileTypes().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.trim().toLowerCase().replaceAll("^\\.", ""))
                    .distinct()
                    .toList();
        }

        return OrgCloudConfig.builder()
                .cloud(provider)
                .bucket(trimOrNull(req.getBucket()))
                .path(trimOrNull(req.getPath()))
                .module(trimOrNull(req.getModule()))
                .accessKey(encAccessKey)
                .secretKey(encSecretKey)
                .awsRegion(trimOrNull(req.getAwsRegion()))
                .allowedFileTypes(fileTypes)
                .maxUploadSizeMb(req.getMaxUploadSizeMb())
                .retentionDays(req.getRetentionDays())
                .presignedUrlExpiration(req.getPresignedUrlExpiration())
                .status(status)
                .createdBy(createdBy)
                .createdAt(createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public OrgCloudConfigResponse toResponse(OrgCloudConfig cfg) {
        if (cfg == null) {
            return OrgCloudConfigResponse.builder().configured(false).build();
        }
        return OrgCloudConfigResponse.builder()
                .configured(true)
                .cloud(cfg.getCloud() != null ? cfg.getCloud().name() : null)
                .bucket(cfg.getBucket())
                .path(cfg.getPath())
                .module(cfg.getModule())
                .accessKey(mask(encryptionService.decryptSafe(cfg.getAccessKey())))
                .secretKey(mask(encryptionService.decryptSafe(cfg.getSecretKey())))
                .awsRegion(cfg.getAwsRegion())
                .allowedFileTypes(cfg.getAllowedFileTypes())
                .maxUploadSizeMb(cfg.getMaxUploadSizeMb())
                .retentionDays(cfg.getRetentionDays())
                .presignedUrlExpiration(cfg.getPresignedUrlExpiration())
                .status(cfg.getStatus() != null ? cfg.getStatus().name() : null)
                .createdAt(cfg.getCreatedAt())
                .updatedAt(cfg.getUpdatedAt())
                .build();
    }

    private String resolveSecret(String newPlainValue, String existingEncrypted) {
        if (newPlainValue != null && !newPlainValue.isBlank()) {
            return encryptionService.encrypt(newPlainValue.trim());
        }
        return existingEncrypted;
    }

    static String mask(String plainText) {
        if (plainText == null) return null;
        if (plainText.length() <= 8) return "****";
        return plainText.substring(0, 4) + "****" + plainText.substring(plainText.length() - 4);
    }

    private static String trimOrNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
