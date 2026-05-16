package com.braify.service;

import com.braify.model.AppUser;
import com.braify.model.AuditLog;
import com.braify.repository.AppUserRepository;
import com.braify.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
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
            AuditLog.Action.QUOTA_EXCEEDED
    );
    private static final Set<AuditLog.Action> WARNING_ACTIONS = Set.of(
            AuditLog.Action.DELETED,
            AuditLog.Action.CANCELLED,
            AuditLog.Action.PASSWORD_CHANGED,
            AuditLog.Action.TEMPLATE_SHARED,
            AuditLog.Action.TEMPLATE_UNSHARED
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

    // ── Core write ────────────────────────────────────────────────────────────

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
        String performer         = (performedBy != null) ? performedBy : "system";
        if (!"system".equals(performer)) {
            try {
                var opt = userRepository.findByEmail(performer);
                if (opt.isPresent()) {
                    var u = opt.get();
                    performedByUserId = u.getId();
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

        return auditLogRepository.save(entry);
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
           .append("OrganizationId,PerformedBy,PerformedByName,IpAddress,UserAgent,")
           .append("Reason,FailureReason,IntegrityHash\n");

        for (AuditLog l : logs) {
            csv.append(csv(l.getTimestamp() != null ? l.getTimestamp().format(fmt) : "")).append(',');
            csv.append(csv(l.getAction()        != null ? l.getAction().name()        : "")).append(',');
            csv.append(csv(l.getSeverity()      != null ? l.getSeverity().name()      : "")).append(',');
            csv.append(csv(l.getOutcome()       != null ? l.getOutcome().name()       : "")).append(',');
            csv.append(csv(l.getResourceType()  != null ? l.getResourceType().name()  : "")).append(',');
            csv.append(csv(l.getTemplateName())).append(',');
            csv.append(csv(l.getTemplateId())).append(',');
            csv.append(csv(l.getOrganizationId())).append(',');
            csv.append(csv(l.getPerformedBy())).append(',');
            csv.append(csv(l.getPerformedByName())).append(',');
            csv.append(csv(l.getIpAddress())).append(',');
            csv.append(csv(l.getUserAgent())).append(',');
            csv.append(csv(l.getReason())).append(',');
            csv.append(csv(l.getFailureReason())).append(',');
            csv.append(csv(l.getIntegrityHash())).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Adds role-based scoping criteria to the provided list. */
    private void addRoleScope(AppUser caller, String orgId, List<Criteria> parts) {
        switch (caller.getRole()) {
            case PLATFORM_ADMIN -> {
                if (orgId != null && !orgId.isBlank())
                    parts.add(Criteria.where("organizationId").is(orgId));
                // else no restriction — sees everything
            }
            case ORG_ADMIN -> {
                List<String> emails = performerEmails(caller.getOrganizationId(), null);
                parts.add(Criteria.where("performedBy").in(emails));
            }
            case ADMIN -> {
                List<String> emails = performerEmails(caller.getOrganizationId(),
                        List.of(AppUser.Role.ADMIN, AppUser.Role.USER));
                parts.add(Criteria.where("performedBy").in(emails));
            }
            default -> parts.add(Criteria.where("performedBy").is(caller.getEmail()));
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

    /** Returns performer emails for all active users in an org, optionally filtered by role. */
    private List<String> performerEmails(String orgId, List<AppUser.Role> roles) {
        List<AppUser> users = (roles != null)
                ? userRepository.findByOrganizationIdAndActiveTrueAndRoleIn(orgId, roles)
                : userRepository.findByOrganizationIdAndActiveTrue(orgId);
        return users.stream().map(AppUser::getEmail).toList();
    }

    /** RFC-4180-compliant CSV field quoting. */
    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}
