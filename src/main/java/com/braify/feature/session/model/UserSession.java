package com.braify.feature.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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

    /** JWT ID claim — unique per token */
    private String jti;

    /** Org the user belongs to — used for role-scoped session listing */
    private String organizationId;

    /** Role of the user — used for role-scoped session listing */
    private String userRole;

    private String deviceInfo;
    private String ipAddress;

    @Builder.Default
    private boolean active = true;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;

    /** Who revoked this session (userId of the revoker) */
    private String revokedBy;
    private String revokedByName;
    private LocalDateTime revokedAt;
}
