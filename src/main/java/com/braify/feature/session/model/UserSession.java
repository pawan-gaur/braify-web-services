package com.braify.feature.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_sessions")
public class UserSession {

    @Id
    private String id;

    @Indexed
    private String userId;

    /**
     * JWT ID claim — unique per token.
     *
     * Indexed because {@code findByJtiAndActiveTrue(jti)} is called on EVERY
     * authenticated request; a collection scan here would degrade every API call.
     */
    @Indexed(unique = true)
    private String jti;

    /** Org the user belongs to — used for role-scoped session listing */
    private String organizationId;

    /** Role of the user — used for role-scoped session listing ("SIGNER" for signer-portal sessions) */
    private String userRole;

    /**
     * Which identity {@link #userId} refers to: {@code USER} (AppUser — default, also
     * when null on legacy rows) or {@code SIGNER} (SignerAccount, signer-portal session).
     */
    private String principalType;

    /** Email of the session owner — set for signer sessions (no AppUser to look up). */
    private String principalEmail;

    /**
     * True when THIS session proved control of the account's mailbox — signed in with an
     * emailed code, or confirmed one later. Required for a Braify user to open the signer
     * inbox: a password alone is not enough, because admins can set users' passwords.
     * Signer-portal sessions are always verified (their credentials are owned by the signer).
     */
    @Builder.Default
    private boolean emailVerified = false;

    private String deviceInfo;
    private String ipAddress;

    /**
     * SHA-256 hash of the session's current refresh token. Rotated on every
     * successful refresh (the raw token is never stored). Indexed because the
     * refresh endpoint looks the session up by this value.
     */
    @Indexed
    private String refreshTokenHash;

    @Builder.Default
    private boolean active = true;

    /** Id of the user this session was created for (audited automatically on insert). */
    @CreatedBy
    private String createdBy;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;

    /** Who revoked this session (userId of the revoker) */
    private String revokedBy;
    private String revokedByName;
    private LocalDateTime revokedAt;
}
