package com.braify.feature.signer.model;

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

/**
 * Identity for an external e-sign recipient who has no Braify {@code AppUser} account.
 *
 * <p>Created lazily the first time the recipient verifies an email OTP. The account
 * only ever grants access to the signer inbox ({@code /api/signer/**}) — the documents
 * where this email is a signatory or CC recipient. A password is optional and can be
 * set after the first OTP login; until then the recipient signs in with a code.
 *
 * <p>If the same email later becomes an {@code AppUser} (invited as Admin/User), the
 * AppUser takes precedence for password login and this record is kept only as an
 * identity anchor — the inbox is resolved by email, so signing history carries over.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signer_accounts")
public class SignerAccount {

    @Id
    private String id;

    /** Normalized (trimmed, lower-case) email — the identity key. */
    @Indexed(unique = true)
    private String email;

    /** Display name, taken from the most recent signatory record for this email. */
    private String name;

    /** BCrypt hash; {@code null} until the signer chooses to set a password. */
    private String passwordHash;
    private LocalDateTime passwordChangedAt;

    @Builder.Default
    private int failedLoginAttempts = 0;
    private LocalDateTime lockedUntil;

    @Builder.Default
    private boolean active = true;

    private LocalDateTime lastLoginAt;

    @CreatedDate
    private LocalDateTime createdAt;
    @LastModifiedDate
    private LocalDateTime updatedAt;
}
