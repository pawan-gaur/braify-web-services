package com.braify.feature.dashboard.service;

import com.braify.feature.dashboard.dto.AnalyticsResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the live analytics shown on the dashboard's <em>Analytics</em> tab.
 *
 * <p>Everything here is computed in MongoDB (aggregations / counts) over the
 * caller's <b>role-scoped</b> data within the requested time window — no sample
 * data, no loading of heavy documents into the JVM.
 *
 * <p><b>Role scoping</b> (mirrors {@link com.braify.feature.audit.model.AuditLog}
 * visibility): PLATFORM_ADMIN → whole platform; ORG_ADMIN → their org;
 * ADMIN → their own + their USERs' activity; USER → only their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final MongoTemplate     mongoTemplate;
    private final AppUserRepository userRepo;

    private static final String AUDIT  = "audit_logs";
    private static final String ESIGN  = "esign_documents";

    public AnalyticsResponse analytics(AppUser caller, int days) {
        int d = Math.max(1, Math.min(days, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(d);

        List<Criteria> auditScope = auditScope(caller);
        Criteria       esignScope = esignScope(caller);

        log.debug("Analytics for '{}' role={} window={}d", caller.getEmail(), caller.getRole(), d);

        return AnalyticsResponse.builder()
                .periodDays(d)
                .topTemplates(templateUsage(auditScope, since, false, 5))
                .leastTemplates(templateUsage(auditScope, since, true, 3))
                .activity(activity(auditScope, since, 8))
                .esignFunnel(funnel(esignScope, since))
                .build();
    }

    // ── Role scopes ───────────────────────────────────────────────────────────

    /** Criteria applied to {@code audit_logs} so a caller only sees permitted activity. */
    private List<Criteria> auditScope(AppUser caller) {
        List<Criteria> parts = new ArrayList<>();
        switch (caller.getRole()) {
            case PLATFORM_ADMIN -> { /* unrestricted */ }
            case ORG_ADMIN -> parts.add(Criteria.where("organizationId").is(caller.getOrganizationId()));
            case ADMIN -> {
                parts.add(Criteria.where("organizationId").is(caller.getOrganizationId()));
                // ADMIN sees ADMIN + USER activity; ORG_ADMIN actions are hidden.
                parts.add(Criteria.where("performedByRole").in("ADMIN", "USER"));
            }
            default -> {  // USER — only their own
                parts.add(caller.getId() != null
                        ? Criteria.where("performedByUserId").is(caller.getId())
                        : Criteria.where("performedBy").is(caller.getEmail()));
            }
        }
        return parts;
    }

    /** Criteria applied to {@code esign_documents}; {@code null} = unrestricted (PLATFORM_ADMIN). */
    private Criteria esignScope(AppUser caller) {
        return switch (caller.getRole()) {
            case PLATFORM_ADMIN -> null;
            case ORG_ADMIN      -> Criteria.where("orgId").is(caller.getOrganizationId());
            case ADMIN          -> {
                List<String> ids = userRepo.findByOrganizationIdAndActiveTrueAndRoleIn(
                                caller.getOrganizationId(),
                                List.of(AppUser.Role.ADMIN, AppUser.Role.USER))
                        .stream().map(AppUser::getId).toList();
                yield Criteria.where("createdBy").in(ids.isEmpty() ? List.of("__none__") : ids);
            }
            default             -> Criteria.where("createdBy")
                    .is(caller.getId() != null ? caller.getId() : "__none__");
        };
    }

    // ── Template usage (audit-derived) ─────────────────────────────────────────

    private List<AnalyticsResponse.UsageItem> templateUsage(List<Criteria> scope, LocalDateTime since,
                                                            boolean ascending, int limit) {
        List<Criteria> crit = new ArrayList<>(scope);
        crit.add(Criteria.where("timestamp").gte(since));
        crit.add(Criteria.where("resourceType").in("TEMPLATE", "EMAIL_TEMPLATE"));
        crit.add(Criteria.where("templateId").ne(null));

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(new Criteria().andOperator(crit.toArray(new Criteria[0]))),
                Aggregation.group("templateId")
                        .first("templateName").as("name")
                        .first("resourceType").as("type")
                        .count().as("count"),
                Aggregation.sort(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, "count"),
                Aggregation.limit(limit));

        AggregationResults<org.bson.Document> res =
                mongoTemplate.aggregate(agg, AUDIT, org.bson.Document.class);

        List<AnalyticsResponse.UsageItem> out = new ArrayList<>();
        for (org.bson.Document doc : res.getMappedResults()) {
            String id = str(doc.get("_id"));
            out.add(AnalyticsResponse.UsageItem.builder()
                    .id(id)
                    .name(orFallback(str(doc.get("name")), id))
                    .type(str(doc.get("type")))
                    .uses(num(doc.get("count")))
                    .build());
        }
        return out;
    }

    // ── Activity by performer (audit-derived) ──────────────────────────────────

    private List<AnalyticsResponse.ActivityItem> activity(List<Criteria> scope, LocalDateTime since, int limit) {
        List<Criteria> crit = new ArrayList<>(scope);
        crit.add(Criteria.where("timestamp").gte(since));
        crit.add(Criteria.where("performedBy").ne(null));

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(new Criteria().andOperator(crit.toArray(new Criteria[0]))),
                Aggregation.group("performedBy")
                        .first("performedByName").as("name")
                        .count().as("count"),
                Aggregation.sort(Sort.Direction.DESC, "count"),
                Aggregation.limit(limit));

        AggregationResults<org.bson.Document> res =
                mongoTemplate.aggregate(agg, AUDIT, org.bson.Document.class);

        List<AnalyticsResponse.ActivityItem> out = new ArrayList<>();
        for (org.bson.Document doc : res.getMappedResults()) {
            String email = str(doc.get("_id"));
            out.add(AnalyticsResponse.ActivityItem.builder()
                    .email(email)
                    .name(orFallback(str(doc.get("name")), email))
                    .activityCount(num(doc.get("count")))
                    .build());
        }
        return out;
    }

    // ── E-Sign conversion funnel (cohort of docs sent in the window) ───────────

    private AnalyticsResponse.Funnel funnel(Criteria scope, LocalDateTime since) {
        Criteria sentInWindow = Criteria.where("sentAt").gte(since);
        long sent   = countEsign(scope, sentInWindow);
        long viewed = countEsign(scope, sentInWindow, Criteria.where("viewedAt").ne(null));
        long signed = countEsign(scope, sentInWindow, Criteria.where("status").in("SIGNED", "COMPLETED"));
        return AnalyticsResponse.Funnel.builder().sent(sent).viewed(viewed).signed(signed).build();
    }

    private long countEsign(Criteria scope, Criteria... extra) {
        List<Criteria> crit = new ArrayList<>();
        if (scope != null) crit.add(scope);
        for (Criteria c : extra) crit.add(c);
        Query q = new Query(new Criteria().andOperator(crit.toArray(new Criteria[0])));
        return mongoTemplate.count(q, ESIGN);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    private static String orFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
