package com.braify.feature.smsconfig.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbound SMS provider configuration.
 *
 * <p>Embedded on {@link com.braify.feature.organization.model.Organization} (per-org)
 * and reused as the platform default inside
 * {@link com.braify.feature.platform.model.PlatformProviderDefaults}.
 *
 * <p>Resolution at send time: the org's own config, else the platform-admin default.
 * (Unlike email, there is no built-in env fallback for SMS.)
 *
 * <p>Secret fields ({@code authToken}, {@code apiSecret}) are encrypted with
 * AES-256-GCM and masked in every API response. The account identifiers
 * ({@code accountSid}, {@code apiKey}) are stored and returned in the clear.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgSmsConfig {

    private SmsProvider provider;

    /** Sender phone number (E.164, e.g. {@code "+15551234567"}) or alphanumeric sender id. */
    private String fromNumber;

    // ── Twilio ────────────────────────────────────────────────────────────────

    /** Twilio Account SID (identifier, not secret). */
    private String accountSid;

    /** Twilio Auth Token. Encrypted at rest, masked in responses. */
    private String authToken;

    // ── Vonage ────────────────────────────────────────────────────────────────

    /** Vonage API key (identifier). */
    private String apiKey;

    /** Vonage API secret. Encrypted at rest, masked in responses. */
    private String apiSecret;

    // ── Custom HTTP provider (any REST-based SMS gateway) ──────────────────────

    /** Endpoint URL the message is POSTed/GETed to. */
    private String apiUrl;

    /** HTTP method: {@code POST} (default) or {@code GET}. */
    private String httpMethod;

    /** Body encoding: {@code JSON} (default) or {@code FORM}. */
    private String contentType;

    /**
     * Request body (or query string for GET) template. Supports the placeholders
     * {@code {{to}}}, {@code {{from}}}, {@code {{text}}}, substituted (and escaped
     * for the chosen content type) at send time.
     */
    private String bodyTemplate;

    /** Optional auth header name, e.g. {@code "Authorization"}. */
    private String authHeaderName;

    /** Optional auth header value, e.g. {@code "Bearer …"}. Encrypted at rest, masked in responses. */
    private String authHeaderValue;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Builder.Default
    private ConfigStatus status = ConfigStatus.ONBOARD;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum SmsProvider  { TWILIO, VONAGE, HTTP }

    public enum ConfigStatus { ONBOARD, ACTIVE, INACTIVE }
}
