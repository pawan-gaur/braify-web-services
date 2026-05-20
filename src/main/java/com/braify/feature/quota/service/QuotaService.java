package com.braify.feature.quota.service;

import com.braify.feature.organization.model.Organization;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
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
