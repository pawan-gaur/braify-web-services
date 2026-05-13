package com.braify.controller;

import com.braify.model.AppUser;
import com.braify.model.AuditLog;
import com.braify.security.UserDetailsImpl;
import com.braify.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    /**
     * Paginated audit log, role-scoped, newest first.
     *
     * <ul>
     *   <li>PLATFORM_ADMIN → all entries; pass {@code orgId} to scope to one organisation</li>
     *   <li>ORG_ADMIN      → entries by users in their org</li>
     *   <li>ADMIN          → entries by ADMIN + USER in their org</li>
     *   <li>USER           → own entries only</li>
     * </ul>
     *
     * GET /api/audit-logs?page=0&size=20&resourceType=TEMPLATE&orgId=abc123
     */
    @GetMapping
    public Page<AuditLog> getAll(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    AuditLog.ResourceType resourceType,
            @RequestParam(required = false)    String orgId,
            Authentication auth) {
        return auditLogService.getAll(page, size, resourceType, orgId, currentUser(auth));
    }

    /**
     * All audit entries for a single resource (template, email template, or user), newest first.
     * GET /api/audit-logs/resource/{resourceId}
     */
    @GetMapping("/resource/{resourceId}")
    public List<AuditLog> getForResource(@PathVariable String resourceId) {
        return auditLogService.getForResource(resourceId);
    }
}
