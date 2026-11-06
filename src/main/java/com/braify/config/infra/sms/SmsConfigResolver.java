package com.braify.config.infra.sms;

import com.braify.config.EncryptionService;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.platform.model.PlatformProviderDefaults;
import com.braify.feature.platform.repository.PlatformProviderDefaultsRepository;
import com.braify.feature.smsconfig.model.OrgSmsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective SMS provider config for a send:
 * the org's own config (if complete), else the platform-admin default.
 * There is no built-in env fallback for SMS.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsConfigResolver {

    private final OrganizationRepository             orgRepository;
    private final PlatformProviderDefaultsRepository platformDefaultsRepository;
    private final EncryptionService                  encryptionService;

    public ResolvedSmsConfig resolve(String orgId) {
        if (orgId != null && !orgId.isBlank()) {
            OrgSmsConfig orgCfg = orgRepository.findById(orgId)
                    .map(o -> o.getSmsConfig()).orElse(null);
            ResolvedSmsConfig org = toResolved(orgCfg, ResolvedSmsConfig.Source.ORG);
            if (isComplete(org)) return org;
        }

        OrgSmsConfig platformCfg = platformDefaultsRepository
                .findById(PlatformProviderDefaults.SINGLETON_ID)
                .map(PlatformProviderDefaults::getSms)
                .orElse(null);
        ResolvedSmsConfig platform = toResolved(platformCfg, ResolvedSmsConfig.Source.PLATFORM);
        if (isComplete(platform)) return platform;

        throw new IllegalStateException(
                "No SMS provider configured: the organisation has no SMS config and there is no platform default.");
    }

    private ResolvedSmsConfig toResolved(OrgSmsConfig cfg, ResolvedSmsConfig.Source source) {
        if (cfg == null || cfg.getProvider() == null) return null;
        return new ResolvedSmsConfig(
                cfg.getProvider(),
                cfg.getFromNumber(),
                cfg.getAccountSid(),
                encryptionService.decryptSafe(cfg.getAuthToken()),
                cfg.getApiKey(),
                encryptionService.decryptSafe(cfg.getApiSecret()),
                cfg.getApiUrl(),
                cfg.getHttpMethod(),
                cfg.getContentType(),
                cfg.getBodyTemplate(),
                cfg.getAuthHeaderName(),
                encryptionService.decryptSafe(cfg.getAuthHeaderValue()),
                source);
    }

    private boolean isComplete(ResolvedSmsConfig c) {
        if (c == null || c.provider() == null) return false;
        return switch (c.provider()) {
            case TWILIO -> !isBlank(c.fromNumber()) && !isBlank(c.accountSid()) && !isBlank(c.authToken());
            case VONAGE -> !isBlank(c.fromNumber()) && !isBlank(c.apiKey()) && !isBlank(c.apiSecret());
            case HTTP   -> !isBlank(c.apiUrl()) && !isBlank(c.bodyTemplate());
        };
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
