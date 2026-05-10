package com.braify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents an active (or recently revoked) login session, enriched with
 * the user's display name and organisation for the management UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private String id;

    /* ── Session owner ────────────────────────────── */
    private String userId;
    private String userName;       // firstName + lastName
    private String userEmail;
    private String userRole;

    private String organizationId;
    private String organizationName;

    /* ── Session metadata ─────────────────────────── */
    private String deviceInfo;
    private String ipAddress;

    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expiresAt;

    /** True when this session belongs to the caller making the request */
    private boolean current;
}
