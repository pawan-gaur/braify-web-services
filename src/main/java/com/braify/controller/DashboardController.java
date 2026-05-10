package com.braify.controller;

import com.braify.dto.DashboardStats;
import com.braify.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * GET /api/dashboard
     *
     * Returns analytics stats scoped to the caller's role / organisation.
     * Any authenticated user can access this endpoint.
     */
    @GetMapping
    public ResponseEntity<DashboardStats> getDashboard(Authentication auth) {
        AppUser caller = ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
        return ResponseEntity.ok(dashboardService.stats(caller));
    }
}
