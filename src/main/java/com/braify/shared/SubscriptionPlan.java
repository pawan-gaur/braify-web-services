package com.braify.shared;

/**
 * Subscription plan tiers.
 * Each constant carries its default quota values; -1 means unlimited.
 * Platform Admins assign a plan to an org via PUT /api/organizations/{id}/subscription.
 * QuotaService reads these defaults when a plan is first assigned and writes them into
 * OrgQuotaConfig — which can then be overridden independently by a Platform Admin.
 */
public enum SubscriptionPlan {

    FREE        ("Free",         3,   50,    512L,    1_000),
    PROFESSIONAL("Professional", 25,  500,   5_120L,  10_000),
    ENTERPRISE  ("Enterprise",   -1,  -1,    -1L,     -1);

    /** Human-readable display label. */
    public final String label;

    /** Maximum active users in the org (-1 = unlimited). */
    public final int defaultMaxUsers;

    /** Maximum documents (PDF + e-sign) per calendar month (-1 = unlimited). */
    public final int defaultMaxDocsPerMonth;

    /** Maximum storage in MB (-1 = unlimited). */
    public final long defaultMaxStorageMb;

    /** Maximum API calls per calendar month (-1 = unlimited). */
    public final int defaultMaxApiCallsPerMonth;

    SubscriptionPlan(String label,
                     int defaultMaxUsers,
                     int defaultMaxDocsPerMonth,
                     long defaultMaxStorageMb,
                     int defaultMaxApiCallsPerMonth) {
        this.label                   = label;
        this.defaultMaxUsers         = defaultMaxUsers;
        this.defaultMaxDocsPerMonth  = defaultMaxDocsPerMonth;
        this.defaultMaxStorageMb     = defaultMaxStorageMb;
        this.defaultMaxApiCallsPerMonth = defaultMaxApiCallsPerMonth;
    }

    /** Returns true when the given usage value is within the limit for this plan. */
    public static boolean withinLimit(int limit, long current) {
        return limit == -1 || current < limit;
    }
}
