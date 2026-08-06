package com.braify.feature.emailconfig.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbound email provider configuration.
 *
 * <p>Embedded inside the {@link com.braify.feature.organization.model.Organization}
 * document (per-org override) and also reused as the platform-wide default inside
 * {@link com.braify.feature.platform.model.PlatformProviderDefaults}.
 *
 * <p>Resolution order at send time (see {@code EmailConfigResolver}):
 * <ol>
 *   <li>the organisation's own {@code emailConfig} (if present and complete),</li>
 *   <li>the platform-admin default,</li>
 *   <li>the built-in Resend credentials from {@code application.yml} as a final fallback.</li>
 * </ol>
 *
 * <p>Sensitive fields ({@code apiKey}, {@code smtpPassword}) are encrypted with
 * AES-256-GCM before persistence and masked in every API response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgEmailConfig {

    /** Which provider adapter sends the message. */
    private EmailProvider provider;

    // ── From identity (all providers) ─────────────────────────────────────────

    /** Bare sender address, e.g. {@code "no-reply@acme.com"}. */
    private String fromEmail;

    /** Optional display name shown in the "From:" header, e.g. {@code "Acme Corp"}. */
    private String fromName;

    /** Optional Reply-To address applied to the actual outbound message. */
    private String replyTo;

    // ── API-key providers (RESEND / SENDGRID / MAILGUN) ───────────────────────

    /** Provider API key. Encrypted at rest, masked in responses. */
    private String apiKey;

    /** Mailgun sending domain, e.g. {@code "mg.acme.com"}. Mailgun only. */
    private String mailgunDomain;

    /** Mailgun API region: {@code "US"} (default) or {@code "EU"}. Mailgun only. */
    private String mailgunRegion;

    // ── SMTP provider ─────────────────────────────────────────────────────────

    private String  smtpHost;
    private Integer smtpPort;
    private String  smtpUsername;

    /** SMTP password. Encrypted at rest, masked in responses. */
    private String  smtpPassword;

    /** Whether to enable STARTTLS on the SMTP connection. Defaults to true. */
    private Boolean smtpStartTls;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Builder.Default
    private ConfigStatus status = ConfigStatus.ONBOARD;

    /** ID of the AppUser who first saved this config; preserved across updates. */
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Nested enums ──────────────────────────────────────────────────────────

    public enum EmailProvider { RESEND, SENDGRID, MAILGUN, SMTP }

    public enum ConfigStatus  { ONBOARD, ACTIVE, INACTIVE }
}
