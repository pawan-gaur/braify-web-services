package com.braify.feature.signer.model;

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
 * A one-time login code emailed to a user or e-sign recipient.
 *
 * <p>Only the BCrypt hash of the code is stored. A code is single-use, expires after a
 * few minutes and is invalidated after too many wrong guesses. Records are purged by a
 * Mongo TTL index once {@link #expiresAt} passes (kept briefly for resend throttling).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "email_otps")
public class EmailOtp {

    @Id
    private String id;

    /** Normalized (trimmed, lower-case) email the code was sent to. */
    @Indexed
    private String email;

    private String codeHash;

    @Builder.Default
    private int attempts = 0;

    @Builder.Default
    private boolean consumed = false;

    /** TTL — Mongo deletes the record shortly after this instant. */
    @Indexed(expireAfterSeconds = 3600)
    private LocalDateTime expiresAt;

    private String requestIp;

    @CreatedDate
    private LocalDateTime createdAt;
}
