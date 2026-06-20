package com.braify.feature.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Live, role-scoped, period-filtered analytics returned by
 * {@code GET /api/dashboard/analytics?days=N}.
 *
 * <p>Scoping mirrors the audit-log visibility rules:
 * <ul>
 *   <li>PLATFORM_ADMIN — entire platform</li>
 *   <li>ORG_ADMIN      — their organisation</li>
 *   <li>ADMIN          — their own + their USERs' activity (ORG_ADMIN actions hidden)</li>
 *   <li>USER           — only their own activity</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsResponse {

    /** Window the figures were computed over (echoed back for the client). */
    private int periodDays;

    /** Most-used PDF + email templates in the window (desc by use count). */
    private List<UsageItem> topTemplates;

    /** Least-used templates in the window (asc by use count). */
    private List<UsageItem> leastTemplates;

    /** Most-active performers in the window (desc by action count). */
    private List<ActivityItem> activity;

    /** E-Sign Sent → Viewed → Signed cohort for documents sent in the window. */
    private Funnel esignFunnel;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UsageItem {
        private String id;
        private String name;
        /** TEMPLATE | EMAIL_TEMPLATE */
        private String type;
        private long uses;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ActivityItem {
        private String email;
        private String name;
        private long activityCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Funnel {
        private long sent;
        private long viewed;
        private long signed;
    }
}
