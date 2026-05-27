package com.braify.feature.organization.service;

import com.braify.feature.organization.dto.OrgFeaturesResponse;
import com.braify.feature.organization.dto.OrganizationRequest;
import com.braify.feature.organization.dto.SubscriptionRequest;
import com.braify.feature.organization.dto.SubscriptionResponse;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.shared.Feature;
import com.braify.feature.organization.model.Organization;
import com.braify.shared.SubscriptionPlan;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.quota.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository orgRepository;
    private final AuditLogService        auditLogService;
    private final QuotaService           quotaService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<Organization> findAll() {
        log.info("Finding all organizations");
        return orgRepository.findByDeletedFalseOrderByNameAsc();
    }

    public List<Organization> search(String q) {
        if (q == null || q.isBlank()) return findAll();
        return orgRepository.searchByQuery(q.trim());
    }

    public Organization findById(String id) {
        return orgRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + id));
    }

    /**
     * Creates a new organisation and logs the action.
     *
     * @param req          request payload
     * @param performedBy  email of the PLATFORM_ADMIN creating the org
     * @param createdById  user ID of the PLATFORM_ADMIN creating the org
     */
    public Organization create(OrganizationRequest req, String performedBy, String createdById) {
        if (orgRepository.existsByCode(req.getCode())) {
            throw new RuntimeException("Code already taken: " + req.getCode());
        }
        Organization org = Organization.builder()
                .name(req.getName())
                .code(req.getCode())
                .description(req.getDescription())
                .features(Feature.sanitise(req.getFeatures()))
                .active(true)
                .deleted(false)
                .createdBy(createdById)
                .build();
        Organization saved = orgRepository.save(org);
        log.info("Created organization '{}' with features {}", saved.getName(), saved.getFeatures());

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.ORGANIZATION,
                0,
                saved.getFeatures().isEmpty() ? null
                        : Map.of("features", saved.getFeatures()),
                performedBy,
                saved.getId());   // organizationId = the org itself

        return saved;
    }

    /**
     * Backward-compatible overload (performer email only, no creator ID).
     * Prefer {@link #create(OrganizationRequest, String, String)} when a user context is available.
     */
    public Organization create(OrganizationRequest req, String performedBy) {
        return create(req, performedBy, null);
    }

    /**
     * Backward-compatible overload (no performer) — kept so existing callers still compile.
     * Should migrate to {@link #create(OrganizationRequest, String, String)} when possible.
     */
    public Organization create(OrganizationRequest req) {
        return create(req, "system", null);
    }

    /**
     * Updates an organisation's metadata (and optionally its features) and logs the change.
     *
     * @param performedBy email of the PLATFORM_ADMIN performing the update
     */
    public Organization update(String id, OrganizationRequest req, String performedBy) {
        Organization org = findById(id);

        List<String> oldFeatures = org.getFeatures() != null ? List.copyOf(org.getFeatures()) : List.of();

        org.setName(req.getName());
        org.setDescription(req.getDescription());
        if (req.getFeatures() != null) {
            org.setFeatures(Feature.sanitise(req.getFeatures()));
        }
        Organization saved = orgRepository.save(org);
        log.info("Updated organization '{}', features now {}", saved.getName(), saved.getFeatures());

        List<String> newFeatures = saved.getFeatures() != null ? saved.getFeatures() : List.of();
        boolean featuresChanged = !oldFeatures.equals(newFeatures);

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0,
                featuresChanged
                        ? Map.of("features", Map.of("from", oldFeatures, "to", newFeatures))
                        : null,
                performedBy,
                saved.getId());

        return saved;
    }

    /** Backward-compatible overload — kept so existing callers still compile. */
    public Organization update(String id, OrganizationRequest req) {
        return update(id, req, "system");
    }

    public void delete(String id) {
        Organization org = findById(id);
        org.setDeleted(true);
        org.setDeletedAt(LocalDateTime.now());
        orgRepository.save(org);
        log.info("Soft-deleted organization '{}'", org.getName());
    }

    // ── Feature management ────────────────────────────────────────────────────

    /**
     * Returns the current feature list for a single organisation.
     * Used by GET /api/organizations/{id}/features.
     */
    public OrgFeaturesResponse getFeatures(String id) {
        Organization org = findById(id);
        return OrgFeaturesResponse.builder()
                .organizationId(org.getId())
                .organizationName(org.getName())
                .features(org.getFeatures() != null ? org.getFeatures() : List.of())
                .build();
    }

    /**
     * Replaces the feature list for an organisation and writes an audit log entry.
     *
     * @param id          organisation ID
     * @param rawFeatures raw feature key list (unknown keys are stripped by {@link Feature#sanitise})
     * @param performedBy email of the PLATFORM_ADMIN performing the change
     */
    public OrgFeaturesResponse updateFeatures(String id, List<String> rawFeatures, String performedBy) {
        Organization org = findById(id);
        List<String> oldFeatures = org.getFeatures() != null ? List.copyOf(org.getFeatures()) : List.of();

        List<String> sanitised = Feature.sanitise(rawFeatures);
        org.setFeatures(sanitised);
        orgRepository.save(org);
        log.info("Updated features for org '{}': {}", org.getName(), sanitised);

        auditLogService.log(
                org.getId(), org.getName(),
                AuditLog.Action.FEATURES_UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0,
                Map.of("features", Map.of("from", oldFeatures, "to", sanitised)),
                performedBy,
                org.getId());   // organizationId = the org itself

        return OrgFeaturesResponse.builder()
                .organizationId(org.getId())
                .organizationName(org.getName())
                .features(sanitised)
                .build();
    }

    /** Backward-compatible overload — kept so existing callers still compile. */
    public OrgFeaturesResponse updateFeatures(String id, List<String> rawFeatures) {
        return updateFeatures(id, rawFeatures, "system");
    }

    // ── Subscription management ───────────────────────────────────────────────

    public SubscriptionResponse getSubscription(String id) {
        Organization org = findById(id);
        SubscriptionPlan plan = org.getSubscriptionPlan() != null
                ? org.getSubscriptionPlan() : SubscriptionPlan.FREE;
        return toSubscriptionResponse(org, plan);
    }

    /**
     * Assigns a subscription plan to an organisation and resets its quota config
     * to the plan's default limits.  Platform Admin may override individual limits
     * afterwards via PUT /api/organizations/{id}/quota/config.
     */
    public SubscriptionResponse assignSubscription(String id,
                                                   SubscriptionRequest req,
                                                   String performedBy) {
        Organization org = findById(id);
        SubscriptionPlan oldPlan = org.getSubscriptionPlan() != null
                ? org.getSubscriptionPlan() : SubscriptionPlan.FREE;

        SubscriptionPlan newPlan;
        try {
            newPlan = SubscriptionPlan.valueOf(req.getSubscriptionPlan().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Unknown subscription plan: " + req.getSubscriptionPlan());
        }

        org.setSubscriptionPlan(newPlan);
        org.setPlanAssignedAt(LocalDateTime.now());
        org.setPlanAssignedBy(performedBy);
        org.setPlanExpiresAt(req.getPlanExpiresAt());
        orgRepository.save(org);

        // Reset quota config to new plan defaults
        quotaService.resetToDefaults(org.getId(), newPlan);

        log.info("Assigned plan '{}' to org '{}' (was '{}')", newPlan, org.getName(), oldPlan);

        auditLogService.log(
                org.getId(), org.getName(),
                AuditLog.Action.SUBSCRIPTION_CHANGED, AuditLog.ResourceType.ORGANIZATION,
                0,
                Map.of("from", oldPlan.name(), "to", newPlan.name()),
                performedBy,
                org.getId());

        return toSubscriptionResponse(org, newPlan);
    }

    private SubscriptionResponse toSubscriptionResponse(Organization org, SubscriptionPlan plan) {
        return SubscriptionResponse.builder()
                .organizationId(org.getId())
                .organizationName(org.getName())
                .subscriptionPlan(plan)
                .planLabel(plan.label)
                .planAssignedAt(org.getPlanAssignedAt())
                .planAssignedBy(org.getPlanAssignedBy())
                .planExpiresAt(org.getPlanExpiresAt())
                .defaultMaxUsers(plan.defaultMaxUsers)
                .defaultMaxDocsPerMonth(plan.defaultMaxDocsPerMonth)
                .defaultMaxStorageMb(plan.defaultMaxStorageMb)
                .defaultMaxApiCallsPerMonth(plan.defaultMaxApiCallsPerMonth)
                .build();
    }
}
