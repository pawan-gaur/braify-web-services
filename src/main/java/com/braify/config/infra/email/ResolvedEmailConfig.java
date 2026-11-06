package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;

/**
 * The effective, decrypted email provider config chosen by {@link EmailConfigResolver}
 * for a given org — after applying the org → platform-default → env fallback chain.
 * Credentials here are plaintext and must never be logged or returned to clients.
 */
public record ResolvedEmailConfig(
        EmailProvider provider,
        String fromEmail,
        String fromName,
        String replyTo,
        String apiKey,
        String mailgunDomain,
        String mailgunRegion,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpPassword,
        boolean smtpStartTls,
        Source source
) {
    /** Which layer of the fallback chain supplied this config. */
    public enum Source { ORG, PLATFORM, ENV }
}
