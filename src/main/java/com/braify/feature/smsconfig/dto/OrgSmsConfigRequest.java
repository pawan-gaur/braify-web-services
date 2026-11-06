package com.braify.feature.smsconfig.dto;

import lombok.Data;

/**
 * Incoming payload for creating/updating an SMS provider configuration
 * (per-org or platform default). All fields are plaintext.
 *
 * <p>Keep-existing semantics for secrets ({@code authToken}, {@code apiSecret}):
 * a null/blank value preserves the stored (encrypted) value; a non-blank value replaces it.
 */
@Data
public class OrgSmsConfigRequest {

    /** TWILIO | VONAGE (case-insensitive). */
    private String provider;

    private String fromNumber;

    // Twilio
    private String accountSid;
    private String authToken;

    // Vonage
    private String apiKey;
    private String apiSecret;

    // Custom HTTP
    private String apiUrl;
    private String httpMethod;
    private String contentType;
    private String bodyTemplate;
    private String authHeaderName;
    private String authHeaderValue;

    /** Optional phone number for the "send test SMS" action. */
    private String testRecipient;
}
