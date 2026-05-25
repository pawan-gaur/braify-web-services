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
import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.repository.ESignAuditEventRepository;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import com.braify.feature.onboarding.model.OnboardingRequest;
import com.braify.feature.onboarding.repository.OnboardingRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OrganizationRepository      orgRepo;
    private final AppUserRepository           userRepo;
    private final TemplateRepository          templateRepo;
    private final EmailTemplateRepository     emailTemplateRepo;
    private final AuditLogRepository          auditLogRepo;
    private final ESignDocumentRepository     esignRepo;
    private final ESignAuditEventRepository   auditEventRepo;
    private final OnboardingRequestRepository onboardingRepo;

    private static final int MONTHS = 6;
    private static final List<ESignDocument.Status> PENDING_STATUSES =
            List.of(ESignDocument.Status.PENDING, ESignDocument.Status.IN_REVIEW);

    public DashboardStats stats(AppUser caller) {
        log.debug("Building dashboard stats for user='{}' role={}", caller.getEmail(), caller.getRole());
        boolean isPlatformAdmin = caller.getRole() == AppUser.Role.PLATFORM_ADMIN;
        String  orgId           = caller.getOrganizationId();

        // Build the performer-email scope once — reused by recentActivity + topUsers.
        // null means PLATFORM_ADMIN: no email filter (sees all activity globally).
        List<String> scopeEmails = buildScopeEmails(caller);

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
                .esignViewed        (esignViewed(isPlatformAdmin, orgId))
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

                // ── Activity (org-scoped — no cross-org data leakage) ────────
                .recentActivity(recentActivity(isPlatformAdmin, orgId))
                .topUsers      (topUsers(scopeEmails))

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

    private long esignViewed(boolean admin, String orgId) {
        if (admin) {
            return auditEventRepo.countByEvent(ESignAuditEvent.EventType.LINK_OPENED);
        }
        List<String> docIds = esignRepo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(ESignDocument::getId)
                .collect(Collectors.toList());
        if (docIds.isEmpty()) return 0L;
        return auditEventRepo.countByEventAndDocumentIdIn(ESignAuditEvent.EventType.LINK_OPENED, docIds);
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

    // ── Role-scoped activity helpers ──────────────────────────────────────────

    /**
     * Builds the set of performer emails visible to the caller based on their role.
     * <ul>
     *   <li>{@code null}   → PLATFORM_ADMIN: no filter, sees all activity globally</li>
     *   <li>email list     → ORG_ADMIN: all active users in their org</li>
     *   <li>email list     → ADMIN: only ADMIN + USER role members in their org</li>
     *   <li>single-element → USER: their own email only</li>
     * </ul>
     */
    private List<String> buildScopeEmails(AppUser caller) {
        return switch (caller.getRole()) {
            case PLATFORM_ADMIN -> null;  // null = unrestricted global view
            case ORG_ADMIN      -> userRepo.findByOrganizationIdAndActiveTrue(caller.getOrganizationId())
                                           .stream().map(AppUser::getEmail).toList();
            case ADMIN          -> userRepo.findByOrganizationIdAndActiveTrueAndRoleIn(
                                           caller.getOrganizationId(),
                                           List.of(AppUser.Role.ADMIN, AppUser.Role.USER))
                                           .stream().map(AppUser::getEmail).toList();
            case USER           -> List.of(caller.getEmail());
        };
    }

    /**
     * Returns the 10 most-recent audit log entries visible to the caller.
     * PLATFORM_ADMIN gets the unrestricted global feed; all other roles get
     * entries scoped to their own organization to prevent cross-org data leakage.
     */
    private List<AuditLog> recentActivity(boolean isPlatformAdmin, String orgId) {
        if (isPlatformAdmin) {
            return auditLogRepo.findTop10ByOrderByTimestampDesc();
        }
        return auditLogRepo.findTop10ByOrganizationIdOrderByTimestampDesc(orgId);
    }

    /**
     * Returns the top-5 most-active users within the last 30 days,
     * scoped to the caller's visible email set.
     * {@code null} scope → PLATFORM_ADMIN global view.
     */
    private List<DashboardStats.TopUser> topUsers(List<String> scopeEmails) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);

        List<AuditLog> logs;
        if (scopeEmails == null) {
            // PLATFORM_ADMIN: unrestricted global view
            logs = auditLogRepo.findByTimestampAfter(since);
        } else if (scopeEmails.isEmpty()) {
            return List.of();
        } else {
            // ORG_ADMIN / ADMIN / USER: scoped to their visible performer emails
            logs = auditLogRepo.findByPerformedByInAndTimestampAfter(scopeEmails, since);
        }

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
