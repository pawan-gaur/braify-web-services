package com.braify.feature.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Returned by POST /api/auth/mfa/setup — the user scans the QR (or types the secret). */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MfaSetupResponse {
    /** Base32 TOTP secret — shown so the user can enter it manually if they can't scan. */
    private String secret;
    /** otpauth:// URI encoded in the QR. */
    private String otpauthUri;
    /** PNG QR code as a data: URI, ready to drop into an <img src>. */
    private String qrDataUri;
}
