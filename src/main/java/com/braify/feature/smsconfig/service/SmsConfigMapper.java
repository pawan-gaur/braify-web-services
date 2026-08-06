package com.braify.feature.smsconfig.service;

import com.braify.config.EncryptionService;
import com.braify.feature.smsconfig.dto.OrgSmsConfigRequest;
import com.braify.feature.smsconfig.dto.OrgSmsConfigResponse;
import com.braify.feature.smsconfig.model.OrgSmsConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Shared mapping between {@link OrgSmsConfig} and its request/response DTOs. */
@Component
@RequiredArgsConstructor
public class SmsConfigMapper {

    private final EncryptionService encryptionService;

    public OrgSmsConfig applyRequest(OrgSmsConfigRequest req, OrgSmsConfig existing, String callerId) {
        OrgSmsConfig.SmsProvider provider = parseProvider(req.getProvider());

        if (provider != null && isBlank(req.getFromNumber())
                && (existing == null || isBlank(existing.getFromNumber()))) {
            throw new IllegalArgumentException("fromNumber is required when a provider is selected");
        }

        LocalDateTime createdAt = (existing != null && existing.getCreatedAt() != null)
                ? existing.getCreatedAt() : LocalDateTime.now();
        String createdBy = (existing != null && existing.getCreatedBy() != null)
                ? existing.getCreatedBy() : callerId;
        OrgSmsConfig.ConfigStatus status = (existing != null && existing.getStatus() != null)
                ? existing.getStatus() : OrgSmsConfig.ConfigStatus.ONBOARD;

        String encAuthToken = resolveSecret(req.getAuthToken(),
                existing != null ? existing.getAuthToken() : null);
        String encApiSecret = resolveSecret(req.getApiSecret(),
                existing != null ? existing.getApiSecret() : null);
        String encAuthHeaderValue = resolveSecret(req.getAuthHeaderValue(),
                existing != null ? existing.getAuthHeaderValue() : null);

        String method = trimOrNull(req.getHttpMethod());
        if (method != null) method = method.toUpperCase();
        String contentType = trimOrNull(req.getContentType());
        if (contentType != null) contentType = contentType.toUpperCase();

        return OrgSmsConfig.builder()
                .provider(provider)
                .fromNumber(trimOrNull(req.getFromNumber()))
                .accountSid(trimOrNull(req.getAccountSid()))
                .authToken(encAuthToken)
                .apiKey(trimOrNull(req.getApiKey()))
                .apiSecret(encApiSecret)
                .apiUrl(trimOrNull(req.getApiUrl()))
                .httpMethod(method)
                .contentType(contentType)
                .bodyTemplate(req.getBodyTemplate() != null && !req.getBodyTemplate().isBlank()
                        ? req.getBodyTemplate() : null)
                .authHeaderName(trimOrNull(req.getAuthHeaderName()))
                .authHeaderValue(encAuthHeaderValue)
                .status(status)
                .createdBy(createdBy)
                .createdAt(createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public OrgSmsConfigResponse toResponse(OrgSmsConfig cfg) {
        if (cfg == null) {
            return OrgSmsConfigResponse.builder().configured(false).build();
        }
        return OrgSmsConfigResponse.builder()
                .configured(true)
                .provider(cfg.getProvider() != null ? cfg.getProvider().name() : null)
                .fromNumber(cfg.getFromNumber())
                .accountSid(cfg.getAccountSid())
                .authToken(mask(encryptionService.decryptSafe(cfg.getAuthToken())))
                .apiKey(cfg.getApiKey())
                .apiSecret(mask(encryptionService.decryptSafe(cfg.getApiSecret())))
                .apiUrl(cfg.getApiUrl())
                .httpMethod(cfg.getHttpMethod())
                .contentType(cfg.getContentType())
                .bodyTemplate(cfg.getBodyTemplate())
                .authHeaderName(cfg.getAuthHeaderName())
                .authHeaderValue(mask(encryptionService.decryptSafe(cfg.getAuthHeaderValue())))
                .status(cfg.getStatus() != null ? cfg.getStatus().name() : null)
                .createdAt(cfg.getCreatedAt())
                .updatedAt(cfg.getUpdatedAt())
                .build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private OrgSmsConfig.SmsProvider parseProvider(String raw) {
        if (isBlank(raw)) return null;
        try {
            return OrgSmsConfig.SmsProvider.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid SMS provider: " + raw + ". Accepted values: TWILIO, VONAGE");
        }
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

    private static String trimOrNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
