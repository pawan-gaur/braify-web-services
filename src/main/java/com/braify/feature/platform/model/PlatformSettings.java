package com.braify.feature.platform.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Platform-wide settings — managed by PLATFORM_ADMIN only and inherited by every
 * organisation, org admin, admin and user.
 *
 * <p>Stored as a <b>singleton</b> document with a fixed id ({@link #SINGLETON_ID}),
 * so there is always exactly one settings record for the whole platform.
 *
 * <p>The JSON shape mirrors the frontend model 1:1 (see
 * {@code braify-web-app/src/config/platformSettings.js}) so the
 * {@code GET/PUT /api/platform/settings} contract needs no field mapping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "platform_settings")
public class PlatformSettings {

    /** Fixed id for the single platform-wide settings document. */
    public static final String SINGLETON_ID = "platform";

    @Id
    private String id;

    @Builder.Default
    private Security security = Security.builder().build();

    @Builder.Default
    private Access access = Access.builder().build();

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** Email of the PLATFORM_ADMIN who last changed the settings. */
    private String updatedBy;

    // ── Security policies ─────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Security {
        @Builder.Default private Mfa      mfa      = Mfa.builder().build();
        @Builder.Default private Password password = Password.builder().build();
        @Builder.Default private Lockout  lockout  = Lockout.builder().build();
        @Builder.Default private Sessions sessions = Sessions.builder().build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Mfa {
        @Builder.Default private boolean required = true;   // mandatory for all users
        @Builder.Default private boolean totp     = true;   // authenticator apps
        @Builder.Default private boolean emailOtp = true;   // email one-time passcode fallback
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Password {
        @Builder.Default private int     minLength        = 12;
        @Builder.Default private boolean requireUpper     = true;
        @Builder.Default private boolean requireLower     = true;
        @Builder.Default private boolean requireDigit     = true;
        @Builder.Default private boolean requireSymbol    = true;
        @Builder.Default private int     expiryDays       = 90;   // 0 = never expires
        @Builder.Default private boolean reuseRestriction = true;
        @Builder.Default private int     reuseCount       = 5;    // block last N passwords
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Lockout {
        @Builder.Default private int maxFailedAttempts = 5;
        @Builder.Default private int lockoutMinutes    = 30;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Sessions {
        @Builder.Default private int sessionTimeoutHours = 8;
        @Builder.Default private int idleTimeoutMinutes  = 30;
        @Builder.Default private int maxConcurrent       = 3;
    }

    // ── User access ───────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Access {
        @Builder.Default private boolean allowSelfSignup                 = false;
        @Builder.Default private boolean requireEmailVerification        = true;
        @Builder.Default private String  defaultRole                     = "USER";
        @Builder.Default private boolean forcePasswordChangeOnFirstLogin = true;
        @Builder.Default private boolean allowProfileSelfEdit            = true;
    }
}
