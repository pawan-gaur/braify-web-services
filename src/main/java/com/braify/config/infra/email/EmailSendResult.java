package com.braify.config.infra.email;

/**
 * Provider-neutral result of an outbound email send. Replaces the Resend-specific
 * {@code CreateEmailResponse} previously returned by {@link EmailDispatcher} so the
 * dispatcher can route through any provider adapter.
 */
public class EmailSendResult {

    private final String id;
    private final String provider;

    public EmailSendResult(String id, String provider) {
        this.id = id;
        this.provider = provider;
    }

    /** Provider message id, when the provider returns one (may be null). */
    public String getId() {
        return id;
    }

    /** Provider that delivered the message (RESEND / SENDGRID / MAILGUN / SMTP). */
    public String getProvider() {
        return provider;
    }
}
