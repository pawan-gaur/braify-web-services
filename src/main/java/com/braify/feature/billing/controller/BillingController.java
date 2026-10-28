package com.braify.feature.billing.controller;

import com.braify.feature.billing.model.OrgBilling;
import com.braify.feature.billing.service.BillingService;
import com.braify.feature.billing.service.PlanAccessService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.shared.SubscriptionPlan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plans & billing for the caller's organisation, plus the Stripe webhook.
 * Checkout / portal are ORG_ADMIN-only (Platform Admins manage plans manually).
 */
@Slf4j
@Tag(name = "Billing", description = "Plan, trial, Stripe checkout / customer portal, entitlements.")
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService         billingService;
    private final OrganizationRepository orgRepository;
    private final PlanAccessService      planAccessService;

    @Value("${app.billing.pro-monthly-display:$29}")
    private String proMonthlyDisplay;

    @Value("${app.billing.pro-yearly-display:$290}")
    private String proYearlyDisplay;

    @Operation(summary = "What the caller's plan allows (any signed-in Braify user)")
    @GetMapping("/entitlements")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
    public ResponseEntity<Map<String, Object>> entitlements(Authentication auth) {
        AppUser user = user(auth);
        Map<String, Object> m = new LinkedHashMap<>();
        if (user.getOrganizationId() == null) {   // Platform Admin — not plan-limited
            m.put("plan", null);
            m.put("bulkEnabled", true);
            m.put("trial", false);
            m.put("billingAvailable", billingService.billingAvailable());
            return ResponseEntity.ok(m);
        }
        Organization org = requireOrg(user.getOrganizationId());
        OrgBilling b = org.getBilling();
        boolean trial = b != null && b.getManagedBy() == OrgBilling.ManagedBy.TRIAL;
        m.put("plan", plan(org).name());
        m.put("planLabel", plan(org).label);
        m.put("bulkEnabled", planAccessService.bulkEnabled(org));
        m.put("trial", trial);
        m.put("trialEndsAt", trial ? b.getTrialEndsAt() : null);
        m.put("trialDaysLeft", PlanAccessService.trialDaysLeft(org));
        m.put("status", b != null ? b.getStatus() : null);
        m.put("billingAvailable", billingService.billingAvailable());
        return ResponseEntity.ok(m);
    }

    @Operation(summary = "Billing summary for an organisation (ORG_ADMIN: own org; PLATFORM_ADMIN: ?orgId=)")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(required = false) String orgId, Authentication auth) {
        Organization org = requireOrg(resolveOrgId(user(auth), orgId));
        OrgBilling b = org.getBilling();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", org.getId());
        m.put("plan", plan(org).name());
        m.put("planLabel", plan(org).label);
        m.put("managedBy", b != null ? b.getManagedBy().name() : "MANUAL");
        m.put("status", b != null ? b.getStatus() : null);
        m.put("trialEndsAt", b != null ? b.getTrialEndsAt() : null);
        m.put("trialDaysLeft", PlanAccessService.trialDaysLeft(org));
        m.put("currentPeriodEnd", b != null ? b.getCurrentPeriodEnd() : null);
        m.put("cancelAtPeriodEnd", b != null && b.isCancelAtPeriodEnd());
        m.put("interval", b != null ? b.getInterval() : null);
        m.put("hasBillingAccount", b != null && b.getCustomerId() != null);
        m.put("bulkEnabled", planAccessService.bulkEnabled(org));
        m.put("billingAvailable", billingService.billingAvailable());
        m.put("yearlyAvailable", billingService.yearlyAvailable());
        m.put("proMonthlyPrice", proMonthlyDisplay);
        m.put("proYearlyPrice", proYearlyDisplay);
        m.put("planExpiresAt", org.getPlanExpiresAt());
        return ResponseEntity.ok(m);
    }

    @Operation(summary = "Start Stripe Checkout for Pro", description = "Body: `{ interval: MONTH | YEAR }`. Returns `{ url }`.")
    @PostMapping("/checkout")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> checkout(@RequestBody(required = false) Map<String, String> body,
                                                        Authentication auth) {
        AppUser u = user(auth);
        Organization org = requireOrg(u.getOrganizationId());
        String interval = body != null ? body.getOrDefault("interval", "MONTH") : "MONTH";
        return ResponseEntity.ok(Map.of("url", billingService.startCheckout(org, u, interval)));
    }

    @Operation(summary = "Open the Stripe Customer Portal", description = "Returns `{ url }`.")
    @PostMapping("/portal")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<Map<String, String>> portal(Authentication auth) {
        Organization org = requireOrg(user(auth).getOrganizationId());
        return ResponseEntity.ok(Map.of("url", billingService.openPortal(org)));
    }

    @Operation(summary = "Stripe webhook (public; verified by the Stripe-Signature header)")
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Boolean>> webhook(@RequestBody String payload,
                                                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        billingService.handleStripeWebhook(payload, signature);
        return ResponseEntity.ok(Map.of("received", true));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AppUser user(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    private static String resolveOrgId(AppUser caller, String requested) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            if (requested == null || requested.isBlank()) throw new IllegalArgumentException("orgId is required");
            return requested;
        }
        if (requested != null && !requested.isBlank() && !requested.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("Access denied");
        }
        return caller.getOrganizationId();
    }

    private Organization requireOrg(String orgId) {
        if (orgId == null) throw new IllegalArgumentException("No organisation");
        return orgRepository.findById(orgId).orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));
    }

    private static SubscriptionPlan plan(Organization org) {
        return org.getSubscriptionPlan() != null ? org.getSubscriptionPlan() : SubscriptionPlan.FREE;
    }
}
