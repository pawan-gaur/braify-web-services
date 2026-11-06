package com.braify.config.infra.email;

import com.braify.config.EncryptionService;
import com.braify.feature.emailconfig.model.OrgEmailConfig;
import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.platform.model.PlatformProviderDefaults;
import com.braify.feature.platform.repository.PlatformProviderDefaultsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective email provider config for a send.
 *
 * <p>Fallback chain:
 * <ol>
 *   <li>the organisation's own {@code emailConfig} (if complete),</li>
 *   <li>the platform-admin default (if complete),</li>
 *   <li>the built-in Resend credentials from {@code application.yml}.</li>
 * </ol>
 *
 * <p>Depends on the platform-defaults <em>repository</em> (not its service) to avoid
 * a bean cycle, since that service depends on {@link EmailDispatcher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailConfigResolver {

    private final OrganizationRepository              orgRepository;
    private final PlatformProviderDefaultsRepository  platformDefaultsRepository;
    private final EncryptionService                   encryptionService;

    @Value("${resend.api-key:}")
    private String envResendApiKey;

    @Value("${resend.from-email:}")
    private String envResendFromEmail;

    /**
     * @param orgId organisation to resolve for; {@code null} skips the org layer
     *              (used for platform-default tests and system emails with no tenant).
     */
    public ResolvedEmailConfig resolve(String orgId) {
        // 1. Org-level config
        if (orgId != null && !orgId.isBlank()) {
            OrgEmailConfig orgCfg = orgRepository.findById(orgId)
                    .map(o -> o.getEmailConfig()).orElse(null);
            ResolvedEmailConfig org = toResolved(orgCfg, ResolvedEmailConfig.Source.ORG);
            if (isComplete(org)) return org;
        }

        // 2. Platform default (and read the env-fallback toggle from the same doc)
        PlatformProviderDefaults defaults = platformDefaultsRepository
                .findById(PlatformProviderDefaults.SINGLETON_ID)
                .orElse(null);

        ResolvedEmailConfig platform = toResolved(
                defaults != null ? defaults.getEmail() : null, ResolvedEmailConfig.Source.PLATFORM);
        if (isComplete(platform)) return platform;

        // 3. Built-in Resend env fallback — only if the platform admin has left it enabled
        boolean envEnabled = defaults == null
                || defaults.getEmailEnvFallbackEnabled() == null
                || defaults.getEmailEnvFallbackEnabled();
        if (envEnabled && !isBlank(envResendApiKey) && !isBlank(envResendFromEmail)) {
            return new ResolvedEmailConfig(
                    EmailProvider.RESEND,
                    addressOf(envResendFromEmail),
                    nameOf(envResendFromEmail),
                    null, envResendApiKey.trim(),
                    null, null,
                    null, null, null, null, true,
                    ResolvedEmailConfig.Source.ENV);
        }

        throw new IllegalStateException(
                "No email provider configured: the organisation has no email config, there is no "
                + "platform default, and the built-in Resend fallback is "
                + (envEnabled ? "not set (resend.api-key/from-email are empty)." : "disabled by the platform admin."));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ResolvedEmailConfig toResolved(OrgEmailConfig cfg, ResolvedEmailConfig.Source source) {
        if (cfg == null || cfg.getProvider() == null) return null;
        return new ResolvedEmailConfig(
                cfg.getProvider(),
                cfg.getFromEmail(),
                cfg.getFromName(),
                cfg.getReplyTo(),
                encryptionService.decryptSafe(cfg.getApiKey()),
                cfg.getMailgunDomain(),
                cfg.getMailgunRegion(),
                cfg.getSmtpHost(),
                cfg.getSmtpPort(),
                cfg.getSmtpUsername(),
                encryptionService.decryptSafe(cfg.getSmtpPassword()),
                cfg.getSmtpStartTls() == null || cfg.getSmtpStartTls(),
                source);
    }

    /** True when the config carries everything its provider needs to send. */
    private boolean isComplete(ResolvedEmailConfig c) {
        if (c == null || c.provider() == null || isBlank(c.fromEmail())) return false;
        return switch (c.provider()) {
            case RESEND, SENDGRID -> !isBlank(c.apiKey());
            case MAILGUN          -> !isBlank(c.apiKey()) && !isBlank(c.mailgunDomain());
            case SMTP             -> !isBlank(c.smtpHost()) && c.smtpPort() != null && c.smtpPort() > 0;
        };
    }

    /** Extracts the bare address from {@code "Name <addr>"} or {@code "addr"}. */
    static String addressOf(String from) {
        if (from == null) return null;
        String f = from.trim();
        if (f.contains("<") && f.contains(">")) {
            return f.substring(f.indexOf('<') + 1, f.lastIndexOf('>')).trim();
        }
        return f;
    }

    /** Extracts the display name from {@code "Name <addr>"}, or null when absent. */
    static String nameOf(String from) {
        if (from == null) return null;
        String f = from.trim();
        if (f.contains("<") && f.indexOf('<') > 0) {
            String name = f.substring(0, f.indexOf('<')).trim();
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
