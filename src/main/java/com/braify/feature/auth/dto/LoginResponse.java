package com.braify.feature.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    private String token;
    private String userId;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private String organizationId;
    private String organizationName;
    private String profilePicture;
    private boolean mustChangePassword;

    /**
     * Feature keys enabled for this user's organisation.
     * Frontend uses this to show/hide modules immediately after login.
     * PLATFORM_ADMIN users receive all feature keys.
     */
    private List<String> features;

    // ── MFA ────────────────────────────────────────────────────────────────────

    /**
     * True when a second factor is required: the password was correct but no
     * session token is issued. The frontend must call POST /api/auth/login/mfa
     * with {@code mfaToken} + the TOTP/recovery code. All other fields are null
     * in this response.
     */
    private boolean mfaRequired;

    /** Short-lived single-purpose challenge token (only set when mfaRequired). */
    private String mfaToken;

    /**
     * True when the org policy is REQUIRED but the user has not enrolled yet.
     * The session IS issued (token present) but the frontend must gate the app
     * to mandatory MFA enrollment (same pattern as mustChangePassword).
     */
    private boolean mustSetupMfa;
}
