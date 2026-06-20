package com.braify.feature.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Returned by GET /api/auth/mfa/status — drives the Security UI per org policy. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MfaStatusResponse {
    /** Org policy: DISABLED | OPTIONAL | REQUIRED. */
    private String orgPolicy;
    /** Whether THIS user has completed enrollment. */
    private boolean enabled;
    private LocalDateTime enrolledAt;
    private int recoveryCodesRemaining;
}
