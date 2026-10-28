package com.braify.feature.emailconfig.dto;

import lombok.Data;

/**
 * Incoming payload for creating/updating an email provider configuration
 * (per-org or platform default). All fields are plaintext.
 *
 * <p>Keep-existing semantics for secrets ({@code apiKey}, {@code smtpPassword}):
 * a {@code null}/blank value preserves the stored (encrypted) value; a non-blank
 * value replaces it.
 */
@Data
public class OrgEmailConfigRequest {

    /** RESEND | SENDGRID | MAILGUN | SMTP (case-insensitive). */
    private String provider;

    private String fromEmail;
    private String fromName;
    private String replyTo;

    /** API key for RESEND / SENDGRID / MAILGUN. */
    private String apiKey;

    private String mailgunDomain;
    /** US | EU. */
    private String mailgunRegion;

    private String  smtpHost;
    private Integer smtpPort;
    private String  smtpUsername;
    private String  smtpPassword;
    private Boolean smtpStartTls;

    /**
     * Optional recipient for the "send test email" action. When present on the
     * test endpoint, a probe email is delivered here using the submitted config.
     */
    private String testRecipient;

    /**
     * Platform-scope only: whether to keep the built-in Resend env credentials as the
     * final fallback. Ignored on the per-org endpoint. {@code null} leaves it unchanged.
     */
    private Boolean envFallbackEnabled;
}
