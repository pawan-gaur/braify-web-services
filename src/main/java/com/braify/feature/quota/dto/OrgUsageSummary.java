package com.braify.feature.quota.dto;

import com.braify.shared.SubscriptionPlan;
import lombok.Builder;
import lombok.Data;

/**
 * Per-organisation usage snapshot for the PLATFORM_ADMIN org-wide usage view.
 * Combines current-month consumption with the configured limits.
 */
@Data
@Builder
public class OrgUsageSummary {

    private String           organizationId;
    private String           organizationName;
    private SubscriptionPlan plan;
    private boolean          active;

    // ── Current usage ─────────────────────────────────────────────────────────
    private long users;
    private long docsThisMonth;       // PDF generations + e-sign sends this month
    private long storageMb;
    private long apiCallsThisMonth;
    private long emailsThisMonth;     // bulk emails sent this month

    // ── Configured limits (-1 = unlimited) ────────────────────────────────────
    private int  maxUsers;
    private int  maxDocsPerMonth;
    private long maxStorageMb;
    private int  maxApiCallsPerMonth;
}
