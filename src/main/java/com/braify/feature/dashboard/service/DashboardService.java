package com.braify.feature.dashboard.service;

import com.braify.feature.dashboard.dto.DashboardStats;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.audit.repository.AuditLogRepository;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import com.braify.feature.onboarding.model.OnboardingRequest;
import com.braify.feature.onboarding.repository.OnboardingRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OrganizationRepository      orgRepo;
    private final AppUserRepository           userRepo;
    private final TemplateRepository          templateRepo;
    private final EmailTemplateRepository     emailTemplateRepo;
    private final AuditLogRepository          auditLogRepo;
    private final ESignDocumentRepository     esignRepo;
    private final OnboardingRequestRepository onboardingRepo;

    private static final int MONTHS = 6;
    private static final List<ESignDocument.Status> PENDING_STATUSES =
            List.of(ESignDocument.Status.PENDING, ESignDocument.Status.IN_REVIEW);

    public DashboardStats stats(AppUser caller) {
        boolean isPlatformAdmin = caller.getRole() == AppUser.Role.PLATFORM_ADMIN;
        String  orgId           = caller.getOrganizationId();

        // Fetch all non-deleted orgs once — shared by multiple PA computations.
        // Using the existing findByDeletedFalseOrderByNameAsc() avoids issues with
        // primitive boolean fields that may be absent in older MongoDB documents.
        List<Organization> allOrgs = isPlatformAdmin
                ? orgRepo.findByDeletedFalseOrderByNameAsc()
                : List.of();

        long activeOrgCount   = allOrgs.stream().filter(Organization::isActive).count();
        long inactiveOrgCount = allOrgs.stream().filter(o -> !o.isActive()).count();

        return DashboardStats.builder()
                // ── KPIs ──────────────────────────────────────────────────────
                .totalOrganizations (isPlatformAdmin ? activeOrgCount : 1L)
                .totalUsers         (kpiUsers(isPlatformAdmin, orgId))
                .totalPdfTemplates  (kpiPdf(isPlatformAdmin, orgId))
                .totalEmailTemplates(kpiEmail(isPlatformAdmin, orgId))
                .pendingInvites     (kpiPendingInvites(isPlatformAdmin, orgId))

                // ── E-Sign analytics ───────────────────────────────────────────
                .esignTotal         (esignTotal(isPlatformAdmin, orgId))
                .esignDraft         (esignByStatus(isPlatformAdmin, orgId, ESignDocument.Status.DRAFT))
                .esignPending       (esignPending(isPlatformAdmin, orgId))
                .esignCompleted     (esignByStatus(isPlatformAdmin, orgId, ESignDocument.Status.COMPLETED))
                .esignOverdue       (esignOverdue(isPlatformAdmin, orgId))
                .esignCancelled     (esignByStatus(isPlatformAdmin, orgId, ESignDocument.Status.CANCELLED))
                .esignExpired       (esignByStatus(isPlatformAdmin, orgId, ESignDocument.Status.EXPIRED))
                .esignAvgSigningHours(esignAvgHours(isPlatformAdmin, orgId))
                .esignDeclineRate   (esignDeclineRate(isPlatformAdmin, orgId))
                .esignGrowth        (esignMonthlyGrowth(isPlatformAdmin, orgId))

                // ── Monthly trends ────────────────────────────────────────────
                .pdfGrowth  (pdfMonthlyGrowth(isPlatformAdmin, orgId))
                .emailGrowth(emailMonthlyGrowth(isPlatformAdmin, orgId))
                .userGrowth (userMonthlyGrowth(isPlatformAdmin, orgId))

                // ── Activity ──────────────────────────────────────────────────
                .recentActivity(auditLogRepo.findTop10ByOrderByTimestampDesc())
                .topUsers      (topUsers(isPlatformAdmin, orgId))

                // ── Platform Admin extras ─────────────────────────────────────
                .orgBreakdown          (isPlatformAdmin ? orgBreakdown(allOrgs)   : List.of())
                .activeOrganizations   (activeOrgCount)
                .inactiveOrganizations (inactiveOrgCount)
                .pendingOnboarding     (isPlatformAdmin ? onboardingRepo.countByStatus(
                        OnboardingRequest.Status.PENDING) : 0)
                .featureDistribution   (isPlatformAdmin ? featureDistribution(allOrgs) : Map.of())
                .tenantGrowth          (isPlatformAdmin ? tenantMonthlyGrowth(allOrgs) : List.of())

                .build();
    }

    // ── KPI helpers ───────────────────────────────────────────────────────────

    private long kpiUsers(boolean admin, String orgId) {
        return admin ? userRepo.countByActiveTrue()
                     : userRepo.countByOrganizationIdAndActiveTrue(orgId);
    }

    private long kpiPdf(boolean admin, String orgId) {
        return admin ? templateRepo.countByDeletedFalse()
                     : templateRepo.countByOrganizationIdAndDeletedFalse(orgId);
    }

    private long kpiEmail(boolean admin, String orgId) {
        return admin ? emailTemplateRepo.countByDeletedFalse()
                     : emailTemplateRepo.countByOrganizationIdAndDeletedFalse(orgId);
    }

    private long kpiPendingInvites(boolean admin, String orgId) {
        return admin ? userRepo.countByActiveTrueAndMustChangePasswordTrue()
                     : userRepo.countByOrganizationIdAndActiveTrueAndMustChangePasswordTrue(orgId);
    }

    // ── E-Sign helpers ────────────────────────────────────────────────────────

    private long esignTotal(boolean admin, String orgId) {
        return admin ? esignRepo.count() : esignRepo.countByOrgId(orgId);
    }

    private long esignByStatus(boolean admin, String orgId, ESignDocument.Status status) {
        return admin ? esignRepo.countByStatus(status)
                     : esignRepo.countByOrgIdAndStatus(orgId, status);
    }

    private long esignPending(boolean admin, String orgId) {
        return admin ? esignRepo.countByStatusIn(PENDING_STATUSES)
                     : esignRepo.countByOrgIdAndStatusIn(orgId, PENDING_STATUSES);
    }

    private long esignOverdue(boolean admin, String orgId) {
        LocalDateTime now = LocalDateTime.now();
        List<ESignDocument> pending = admin
                ? esignRepo.findByStatusIn(PENDING_STATUSES)
                : esignRepo.findByOrgIdAndStatusIn(orgId, PENDING_STATUSES);
        return pending.stream()
                .filter(d -> d.getTokenExpiresAt() != null && d.getTokenExpiresAt().isBefore(now))
                .count();
    }

    private Double esignAvgHours(boolean admin, String orgId) {
        List<ESignDocument> completed = admin
                ? esignRepo.findByStatus(ESignDocument.Status.COMPLETED)
                : esignRepo.findByOrgIdAndStatus(orgId, ESignDocument.Status.COMPLETED);

        return completed.stream()
                .filter(d -> d.getSentAt() != null && d.getCompletedAt() != null)
                .mapToLong(d -> Duration.between(d.getSentAt(), d.getCompletedAt()).toMinutes())
                .average()
                .stream()
                .map(mins -> Math.round(mins / 6.0) / 10.0)   // hours to 1 d.p.
                .boxed()
                .findFirst()
                .orElse(null);
    }

    private Double esignDeclineRate(boolean admin, String orgId) {
        long sent = admin ? esignRepo.countBySentAtIsNotNull()
                          : esignRepo.countByOrgIdAndSentAtIsNotNull(orgId);
        if (sent == 0) return null;
        long cancelled = admin ? esignRepo.countByStatus(ESignDocument.Status.CANCELLED)
                               : esignRepo.countByOrgIdAndStatus(orgId, ESignDocument.Status.CANCELLED);
        return Math.round((cancelled * 1000.0) / sent) / 10.0;  // one decimal place
    }

    // ── Monthly growth helpers ────────────────────────────────────────────────

    private List<DashboardStats.MonthStat> monthlyStats(java.util.function.BiFunction<LocalDateTime, LocalDateTime, Long> counter) {
        List<DashboardStats.MonthStat> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = MONTHS - 1; i >= 0; i--) {
            LocalDate     month = today.minusMonths(i);
            LocalDateTime from  = month.withDayOfMonth(1).atStartOfDay();
            LocalDateTime to    = month.withDayOfMonth(month.lengthOfMonth()).atTime(23, 59, 59);
            String        label = month.getMonth().getDisplayName(TextStyle.SHORT, java.util.Locale.ENGLISH)
                                  + " '" + String.format("%02d", month.getYear() % 100);
            result.add(new DashboardStats.MonthStat(label, counter.apply(from, to)));
        }
        return result;
    }

    private List<DashboardStats.MonthStat> pdfMonthlyGrowth(boolean admin, String orgId) {
        return monthlyStats((from, to) -> admin
                ? templateRepo.countByDeletedFalseAndCreatedAtBetween(from, to)
                : templateRepo.countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(orgId, from, to));
    }

    private List<DashboardStats.MonthStat> emailMonthlyGrowth(boolean admin, String orgId) {
        return monthlyStats((from, to) -> admin
                ? emailTemplateRepo.countByDeletedFalseAndCreatedAtBetween(from, to)
                : emailTemplateRepo.countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(orgId, from, to));
    }

    private List<DashboardStats.MonthStat> userMonthlyGrowth(boolean admin, String orgId) {
        return monthlyStats((from, to) -> admin
                ? userRepo.countByActiveTrueAndCreatedAtBetween(from, to)
                : userRepo.countByOrganizationIdAndActiveTrueAndCreatedAtBetween(orgId, from, to));
    }

    private List<DashboardStats.MonthStat> esignMonthlyGrowth(boolean admin, String orgId) {
        return monthlyStats((from, to) -> admin
                ? esignRepo.countBySentAtBetween(from, to)
                : esignRepo.countByOrgIdAndSentAtBetween(orgId, from, to));
    }

    private List<DashboardStats.MonthStat> tenantMonthlyGrowth(List<Organization> allOrgs) {
        List<DashboardStats.MonthStat> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = MONTHS - 1; i >= 0; i--) {
            LocalDate     month = today.minusMonths(i);
            LocalDateTime from  = month.withDayOfMonth(1).atStartOfDay();
            LocalDateTime to    = month.withDayOfMonth(month.lengthOfMonth()).atTime(23, 59, 59);
            String        label = month.getMonth().getDisplayName(TextStyle.SHORT, java.util.Locale.ENGLISH)
                                  + " '" + String.format("%02d", month.getYear() % 100);
            long count = allOrgs.stream()
                    .filter(o -> o.getCreatedAt() != null
                              && !o.getCreatedAt().isBefore(from)
                              && !o.getCreatedAt().isAfter(to))
                    .count();
            result.add(new DashboardStats.MonthStat(label, count));
        }
        return result;
    }

    // ── Top users ─────────────────────────────────────────────────────────────

    private List<DashboardStats.TopUser> topUsers(boolean admin, String orgId) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);

        List<AuditLog> logs = admin
                ? auditLogRepo.findByTimestampAfter(since)
                : auditLogRepo.findByOrganizationIdAndTimestampAfter(orgId, since);

        // Group by performedBy, count occurrences
        Map<String, Long> counts = logs.stream()
                .filter(l -> l.getPerformedBy() != null && !l.getPerformedBy().isBlank())
                .collect(Collectors.groupingBy(AuditLog::getPerformedBy, Collectors.counting()));

        // Build top-5 list enriched with display name from user repo
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    String email = e.getKey();
                    String name  = userRepo.findByEmail(email)
                            .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                            .orElse(email);
                    return DashboardStats.TopUser.builder()
                            .email(email)
                            .name(name.isBlank() ? email : name)
                            .activityCount(e.getValue())
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ── Platform Admin: org breakdown ─────────────────────────────────────────

    private List<DashboardStats.OrgSummary> orgBreakdown(List<Organization> allOrgs) {
        return allOrgs.stream()
                .map(org -> DashboardStats.OrgSummary.builder()
                        .organizationId(org.getId())
                        .organizationName(org.getName())
                        .features(org.getFeatures())
                        .users(userRepo.countByOrganizationIdAndActiveTrue(org.getId()))
                        .pdfTemplates(templateRepo.countByOrganizationIdAndDeletedFalse(org.getId()))
                        .emailTemplates(emailTemplateRepo.countByOrganizationIdAndDeletedFalse(org.getId()))
                        .esignDocuments(esignRepo.countByOrgId(org.getId()))
                        .build())
                .collect(Collectors.toList());
    }

    // ── Platform Admin: feature distribution ──────────────────────────────────

    private Map<String, Long> featureDistribution(List<Organization> allOrgs) {
        Map<String, Long> dist = new LinkedHashMap<>();
        List<Organization> active = allOrgs.stream()
                .filter(Organization::isActive)
                .collect(Collectors.toList());
        for (Organization org : active) {
            for (String feat : org.getFeatures()) {
                dist.merge(feat, 1L, Long::sum);
            }
        }
        return dist;
    }
}
