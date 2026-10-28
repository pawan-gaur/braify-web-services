package com.braify.feature.smsconfig.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API response for an SMS provider configuration.
 * Secret fields ({@code authToken}, {@code apiSecret}) are masked; identifiers
 * ({@code accountSid}, {@code apiKey}) are returned in the clear.
 */
@Data
@Builder
public class OrgSmsConfigResponse {

    private boolean configured;

    private String provider;
    private String fromNumber;

    private String accountSid;
    /** Masked Twilio auth token. */
    private String authToken;

    private String apiKey;
    /** Masked Vonage API secret. */
    private String apiSecret;

    // Custom HTTP
    private String apiUrl;
    private String httpMethod;
    private String contentType;
    private String bodyTemplate;
    private String authHeaderName;
    /** Masked auth header value. */
    private String authHeaderValue;

    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
