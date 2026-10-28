package com.braify.feature.billing.service;

import com.braify.feature.billing.UpgradeRequiredException;
import com.braify.feature.billing.model.OrgBilling;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.shared.SubscriptionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * What an organisation's plan allows. The single place plan-gated features are decided, so the
 * API, the UI ({@code /api/billing/entitlements}) and the pricing page stay consistent.
 */
@Service
@RequiredArgsConstructor
public class PlanAccessService {

    private final OrganizationRepository orgRepository;

    /**
     * Bulk email campaigns and bulk e-sign are paid features. Organisations that existed before
     * plan gating ({@code billing.bulkGrandfathered}) keep them on Free. Platform Admins
     * ({@code orgId == null}) are never gated.
     */
    public boolean bulkEnabled(Organization org) {
        if (org == null) return true;
        if (org.getSubscriptionPlan() != null && org.getSubscriptionPlan() != SubscriptionPlan.FREE) return true;
        OrgBilling b = org.getBilling();
        return b == null || b.isBulkGrandfathered();   // null = predates billing (grandfathered)
    }

    /** Throws {@link UpgradeRequiredException} unless the org may use bulk features. */
    public void requireBulk(String orgId) {
        if (orgId == null) return;   // Platform Admin
        Organization org = orgRepository.findById(orgId).orElse(null);
        if (!bulkEnabled(org)) {
            throw new UpgradeRequiredException(
                    "Bulk sending is available on the Pro plan. Upgrade to send campaigns and bulk e-sign requests.");
        }
    }

    /** Whole days left in a trial (0 when none / ended). */
    public static long trialDaysLeft(Organization org) {
        OrgBilling b = org.getBilling();
        if (b == null || b.getManagedBy() != OrgBilling.ManagedBy.TRIAL || b.getTrialEndsAt() == null) return 0;
        long hours = Duration.between(LocalDateTime.now(), b.getTrialEndsAt()).toHours();
        return hours <= 0 ? 0 : (hours + 23) / 24;
    }
}
