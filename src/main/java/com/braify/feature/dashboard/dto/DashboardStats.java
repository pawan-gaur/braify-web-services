package com.braify.feature.dashboard.dto;

import com.braify.feature.audit.model.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregated statistics returned by GET /api/dashboard.
 * Values are scoped to the caller's organisation unless they are PLATFORM_ADMIN.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStats {

    // ── Summary KPI cards ────────────────────────────────────────────────────

    /** Number of active organisations (PLATFORM_ADMIN: all; others: always 1). */
    private long totalOrganizations;

    /** Active users visible to the caller. */
    private long totalUsers;

    /** Non-deleted PDF templates visible to the caller. */
    private long totalPdfTemplates;

    /** Non-deleted email templates visible to the caller. */
    private long totalEmailTemplates;

    /** Users who have not yet accepted their invite (mustChangePassword = true). */
    private long pendingInvites;

    // ── E-Sign analytics ────────────────────────────────────────────────────

    /** Total e-sign documents created (all statuses). */
    private long esignTotal;

    /** Documents in DRAFT state. */
    private long esignDraft;

    /** Documents in PENDING or IN_REVIEW state (awaiting signature). */
    private long esignPending;

    /** Documents that reached COMPLETED status (fully signed + delivered). */
    private long esignCompleted;

    /** Number of LINK_OPENED audit events — proxy for how many clients viewed the signing link. */
    private long esignViewed;

    /** Documents in PENDING/IN_REVIEW but past their signing token expiry. */
    private long esignOverdue;

    /** Documents explicitly cancelled. */
    private long esignCancelled;

    /** Documents whose signing token expired before submission. */
    private long esignExpired;

    /** Average hours from sentAt → completedAt for COMPLETED documents. Null when no data. */
    private Double esignAvgSigningHours;

    /** Cancellation / decline rate as a percentage (0–100). Null when no sent documents. */
    private Double esignDeclineRate;

    /** Monthly count of documents sent (sentAt != null), last 6 months. */
    private List<MonthStat> esignGrowth;

    // ── Month-over-month trends (last 6 calendar months) ────────────────────

    /** PDF template creations per month. */
    private List<MonthStat> pdfGrowth;

    /** Email template creations per month. */
    private List<MonthStat> emailGrowth;

    /** User sign-ups per month. */
    private List<MonthStat> userGrowth;

    // ── Team activity ────────────────────────────────────────────────────────

    /** Recent activity feed (last 10 events). */
    private List<AuditLog> recentActivity;

    /** Top 5 most active users in the last 30 days (by audit log count). */
    private List<TopUser> topUsers;

    // ── Platform Admin: organisation breakdown ───────────────────────────────

    /** Per-org summary table. Populated for PLATFORM_ADMIN only. */
    private List<OrgSummary> orgBreakdown;

    /** Active (non-deleted, active=true) org count. PLATFORM_ADMIN only. */
    private long activeOrganizations;

    /** Inactive (non-deleted, active=false) org count. PLATFORM_ADMIN only. */
    private long inactiveOrganizations;

    /** Pending onboarding requests. PLATFORM_ADMIN only. */
    private long pendingOnboarding;

    /**
     * Feature adoption counts: how many active orgs have each feature enabled.
     * Keys are feature identifiers (e.g. "PDF_TEMPLATES"). PLATFORM_ADMIN only.
     */
    private Map<String, Long> featureDistribution;

    /** New tenants created per month, last 6 months. PLATFORM_ADMIN only. */
    private List<MonthStat> tenantGrowth;

    // ── Inner types ──────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthStat {
        /** e.g. "Jan '25" */
        private String label;
        private long count;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrgSummary {
        private String organizationId;
        private String organizationName;
        private List<String> features;
        private long users;
        private long pdfTemplates;
        private long emailTemplates;
        private long esignDocuments;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopUser {
        private String email;
        private String name;
        private long activityCount;
    }
}
