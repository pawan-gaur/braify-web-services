package com.braify.config.infra.sms;

import com.braify.feature.smsconfig.model.OrgSmsConfig.SmsProvider;

/**
 * The effective, decrypted SMS provider config chosen by {@link SmsConfigResolver}
 * for a given org — after applying the org → platform-default fallback chain.
 * Credentials here are plaintext and must never be logged.
 */
public record ResolvedSmsConfig(
        SmsProvider provider,
        String fromNumber,
        String accountSid,
        String authToken,
        String apiKey,
        String apiSecret,
        String apiUrl,
        String httpMethod,
        String contentType,
        String bodyTemplate,
        String authHeaderName,
        String authHeaderValue,
        Source source
) {
    public enum Source { ORG, PLATFORM }
}
