package com.braify.service;

import com.braify.dto.OrgFeaturesResponse;
import com.braify.dto.OrganizationRequest;
import com.braify.model.AuditLog;
import com.braify.model.Feature;
import com.braify.model.Organization;
import com.braify.repository.OrganizationRepository;
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
     * @param req         request payload
     * @param performedBy email of the PLATFORM_ADMIN creating the org
     */
    public Organization create(OrganizationRequest req, String performedBy) {
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
     * Backward-compatible overload (no performer) — kept so existing callers still compile.
     * Should migrate to {@link #create(OrganizationRequest, String)} when possible.
     */
    public Organization create(OrganizationRequest req) {
        return create(req, "system");
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
}
