package com.braify.dto;

import com.braify.model.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    // ── Month-over-month trends (last 6 calendar months) ────────────────────

    /** PDF template creations per month. */
    private List<MonthStat> pdfGrowth;

    /** Email template creations per month. */
    private List<MonthStat> emailGrowth;

    /** User sign-ups per month. */
    private List<MonthStat> userGrowth;

    // ── Recent activity feed ─────────────────────────────────────────────────

    private List<AuditLog> recentActivity;

    // ── Organisation breakdown (PLATFORM_ADMIN only) ─────────────────────────

    private List<OrgSummary> orgBreakdown;

    // ── Inner types ──────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthStat {
        /** e.g. "Jan", "Feb" … */
        private String label;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrgSummary {
        private String organizationId;
        private String organizationName;
        private long users;
        private long pdfTemplates;
        private long emailTemplates;
    }
}
