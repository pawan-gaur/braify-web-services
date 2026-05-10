package com.braify.service;

import com.braify.dto.DashboardStats;
import com.braify.model.AppUser;
import com.braify.model.Organization;
import com.braify.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OrganizationRepository  orgRepo;
    private final AppUserRepository       userRepo;
    private final TemplateRepository      templateRepo;
    private final EmailTemplateRepository emailTemplateRepo;
    private final AuditLogRepository      auditLogRepo;

    private static final int MONTHS = 6;

    public DashboardStats stats(AppUser caller) {
        boolean isAdmin = caller.getRole() == AppUser.Role.PLATFORM_ADMIN;
        String  orgId   = caller.getOrganizationId();

        // ── KPI counts ────────────────────────────────────────────────────
        long totalOrgs  = isAdmin
                ? orgRepo.findByDeletedFalseOrderByNameAsc().size()
                : 1L;

        long totalUsers = isAdmin
                ? userRepo.countByActiveTrue()
                : userRepo.countByOrganizationIdAndActiveTrue(orgId);

        long totalPdf = isAdmin
                ? templateRepo.countByDeletedFalse()
                : templateRepo.countByOrganizationIdAndDeletedFalse(orgId);

        long totalEmail = isAdmin
                ? emailTemplateRepo.countByDeletedFalse()
                : emailTemplateRepo.countByOrganizationIdAndDeletedFalse(orgId);

        long pending = isAdmin
                ? userRepo.countByActiveTrueAndMustChangePasswordTrue()
                : userRepo.countByOrganizationIdAndActiveTrueAndMustChangePasswordTrue(orgId);

        // ── Monthly growth (last MONTHS calendar months) ──────────────────
        List<DashboardStats.MonthStat> pdfGrowth   = new ArrayList<>();
        List<DashboardStats.MonthStat> emailGrowth = new ArrayList<>();
        List<DashboardStats.MonthStat> userGrowth  = new ArrayList<>();

        LocalDate today = LocalDate.now();
        for (int i = MONTHS - 1; i >= 0; i--) {
            LocalDate      month = today.minusMonths(i);
            LocalDateTime  from  = month.withDayOfMonth(1).atStartOfDay();
            LocalDateTime  to    = month.withDayOfMonth(month.lengthOfMonth()).atTime(23, 59, 59);
            String         label = month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                                   + " '" + String.format("%02d", month.getYear() % 100);

            pdfGrowth.add(new DashboardStats.MonthStat(label,
                    isAdmin
                        ? templateRepo.countByDeletedFalseAndCreatedAtBetween(from, to)
                        : templateRepo.countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(orgId, from, to)));

            emailGrowth.add(new DashboardStats.MonthStat(label,
                    isAdmin
                        ? emailTemplateRepo.countByDeletedFalseAndCreatedAtBetween(from, to)
                        : emailTemplateRepo.countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(orgId, from, to)));

            userGrowth.add(new DashboardStats.MonthStat(label,
                    isAdmin
                        ? userRepo.countByActiveTrueAndCreatedAtBetween(from, to)
                        : userRepo.countByOrganizationIdAndActiveTrueAndCreatedAtBetween(orgId, from, to)));
        }

        // ── Recent activity (last 10 events) ─────────────────────────────
        var recent = auditLogRepo.findTop10ByOrderByTimestampDesc();

        // ── Org breakdown (PLATFORM_ADMIN only) ───────────────────────────
        List<DashboardStats.OrgSummary> breakdown = new ArrayList<>();
        if (isAdmin) {
            for (Organization org : orgRepo.findByDeletedFalseOrderByNameAsc()) {
                breakdown.add(DashboardStats.OrgSummary.builder()
                        .organizationId(org.getId())
                        .organizationName(org.getName())
                        .users(userRepo.countByOrganizationIdAndActiveTrue(org.getId()))
                        .pdfTemplates(templateRepo.countByOrganizationIdAndDeletedFalse(org.getId()))
                        .emailTemplates(emailTemplateRepo.countByOrganizationIdAndDeletedFalse(org.getId()))
                        .build());
            }
        }

        return DashboardStats.builder()
                .totalOrganizations(totalOrgs)
                .totalUsers(totalUsers)
                .totalPdfTemplates(totalPdf)
                .totalEmailTemplates(totalEmail)
                .pendingInvites(pending)
                .pdfGrowth(pdfGrowth)
                .emailGrowth(emailGrowth)
                .userGrowth(userGrowth)
                .recentActivity(recent)
                .orgBreakdown(breakdown)
                .build();
    }
}
