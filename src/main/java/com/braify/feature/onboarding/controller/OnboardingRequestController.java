package com.braify.feature.onboarding.controller;

import com.braify.feature.onboarding.dto.OnboardingReviewRequest;
import com.braify.feature.onboarding.dto.OnboardingSubmitRequest;
import com.braify.feature.onboarding.model.OnboardingRequest;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.onboarding.service.OnboardingRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingRequestController {

    private final OnboardingRequestService onboardingService;

    private String performedBy(Authentication auth) {
        if (auth == null) return "system";
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetailsImpl ud) return ud.getUsername();
        return auth.getName();
    }

    // ── Public endpoint — no authentication required ───────────────────────────

    /**
     * POST /api/onboarding
     * Submit an onboarding request from the public "Get Started" form.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody OnboardingSubmitRequest req) {
        log.info("POST /api/onboarding applicantEmail='{}' org='{}'", req.getApplicantEmail(), req.getOrganizationName());
        try {
            OnboardingRequest saved = onboardingService.submit(req);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Your application has been received. We'll be in touch shortly.",
                    "id", saved.getId()
            ));
        } catch (RuntimeException ex) {
            log.warn("Onboarding submission rejected for '{}': {}", req.getApplicantEmail(), ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    // ── Platform Admin endpoints ───────────────────────────────────────────────

    /**
     * GET /api/onboarding
     * Returns all onboarding requests, optionally filtered by status.
     */
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<OnboardingRequest> getAll(
            @RequestParam(required = false) OnboardingRequest.Status status) {
        log.debug("GET /api/onboarding status={}", status);
        return status != null
                ? onboardingService.findByStatus(status)
                : onboardingService.findAll();
    }

    /**
     * GET /api/onboarding/count/pending
     * Returns the count of PENDING requests (used for badge in sidebar/dashboard).
     */
    @GetMapping("/count/pending")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Map<String, Long> pendingCount() {
        return Map.of("count", onboardingService.countPending());
    }

    /**
     * GET /api/onboarding/{id}
     * Returns a single onboarding request by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public OnboardingRequest getById(@PathVariable String id) {
        return onboardingService.findById(id);
    }

    /**
     * PUT /api/onboarding/{id}/review
     * Approve, reject, or request more information.
     * Body: { "action": "APPROVE|REJECT|INFO_REQUIRED", "note": "...", "approvedFeatures": [...] }
     */
    @PutMapping("/{id}/review")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OnboardingRequest> review(
            @PathVariable String id,
            @Valid @RequestBody OnboardingReviewRequest reviewRequest,
            Authentication auth) {
        log.info("PUT /api/onboarding/{}/review action='{}' by '{}'", id, reviewRequest.getAction(), performedBy(auth));
        ResponseEntity<OnboardingRequest> result = ResponseEntity.ok(onboardingService.review(id, reviewRequest, performedBy(auth)));
        log.info("Onboarding request '{}' reviewed with action '{}'", id, reviewRequest.getAction());
        return result;
    }
}
