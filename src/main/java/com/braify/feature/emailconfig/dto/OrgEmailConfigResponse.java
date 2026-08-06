package com.braify.feature.emailconfig.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API response for an email provider configuration.
 * Secret fields ({@code apiKey}, {@code smtpPassword}) are masked — the plaintext
 * value is never returned.
 */
@Data
@Builder
public class OrgEmailConfigResponse {

    /** True when a provider configuration has been saved at this scope. */
    private boolean configured;

    private String provider;

    private String fromEmail;
    private String fromName;
    private String replyTo;

    /** Masked, e.g. {@code "re_1****9abc"}. Null when unset. */
    private String apiKey;

    private String mailgunDomain;
    private String mailgunRegion;

    private String  smtpHost;
    private Integer smtpPort;
    private String  smtpUsername;
    /** Masked SMTP password. Null when unset. */
    private String  smtpPassword;
    private Boolean smtpStartTls;

    private String status;

    // ── Platform-scope only ────────────────────────────────────────────────────

    /** Whether the built-in Resend env fallback is currently enabled. */
    private Boolean envFallbackEnabled;

    /** Whether built-in Resend env credentials actually exist (so the toggle is meaningful). */
    private Boolean envFallbackAvailable;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
