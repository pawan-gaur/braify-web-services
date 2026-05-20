package com.braify.feature.audit.controller;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    // ── Paginated log ─────────────────────────────────────────────────────────

    /**
     * Paginated audit log, role-scoped, newest first.
     *
     * <ul>
     *   <li>PLATFORM_ADMIN → all entries; pass {@code orgId} to scope to one org</li>
     *   <li>ORG_ADMIN      → entries by users in their org</li>
     *   <li>ADMIN          → entries by ADMIN + USER in their org</li>
     *   <li>USER           → own entries only</li>
     * </ul>
     *
     * All filter params are optional and may be combined freely.
     * GET /api/audit-logs?page=0&size=20&resourceType=TEMPLATE&action=DELETED
     *                    &orgId=abc&performedBy=john@example.com
     *                    &from=2025-01-01T00:00:00&to=2025-12-31T23:59:59
     */
    @GetMapping
    public Page<AuditLog> getAll(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    AuditLog.ResourceType resourceType,
            @RequestParam(required = false)    String orgId,
            @RequestParam(required = false)    AuditLog.Action action,
            @RequestParam(required = false)    String performedBy,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication auth) {

        log.debug("GET /api/audit-logs page={} size={} resourceType={} orgId={}", page, size, resourceType, orgId);
        return auditLogService.getAll(page, size, resourceType, orgId,
                action, performedBy, from, to, currentUser(auth));
    }

    // ── Per-resource log ──────────────────────────────────────────────────────

    /**
     * All audit entries for a single resource, newest first.
     * GET /api/audit-logs/resource/{resourceId}
     */
    @GetMapping("/resource/{resourceId}")
    public List<AuditLog> getForResource(@PathVariable String resourceId) {
        log.debug("GET /api/audit-logs/resource/{}", resourceId);
        return auditLogService.getForResource(resourceId);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /**
     * Returns four aggregate counts scoped to the caller's role:
     * {@code total}, {@code today}, {@code critical}, {@code failures}.
     * PLATFORM_ADMIN may pass {@code orgId} to scope to one organisation.
     *
     * GET /api/audit-logs/stats?orgId=abc123
     */
    @GetMapping("/stats")
    public Map<String, Long> getStats(
            @RequestParam(required = false) String orgId,
            Authentication auth) {
        log.debug("GET /api/audit-logs/stats orgId={}", orgId);
        return auditLogService.getStats(currentUser(auth), orgId);
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    /**
     * Exports up to 10 000 log entries as a UTF-8 CSV file.
     * Accepts the same filter parameters as {@link #getAll} (except page/size).
     * The caller's role scope is always enforced — no privilege escalation is possible.
     *
     * GET /api/audit-logs/export?action=DELETED&from=2025-01-01T00:00:00
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false)    AuditLog.ResourceType resourceType,
            @RequestParam(required = false)    String orgId,
            @RequestParam(required = false)    AuditLog.Action action,
            @RequestParam(required = false)    String performedBy,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Authentication auth) {

        log.info("GET /api/audit-logs/export resourceType={} orgId={}", resourceType, orgId);
        byte[] csv = auditLogService.exportCsv(
                resourceType, orgId, action, performedBy, from, to, currentUser(auth));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }
}
