package com.braify.feature.pdfbulk.service;

import com.braify.feature.billing.service.PlanAccessService;
import com.braify.feature.fileupload.service.FileService;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.pdfbulk.model.PdfBulkJob;
import com.braify.feature.pdfbulk.repository.PdfBulkJobRepository;
import com.braify.feature.quota.service.QuotaService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Creates and reads bulk PDF jobs; the work itself runs in {@link PdfBulkProcessor}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfBulkService {

    public static final int MAX_ROWS = 1000;
    private static final int MAX_ACTIVE_JOBS_PER_ORG = 2;

    private final PdfBulkJobRepository jobRepo;
    private final TemplateRepository   templateRepo;
    private final PdfBulkProcessor     processor;
    private final PlanAccessService    planAccessService;
    private final QuotaService         quotaService;
    private final FileService          fileService;
    private final MongoTemplate        mongoTemplate;

    public record CreateRequest(String templateId, List<Map<String, Object>> rows, String filenamePattern, String label) {}
    public record Download(String url, Path tempFile, String filename) {}

    public PdfBulkJob create(CreateRequest req, UserDetailsImpl principal) {
        String orgId = requireOrg(principal);
        planAccessService.requireBulk(orgId);

        List<Map<String, Object>> rows = req.rows() == null ? List.of() : req.rows();
        if (rows.isEmpty()) throw new IllegalArgumentException("The spreadsheet has no data rows.");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("Up to " + MAX_ROWS + " rows per job — split the spreadsheet.");
        if (req.templateId() == null || req.templateId().isBlank()) throw new IllegalArgumentException("Choose a PDF template.");
        Template template = templateRepo.findByIdAndOrganizationIdAndDeletedFalse(req.templateId(), orgId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + req.templateId()));

        long active = mongoTemplate.count(new Query(Criteria.where("orgId").is(orgId)
                .and("status").in(PdfBulkJob.Status.QUEUED, PdfBulkJob.Status.RUNNING)), PdfBulkJob.class);
        if (active >= MAX_ACTIVE_JOBS_PER_ORG) {
            throw new IllegalStateException("Your organisation already has " + active + " bulk PDF jobs running — wait for one to finish.");
        }
        // Fail fast when the month's remaining documents can't cover the whole sheet
        // (each PDF still counts individually while the job runs).
        quotaService.checkEsignBulkCapacity(orgId, rows.size());

        String label = trim(req.label(), 120);
        String base = (label != null && !label.isBlank() ? label : template.getName())
                .replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        PdfBulkJob job = jobRepo.save(PdfBulkJob.builder()
                .orgId(orgId)
                .createdBy(principal.getId())
                .createdByEmail(principal.getUsername())
                .templateId(template.getId())
                .templateName(template.getName())
                .label(label)
                .filenamePattern(trim(req.filenamePattern(), 200))
                .total(rows.size())
                .zipFilename((base.isEmpty() ? "bulk-pdf" : base) + " "
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")) + ".zip")
                .status(PdfBulkJob.Status.QUEUED)
                .createdAt(LocalDateTime.now())
                .build());
        try {
            processor.processAsync(job.getId(), List.copyOf(rows));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            job.setStatus(PdfBulkJob.Status.FAILED);
            job.setError("The server is busy — please try again in a few minutes.");
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);
            throw new IllegalStateException(job.getError());
        }
        return job;
    }

    /** Org / Platform admins see the org's jobs; others see their own. */
    public Page<PdfBulkJob> list(UserDetailsImpl principal, int page, int size) {
        String orgId = requireOrg(principal);
        var pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        return seesAll(principal)
                ? jobRepo.findByOrgIdOrderByCreatedAtDesc(orgId, pr)
                : jobRepo.findByOrgIdAndCreatedByOrderByCreatedAtDesc(orgId, principal.getId(), pr);
    }

    public PdfBulkJob get(String id, UserDetailsImpl principal) {
        PdfBulkJob job = jobRepo.findByIdAndOrgId(id, requireOrg(principal))
                .orElseThrow(() -> new IllegalArgumentException("Bulk PDF job not found: " + id));
        if (!seesAll(principal) && !principal.getId().equals(job.getCreatedBy())) {
            throw new AccessDeniedException("Access denied to this job.");
        }
        return job;
    }

    public Download download(String id, UserDetailsImpl principal) {
        PdfBulkJob job = get(id, principal);
        if (job.getFileId() != null) {
            var d = fileService.download(job.getOrgId(), job.getFileId(), principal.getUsername());
            return new Download(d.getDownloadUrl(), null, job.getZipFilename());
        }
        if (job.getTempZipPath() != null && Files.exists(Path.of(job.getTempZipPath()))) {
            return new Download(null, Path.of(job.getTempZipPath()), job.getZipFilename());
        }
        throw new IllegalStateException(job.isExpired()
                ? "This download has expired — run the job again."
                : "No ZIP is available for this job.");
    }

    private static boolean seesAll(UserDetailsImpl principal) {
        AppUser.Role r = principal.getAppUser().getRole();
        return r == AppUser.Role.ORG_ADMIN || r == AppUser.Role.PLATFORM_ADMIN;
    }

    private static String requireOrg(UserDetailsImpl principal) {
        if (principal.getOrgId() == null) throw new IllegalArgumentException("Bulk PDF jobs belong to an organisation.");
        return principal.getOrgId();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
