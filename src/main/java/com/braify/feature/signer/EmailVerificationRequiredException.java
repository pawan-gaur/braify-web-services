package com.braify.feature.signer;

/**
 * A Braify user's session tried to open the signer inbox without having proven control of
 * the account's mailbox in this session. Mapped to HTTP 403 with
 * {@code reason = "EMAIL_VERIFICATION_REQUIRED"} so the client can offer the email-code step.
 */
public class EmailVerificationRequiredException extends RuntimeException {
    public EmailVerificationRequiredException() {
        super("Confirm it's you to see documents sent to your email — we'll email you a one-time code.");
    }
}
