package com.braify.feature.bulkemail.service;

import com.braify.config.infra.email.CssInliner;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.bulkemail.dto.BulkEmailJobRequest;
import com.braify.feature.bulkemail.dto.BulkEmailJobResponse;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.repository.BulkEmailJobRepository;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkEmailService {

    private static final int MAX_ROWS = 500;

    private final BulkEmailJobRepository   jobRepo;
    private final EmailTemplateRepository  emailTemplateRepo;
    private final OrganizationRepository   orgRepo;
    private final TemplateRepository       pdfTemplateRepo;
    private final CssInliner               cssInliner;
    private final AuditLogService          auditLogService;

    /**
     * Injected as a separate bean so that the {@code @Async} proxy on
     * {@link BulkEmailProcessor#processJobAsync} is honoured.  A direct
     * {@code this.processJobAsync()} call would bypass Spring's proxy and
     * execute synchronously on the request thread.
     */
    private final BulkEmailProcessor bulkEmailProcessor;

    // ── Create & start job ───────────────────────────────────────────────────

    public BulkEmailJobResponse createJob(BulkEmailJobRequest req, UserDetailsImpl principal) {

        // Validate email template
        EmailTemplate emailTemplate = emailTemplateRepo
                .findByIdAndOrganizationIdAndDeletedFalse(req.getEmailTemplateId(), principal.getOrgId())
                .orElseThrow(() -> new IllegalArgumentException("Email template not found"));

        // Validate rows
        if (req.getRows() == null || req.getRows().isEmpty())
            throw new IllegalArgumentException("No rows provided");
        if (req.getRows().size() > MAX_ROWS)
            throw new IllegalArgumentException("Maximum " + MAX_ROWS + " rows per job");

        // Build validated row list
        List<BulkEmailJob.BulkEmailRow> rows = new ArrayList<>();
        for (int i = 0; i < req.getRows().size(); i++) {
            Map<String, String> rowData = req.getRows().get(i);
            String email = rowData.get(req.getEmailColumn());
            if (email == null || email.isBlank()) continue;
            String name = req.getNameColumn() != null
                    ? rowData.getOrDefault(req.getNameColumn(), "") : "";
            rows.add(BulkEmailJob.BulkEmailRow.builder()
                    .rowIndex(i)
                    .recipientEmail(email.trim())
                    .recipientName(name)
                    .data(new HashMap<>(rowData))
                    .status(BulkEmailJob.BulkEmailRow.RowStatus.PENDING)
                    .build());
        }
        if (rows.isEmpty())
            throw new IllegalArgumentException("No valid email addresses found in column '" + req.getEmailColumn() + "'");

        // Resolve attachment config
        BulkEmailJob.AttachmentType attType =
                req.getAttachmentType() != null ? req.getAttachmentType() : BulkEmailJob.AttachmentType.NONE;

        byte[] uploadedPdfData = null;
        if (attType == BulkEmailJob.AttachmentType.UPLOAD) {
            if (req.getUploadedPdfBase64() == null || req.getUploadedPdfBase64().isBlank())
                throw new IllegalArgumentException("Uploaded PDF is required for UPLOAD attachment type");
            String b64 = req.getUploadedPdfBase64().contains(",")
                    ? req.getUploadedPdfBase64().split(",")[1]
                    : req.getUploadedPdfBase64();
            uploadedPdfData = java.util.Base64.getDecoder().decode(b64);
        }

        String pdfTemplateName = null;
        if (attType == BulkEmailJob.AttachmentType.PDF_TEMPLATE) {
            if (req.getPdfTemplateId() == null || req.getPdfTemplateId().isBlank())
                throw new IllegalArgumentException("PDF template ID is required for PDF_TEMPLATE attachment type");
            pdfTemplateName = pdfTemplateRepo.findById(req.getPdfTemplateId())
                    .map(t -> t.getName())
                    .orElseThrow(() -> new IllegalArgumentException("PDF template not found: " + req.getPdfTemplateId()));
        }

        if (attType == BulkEmailJob.AttachmentType.EXTERNAL_API) {
            if (req.getExternalApiUrl() == null || req.getExternalApiUrl().isBlank())
                throw new IllegalArgumentException("API URL is required for EXTERNAL_API attachment type");
        }

        if (attType == BulkEmailJob.AttachmentType.EXCEL_SHEET) {
            if (req.getDetailSheetRows() == null || req.getDetailSheetRows().isEmpty())
                throw new IllegalArgumentException("Detail sheet rows are required for EXCEL_SHEET attachment type");
            if (req.getDetailSheetIdColumn() == null || req.getDetailSheetIdColumn().isBlank())
                throw new IllegalArgumentException("Detail sheet ID column is required for EXCEL_SHEET attachment type");
            if (req.getMainSheetIdColumn() == null || req.getMainSheetIdColumn().isBlank())
                throw new IllegalArgumentException("Main sheet ID column is required for EXCEL_SHEET attachment type");
        }

        // Resolve organisation display name (used as email "From:" sender)
        String orgName = orgRepo.findById(principal.getOrgId())
                .map(org -> org.getName())
                .orElse(null);

        // Generate a default label if not provided
        String label = (req.getLabel() != null && !req.getLabel().isBlank())
                ? req.getLabel()
                : emailTemplate.getName() + " — " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"));

        BulkEmailJob job = BulkEmailJob.builder()
                .createdBy(principal.getId())
                .orgId(principal.getOrgId())
                .orgName(orgName)
                .label(label)
                .emailTemplateId(req.getEmailTemplateId())
                .emailTemplateName(emailTemplate.getName())
                .emailTemplateSubject(emailTemplate.getSubject())
                .emailTemplateHtml(cssInliner.inline(
                        emailTemplate.getHtmlContent(), emailTemplate.getCssContent()))
                .emailColumn(req.getEmailColumn())
                .nameColumn(req.getNameColumn())
                .columnMapping(req.getColumnMapping())
                .attachmentType(attType)
                .uploadedPdfData(uploadedPdfData)
                .uploadedPdfName(req.getUploadedPdfName())
                .pdfTemplateId(req.getPdfTemplateId())
                .pdfTemplateName(pdfTemplateName)
                .pdfColumnMapping(req.getPdfColumnMapping())
                .externalApiUrl(req.getExternalApiUrl())
                .externalApiMethod(req.getExternalApiMethod())
                .externalApiHeaders(req.getExternalApiHeaders())
                .externalApiBody(req.getExternalApiBody())
                .detailSheetRows(req.getDetailSheetRows() != null ? req.getDetailSheetRows() : List.of())
                .detailSheetColumns(req.getDetailSheetColumns() != null ? req.getDetailSheetColumns() : List.of())
                .detailSheetIdColumn(req.getDetailSheetIdColumn())
                .mainSheetIdColumn(req.getMainSheetIdColumn())
                .detailSheetFileName(req.getDetailSheetFileName())
                .status(BulkEmailJob.JobStatus.PENDING)
                .totalCount(rows.size())
                .pendingCount(rows.size())
                .sentCount(0)
                .failedCount(0)
                .rows(rows)
                .build();

        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_CREATED,
                "Job created with " + rows.size() + " recipient(s), attachment=" + attType
                + ", template=\"" + emailTemplate.getName() + "\"");
        job = jobRepo.save(job);
        log.info("BulkEmailJob '{}' created: {} rows, attachment={}", job.getId(), rows.size(), attType);

        // Unified audit log (visible on the main Audit Log page)
        auditLogService.log(
                job.getId(), job.getLabel(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("rows", rows.size(), "attachment", attType.name(),
                           "emailTemplate", emailTemplate.getName()),
                principal.getUsername(), principal.getOrgId());

        // Delegate to BulkEmailProcessor — cross-bean call so @Async proxy is honoured
        bulkEmailProcessor.processJobAsync(job.getId());
        return BulkEmailJobResponse.from(job, false);
    }

    // ── List & get ───────────────────────────────────────────────────────────

    /**
     * Returns jobs based on the caller's role:
     * <ul>
     *   <li>PLATFORM_ADMIN — all jobs across all orgs</li>
     *   <li>ORG_ADMIN      — all jobs within their organisation</li>
     *   <li>ADMIN / USER   — only jobs created by themselves</li>
     * </ul>
     */
    public Page<BulkEmailJobResponse> listJobs(UserDetailsImpl principal, int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 50));
        AppUser.Role role = principal.getAppUser().getRole();

        Page<BulkEmailJob> result = switch (role) {
            case PLATFORM_ADMIN ->
                    jobRepo.findAllByOrderByCreatedAtDesc(pageable);
            case ORG_ADMIN ->
                    jobRepo.findByOrgIdOrderByCreatedAtDesc(principal.getOrgId(), pageable);
            default ->
                    jobRepo.findByCreatedByOrderByCreatedAtDesc(principal.getId(), pageable);
        };
        return result.map(j -> BulkEmailJobResponse.from(j, false));
    }

    /**
     * Returns a single job. Access rules:
     * <ul>
     *   <li>PLATFORM_ADMIN — any job</li>
     *   <li>ORG_ADMIN      — any job in their org</li>
     *   <li>ADMIN / USER   — only their own jobs</li>
     * </ul>
     */
    public BulkEmailJobResponse getJob(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = resolveJob(jobId, principal);
        return BulkEmailJobResponse.from(job, true);
    }

    /**
     * Resolves a job with role-based access enforcement.
     */
    private BulkEmailJob resolveJob(String jobId, UserDetailsImpl principal) {
        AppUser.Role role = principal.getAppUser().getRole();
        return switch (role) {
            case PLATFORM_ADMIN ->
                    jobRepo.findById(jobId)
                            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
            case ORG_ADMIN ->
                    jobRepo.findByIdAndOrgId(jobId, principal.getOrgId())
                            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
            default ->
                    jobRepo.findByIdAndCreatedBy(jobId, principal.getId())
                            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        };
    }

    // ── Resend failed rows ───────────────────────────────────────────────────

    public BulkEmailJobResponse resendFailed(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = resolveJob(jobId, principal);

        // Guard: only PARTIAL or FAILED jobs can be retried
        if (job.getStatus() != BulkEmailJob.JobStatus.PARTIAL
                && job.getStatus() != BulkEmailJob.JobStatus.FAILED) {
            throw new IllegalStateException(
                    "Can only resend failed rows from a PARTIAL or FAILED job. Current status: "
                    + job.getStatus());
        }

        // Collect the failed rows (we need the count before mutating)
        long failedCount = job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.FAILED)
                .count();

        if (failedCount == 0)
            throw new IllegalStateException("No failed rows to resend");

        // ── Reset every FAILED row to PENDING in-place ───────────────────────
        //    sentCount / totalCount are intentionally preserved so the progress
        //    bar reflects the full picture (already-sent + now-retrying rows).
        job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.FAILED)
                .forEach(r -> {
                    r.setStatus(BulkEmailJob.BulkEmailRow.RowStatus.PENDING);
                    r.setError(null);
                    r.setMessageId(null);
                    r.setSentAt(null);
                });

        // ── Update counters ──────────────────────────────────────────────────
        job.setPendingCount((int) failedCount);   // the just-reset rows are now pending
        job.setFailedCount(0);
        // sentCount and totalCount stay the same

        // ── Reset status & timestamps ────────────────────────────────────────
        job.setStatus(BulkEmailJob.JobStatus.PENDING);
        job.setCompletedAt(null);

        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.RESEND_CREATED,
                "Resend initiated — " + failedCount + " failed row(s) reset to PENDING");
        job = jobRepo.save(job);
        log.info("Resend initiated for job '{}': {} failed row(s) reset to PENDING", jobId, failedCount);

        // Cross-bean call so @Async proxy is honoured
        bulkEmailProcessor.processJobAsync(job.getId());
        return BulkEmailJobResponse.from(job, false);
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    public BulkEmailJobResponse cancelJob(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = resolveJob(jobId, principal);
        if (job.getStatus() == BulkEmailJob.JobStatus.COMPLETED
         || job.getStatus() == BulkEmailJob.JobStatus.FAILED
         || job.getStatus() == BulkEmailJob.JobStatus.CANCELLED)
            throw new IllegalStateException("Job is already in terminal state: " + job.getStatus());

        job.setStatus(BulkEmailJob.JobStatus.CANCELLED);
        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_CANCELLED,
                "Job cancelled. Sent=" + job.getSentCount() + ", Failed=" + job.getFailedCount()
                + ", Pending=" + job.getPendingCount());
        jobRepo.save(job);

        // Unified audit log
        auditLogService.log(
                job.getId(), job.getLabel(),
                AuditLog.Action.CANCELLED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("sentCount", job.getSentCount(), "failedCount", job.getFailedCount()),
                principal.getUsername(), job.getOrgId());

        return BulkEmailJobResponse.from(job, false);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void addAuditEvent(BulkEmailJob job,
                               BulkEmailJob.BulkEmailAuditEvent.EventType type,
                               String description) {
        if (job.getAuditEvents() == null) job.setAuditEvents(new java.util.ArrayList<>());
        job.getAuditEvents().add(BulkEmailJob.BulkEmailAuditEvent.builder()
                .type(type)
                .description(description)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
