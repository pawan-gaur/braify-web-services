package com.braify.feature.billing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Billing state embedded in an {@code Organization}. {@code null} on organisations that
 * predate billing — those stay fully under Platform-Admin (manual) control.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgBilling {

    /** Who drives this org's plan. Only TRIAL and STRIPE orgs are changed automatically. */
    public enum ManagedBy { MANUAL, TRIAL, STRIPE }

    @Builder.Default
    private ManagedBy managedBy = ManagedBy.MANUAL;

    /** Provider-normalised subscription status: TRIALING, ACTIVE, PAST_DUE, CANCELED, TRIAL_ENDED, NONE. */
    private String status;

    /** Payment provider ("STRIPE") and its ids. */
    private String provider;
    private String customerId;
    private String subscriptionId;
    private String priceId;
    /** MONTH or YEAR. */
    private String interval;

    private LocalDateTime currentPeriodEnd;
    private boolean cancelAtPeriodEnd;
    private LocalDateTime trialEndsAt;

    /**
     * Organisations that existed before plan-gated features were introduced keep using them on
     * the Free plan. A {@code null} billing block means "predates billing" and is treated as
     * grandfathered; every organisation created since gets an explicit block with this false.
     */
    private boolean bulkGrandfathered;

    /** The "trial ends soon" notification was sent (once per trial). */
    private boolean trialReminderSent;

    private LocalDateTime updatedAt;
}
