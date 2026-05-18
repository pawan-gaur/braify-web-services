package com.braify.feature.auth.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One-time token used for email invitation (new user set-password)
 * and self-service password reset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "invitation_tokens")
public class InvitationToken {

    public enum TokenType {
        INVITE,          // sent when admin creates a new user
        PASSWORD_RESET   // sent on "forgot password" request
    }

    @Id
    private String id;

    /** The user this token belongs to. */
    private String userId;

    /** Secure random UUID string stored as-is. */
    @Indexed(unique = true)
    private String token;

    private TokenType type;

    /** Invite = 7 days; Password reset = 1 hour. */
    private LocalDateTime expiresAt;

    /** Flipped to true when the token is consumed. */
    private boolean used;
    private LocalDateTime usedAt;

    @CreatedDate
    private LocalDateTime createdAt;
}
