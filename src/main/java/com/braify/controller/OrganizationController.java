package com.braify.controller;

import com.braify.dto.OrgFeaturesRequest;
import com.braify.dto.OrgFeaturesResponse;
import com.braify.dto.OrganizationRequest;
import com.braify.model.Organization;
import com.braify.security.UserDetailsImpl;
import com.braify.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService orgService;

    /** Extracts the acting user's email from the JWT principal. */
    private String performedBy(Authentication auth) {
        if (auth == null) return "system";
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetailsImpl ud) return ud.getUsername();
        return auth.getName();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Organization> getAll() {
        return orgService.findAll();
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Organization> search(@RequestParam(defaultValue = "") String q) {
        return orgService.search(q);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Organization getById(@PathVariable String id) {
        return orgService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Organization> create(@RequestBody OrganizationRequest req,
                                               Authentication auth) {
        return ResponseEntity.ok(orgService.create(req, performedBy(auth)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Organization> update(@PathVariable String id,
                                               @RequestBody OrganizationRequest req,
                                               Authentication auth) {
        return ResponseEntity.ok(orgService.update(id, req, performedBy(auth)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        orgService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Feature management ────────────────────────────────────────────────────

    /**
     * GET /api/organizations/{id}/features
     * Returns the current feature list for an organisation.
     * Used by the "Manage Features" modal to load the latest state before editing.
     */
    @GetMapping("/{id}/features")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgFeaturesResponse> getFeatures(@PathVariable String id) {
        return ResponseEntity.ok(orgService.getFeatures(id));
    }

    /**
     * PUT /api/organizations/{id}/features
     * Replaces the feature list for an organisation.
     * Body: { "features": ["PDF_TEMPLATES", "E_SIGN"] }
     * Returns the updated feature list.
     */
    @PutMapping("/{id}/features")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgFeaturesResponse> updateFeatures(@PathVariable String id,
                                                               @RequestBody OrgFeaturesRequest req,
                                                               Authentication auth) {
        return ResponseEntity.ok(orgService.updateFeatures(id, req.getFeatures(), performedBy(auth)));
    }
}
