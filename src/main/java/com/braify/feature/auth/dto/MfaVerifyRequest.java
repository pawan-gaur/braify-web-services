package com.braify.feature.auth.dto;

import lombok.Data;

/** Body for POST /api/auth/login/mfa — completes the login challenge. */
@Data
public class MfaVerifyRequest {
    /** The short-lived challenge token returned by POST /api/auth/login. */
    private String mfaToken;
    /** TOTP code (6 digits) or a one-time recovery code. */
    private String code;
    /** Optional device label, mirroring LoginRequest.deviceInfo. */
    private String deviceInfo;
}
