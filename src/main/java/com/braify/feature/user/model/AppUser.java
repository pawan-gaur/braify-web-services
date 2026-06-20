package com.braify.feature.user.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class AppUser {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    /** BCrypt hashed password */
    private String password;

    private String firstName;
    private String lastName;

    private Role role;

    /** null for PLATFORM_ADMIN */
    @Indexed
    private String organizationId;

    private boolean active = true;

    /** Set to true until the user completes the invite / password-reset flow */
    private boolean mustChangePassword = false;

    /** Base64 data-URL of the user's profile picture (may be null) */
    private String profilePicture;

    /** Optional short bio / display note */
    private String bio;

    /** ID of the AppUser (admin) who created this account; null for self-registered / system-created accounts. */
    private String createdBy;

    // ── MFA / 2FA (TOTP) ───────────────────────────────────────────────────────
    // Enrollment is PRESERVED across org-level MFA policy toggles — disabling the
    // org policy never clears these fields; only the user self-disabling does.

    /** True once the user has completed TOTP enrollment. */
    @Builder.Default
    private boolean mfaEnabled = false;

    /** AES-GCM-encrypted base32 TOTP secret (via EncryptionService); null until enrolled. */
    private String mfaSecret;

    /** AES-encrypted base32 secret held during enrollment, before the first code is verified. */
    private String mfaPendingSecret;

    /** BCrypt-hashed one-time recovery codes (consumed on use). */
    @Builder.Default
    private List<String> mfaRecoveryCodes = new ArrayList<>();

    private LocalDateTime mfaEnrolledAt;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public enum Role {
        PLATFORM_ADMIN, ORG_ADMIN, ADMIN, USER
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
