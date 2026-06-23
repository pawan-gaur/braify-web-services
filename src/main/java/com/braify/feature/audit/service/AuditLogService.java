package com.braify.feature.audit.service;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository  userRepository;
    private final MongoTemplate      mongoTemplate;

    // ── Severity mapping ──────────────────────────────────────────────────────

    private static final Set<AuditLog.Action> CRITICAL_ACTIONS = Set.of(
            AuditLog.Action.DEACTIVATED,
            AuditLog.Action.SESSION_REVOKED,
            AuditLog.Action.FEATURES_UPDATED,
            AuditLog.Action.SUBSCRIPTION_CHANGED,
            AuditLog.Action.QUOTA_EXCEEDED,
            AuditLog.Action.API_KEY_REVOKED,
            AuditLog.Action.API_KEY_TOGGLED,
            AuditLog.Action.PLATFORM_SETTINGS_UPDATED
    );
    private static final Set<AuditLog.Action> WARNING_ACTIONS = Set.of(
            AuditLog.Action.DELETED,
            AuditLog.Action.CANCELLED,
            AuditLog.Action.PASSWORD_CHANGED,
            AuditLog.Action.TEMPLATE_SHARED,
            AuditLog.Action.TEMPLATE_UNSHARED,
            AuditLog.Action.API_KEY_CREATED,
            AuditLog.Action.LOGOUT
    );

    private static AuditLog.Severity resolveSeverity(AuditLog.Action action) {
        if (action == null)                     return AuditLog.Severity.INFO;
        if (CRITICAL_ACTIONS.contains(action))  return AuditLog.Severity.CRITICAL;
        if (WARNING_ACTIONS.contains(action))   return AuditLog.Severity.WARNING;
        return AuditLog.Severity.INFO;
    }

    // ── SHA-256 integrity hash ────────────────────────────────────────────────

    private static String computeHash(AuditLog entry) {
        String payload = String.join("|",
                nullSafe(entry.getTemplateId()),
                entry.getAction()       != null ? entry.getAction().name()       : "",
                entry.getResourceType() != null ? entry.getResourceType().name() : "",
                nullSafe(entry.getPerformedBy()),
                nullSafe(entry.getOrganizationId()),
                entry.getTimestamp()    != null ? entry.getTimestamp().toString() : ""
        );
        try {
            MessageDigest md   = MessageDigest.getInstance("SHA-256");
            byte[]        hash = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb   = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null; // NoSuchAlgorithmException — should never happen for SHA-256
        }
    }

    private static String nullSafe(String s) { return s != null ? s : ""; }

    // ── IP / UA extraction ────────────────────────────────────────────────────

    private static String extractIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            return (xff != null && !xff.isBlank())
                    ? xff.split(",")[0].trim()
                    : req.getRemoteAddr();
        } catch (Exception e) { return null; }
    }

    private static String extractUa() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return attrs.getRequest().getHeader("User-Agent");
        } catch (Exception e) { return null; }
    }

    // ── Write (public API — backward-compatible overloads) ────────────────────

    /**
     * Full entry with organisation scope.
     * All other overloads delegate here; maintains the 8-param signature used
     * by existing callers (TemplateService, ESignService, etc.).
     */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes,
                        String performedBy,
                        String organizationId) {
        return persist(resourceId, resourceName, action, resourceType, version,
                changes, performedBy, organizationId, AuditLog.Outcome.SUCCESS, null);
    }

    /** 7-param overload — organizationId defaults to null. */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes,
                        String performedBy) {
        return log(resourceId, resourceName, action, resourceType, version, changes, performedBy, null);
    }

    /** 6-param overload — performer defaults to "system", orgId to null. */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes) {
        return log(resourceId, resourceName, action, resourceType, version, changes, "system", null);
    }

    /**
     * Preferred overload — accepts the {@link AppUser} directly so that
     * {@code performedByUserId}, {@code performedByRole} and display name are
     * captured without an extra database round-trip.
     *
     * <p>Use this in new code; the email-based overloads are kept for backward
     * compatibility with existing call sites.
     */
    public AuditLog logByUser(String resourceId,
                              String resourceName,
                              AuditLog.Action action,
                              AuditLog.ResourceType resourceType,
                              int version,
                              Map<String, Object> changes,
                              AppUser performer) {
        return persistByUser(resourceId, resourceName, action, resourceType, version,
                changes, performer, AuditLog.Outcome.SUCCESS, null);
    }

    /**
     * Records a failed action for compliance trail.
     * Outcome is set to FAILURE and the failure reason is stored.
     */
    public AuditLog logFailure(String resourceId,
                               String resourceName,
                               AuditLog.Action action,
                               AuditLog.ResourceType resourceType,
                               String performedBy,
                               String organizationId,
                               String failureReason) {
        return persist(resourceId, resourceName, action, resourceType, 0, null,
                performedBy, organizationId, AuditLog.Outcome.FAILURE, failureReason);
    }

    /**
     * Records a failed action by a known AppUser.
     */
    public AuditLog logFailureByUser(String resourceId,
                                     String resourceName,
                                     AuditLog.Action action,
                                     AuditLog.ResourceType resourceType,
                                     AppUser performer,
                                     String failureReason) {
        return persistByUser(resourceId, resourceName, action, resourceType, 0,
                null, performer, AuditLog.Outcome.FAILURE, failureReason);
    }

    // ── Core write ────────────────────────────────────────────────────────────

    /**
     * Core write path — performer identified by email (backward-compat).
     * Looks up the user record from the email to obtain userId, role, and name.
     */
    private AuditLog persist(String resourceId,
                             String resourceName,
                             AuditLog.Action action,
                             AuditLog.ResourceType resourceType,
                             int version,
                             Map<String, Object> changes,
                             String performedBy,
                             String organizationId,
                             AuditLog.Outcome outcome,
                             String failureReason) {

        // Snapshot performer display info (non-blocking; failures silently skipped)
        String performedByUserId = null;
        String performedByName   = null;
        String performedByRole   = null;
        String performer         = (performedBy != null) ? performedBy : "system";
        if (!"system".equals(performer)) {
            try {
                var opt = userRepository.findByEmail(performer);
                if (opt.isPresent()) {
                    var u = opt.get();
                    performedByUserId = u.getId();
                    performedByRole   = u.getRole() != null ? u.getRole().name() : null;
                    String full = (u.getFirstName() + " " + u.getLastName()).trim();
                    if (!full.isBlank()) performedByName = full;
                }
            } catch (Exception ignored) {}
        }

        // Set timestamp manually so computeHash() has a stable value before save
        LocalDateTime now = LocalDateTime.now();

        AuditLog entry = AuditLog.builder()
                .templateId(resourceId)
                .templateName(resourceName)
                .action(action)
                .resourceType(resourceType)
                .versionNumber(version)
                .performedBy(performer)
                .performedByUserId(performedByUserId)
                .performedByName(performedByName)
                .performedByRole(performedByRole)
                .changes(changes)
                .organizationId(organizationId)
                .timestamp(now)
                .ipAddress(extractIp())
                .userAgent(extractUa())
                .severity(resolveSeverity(action))
                .outcome(outcome)
                .failureReason(failureReason)
                .build();

        entry.setIntegrityHash(computeHash(entry));

        AuditLog saved = auditLogRepository.save(entry);
        log.debug("Audit logged: action={} resource={} resourceId='{}' by='{}' role='{}'",
                action, resourceType, resourceId, performer, performedByRole);
        return saved;
    }

    /**
     * Preferred core write path — performer supplied as an {@link AppUser} object.
     * No extra DB lookup needed; userId, role, and name are taken directly from the user.
     */
    private AuditLog persistByUser(String resourceId,
                                   String resourceName,
                                   AuditLog.Action action,
                                   AuditLog.ResourceType resourceType,
                                   int version,
                                   Map<String, Object> changes,
                                   AppUser performer,
                                   AuditLog.Outcome outcome,
                                   String failureReason) {

        String displayName = (performer.getFirstName() + " " + performer.getLastName()).trim();

        LocalDateTime now = LocalDateTime.now();

        AuditLog entry = AuditLog.builder()
                .templateId(resourceId)
                .templateName(resourceName)
                .action(action)
                .resourceType(resourceType)
                .versionNumber(version)
                .performedByUserId(performer.getId())
                .performedBy(performer.getEmail())
                .performedByName(displayName.isBlank() ? null : displayName)
                .performedByRole(performer.getRole() != null ? performer.getRole().name() : null)
                .changes(changes)
                .organizationId(performer.getOrganizationId())
                .timestamp(now)
                .ipAddress(extractIp())
                .userAgent(extractUa())
                .severity(resolveSeverity(action))
                .outcome(outcome)
                .failureReason(failureReason)
                .build();

        entry.setIntegrityHash(computeHash(entry));

        AuditLog saved = auditLogRepository.save(entry);
        log.debug("Audit logged: action={} resource={} resourceId='{}' userId='{}' role='{}'",
                action, resourceType, resourceId, performer.getId(),
                performer.getRole() != null ? performer.getRole().name() : "null");
        return saved;
    }

    // ── Read — single resource ────────────────────────────────────────────────

    /** All logs for a specific resource (PDF template, email template, user…), newest first. */
    public List<AuditLog> getForResource(String resourceId) {
        return auditLogRepository.findByTemplateIdOrderByTimestampDesc(resourceId);
    }

    // ── Read — paginated, role-scoped, multi-filter ───────────────────────────

    /**
     * Paginated audit log scoped by the caller's role with optional multi-dimensional
     * filtering.  Uses MongoTemplate so every combination of filters can be expressed
     * as a single Criteria without combinatorial repository methods.
     *
     * <ul>
     *   <li>PLATFORM_ADMIN → all entries; pass {@code orgId} to scope to one org</li>
     *   <li>ORG_ADMIN      → entries performed by any user in their org</li>
     *   <li>ADMIN          → entries by ADMIN + USER in their org</li>
     *   <li>USER           → own entries only</li>
     * </ul>
     */
    public Page<AuditLog> getAll(int page, int size,
                                 AuditLog.ResourceType resourceType,
                                 String orgId,
                                 AuditLog.Action action,
                                 String performedBy,
                                 LocalDateTime from,
                                 LocalDateTime to,
                                 AppUser caller) {

        List<Criteria> parts = new ArrayList<>();
        addRoleScope(caller, orgId, parts);

        if (resourceType != null)
            parts.add(Criteria.where("resourceType").is(resourceType));
        if (action != null)
            parts.add(Criteria.where("action").is(action));
        if (performedBy != null && !performedBy.isBlank())
            parts.add(Criteria.where("performedBy").regex(performedBy, "i"));
        if (from != null && to != null)
            parts.add(Criteria.where("timestamp").gte(from).lte(to));
        else if (from != null)
            parts.add(Criteria.where("timestamp").gte(from));
        else if (to != null)
            parts.add(Criteria.where("timestamp").lte(to));

        Criteria combined = toCriteria(parts);
        Query    query    = Query.query(combined).with(Sort.by(Sort.Direction.DESC, "timestamp"));

        long          total   = mongoTemplate.count(query, AuditLog.class);
        List<AuditLog> items  = mongoTemplate.find(
                query.skip((long) page * size).limit(size), AuditLog.class);

        return new PageImpl<>(items, PageRequest.of(page, size), total);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /**
     * Returns four aggregate counts scoped by the caller's role:
     * total, today, critical, failures.
     */
    public Map<String, Long> getStats(AppUser caller, String orgId) {
        List<Criteria> base = new ArrayList<>();
        addRoleScope(caller, orgId, base);
        Criteria scope = toCriteria(base);

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();

        long total    = mongoTemplate.count(Query.query(scope), AuditLog.class);
        long today    = mongoTemplate.count(Query.query(and(scope, Criteria.where("timestamp").gte(dayStart))), AuditLog.class);
        long critical = mongoTemplate.count(Query.query(and(scope, Criteria.where("severity").is(AuditLog.Severity.CRITICAL))), AuditLog.class);
        long failures = mongoTemplate.count(Query.query(and(scope, Criteria.where("outcome").is(AuditLog.Outcome.FAILURE))), AuditLog.class);

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total",    total);
        stats.put("today",    today);
        stats.put("critical", critical);
        stats.put("failures", failures);
        return stats;
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    /**
     * Exports up to 10 000 log entries (same filters as getAll) as UTF-8 CSV bytes.
     * The caller's role scope is always applied — admin can't bypass their constraints.
     */
    public byte[] exportCsv(AuditLog.ResourceType resourceType,
                             String orgId,
                             AuditLog.Action action,
                             String performedBy,
                             LocalDateTime from,
                             LocalDateTime to,
                             AppUser caller) {

        List<Criteria> parts = new ArrayList<>();
        addRoleScope(caller, orgId, parts);
        if (resourceType != null) parts.add(Criteria.where("resourceType").is(resourceType));
        if (action       != null) parts.add(Criteria.where("action").is(action));
        if (performedBy  != null && !performedBy.isBlank()) parts.add(Criteria.where("performedBy").regex(performedBy, "i"));
        if (from != null && to != null) parts.add(Criteria.where("timestamp").gte(from).lte(to));
        else if (from != null)          parts.add(Criteria.where("timestamp").gte(from));
        else if (to   != null)          parts.add(Criteria.where("timestamp").lte(to));

        List<AuditLog> logs = mongoTemplate.find(
                Query.query(toCriteria(parts))
                     .with(Sort.by(Sort.Direction.DESC, "timestamp"))
                     .limit(10_000),
                AuditLog.class);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder csv = new StringBuilder();
        csv.append("Timestamp,Action,Severity,Outcome,ResourceType,ResourceName,ResourceId,")
           .append("OrganizationId,PerformedByUserId,PerformedBy,PerformedByName,PerformedByRole,")
           .append("IpAddress,UserAgent,Reason,FailureReason,IntegrityHash\n");

        for (AuditLog l : logs) {
            csv.append(csv(l.getTimestamp()     != null ? l.getTimestamp().format(fmt) : "")).append(',');
            csv.append(csv(l.getAction()        != null ? l.getAction().name()         : "")).append(',');
            csv.append(csv(l.getSeverity()      != null ? l.getSeverity().name()       : "")).append(',');
            csv.append(csv(l.getOutcome()       != null ? l.getOutcome().name()        : "")).append(',');
            csv.append(csv(l.getResourceType()  != null ? l.getResourceType().name()   : "")).append(',');
            csv.append(csv(l.getTemplateName())).append(',');
            csv.append(csv(l.getTemplateId())).append(',');
            csv.append(csv(l.getOrganizationId())).append(',');
            csv.append(csv(l.getPerformedByUserId())).append(',');
            csv.append(csv(l.getPerformedBy())).append(',');
            csv.append(csv(l.getPerformedByName())).append(',');
            csv.append(csv(l.getPerformedByRole())).append(',');
            csv.append(csv(l.getIpAddress())).append(',');
            csv.append(csv(l.getUserAgent())).append(',');
            csv.append(csv(l.getReason())).append(',');
            csv.append(csv(l.getFailureReason())).append(',');
            csv.append(csv(l.getIntegrityHash())).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Adds role-based scoping criteria to the provided list.
     *
     * <p>Visibility rules (per compliance spec):
     * <ul>
     *   <li>PLATFORM_ADMIN — all entries; optionally scoped to one org via {@code orgId}</li>
     *   <li>ORG_ADMIN      — all entries within their org
     *                        (performedByRole ∈ {ORG_ADMIN, ADMIN, USER})</li>
     *   <li>ADMIN          — ADMIN + USER entries within their org
     *                        (ORG_ADMIN actions are hidden from ADMIN)</li>
     *   <li>USER           — only their own entries (matched by performedByUserId)</li>
     * </ul>
     *
     * <p>Filtering uses the stored {@code performedByRole} field rather than looking up
     * current user emails, so historical entries are evaluated against the role that was
     * held <em>at the time of the action</em>.  This prevents role changes from
     * retroactively altering what is visible.  Entries written before {@code performedByRole}
     * was introduced fall back to the {@code performedBy} (email) field.
     */
    private void addRoleScope(AppUser caller, String orgId, List<Criteria> parts) {
        switch (caller.getRole()) {
            case PLATFORM_ADMIN -> {
                // Sees everything; optionally scoped to one org
                if (orgId != null && !orgId.isBlank())
                    parts.add(Criteria.where("organizationId").is(orgId));
            }
            case ORG_ADMIN -> {
                // All entries within own org — ORG_ADMIN, ADMIN, and USER actions all visible
                parts.add(Criteria.where("organizationId").is(caller.getOrganizationId()));
                // ORG_ADMIN does NOT see PLATFORM_ADMIN actions (those have no orgId)
            }
            case ADMIN -> {
                // Only ADMIN + USER role actions within own org; ORG_ADMIN actions hidden
                parts.add(Criteria.where("organizationId").is(caller.getOrganizationId()));
                // Filter by performedByRole — hide ORG_ADMIN entries
                // Legacy entries without performedByRole fall back to userId match
                Criteria byRole = Criteria.where("performedByRole")
                        .in(List.of("ADMIN", "USER"));
                Criteria legacyOwn = new Criteria().andOperator(
                        Criteria.where("performedByRole").exists(false),
                        Criteria.where("performedByUserId").is(caller.getId())
                );
                parts.add(new Criteria().orOperator(byRole, legacyOwn));
            }
            default -> {
                // USER — own entries only, matched by stable userId
                if (caller.getId() != null) {
                    parts.add(Criteria.where("performedByUserId").is(caller.getId()));
                } else {
                    // Fallback for legacy entries without userId stored
                    parts.add(Criteria.where("performedBy").is(caller.getEmail()));
                }
            }
        }
    }

    /** Merges a list of criteria into a single Criteria (and-combination). */
    private static Criteria toCriteria(List<Criteria> parts) {
        if (parts.isEmpty())     return new Criteria();
        if (parts.size() == 1)  return parts.get(0);
        return new Criteria().andOperator(parts.toArray(Criteria[]::new));
    }

    /** Combines an existing criteria with one more condition via $and. */
    private static Criteria and(Criteria base, Criteria extra) {
        return new Criteria().andOperator(base, extra);
    }

    /** RFC-4180-compliant CSV field quoting. */
    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}
