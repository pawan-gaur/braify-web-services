package com.braify.config.infra.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Central outbound-SMS entry point. The provider (Twilio / Vonage) and credentials
 * are resolved per-organisation by {@link SmsConfigResolver}: the org's own config,
 * else the platform-admin default. {@code orgId} may be {@code null} to use the
 * platform default directly.
 *
 * <p>No callers send SMS in the product yet — this is the groundwork plus a working
 * test-send path from the settings screens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsDispatcher {

    private final SmsConfigResolver configResolver;
    private final SmsSenderFactory  senderFactory;

    public SmsSendResult sendSms(String orgId, String to, String body) {
        if (isBlank(to)) {
            throw new IllegalArgumentException("Recipient phone number is required");
        }
        if (isBlank(body)) {
            throw new IllegalArgumentException("SMS body is required");
        }
        ResolvedSmsConfig cfg = configResolver.resolve(orgId);
        OutboundSms sms = new OutboundSms(cfg.fromNumber(), to.trim(), body);
        SmsSendResult result = senderFactory.get(cfg.provider()).send(cfg, sms);
        log.debug("SMS dispatched via {} (source={}) to {}", cfg.provider(), cfg.source(), to);
        return result;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
