package com.braify.feature.quota.dto;

import com.braify.shared.SubscriptionPlan;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class QuotaConfigResponse {

    private String           organizationId;
    private String           organizationName;
    private SubscriptionPlan subscriptionPlan;

    // ── Configured limits ─────────────────────────────────────────────────────
    private int  maxUsers;
    private int  maxDocsPerMonth;
    private long maxStorageMb;
    private int  maxApiCallsPerMonth;

    // ── Current month usage ───────────────────────────────────────────────────
    private long currentUsers;
    private long currentDocsThisMonth;
    private long currentEsignThisMonth;
    private long currentStorageMb;
    private long currentApiCallsThisMonth;

    // ── Percentages (0–100, -1 when unlimited) ────────────────────────────────
    private int usersPercent;
    private int docsPercent;
    private int storagePercent;
    private int apiPercent;

    private LocalDateTime updatedAt;
    private String        updatedBy;
}
