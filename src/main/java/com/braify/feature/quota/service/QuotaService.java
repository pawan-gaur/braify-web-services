package com.braify.feature.quota.service;

import com.braify.feature.organization.model.Organization;
import com.braify.feature.quota.dto.OrgUsageSummary;
import com.braify.feature.quota.dto.QuotaConfigRequest;
import com.braify.feature.quota.dto.QuotaConfigResponse;
import com.braify.feature.quota.dto.UsageResponse;
import com.braify.feature.quota.exception.QuotaExceededException;
import com.braify.feature.quota.model.OrgQuotaConfig;
import com.braify.feature.quota.model.OrgUsage;
import com.braify.feature.user.model.AppUser;
import com.braify.shared.SubscriptionPlan;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.quota.repository.OrgQuotaConfigRepository;
import com.braify.feature.quota.repository.OrgUsageRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final OrgQuotaConfigRepository quotaConfigRepo;
    private final OrgUsageRepository       usageRepo;
    private final AppUserRepository        userRepo;
    private final OrganizationRepository   orgRepo;
    private final MongoTemplate            mongoTemplate;
    private final AuditorAware<String>     auditorAware;

    /** Current actor for audit fields on upserted usage docs (userId, "api-key:…", or "system"). */
    private String currentActor() {
        return auditorAware.getCurrentAuditor().orElse("system");
    }

    // ── Plan defaults ─────────────────────────────────────────────────────────

    /**
     * Called when a subscription plan is assigned.
     * Upserts the OrgQuotaConfig with the plan's default limits.
     */
    public void resetToDefaults(String orgId, SubscriptionPlan plan) {
        OrgQuotaConfig config = quotaConfigRepo.findByOrganizationId(orgId)
                .orElseGet(() -> OrgQuotaConfig.builder().organizationId(orgId).build());
        config.setMaxUsers(plan.defaultMaxUsers);
        config.setMaxDocsPerMonth(plan.defaultMaxDocsPerMonth);
        config.setMaxStorageMb(plan.defaultMaxStorageMb);
        config.setMaxApiCallsPerMonth(plan.defaultMaxApiCallsPerMonth);
        config.setUpdatedBy("plan-assignment");
        quotaConfigRepo.save(config);
        log.info("Reset quota config for org '{}' to plan '{}'", orgId, plan);
    }

    /**
     * Platform Admin override — sets custom limits independently of the plan.
     */
    public OrgQuotaConfig overrideQuota(String orgId, QuotaConfigRequest req, String performedBy) {
        OrgQuotaConfig config = quotaConfigRepo.findByOrganizationId(orgId)
                .orElseGet(() -> OrgQuotaConfig.builder().organizationId(orgId).build());
        config.setMaxUsers(req.getMaxUsers());
        config.setMaxDocsPerMonth(req.getMaxDocsPerMonth());
        config.setMaxStorageMb(req.getMaxStorageMb());
        config.setMaxApiCallsPerMonth(req.getMaxApiCallsPerMonth());
        config.setUpdatedBy(performedBy);
        return quotaConfigRepo.save(config);
    }

    // ── Enforcement ──────────────────────────────────────────────────────────

    /**
     * Verifies the active user count is below the configured limit.
     * Call this BEFORE creating a new user.
     *
     * @throws QuotaExceededException if the user limit has been reached.
     */
    public void checkUserCount(String orgId) {
        if (orgId == null) return; // PLATFORM_ADMIN users have no orgId
        OrgQuotaConfig cfg = getConfig(orgId);
        if (cfg.getMaxUsers() == -1) return; // unlimited

        long current = userRepo.countByOrganizationIdAndActiveTrue(orgId);
        if (current >= cfg.getMaxUsers()) {
            throw new QuotaExceededException("Users", cfg.getMaxUsers(), current);
        }
    }

    /**
     * Atomically checks and increments the monthly document counter.
     * Call this BEFORE generating a PDF.
     *
     * @throws QuotaExceededException if the monthly document limit has been reached.
     */
    public void checkAndIncrementDocs(String orgId) {
        if (orgId == null) return;
        OrgQuotaConfig cfg = getConfig(orgId);
        if (cfg.getMaxDocsPerMonth() == -1) {
            incrementField(orgId, "docsGenerated");
            return;
        }

        OrgUsage current = getCurrentUsage(orgId);
        long total = current.getDocsGenerated() + current.getEsignSent();
        if (total >= cfg.getMaxDocsPerMonth()) {
            throw new QuotaExceededException("Monthly documents", cfg.getMaxDocsPerMonth(), total);
        }
        incrementField(orgId, "docsGenerated");
    }

    /**
     * Pre-flight check for bulk e-sign operations: verifies that the org has
     * enough remaining quota for {@code count} additional documents WITHOUT
     * incrementing the counter.  Call this at the start of a bulk request so
     * the operation can fail fast — before any documents are created — rather
     * than discovering a quota problem halfway through.
     *
     * <p>Note: this check is advisory (no lock is held between the check and
     * the per-row increments), so a small over-run is theoretically possible
     * under concurrent load.  The per-row {@link #checkAndIncrementEsign}
     * calls remain the authoritative enforcement point.</p>
     *
     * @param orgId org to check
     * @param count number of documents the caller intends to send
     * @throws QuotaExceededException if remaining capacity is less than {@code count}
     */
    public void checkEsignBulkCapacity(String orgId, int count) {
        if (orgId == null || count <= 0) return;
        OrgQuotaConfig cfg = getConfig(orgId);
        if (cfg.getMaxDocsPerMonth() == -1) return; // unlimited
        OrgUsage current = getCurrentUsage(orgId);
        long used      = current.getDocsGenerated() + current.getEsignSent();
        long remaining = cfg.getMaxDocsPerMonth() - used;
        if (remaining < count) {
            throw new QuotaExceededException(
                    "Monthly documents (bulk send — need " + count + ", have " + remaining + " remaining)",
                    cfg.getMaxDocsPerMonth(), used);
        }
    }

    /**
     * Atomically checks and increments the monthly e-sign send counter.
     * Call this BEFORE sending an e-sign document.
     *
     * @throws QuotaExceededException if the monthly document limit has been reached.
     */
    public void checkAndIncrementEsign(String orgId) {
        if (orgId == null) return;
        OrgQuotaConfig cfg = getConfig(orgId);
        if (cfg.getMaxDocsPerMonth() == -1) {
            incrementField(orgId, "esignSent");
            return;
        }

        OrgUsage current = getCurrentUsage(orgId);
        long total = current.getDocsGenerated() + current.getEsignSent();
        if (total >= cfg.getMaxDocsPerMonth()) {
            throw new QuotaExceededException("Monthly documents", cfg.getMaxDocsPerMonth(), total);
        }
        incrementField(orgId, "esignSent");
    }

    /** Fire-and-forget API call counter increment — no enforcement. */
    public void incrementApiCall(String orgId) {
        if (orgId == null) return;
        incrementField(orgId, "apiCalls");
    }

    /**
     * Atomically adds {@code additionalMb} to the current month's storage counter.
     * No enforcement — storage limit is checked before upload in {@code FileService}.
     *
     * @param orgId        the organisation to update
     * @param additionalMb megabytes to add (may be fractional)
     */
    public void incrementStorage(String orgId, double additionalMb) {
        if (orgId == null || additionalMb <= 0) return;
        // Round up to the nearest whole MB so we stay compatible with OrgUsage.storageMb (long)
        long wholeMb = Math.max(1L, Math.round(additionalMb));
        LocalDate now = LocalDate.now();
        Query  q = Query.query(Criteria.where("organizationId").is(orgId)
                .and("year").is(now.getYear())
                .and("month").is(now.getMonthValue()));
        Update u = new Update()
                .setOnInsert("organizationId", orgId)
                .setOnInsert("year",  now.getYear())
                .setOnInsert("month", now.getMonthValue())
                .setOnInsert("createdBy", currentActor())
                .inc("storageMb", wholeMb);
        mongoTemplate.findAndModify(q, u,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                OrgUsage.class);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public OrgQuotaConfig getConfig(String orgId) {
        return quotaConfigRepo.findByOrganizationId(orgId)
                .orElseGet(() -> {
                    // Lazily initialise with FREE plan defaults if not yet configured
                    OrgQuotaConfig cfg = OrgQuotaConfig.builder().organizationId(orgId).build();
                    return quotaConfigRepo.save(cfg);
                });
    }

    public OrgUsage getCurrentUsage(String orgId) {
        LocalDate now = LocalDate.now();
        return usageRepo.findByOrganizationIdAndYearAndMonth(orgId, now.getYear(), now.getMonthValue())
                .orElseGet(() -> OrgUsage.builder()
                        .organizationId(orgId)
                        .year(now.getYear())
                        .month(now.getMonthValue())
                        .build());
    }

    public QuotaConfigResponse getConfigResponse(String orgId) {
        OrgQuotaConfig cfg     = getConfig(orgId);
        OrgUsage       usage   = getCurrentUsage(orgId);
        Organization   org     = orgRepo.findById(orgId).orElse(null);
        long           users   = userRepo.countByOrganizationIdAndActiveTrue(orgId);

        return QuotaConfigResponse.builder()
                .organizationId(orgId)
                .organizationName(org != null ? org.getName() : orgId)
                .subscriptionPlan(org != null && org.getSubscriptionPlan() != null
                        ? org.getSubscriptionPlan() : SubscriptionPlan.FREE)
                .maxUsers(cfg.getMaxUsers())
                .maxDocsPerMonth(cfg.getMaxDocsPerMonth())
                .maxStorageMb(cfg.getMaxStorageMb())
                .maxApiCallsPerMonth(cfg.getMaxApiCallsPerMonth())
                .currentUsers(users)
                .currentDocsThisMonth(usage.getDocsGenerated())
                .currentEsignThisMonth(usage.getEsignSent())
                .currentStorageMb(usage.getStorageMb())
                .currentApiCallsThisMonth(usage.getApiCalls())
                .currentEmailsThisMonth(emailsThisMonth(orgId))
                .usersPercent(pct(users, cfg.getMaxUsers()))
                .docsPercent(pct(usage.getDocsGenerated() + usage.getEsignSent(), cfg.getMaxDocsPerMonth()))
                .storagePercent(pct(usage.getStorageMb(), cfg.getMaxStorageMb()))
                .apiPercent(pct(usage.getApiCalls(), cfg.getMaxApiCallsPerMonth()))
                .updatedAt(cfg.getUpdatedAt())
                .updatedBy(cfg.getUpdatedBy())
                .build();
    }

    public List<UsageResponse> getUsageHistory(String orgId, int months) {
        return usageRepo.findByOrganizationIdOrderByYearDescMonthDesc(
                        orgId, PageRequest.of(0, months))
                .stream()
                .map(u -> UsageResponse.builder()
                        .organizationId(u.getOrganizationId())
                        .year(u.getYear())
                        .month(u.getMonth())
                        .monthLabel(monthLabel(u.getYear(), u.getMonth()))
                        .docsGenerated(u.getDocsGenerated())
                        .esignSent(u.getEsignSent())
                        .storageMb(u.getStorageMb())
                        .apiCalls(u.getApiCalls())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Platform-wide org usage (PLATFORM_ADMIN) ──────────────────────────────

    /**
     * Per-organisation usage snapshot across all non-deleted organisations,
     * for the PLATFORM_ADMIN org-wide usage view.
     */
    public List<OrgUsageSummary> getAllOrgUsage() {
        Map<String, Long> emailsByOrg = emailsThisMonthByOrg();
        List<OrgUsageSummary> out = new ArrayList<>();
        for (Organization org : orgRepo.findByDeletedFalseOrderByNameAsc()) {
            String orgId = org.getId();
            // Read-only: don't lazily persist a config doc just to render the table.
            OrgQuotaConfig cfg = quotaConfigRepo.findByOrganizationId(orgId)
                    .orElseGet(() -> OrgQuotaConfig.builder().organizationId(orgId).build());
            OrgUsage usage = getCurrentUsage(orgId);
            long users = userRepo.countByOrganizationIdAndActiveTrue(orgId);

            out.add(OrgUsageSummary.builder()
                    .organizationId(orgId)
                    .organizationName(org.getName())
                    .plan(org.getSubscriptionPlan() != null ? org.getSubscriptionPlan() : SubscriptionPlan.FREE)
                    .active(org.isActive())
                    .users(users)
                    .docsThisMonth(usage.getDocsGenerated() + usage.getEsignSent())
                    .storageMb(usage.getStorageMb())
                    .apiCallsThisMonth(usage.getApiCalls())
                    .emailsThisMonth(emailsByOrg.getOrDefault(orgId, 0L))
                    .maxUsers(cfg.getMaxUsers())
                    .maxDocsPerMonth(cfg.getMaxDocsPerMonth())
                    .maxStorageMb(cfg.getMaxStorageMb())
                    .maxApiCallsPerMonth(cfg.getMaxApiCallsPerMonth())
                    .build());
        }
        return out;
    }

    // ── Email usage (derived from bulk_email_jobs) ────────────────────────────

    private static LocalDateTime monthStart() {
        return LocalDate.now().withDayOfMonth(1).atStartOfDay();
    }

    /** Bulk emails sent this month for one org (sum of sentCount). */
    public long emailsThisMonth(String orgId) {
        if (orgId == null) return 0L;
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("orgId").is(orgId).and("createdAt").gte(monthStart())),
                Aggregation.group().sum("sentCount").as("total"));
        AggregationResults<org.bson.Document> res =
                mongoTemplate.aggregate(agg, "bulk_email_jobs", org.bson.Document.class);
        org.bson.Document d = res.getUniqueMappedResult();
        return d != null && d.get("total") instanceof Number n ? n.longValue() : 0L;
    }

    /** Bulk emails sent this month grouped by org → {orgId: total}. */
    private Map<String, Long> emailsThisMonthByOrg() {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("createdAt").gte(monthStart())),
                Aggregation.group("orgId").sum("sentCount").as("total"));
        AggregationResults<org.bson.Document> res =
                mongoTemplate.aggregate(agg, "bulk_email_jobs", org.bson.Document.class);
        Map<String, Long> map = new HashMap<>();
        for (org.bson.Document d : res.getMappedResults()) {
            Object id = d.get("_id");
            if (id != null) {
                Object total = d.get("total");
                map.put(id.toString(), total instanceof Number n ? n.longValue() : 0L);
            }
        }
        return map;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Atomically upserts the current month's usage record and increments a field. */
    private void incrementField(String orgId, String field) {
        LocalDate now = LocalDate.now();
        Query  q = Query.query(Criteria.where("organizationId").is(orgId)
                .and("year").is(now.getYear())
                .and("month").is(now.getMonthValue()));
        Update u = new Update()
                .setOnInsert("organizationId", orgId)
                .setOnInsert("year",  now.getYear())
                .setOnInsert("month", now.getMonthValue())
                .setOnInsert("createdBy", currentActor())
                .inc(field, 1);
        mongoTemplate.findAndModify(q, u,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                OrgUsage.class);
    }

    /** Returns a 0–100 percentage, or -1 when the limit is unlimited (-1). */
    private static int pct(long current, long limit) {
        if (limit == -1) return -1;
        if (limit == 0)  return 100;
        return (int) Math.min(100, (current * 100) / limit);
    }

    private static String monthLabel(int year, int month) {
        return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                + " '" + String.format("%02d", year % 100);
    }
}
