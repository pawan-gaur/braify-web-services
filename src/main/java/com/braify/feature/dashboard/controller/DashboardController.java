package com.braify.feature.dashboard.controller;

import com.braify.feature.dashboard.dto.AnalyticsResponse;
import com.braify.feature.dashboard.dto.DashboardStats;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.dashboard.service.AnalyticsService;
import com.braify.feature.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final AnalyticsService analyticsService;

    /**
     * GET /api/dashboard
     *
     * Returns analytics stats scoped to the caller's role / organisation.
     * Any authenticated user can access this endpoint.
     */
    @GetMapping
    public ResponseEntity<DashboardStats> getDashboard(Authentication auth) {
        AppUser caller = ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
        log.debug("GET /api/dashboard caller='{}'", caller.getEmail());
        return ResponseEntity.ok(dashboardService.stats(caller));
    }

    /**
     * GET /api/dashboard/analytics?days=30
     *
     * Live, role-scoped, period-filtered analytics for the dashboard's Analytics tab:
     * top / least-used templates, most-active performers, and the e-sign conversion
     * funnel. Scope follows the caller's role (PLATFORM_ADMIN → all; ORG_ADMIN → org;
     * ADMIN → own + USERs; USER → own).
     */
    @GetMapping("/analytics")
    public ResponseEntity<AnalyticsResponse> getAnalytics(
            @RequestParam(defaultValue = "30") int days,
            Authentication auth) {
        AppUser caller = ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
        log.debug("GET /api/dashboard/analytics caller='{}' days={}", caller.getEmail(), days);
        return ResponseEntity.ok(analyticsService.analytics(caller, days));
    }
}
