package com.braify.feature.billing.service;

import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.billing.model.BillingWebhookEvent;
import com.braify.feature.billing.model.OrgBilling;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.quota.service.QuotaService;
import com.braify.feature.user.model.AppUser;
import com.braify.shared.SubscriptionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Self-serve billing: free trial, Stripe Checkout / Customer Portal, and applying subscription
 * changes from Stripe webhooks to the organisation's plan + quota.
 *
 * <p>Only organisations whose {@code billing.managedBy} is TRIAL or STRIPE are ever changed
 * automatically; MANUAL (or no billing record) stays under Platform-Admin control.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final OrganizationRepository orgRepository;
    private final QuotaService           quotaService;
    private final StripeClient           stripe;
    private final MongoTemplate          mongoTemplate;
    private final AuditLogService        auditLogService;
    private final com.braify.feature.notification.service.NotificationService notificationService;
    private final com.braify.feature.user.repository.AppUserRepository userRepository;

    @Value("${app.billing.enabled:true}")
    private boolean enabled;

    @Value("${app.billing.trial-days:14}")
    private int trialDays;

    @Value("${app.billing.pro-monthly-price-id:}")
    private String proMonthlyPriceId;

    @Value("${app.billing.pro-yearly-price-id:}")
    private String proYearlyPriceId;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /** Checkout/portal are available only when switched on and Stripe + a Pro price are configured. */
    public boolean billingAvailable() {
        return enabled && stripe.isConfigured() && !blank(proMonthlyPriceId);
    }

    public boolean yearlyAvailable() {
        return billingAvailable() && !blank(proYearlyPriceId);
    }

    // ── Trial ────────────────────────────────────────────────────────────────

    /**
     * Puts a brand-new self-serve organisation on a Pro trial (no card). The lifecycle scheduler
     * drops it to Free when the trial ends unless it has subscribed. No-op when trials are off.
     */
    public void startTrial(Organization org) {
        if (trialDays <= 0) {
            org.setBilling(OrgBilling.builder().managedBy(OrgBilling.ManagedBy.MANUAL).status("NONE")
                    .bulkGrandfathered(false).updatedAt(LocalDateTime.now()).build());
            orgRepository.save(org);
            return;
        }
        LocalDateTime ends = LocalDateTime.now().plusDays(trialDays);
        org.setBilling(OrgBilling.builder()
                .managedBy(OrgBilling.ManagedBy.TRIAL)
                .status("TRIALING")
                .trialEndsAt(ends)
                .bulkGrandfathered(false)
                .updatedAt(LocalDateTime.now())
                .build());
        org.setSubscriptionPlan(SubscriptionPlan.PROFESSIONAL);
        org.setPlanAssignedAt(LocalDateTime.now());
        org.setPlanAssignedBy("trial");
        org.setPlanExpiresAt(ends);
        orgRepository.save(org);
        quotaService.resetToDefaults(org.getId(), SubscriptionPlan.PROFESSIONAL);
        log.info("Started {}-day Pro trial for org {}", trialDays, org.getId());
    }

    // ── Checkout / portal ────────────────────────────────────────────────────

    /** Creates a Stripe Checkout session for Pro and returns its URL. */
    public String startCheckout(Organization org, AppUser caller, String interval) {
        requireAvailable();
        boolean yearly = "YEAR".equalsIgnoreCase(interval);
        String priceId = yearly ? proYearlyPriceId : proMonthlyPriceId;
        if (blank(priceId)) throw new IllegalArgumentException("Yearly billing isn't available yet.");

        OrgBilling b = ensureBilling(org);
        if (b.getManagedBy() == OrgBilling.ManagedBy.STRIPE && isLive(b.getStatus())) {
            throw new IllegalStateException("This organisation already has an active subscription — use Manage billing.");
        }
        if (blank(b.getCustomerId())) {
            b.setCustomerId(stripe.createCustomer(caller.getEmail(), org.getName(), org.getId()));
            b.setProvider("STRIPE");
            b.setUpdatedAt(LocalDateTime.now());
            orgRepository.save(org);
        }
        String base = frontendUrl.replaceAll("/$", "");
        return stripe.createCheckoutSession(b.getCustomerId(), priceId, org.getId(),
                base + "/settings/billing?checkout=success", base + "/settings/billing?checkout=cancel");
    }

    /** Stripe Customer Portal (card, invoices, plan change, cancel). */
    public String openPortal(Organization org) {
        requireAvailable();
        OrgBilling b = org.getBilling();
        if (b == null || blank(b.getCustomerId())) {
            throw new IllegalStateException("There's no billing account yet — subscribe first.");
        }
        return stripe.createPortalSession(b.getCustomerId(), frontendUrl.replaceAll("/$", "") + "/settings/billing");
    }

    // ── Webhooks ─────────────────────────────────────────────────────────────

    /** Verifies and applies a Stripe webhook. Idempotent: replays of the same event are ignored. */
    public void handleStripeWebhook(String payload, String signature) {
        JsonNode event = stripe.verifyWebhook(payload, signature);
        String eventId = event.path("id").asText(null);
        String type    = event.path("type").asText("");
        JsonNode obj   = event.path("data").path("object");

        try {
            mongoTemplate.insert(BillingWebhookEvent.builder()
                    .id(eventId).type(type).receivedAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            log.info("Stripe event {} already processed — skipping", eventId);
            return;
        }

        try {
            dispatch(eventId, type, obj);
        } catch (RuntimeException e) {
            // Un-mark the event so Stripe's retry is processed instead of being skipped as a duplicate.
            mongoTemplate.remove(new Query(Criteria.where("_id").is(eventId)), BillingWebhookEvent.class);
            throw e;
        }
    }

    private void dispatch(String eventId, String type, JsonNode obj) {
        switch (type) {
            case "checkout.session.completed" -> {
                String orgId = text(obj.path("client_reference_id"));
                if (orgId == null) orgId = text(obj.path("metadata").path("orgId"));
                String subId = text(obj.path("subscription"));
                Organization org = orgId != null ? orgRepository.findById(orgId).orElse(null) : null;
                if (org == null) org = findByCustomer(text(obj.path("customer")));
                if (org == null || subId == null) { log.warn("checkout.session.completed without org/subscription ({})", eventId); return; }
                ensureBilling(org).setCustomerId(text(obj.path("customer")));
                applySubscription(org, stripe.getSubscription(subId), type);
            }
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" -> {
                Organization org = null;
                String orgId = text(obj.path("metadata").path("orgId"));
                if (orgId != null) org = orgRepository.findById(orgId).orElse(null);
                if (org == null) org = findByCustomer(text(obj.path("customer")));
                if (org == null) { log.warn("{} for unknown customer {} ({})", type, text(obj.path("customer")), eventId); return; }
                // Stripe doesn't guarantee event order: apply the subscription's CURRENT state, not the
                // event's snapshot (a late "updated: active" must not undo a "deleted").
                String subId = text(obj.path("id"));
                applySubscription(org, subId != null ? stripe.getSubscription(subId) : obj, type);
            }
            default -> log.debug("Ignoring Stripe event {} ({})", type, eventId);
        }
    }

    /**
     * Maps a Stripe subscription onto the org: plan, status, period end. Plan changes reset the
     * quota limits to the plan defaults (only when the plan actually changes).
     */
    void applySubscription(Organization org, JsonNode sub, String source) {
        OrgBilling b = ensureBilling(org);
        String status   = sub.path("status").asText("");
        // Manually managed (a Platform Admin's plan, or Free before any purchase): only a completed
        // checkout hands the org to Stripe automation; other subscription events just record status.
        if (b.getManagedBy() == OrgBilling.ManagedBy.MANUAL && !"checkout.session.completed".equals(source)) {
            b.setStatus(status.isEmpty() ? b.getStatus() : status.toUpperCase());
            b.setUpdatedAt(LocalDateTime.now());
            orgRepository.save(org);
            log.info("Billing: org {} is manually managed — recorded Stripe status {} ({}) without changing the plan",
                    org.getId(), b.getStatus(), source);
            return;
        }
        String priceId  = text(sub.path("items").path("data").path(0).path("price").path("id"));
        String interval = text(sub.path("items").path("data").path(0).path("price").path("recurring").path("interval"));
        // current_period_end lives on the subscription (older API versions) or its items (newer).
        long periodEnd  = sub.path("current_period_end").asLong(
                sub.path("items").path("data").path(0).path("current_period_end").asLong(0));

        b.setManagedBy(OrgBilling.ManagedBy.STRIPE);
        b.setProvider("STRIPE");
        b.setSubscriptionId(text(sub.path("id")));
        if (text(sub.path("customer")) != null) b.setCustomerId(text(sub.path("customer")));
        b.setPriceId(priceId);
        b.setInterval(interval != null ? interval.toUpperCase() : null);
        b.setCancelAtPeriodEnd(sub.path("cancel_at_period_end").asBoolean(false));
        b.setCurrentPeriodEnd(periodEnd > 0 ? LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC) : null);
        b.setTrialEndsAt(null);
        b.setUpdatedAt(LocalDateTime.now());

        SubscriptionPlan target;
        switch (status) {
            case "active", "trialing" -> { b.setStatus("ACTIVE"); target = planForPrice(priceId); }
            case "past_due"           -> { b.setStatus("PAST_DUE"); target = null; }   // keep plan during dunning
            case "incomplete"         -> { b.setStatus("INCOMPLETE"); target = null; }
            default                   -> { b.setStatus("CANCELED"); target = SubscriptionPlan.FREE; }   // canceled, unpaid, incomplete_expired
        }
        if ("customer.subscription.deleted".equals(source)) { b.setStatus("CANCELED"); target = SubscriptionPlan.FREE; }

        if (target != null) {
            org.setPlanExpiresAt(target == SubscriptionPlan.FREE ? null : b.getCurrentPeriodEnd());
            changePlan(org, target, "stripe");
        }
        orgRepository.save(org);
        log.info("Billing: org {} subscription {} → status={} plan={}", org.getId(), b.getSubscriptionId(),
                b.getStatus(), org.getSubscriptionPlan());
    }

    /** Notifies org admins once, ~3 days before their trial ends. */
    public int notifyTrialsEnding() {
        LocalDateTime now = LocalDateTime.now();
        Query q = new Query(Criteria.where("billing.managedBy").is("TRIAL")
                .and("billing.trialReminderSent").ne(true)
                .and("billing.trialEndsAt").gt(now).lte(now.plusDays(3)));
        int n = 0;
        for (Organization org : mongoTemplate.find(q, Organization.class)) {
            long days = PlanAccessService.trialDaysLeft(org);
            String title = "Your Pro trial ends in " + days + " day" + (days == 1 ? "" : "s") + " — upgrade to keep Pro features";
            userRepository.findByOrganizationIdAndActiveTrueAndRoleIn(org.getId(), List.of(AppUser.Role.ORG_ADMIN))
                    .forEach(u -> notificationService.notify(org.getId(), u.getId(), "billing.trial.ending", title, "/settings/billing"));
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(org.getId())),
                    new org.springframework.data.mongodb.core.query.Update().set("billing.trialReminderSent", true), Organization.class);
            n++;
        }
        return n;
    }

    /** Ends expired trials and lapsed Stripe subscriptions (safety net behind webhooks). */
    public int downgradeExpired() {
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        Query q = new Query(new Criteria().orOperator(
                Criteria.where("billing.managedBy").is("TRIAL").and("billing.trialEndsAt").lt(now),
                Criteria.where("billing.managedBy").is("STRIPE").and("billing.status").is("CANCELED")
                        .and("subscriptionPlan").ne(SubscriptionPlan.FREE)
                        .and("billing.currentPeriodEnd").lt(now)));
        for (Organization org : mongoTemplate.find(q, Organization.class)) {
            OrgBilling b = org.getBilling();
            if (b.getManagedBy() == OrgBilling.ManagedBy.TRIAL) {
                b.setStatus("TRIAL_ENDED");
                b.setManagedBy(OrgBilling.ManagedBy.MANUAL);   // back to normal Free; no further automation
            }
            b.setUpdatedAt(now);
            org.setPlanExpiresAt(null);
            changePlan(org, SubscriptionPlan.FREE, "billing-lifecycle");
            orgRepository.save(org);
            n++;
            log.info("Billing: org {} moved to Free ({})", org.getId(), b.getStatus());
        }
        return n;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void changePlan(Organization org, SubscriptionPlan plan, String by) {
        SubscriptionPlan previous = org.getSubscriptionPlan();
        if (previous == plan) return;
        org.setSubscriptionPlan(plan);
        org.setPlanAssignedAt(LocalDateTime.now());
        org.setPlanAssignedBy(by);
        quotaService.resetToDefaults(org.getId(), plan);
        auditLogService.log(org.getId(), org.getName(), AuditLog.Action.UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0, Map.of("plan", (previous != null ? previous.name() : "none") + " → " + plan.name(), "source", by),
                by, org.getId());
    }

    /**
     * Returns the org's billing record, creating it on first use. An org with no record predates
     * billing, so it keeps plan-gated features it already had (grandfathered).
     */
    private OrgBilling ensureBilling(Organization org) {
        if (org.getBilling() == null) {
            org.setBilling(OrgBilling.builder()
                    .managedBy(OrgBilling.ManagedBy.MANUAL)
                    .status("NONE")
                    .bulkGrandfathered(true)
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        return org.getBilling();
    }

    private SubscriptionPlan planForPrice(String priceId) {
        if (priceId != null && (priceId.equals(proMonthlyPriceId) || priceId.equals(proYearlyPriceId))) {
            return SubscriptionPlan.PROFESSIONAL;
        }
        log.warn("Unknown Stripe price '{}' — plan left unchanged", priceId);
        return null;
    }

    private Organization findByCustomer(String customerId) {
        if (customerId == null) return null;
        return mongoTemplate.findOne(new Query(Criteria.where("billing.customerId").is(customerId)), Organization.class);
    }

    private void requireAvailable() {
        if (!billingAvailable()) throw new IllegalStateException("Online billing isn't available yet. Please contact us to upgrade.");
    }

    private static boolean isLive(String status) {
        return "ACTIVE".equals(status) || "PAST_DUE".equals(status) || "INCOMPLETE".equals(status);
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
