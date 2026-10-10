package com.braify.feature.bulkemail.service;

import com.braify.config.infra.email.CssInliner;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.bulkemail.dto.BulkEmailJobRequest;
import com.braify.feature.bulkemail.dto.BulkEmailJobResponse;
import com.braify.feature.bulkemail.dto.RecipientPageResponse;
import com.braify.feature.bulkemail.migration.BulkEmailRowMigrationService;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.model.BulkEmailJob.BulkEmailAuditEvent.EventType;
import com.braify.feature.bulkemail.model.BulkEmailJob.JobStatus;
import com.braify.feature.bulkemail.model.BulkEmailRecipient;
import com.braify.feature.bulkemail.repository.BulkEmailJobRepository;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk-email campaigns: creation, reads, resend/retry/cancel and follow-ups.
 *
 * <p>Recipients live in {@code bulk_email_recipients}. Existing jobs are changed only through
 * {@link BulkEmailJobStore}'s targeted updates and conditional status transitions — never a
 * full-document save — and every operation on an existing job first guarantees the job is
 * migrated ({@link BulkEmailRowMigrationService#ensureMigrated}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkEmailService {

    private final BulkEmailJobRepository       jobRepo;
    private final BulkEmailJobStore            jobStore;
    private final BulkEmailRecipientStore      recipientStore;
    private final BulkEmailRowMigrationService migrationService;
    private final EmailTemplateRepository      emailTemplateRepo;
    private final OrganizationRepository       orgRepo;
    private final TemplateRepository           pdfTemplateRepo;
    private final CssInliner                   cssInliner;
    private final AuditLogService              auditLogService;
    private final MongoTemplate                mongoTemplate;
    private final com.braify.feature.bulkemail.repository.EmailSuppressionRepository suppressionRepo;
    private final com.braify.feature.bulkemail.repository.BulkEmailEventRepository   eventRepo;

    /**
     * Injected as a separate bean so that the {@code @Async} proxy on
     * {@link BulkEmailProcessor#processJobAsync} is honoured. A direct
     * {@code this.processJobAsync()} call would bypass Spring's proxy and
     * execute synchronously on the request thread.
     */
    private final BulkEmailProcessor bulkEmailProcessor;

    /** Max recipients per campaign. Recipients are separate documents, so this is about request size. */
    @Value("${bulkemail.max-rows:25000}")
    private int maxRows;

    /** Max Sheet-2 (detail) rows per campaign. */
    @Value("${bulkemail.max-detail-rows:100000}")
    private int maxDetailRows;

    /** Pragmatic email-shape check (not full RFC 5322) — rejects the obvious garbage. */
    private static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Cryptographically-strong source for opaque per-recipient tracking tokens. */
    private static final java.security.SecureRandom TOKEN_RNG = new java.security.SecureRandom();

    private static String newTrackingId() {
        byte[] buf = new byte[18];                       // 144 bits — unguessable
        TOKEN_RNG.nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Fields excluded from list-view projections. {@code rows}/{@code detailSheetRows} only exist on
     * legacy documents that have not been migrated yet; the rest are config the list never shows.
     */
    private static final String[] LIST_EXCLUDE_FIELDS = {
        "rows",
        "detailSheetRows",
        "emailTemplateHtml",
        "uploadedPdfData",
        "columnMapping",
        "pdfColumnMapping",
        "externalApiHeaders",
        "externalApiBody",
    };

    // ── Create & start job ───────────────────────────────────────────────────

    public BulkEmailJobResponse createJob(BulkEmailJobRequest req, UserDetailsImpl principal) {

        // Validate email template
        EmailTemplate emailTemplate = emailTemplateRepo
                .findByIdAndOrganizationIdAndDeletedFalse(req.getEmailTemplateId(), principal.getOrgId())
                .orElseThrow(() -> new IllegalArgumentException("Email template not found"));

        // Validate rows
        if (req.getRows() == null || req.getRows().isEmpty())
            throw new IllegalArgumentException("No rows provided");
        if (req.getRows().size() > maxRows)
            throw new IllegalArgumentException("Maximum " + maxRows + " rows per job");
        if (req.getDetailSheetRows() != null && req.getDetailSheetRows().size() > maxDetailRows)
            throw new IllegalArgumentException("Maximum " + maxDetailRows + " detail-sheet rows per job");

        // Suppression list — addresses that unsubscribed (or bounced) for this org are
        // silently skipped so a campaign never re-mails someone who opted out.
        java.util.Set<String> suppressed = suppressionRepo.findByOrgId(principal.getOrgId()).stream()
                .map(s -> s.getEmail() == null ? "" : s.getEmail().trim().toLowerCase())
                .filter(e -> !e.isEmpty())
                .collect(java.util.stream.Collectors.toSet());

        // Pre-assign the id so recipients can reference the job before it is inserted.
        String jobId = new ObjectId().toHexString();

        // Build the recipient list, filtering in one pass:
        //   blank → skipped silently; bad format → invalid; opted-out → suppressed;
        //   already-seen (case-insensitive) → duplicate. Only survivors get a row + token.
        List<BulkEmailRecipient> recipients = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int suppressedSkipped = 0, invalidSkipped = 0, duplicateSkipped = 0;
        for (int i = 0; i < req.getRows().size(); i++) {
            Map<String, String> rowData = req.getRows().get(i);
            String raw = rowData.get(req.getEmailColumn());
            if (raw == null || raw.isBlank()) continue;
            String email = raw.trim();
            String key   = email.toLowerCase();
            if (!EMAIL_RE.matcher(email).matches()) { invalidSkipped++;   continue; }
            if (suppressed.contains(key))           { suppressedSkipped++; continue; }
            if (!seen.add(key))                     { duplicateSkipped++;  continue; }
            String name = req.getNameColumn() != null
                    ? rowData.getOrDefault(req.getNameColumn(), "") : "";
            recipients.add(BulkEmailRecipient.builder()
                    .jobId(jobId)
                    .orgId(principal.getOrgId())
                    .seq(recipients.size())
                    .rowIndex(i)
                    .recipientEmail(email)
                    .recipientName(name)
                    .data(new HashMap<>(rowData))
                    .status(BulkEmailRecipient.Status.PENDING)
                    .trackingId(newTrackingId())
                    .build());
        }
        if (recipients.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            if (suppressedSkipped > 0) reasons.add(suppressedSkipped + " unsubscribed");
            if (invalidSkipped > 0)    reasons.add(invalidSkipped + " invalid");
            if (duplicateSkipped > 0)  reasons.add(duplicateSkipped + " duplicate");
            throw new IllegalArgumentException(reasons.isEmpty()
                    ? "No valid email addresses found in column '" + req.getEmailColumn() + "'"
                    : "No recipients left to send after filtering (" + String.join(", ", reasons) + ")");
        }

        // Scheduling — a future scheduledAt parks the job in SCHEDULED for the poller.
        LocalDateTime scheduledAt = req.getScheduledAt();
        boolean scheduled = scheduledAt != null && scheduledAt.isAfter(LocalDateTime.now());

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

        // Resolve organisation display name (used as email "From:" sender).
        // Guard against a null orgId (e.g. a platform-admin account with no org).
        String orgName = principal.getOrgId() != null
                ? orgRepo.findById(principal.getOrgId()).map(org -> org.getName()).orElse(null)
                : null;

        // Generate a default label if not provided
        String label = (req.getLabel() != null && !req.getLabel().isBlank())
                ? req.getLabel()
                : emailTemplate.getName() + " — " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"));

        BulkEmailJob job = BulkEmailJob.builder()
                .id(jobId)
                .schemaVersion(BulkEmailJob.SCHEMA_VERSION)
                .createdBy(principal.getId())
                .createdAt(LocalDateTime.now())   // set explicitly: a pre-assigned id skips @CreatedDate
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
                .ccColumns(req.getCcColumns() != null ? req.getCcColumns() : List.of())
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
                .detailSheetColumns(req.getDetailSheetColumns() != null ? req.getDetailSheetColumns() : List.of())
                .detailSheetIdColumn(req.getDetailSheetIdColumn())
                .mainSheetIdColumn(req.getMainSheetIdColumn())
                .detailSheetFileName(req.getDetailSheetFileName())
                .includeExcelSheet(req.isIncludeExcelSheet())
                .status(scheduled ? JobStatus.SCHEDULED : JobStatus.PENDING)
                .scheduledAt(scheduled ? scheduledAt : null)
                .totalCount(recipients.size())
                .pendingCount(recipients.size())
                .sentCount(0)
                .failedCount(0)
                .suppressedCount(suppressedSkipped)
                .invalidSkippedCount(invalidSkipped)
                .duplicateSkippedCount(duplicateSkipped)
                .build();

        String skips = java.util.stream.Stream.of(
                        suppressedSkipped > 0 ? suppressedSkipped + " unsubscribed" : null,
                        invalidSkipped    > 0 ? invalidSkipped + " invalid"        : null,
                        duplicateSkipped  > 0 ? duplicateSkipped + " duplicate"    : null)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(", "));
        addAuditEvent(job, EventType.JOB_CREATED,
                "Job created with " + recipients.size() + " recipient(s), attachment=" + attType
                + ", template=\"" + emailTemplate.getName() + "\""
                + (skips.isEmpty() ? "" : " (skipped: " + skips + ")"));
        if (scheduled)
            addAuditEvent(job, EventType.JOB_SCHEDULED, "Scheduled to send at " + scheduledAt);

        // Store recipients + Sheet-2 rows first; the job document is inserted last, so a job is only
        // ever visible (or resumable) once all of its recipients exist. Roll back on any failure.
        try {
            recipientStore.insertRecipients(recipients);
            job.setDetailRowCount(recipientStore.insertDetailRows(
                    jobId, req.getDetailSheetIdColumn(), req.getDetailSheetRows()));
            job = jobRepo.insert(job);
        } catch (RuntimeException e) {
            log.error("BulkEmailJob '{}' creation failed — rolling back stored recipients: {}", jobId, e.getMessage());
            recipientStore.deleteJobData(jobId);
            throw e;
        }
        log.info("BulkEmailJob '{}' created: {} recipients, {} detail rows, attachment={}",
                jobId, recipients.size(), job.getDetailRowCount(), attType);

        // Unified audit log (visible on the main Audit Log page)
        auditLogService.log(
                job.getId(), job.getLabel(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("rows", recipients.size(), "attachment", attType.name(),
                           "emailTemplate", emailTemplate.getName()),
                principal.getUsername(), principal.getOrgId());

        // Delegate to BulkEmailProcessor — cross-bean call so @Async proxy is honoured.
        // Scheduled jobs are left for BulkEmailScheduler to pick up when due.
        if (!scheduled) bulkEmailProcessor.processJobAsync(job.getId());
        return BulkEmailJobResponse.from(job);
    }

    // ── List & get ───────────────────────────────────────────────────────────

    /**
     * Returns a lightweight list of jobs for the caller's role.
     * <ul>
     *   <li>PLATFORM_ADMIN — all jobs across all orgs</li>
     *   <li>ORG_ADMIN      — all jobs within their organisation</li>
     *   <li>ADMIN / USER   — only jobs created by themselves</li>
     * </ul>
     */
    public Page<BulkEmailJobResponse> listJobs(UserDetailsImpl principal, int page, int size) {
        int safeSize = Math.min(size, 50);
        Criteria criteria = scopeCriteria(principal);

        long total = mongoTemplate.count(Query.query(criteria), BulkEmailJob.class);
        if (total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(page, safeSize), 0);
        }

        Query dataQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) page * safeSize)
                .limit(safeSize);
        for (String field : LIST_EXCLUDE_FIELDS) dataQuery.fields().exclude(field);

        List<BulkEmailJobResponse> responses = mongoTemplate.find(dataQuery, BulkEmailJob.class).stream()
                .map(BulkEmailJobResponse::from)
                .toList();
        return new PageImpl<>(responses, PageRequest.of(page, safeSize), total);
    }

    /** A single job's summary (recipients are served separately by {@link #getRecipients}). */
    public BulkEmailJobResponse getJob(String jobId, UserDetailsImpl principal) {
        Query q = Query.query(accessCriteria(jobId, principal));
        for (String field : LIST_EXCLUDE_FIELDS) q.fields().exclude(field);
        BulkEmailJob job = mongoTemplate.findOne(q, BulkEmailJob.class);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);
        return BulkEmailJobResponse.from(job);
    }

    /**
     * One page of a job's recipients (Recipients tab), filtered and searched server-side, plus the
     * count for every filter chip. Works for campaigns of any size — nothing is loaded in full.
     */
    public RecipientPageResponse getRecipients(String jobId, BulkEmailRecipientStore.Filter filter,
                                               String search, int page, int size,
                                               UserDetailsImpl principal) {
        assertAccess(jobId, principal);
        migrationService.ensureMigrated(jobId);

        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        long total = recipientStore.count(jobId, filter, search);
        List<BulkEmailJobResponse.RowResponse> rows = recipientStore.page(jobId, filter, search, safePage, safeSize)
                .stream().map(BulkEmailJobResponse.RowResponse::from).toList();

        return RecipientPageResponse.builder()
                .content(rows)
                .page(safePage)
                .size(safeSize)
                .totalElements(total)
                .totalPages((int) ((total + safeSize - 1) / safeSize))
                .counts(recipientStore.filterCounts(jobId))
                .build();
    }

    /**
     * Returns only the audit trail for a job — no rows, no binary data.
     */
    public List<BulkEmailJobResponse.AuditEventResponse> getJobAudit(String jobId, UserDetailsImpl principal) {
        Query q = Query.query(accessCriteria(jobId, principal));
        q.fields().include("auditEvents");
        BulkEmailJob job = mongoTemplate.findOne(q, BulkEmailJob.class);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);

        if (job.getAuditEvents() == null) return List.of();
        return job.getAuditEvents().stream()
                .map(e -> BulkEmailJobResponse.AuditEventResponse.builder()
                        .type(e.getType())
                        .description(e.getDescription())
                        .timestamp(e.getTimestamp())
                        .build())
                .toList();
    }

    /**
     * Lightweight status poll — returns only the five counter fields.
     * Used by the progress-bar polling in the UI (every 3 s while active).
     */
    public Map<String, Object> getJobStatus(String jobId, UserDetailsImpl principal) {
        Query q = Query.query(accessCriteria(jobId, principal));
        q.fields()
                .include("status")
                .include("totalCount")
                .include("sentCount")
                .include("failedCount")
                .include("pendingCount");

        BulkEmailJob job = mongoTemplate.findOne(q, BulkEmailJob.class);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);

        return Map.of(
                "id",           jobId,
                "status",       job.getStatus(),
                "totalCount",   job.getTotalCount(),
                "sentCount",    job.getSentCount(),
                "failedCount",  job.getFailedCount(),
                "pendingCount", job.getPendingCount()
        );
    }

    // ── Engagement analytics ──────────────────────────────────────────────────

    /**
     * Aggregated open/click/unsubscribe analytics for one campaign — distinct-recipient
     * counts come from the event log (authoritative), plus an hourly opens/clicks timeline
     * and the most-clicked destination links.
     */
    public com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse getAnalytics(
            String jobId, UserDetailsImpl principal) {

        Query q = Query.query(accessCriteria(jobId, principal));
        q.fields().include("label").include("sentCount").include("suppressedCount")
                  .include("unsubscribedCount");
        BulkEmailJob job = mongoTemplate.findOne(q, BulkEmailJob.class);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);

        int sent = job.getSentCount();

        // Distinct recipients (exact) + raw hit totals from the event log.
        int openedRecipients  = mongoTemplate.findDistinct(
                Query.query(eventCriteria(jobId, "OPEN")),  "trackingId",
                com.braify.feature.bulkemail.model.BulkEmailEvent.class, String.class).size();
        int clickedRecipients = mongoTemplate.findDistinct(
                Query.query(eventCriteria(jobId, "CLICK")), "trackingId",
                com.braify.feature.bulkemail.model.BulkEmailEvent.class, String.class).size();
        long totalOpens  = eventRepo.countByJobIdAndType(jobId,
                com.braify.feature.bulkemail.model.BulkEmailEvent.Type.OPEN);
        long totalClicks = eventRepo.countByJobIdAndType(jobId,
                com.braify.feature.bulkemail.model.BulkEmailEvent.Type.CLICK);

        // Self-heal: rewrite the campaign's denormalised summary counters (used by the list view
        // and header chips) from the authoritative event log, so they can never drift from reality.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(jobId)),
                new Update()
                        .set("openedCount",  openedRecipients)
                        .set("clickedCount", clickedRecipients)
                        .set("totalOpens",   (int) totalOpens)
                        .set("totalClicks",  (int) totalClicks),
                BulkEmailJob.class);

        return com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse.builder()
                .jobId(jobId)
                .label(job.getLabel())
                .sentCount(sent)
                .openedRecipients(openedRecipients)
                .clickedRecipients(clickedRecipients)
                .unsubscribedCount(job.getUnsubscribedCount())
                .suppressedCount(job.getSuppressedCount())
                .totalOpens(totalOpens)
                .totalClicks(totalClicks)
                .openRate(sent > 0 ? (double) openedRecipients / sent : 0)
                .clickRate(sent > 0 ? (double) clickedRecipients / sent : 0)
                .clickToOpenRate(openedRecipients > 0 ? (double) clickedRecipients / openedRecipients : 0)
                .timeline(buildTimeline(jobId))
                .topLinks(buildTopLinks(jobId))
                .build();
    }

    private static Criteria eventCriteria(String jobId, String type) {
        return Criteria.where("jobId").is(jobId).and("type").is(type);
    }

    /** Hourly opens/clicks buckets (UTC), chronological. Groups by extracted
     *  year/month/day/hour (same aggregation pattern as the dashboard) so no
     *  MongoDB-version-specific date operator is required. */
    private List<com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse.TimePoint> buildTimeline(String jobId) {
        var agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        Criteria.where("jobId").is(jobId).and("type").in("OPEN", "CLICK")),
                org.springframework.data.mongodb.core.aggregation.Aggregation.project()
                        .and(org.springframework.data.mongodb.core.aggregation.DateOperators.Year.yearOf("timestamp")).as("y")
                        .and(org.springframework.data.mongodb.core.aggregation.DateOperators.Month.monthOf("timestamp")).as("mo")
                        .and(org.springframework.data.mongodb.core.aggregation.DateOperators.DayOfMonth.dayOfMonth("timestamp")).as("d")
                        .and(org.springframework.data.mongodb.core.aggregation.DateOperators.Hour.hourOf("timestamp")).as("h")
                        .and("type").as("type"),
                org.springframework.data.mongodb.core.aggregation.Aggregation
                        .group("y", "mo", "d", "h", "type").count().as("count"));

        java.util.Map<String, long[]> byBucket = new java.util.TreeMap<>();   // bucket → [opens, clicks]
        for (org.bson.Document doc : mongoTemplate.aggregate(agg,
                "bulk_email_events", org.bson.Document.class).getMappedResults()) {
            if (!(doc.get("_id") instanceof org.bson.Document id)) continue;
            int y  = num(id.get("y")),  mo = num(id.get("mo"));
            int d  = num(id.get("d")),  h  = num(id.get("h"));
            String bucket = String.format("%04d-%02d-%02d %02d:00", y, mo, d, h);
            String type   = String.valueOf(id.get("type"));
            long   count  = doc.get("count") instanceof Number n ? n.longValue() : 0;
            long[] slot   = byBucket.computeIfAbsent(bucket, k -> new long[2]);
            if ("CLICK".equals(type)) slot[1] += count; else slot[0] += count;
        }
        return byBucket.entrySet().stream()
                .map(e -> com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse.TimePoint.builder()
                        .bucket(e.getKey()).opens(e.getValue()[0]).clicks(e.getValue()[1]).build())
                .toList();
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** Top-10 most-clicked destination links. */
    private List<com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse.LinkStat> buildTopLinks(String jobId) {
        var agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        Criteria.where("jobId").is(jobId).and("type").is("CLICK")),
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("url").count().as("count"),
                org.springframework.data.mongodb.core.aggregation.Aggregation
                        .sort(Sort.Direction.DESC, "count"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.limit(10));

        List<com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse.LinkStat> out = new ArrayList<>();
        for (org.bson.Document d : mongoTemplate.aggregate(agg,
                "bulk_email_events", org.bson.Document.class).getMappedResults()) {
            Object url = d.get("_id");
            if (url == null) continue;
            long count = d.get("count") instanceof Number n ? n.longValue() : 0;
            out.add(com.braify.feature.bulkemail.dto.BulkEmailAnalyticsResponse.LinkStat.builder()
                    .url(String.valueOf(url)).clicks(count).build());
        }
        return out;
    }

    // ── Resend failed rows ───────────────────────────────────────────────────

    public BulkEmailJobResponse resendFailed(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = accessibleSummary(jobId, principal);

        // Guard: only PARTIAL or FAILED jobs can be retried
        if (job.getStatus() != JobStatus.PARTIAL && job.getStatus() != JobStatus.FAILED) {
            throw new IllegalStateException(
                    "Can only resend failed rows from a PARTIAL or FAILED job. Current status: " + job.getStatus());
        }
        long failedCount = recipientStore.count(jobId, BulkEmailRecipientStore.Filter.FAILED, null);
        if (failedCount == 0) throw new IllegalStateException("No failed rows to resend");

        // Claim the job first so a concurrent action can't interleave, then reset the rows.
        if (!jobStore.transition(jobId, List.of(JobStatus.PARTIAL, JobStatus.FAILED), JobStatus.PENDING,
                new Update().unset("completedAt"))) {
            throw new IllegalStateException("The job's status changed — refresh and try again");
        }
        long reset = recipientStore.resetFailedToPending(jobId);
        jobStore.refreshCounters(jobId);   // sent/total preserved, failed → pending
        jobStore.pushAudit(jobId, EventType.RESEND_CREATED,
                "Resend initiated — " + reset + " failed row(s) reset to PENDING");
        log.info("Resend initiated for job '{}': {} failed row(s) reset to PENDING", jobId, reset);

        bulkEmailProcessor.processJobAsync(jobId);
        return BulkEmailJobResponse.from(jobStore.findSummary(jobId));
    }

    // ── Retry pending rows (after cancellation) ──────────────────────────────

    /**
     * Re-queues all PENDING rows from a CANCELLED job and restarts processing.
     * SENT rows are untouched; only rows still PENDING are re-processed.
     *
     * @throws IllegalStateException if the job is not CANCELLED or has no pending rows
     */
    public BulkEmailJobResponse retryPending(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = accessibleSummary(jobId, principal);

        if (job.getStatus() != JobStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Only CANCELLED jobs can have their pending rows retried. Current status: " + job.getStatus());
        }
        long pendingCount = recipientStore.count(jobId, BulkEmailRecipientStore.Filter.PENDING, null);
        if (pendingCount == 0)
            throw new IllegalStateException("No pending rows to retry — all rows were already sent or failed");

        if (!jobStore.transition(jobId, List.of(JobStatus.CANCELLED), JobStatus.PENDING,
                new Update().unset("completedAt"))) {
            throw new IllegalStateException("The job's status changed — refresh and try again");
        }
        BulkEmailJobStore.Counts c = jobStore.refreshCounters(jobId);
        jobStore.pushAudit(jobId, EventType.RETRY_PENDING,
                "Retry pending initiated — " + pendingCount + " pending row(s) will be sent "
                + "(already sent: " + c.sent() + ", failed: " + c.failed() + ")");
        log.info("Retry-pending initiated for job '{}': {} pending row(s) will be processed", jobId, pendingCount);

        auditLogService.log(
                jobId, job.getLabel(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("pendingCount", pendingCount, "sentCount", c.sent()),
                principal.getUsername(), job.getOrgId());

        bulkEmailProcessor.processJobAsync(jobId);
        return BulkEmailJobResponse.from(jobStore.findSummary(jobId));
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    public BulkEmailJobResponse cancelJob(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = accessibleSummary(jobId, principal);

        // Conditional transition: the running processor sees CANCELLED and stops within a few rows,
        // and its final status update is itself conditional, so this can no longer be overwritten.
        if (!jobStore.transition(jobId,
                List.of(JobStatus.SCHEDULED, JobStatus.PENDING, JobStatus.PROCESSING, JobStatus.PARTIAL),
                JobStatus.CANCELLED, null)) {
            throw new IllegalStateException("Job is already in terminal state: " + jobStore.status(jobId));
        }
        BulkEmailJobStore.Counts c = jobStore.refreshCounters(jobId);
        jobStore.pushAudit(jobId, EventType.JOB_CANCELLED,
                "Job cancelled. Sent=" + c.sent() + ", Failed=" + c.failed() + ", Pending=" + c.pending());

        auditLogService.log(
                jobId, job.getLabel(),
                AuditLog.Action.CANCELLED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("sentCount", c.sent(), "failedCount", c.failed()),
                principal.getUsername(), job.getOrgId());

        return BulkEmailJobResponse.from(jobStore.findSummary(jobId));
    }

    // ── Re-engagement: follow-up campaign to non-openers / non-clickers ────────

    public enum ResendSegment { UNOPENED, UNCLICKED }

    /**
     * Creates a NEW campaign that re-sends to the recipients of {@code jobId} who were sent
     * successfully but did not open (or did not click). The original campaign is untouched;
     * the follow-up gets fresh tracking tokens and its own analytics. Unsubscribed and
     * now-suppressed addresses are excluded. Recipients are streamed, never loaded in full.
     */
    public BulkEmailJobResponse resendToSegment(String jobId, ResendSegment segment,
                                                String label, UserDetailsImpl principal) {
        assertAccess(jobId, principal);
        migrationService.ensureMigrated(jobId);
        BulkEmailJob src = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (src.getStatus() != JobStatus.COMPLETED && src.getStatus() != JobStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Follow-up sends require a finished campaign (COMPLETED or PARTIAL). Current: " + src.getStatus());
        }

        java.util.Set<String> suppressed = suppressionRepo.findByOrgId(src.getOrgId()).stream()
                .map(s -> s.getEmail() == null ? "" : s.getEmail().trim().toLowerCase())
                .filter(e -> !e.isEmpty()).collect(java.util.stream.Collectors.toSet());

        String newJobId = new ObjectId().toHexString();
        Criteria segmentCriteria = new Criteria().andOperator(
                Criteria.where("status").is(BulkEmailRecipient.Status.SENT),
                Criteria.where("unsubscribed").ne(true),
                Criteria.where(segment == ResendSegment.UNOPENED ? "openCount" : "clickCount").not().gt(0));

        List<BulkEmailRecipient> buffer = new ArrayList<>(BulkEmailRecipientStore.INSERT_CHUNK);
        java.util.Set<String> seen = new java.util.HashSet<>();
        int[] count = {0};
        int detailRows;
        BulkEmailJob job;
        try {
            recipientStore.forEach(jobId, segmentCriteria, r -> {
                String email = r.getRecipientEmail();
                if (email == null || email.isBlank()) return;
                String key = email.trim().toLowerCase();
                if (suppressed.contains(key) || !seen.add(key)) return;
                buffer.add(BulkEmailRecipient.builder()
                        .jobId(newJobId)
                        .orgId(src.getOrgId())
                        .seq(count[0])
                        .rowIndex(count[0])
                        .recipientEmail(email.trim())
                        .recipientName(r.getRecipientName())
                        .data(new HashMap<>(r.getData() != null ? r.getData() : Map.of()))
                        .status(BulkEmailRecipient.Status.PENDING)
                        .trackingId(newTrackingId())
                        .build());
                count[0]++;
                if (buffer.size() == BulkEmailRecipientStore.INSERT_CHUNK) {
                    recipientStore.insertRecipients(buffer);
                    buffer.clear();
                }
            });
            if (!buffer.isEmpty()) recipientStore.insertRecipients(buffer);
            if (count[0] == 0) {
                throw new IllegalStateException("No "
                        + (segment == ResendSegment.UNOPENED ? "non-openers" : "non-clickers") + " to email");
            }
            detailRows = recipientStore.copyDetailRows(jobId, newJobId);

            String defaultLabel = (segment == ResendSegment.UNOPENED ? "Re-engage non-openers" : "Re-engage non-clickers")
                    + " — " + src.getLabel();
            job = BulkEmailJob.builder()
                    .id(newJobId)
                    .schemaVersion(BulkEmailJob.SCHEMA_VERSION)
                    .createdBy(principal.getId())
                    .createdAt(LocalDateTime.now())
                    .orgId(src.getOrgId())
                    .orgName(src.getOrgName())
                    .label(label != null && !label.isBlank() ? label : defaultLabel)
                    .emailTemplateId(src.getEmailTemplateId())
                    .emailTemplateName(src.getEmailTemplateName())
                    .emailTemplateSubject(src.getEmailTemplateSubject())
                    .emailTemplateHtml(src.getEmailTemplateHtml())
                    .emailColumn(src.getEmailColumn())
                    .nameColumn(src.getNameColumn())
                    .columnMapping(src.getColumnMapping())
                    .ccColumns(src.getCcColumns() != null ? src.getCcColumns() : List.of())
                    .attachmentType(src.getAttachmentType())
                    .uploadedPdfData(src.getUploadedPdfData())
                    .uploadedPdfName(src.getUploadedPdfName())
                    .pdfTemplateId(src.getPdfTemplateId())
                    .pdfTemplateName(src.getPdfTemplateName())
                    .pdfColumnMapping(src.getPdfColumnMapping())
                    .externalApiUrl(src.getExternalApiUrl())
                    .externalApiMethod(src.getExternalApiMethod())
                    .externalApiHeaders(src.getExternalApiHeaders())
                    .externalApiBody(src.getExternalApiBody())
                    .detailSheetColumns(src.getDetailSheetColumns() != null ? src.getDetailSheetColumns() : List.of())
                    .detailRowCount(detailRows)
                    .detailSheetIdColumn(src.getDetailSheetIdColumn())
                    .mainSheetIdColumn(src.getMainSheetIdColumn())
                    .detailSheetFileName(src.getDetailSheetFileName())
                    .includeExcelSheet(src.isIncludeExcelSheet())
                    .status(JobStatus.PENDING)
                    .totalCount(count[0])
                    .pendingCount(count[0])
                    .build();
            addAuditEvent(job, EventType.RESEND_SEGMENT,
                    "Follow-up to " + count[0] + " "
                    + (segment == ResendSegment.UNOPENED ? "non-opener" : "non-clicker")
                    + "(s) from campaign " + src.getId());
            job = jobRepo.insert(job);
        } catch (RuntimeException e) {
            recipientStore.deleteJobData(newJobId);   // never leave a half-built follow-up behind
            throw e;
        }
        log.info("Segment resend '{}' ({}) created from '{}': {} recipients",
                newJobId, segment, src.getId(), count[0]);

        auditLogService.log(newJobId, job.getLabel(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.BULK_EMAIL, 0,
                Map.of("segment", segment.name(), "recipients", count[0], "sourceJob", src.getId()),
                principal.getUsername(), principal.getOrgId());

        bulkEmailProcessor.processJobAsync(newJobId);
        return BulkEmailJobResponse.from(job);
    }

    // ── Suppression (unsubscribe) list management ──────────────────────────────

    public List<com.braify.feature.bulkemail.model.EmailSuppression> listSuppressions(UserDetailsImpl principal) {
        return suppressionRepo.findByOrgId(principal.getOrgId()).stream()
                .sorted(java.util.Comparator.comparing(
                        com.braify.feature.bulkemail.model.EmailSuppression::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    public com.braify.feature.bulkemail.model.EmailSuppression addSuppression(String email, UserDetailsImpl principal) {
        if (email == null || !EMAIL_RE.matcher(email.trim()).matches())
            throw new IllegalArgumentException("Enter a valid email address");
        String normalised = email.trim().toLowerCase();
        return suppressionRepo.findByOrgIdAndEmail(principal.getOrgId(), normalised)
                .orElseGet(() -> suppressionRepo.save(
                        com.braify.feature.bulkemail.model.EmailSuppression.builder()
                                .orgId(principal.getOrgId())
                                .email(normalised)
                                .reason(com.braify.feature.bulkemail.model.EmailSuppression.Reason.MANUAL)
                                .createdAt(LocalDateTime.now())
                                .build()));
    }

    public void removeSuppression(String id, UserDetailsImpl principal) {
        var sup = suppressionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Suppression not found"));
        if (!principal.getOrgId().equals(sup.getOrgId()))
            throw new AccessDeniedException("Not allowed to modify this suppression");
        suppressionRepo.deleteById(id);
    }

    // ── Access + helpers ─────────────────────────────────────────────────────

    /** Role scope for listing: PLATFORM_ADMIN all, ORG_ADMIN own org, others own jobs. */
    private static Criteria scopeCriteria(UserDetailsImpl principal) {
        AppUser.Role role = principal.getAppUser().getRole();
        return switch (role) {
            case PLATFORM_ADMIN -> new Criteria();
            case ORG_ADMIN      -> Criteria.where("orgId").is(principal.getOrgId());
            default             -> Criteria.where("createdBy").is(principal.getId());
        };
    }

    /** Criteria matching {@code jobId} only if the caller may access it. */
    private static Criteria accessCriteria(String jobId, UserDetailsImpl principal) {
        AppUser.Role role = principal.getAppUser().getRole();
        return switch (role) {
            case PLATFORM_ADMIN -> Criteria.where("_id").is(jobId);
            case ORG_ADMIN      -> Criteria.where("_id").is(jobId).and("orgId").is(principal.getOrgId());
            default             -> Criteria.where("_id").is(jobId).and("createdBy").is(principal.getId());
        };
    }

    private void assertAccess(String jobId, UserDetailsImpl principal) {
        if (!mongoTemplate.exists(Query.query(accessCriteria(jobId, principal)), BulkEmailJob.class)) {
            throw new IllegalArgumentException("Job not found: " + jobId);
        }
    }

    /** Access-checked, migrated, lightweight job (no heavy fields). */
    private BulkEmailJob accessibleSummary(String jobId, UserDetailsImpl principal) {
        assertAccess(jobId, principal);
        migrationService.ensureMigrated(jobId);
        BulkEmailJob job = jobStore.findSummary(jobId);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);
        return job;
    }

    /** Appends an audit event to a NEW (not yet inserted) job. Existing jobs use {@link BulkEmailJobStore#pushAudit}. */
    private void addAuditEvent(BulkEmailJob job, EventType type, String description) {
        if (job.getAuditEvents() == null) job.setAuditEvents(new ArrayList<>());
        job.getAuditEvents().add(BulkEmailJob.BulkEmailAuditEvent.builder()
                .type(type)
                .description(description)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
