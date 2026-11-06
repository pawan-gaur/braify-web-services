package com.braify.feature.emailconfig.service;

import com.braify.config.EncryptionService;
import com.braify.feature.emailconfig.dto.OrgEmailConfigRequest;
import com.braify.feature.emailconfig.dto.OrgEmailConfigResponse;
import com.braify.feature.emailconfig.model.OrgEmailConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Shared mapping between {@link OrgEmailConfig} and its request/response DTOs.
 * Reused by both the per-org config service and the platform-default service so
 * the encrypt / mask / keep-existing rules live in exactly one place.
 */
@Component
@RequiredArgsConstructor
public class EmailConfigMapper {

    private final EncryptionService encryptionService;

    /**
     * Builds an updated {@link OrgEmailConfig} from an incoming request, encrypting
     * secrets and preserving immutable/omitted fields from {@code existing}.
     *
     * <p>Secret keep-existing: a null/blank {@code apiKey}/{@code smtpPassword}
     * retains the previously stored ciphertext; a non-blank value replaces it.
     */
    public OrgEmailConfig applyRequest(OrgEmailConfigRequest req,
                                       OrgEmailConfig existing,
                                       String callerId) {
        OrgEmailConfig.EmailProvider provider = parseProvider(req.getProvider());

        // Light validation — the resolver enforces send-time completeness.
        if (provider != null && isBlank(req.getFromEmail())
                && (existing == null || isBlank(existing.getFromEmail()))) {
            throw new IllegalArgumentException("fromEmail is required when a provider is selected");
        }
        if (req.getSmtpPort() != null && req.getSmtpPort() <= 0) {
            throw new IllegalArgumentException("smtpPort must be greater than zero");
        }

        LocalDateTime createdAt = (existing != null && existing.getCreatedAt() != null)
                ? existing.getCreatedAt() : LocalDateTime.now();
        String createdBy = (existing != null && existing.getCreatedBy() != null)
                ? existing.getCreatedBy() : callerId;
        OrgEmailConfig.ConfigStatus status = (existing != null && existing.getStatus() != null)
                ? existing.getStatus() : OrgEmailConfig.ConfigStatus.ONBOARD;

        String encApiKey = resolveSecret(req.getApiKey(),
                existing != null ? existing.getApiKey() : null);
        String encSmtpPassword = resolveSecret(req.getSmtpPassword(),
                existing != null ? existing.getSmtpPassword() : null);

        String region = trimOrNull(req.getMailgunRegion());
        if (region != null) region = region.toUpperCase();

        return OrgEmailConfig.builder()
                .provider(provider)
                .fromEmail(trimOrNull(req.getFromEmail()))
                .fromName(trimOrNull(req.getFromName()))
                .replyTo(trimOrNull(req.getReplyTo()))
                .apiKey(encApiKey)
                .mailgunDomain(trimOrNull(req.getMailgunDomain()))
                .mailgunRegion(region)
                .smtpHost(trimOrNull(req.getSmtpHost()))
                .smtpPort(req.getSmtpPort())
                .smtpUsername(trimOrNull(req.getSmtpUsername()))
                .smtpPassword(encSmtpPassword)
                .smtpStartTls(req.getSmtpStartTls())
                .status(status)
                .createdBy(createdBy)
                .createdAt(createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** Converts stored config to a masked response DTO (plaintext never exposed). */
    public OrgEmailConfigResponse toResponse(OrgEmailConfig cfg) {
        if (cfg == null) {
            return OrgEmailConfigResponse.builder().configured(false).build();
        }
        return OrgEmailConfigResponse.builder()
                .configured(true)
                .provider(cfg.getProvider() != null ? cfg.getProvider().name() : null)
                .fromEmail(cfg.getFromEmail())
                .fromName(cfg.getFromName())
                .replyTo(cfg.getReplyTo())
                .apiKey(mask(encryptionService.decryptSafe(cfg.getApiKey())))
                .mailgunDomain(cfg.getMailgunDomain())
                .mailgunRegion(cfg.getMailgunRegion())
                .smtpHost(cfg.getSmtpHost())
                .smtpPort(cfg.getSmtpPort())
                .smtpUsername(cfg.getSmtpUsername())
                .smtpPassword(mask(encryptionService.decryptSafe(cfg.getSmtpPassword())))
                .smtpStartTls(cfg.getSmtpStartTls())
                .status(cfg.getStatus() != null ? cfg.getStatus().name() : null)
                .createdAt(cfg.getCreatedAt())
                .updatedAt(cfg.getUpdatedAt())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgEmailConfig.EmailProvider parseProvider(String raw) {
        if (isBlank(raw)) return null;
        try {
            return OrgEmailConfig.EmailProvider.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid email provider: " + raw + ". Accepted values: RESEND, SENDGRID, MAILGUN, SMTP");
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

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
