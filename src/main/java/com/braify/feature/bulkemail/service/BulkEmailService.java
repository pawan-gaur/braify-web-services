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

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkEmailService {

    private static final int MAX_ROWS = 10000;

    private final BulkEmailJobRepository   jobRepo;
    private final EmailTemplateRepository  emailTemplateRepo;
    private final OrganizationRepository   orgRepo;
    private final TemplateRepository       pdfTemplateRepo;
    private final CssInliner               cssInliner;
    private final AuditLogService          auditLogService;
    private final MongoTemplate            mongoTemplate;
    private final com.braify.feature.bulkemail.repository.EmailSuppressionRepository suppressionRepo;
    private final com.braify.feature.bulkemail.repository.BulkEmailEventRepository   eventRepo;

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
     * Fields excluded from list-view projections.
     * These are the heavyweight embedded arrays / blobs that the list page never
     * displays — excluding them can reduce per-document payload from hundreds of KB
     * (5 000 rows + HTML template + PDF bytes) down to under 1 KB.
     */
    private static final String[] LIST_EXCLUDE_FIELDS = {
        "rows",               // per-recipient results (up to 5 000 entries each with full data Map)
        "emailTemplateHtml",  // the full rendered HTML body of the email template
        "uploadedPdfData",    // binary PDF bytes (potentially several MB)
        "detailSheetRows",    // all Sheet-2 rows for EXCEL_SHEET attachment type
        "columnMapping",      // placeholder→column maps not needed in list view
        "pdfColumnMapping",
        "externalApiHeaders",
        "externalApiBody",
    };

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

        // Suppression list — addresses that unsubscribed (or bounced) for this org are
        // silently skipped so a campaign never re-mails someone who opted out.
        java.util.Set<String> suppressed = suppressionRepo.findByOrgId(principal.getOrgId()).stream()
                .map(s -> s.getEmail() == null ? "" : s.getEmail().trim().toLowerCase())
                .filter(e -> !e.isEmpty())
                .collect(java.util.stream.Collectors.toSet());

        // Build the recipient list, filtering in one pass:
        //   blank → skipped silently; bad format → invalid; opted-out → suppressed;
        //   already-seen (case-insensitive) → duplicate. Only survivors get a row + token.
        List<BulkEmailJob.BulkEmailRow> rows = new ArrayList<>();
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
            rows.add(BulkEmailJob.BulkEmailRow.builder()
                    .rowIndex(i)
                    .recipientEmail(email)
                    .recipientName(name)
                    .data(new HashMap<>(rowData))
                    .status(BulkEmailJob.BulkEmailRow.RowStatus.PENDING)
                    .trackingId(newTrackingId())
                    .build());
        }
        if (rows.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            if (suppressedSkipped > 0) reasons.add(suppressedSkipped + " unsubscribed");
            if (invalidSkipped > 0)    reasons.add(invalidSkipped + " invalid");
            if (duplicateSkipped > 0)  reasons.add(duplicateSkipped + " duplicate");
            throw new IllegalArgumentException(reasons.isEmpty()
                    ? "No valid email addresses found in column '" + req.getEmailColumn() + "'"
                    : "No recipients left to send after filtering (" + String.join(", ", reasons) + ")");
        }

        // Scheduling — a future scheduledAt parks the job in SCHEDULED for the poller.
        java.time.LocalDateTime scheduledAt = req.getScheduledAt();
        boolean scheduled = scheduledAt != null && scheduledAt.isAfter(java.time.LocalDateTime.now());

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
                .detailSheetRows(req.getDetailSheetRows() != null ? req.getDetailSheetRows() : List.of())
                .detailSheetColumns(req.getDetailSheetColumns() != null ? req.getDetailSheetColumns() : List.of())
                .detailSheetIdColumn(req.getDetailSheetIdColumn())
                .mainSheetIdColumn(req.getMainSheetIdColumn())
                .detailSheetFileName(req.getDetailSheetFileName())
                .includeExcelSheet(req.isIncludeExcelSheet())
                .status(scheduled ? BulkEmailJob.JobStatus.SCHEDULED : BulkEmailJob.JobStatus.PENDING)
                .scheduledAt(scheduled ? scheduledAt : null)
                .totalCount(rows.size())
                .pendingCount(rows.size())
                .sentCount(0)
                .failedCount(0)
                .suppressedCount(suppressedSkipped)
                .invalidSkippedCount(invalidSkipped)
                .duplicateSkippedCount(duplicateSkipped)
                .rows(rows)
                .build();

        String skips = java.util.stream.Stream.of(
                        suppressedSkipped > 0 ? suppressedSkipped + " unsubscribed" : null,
                        invalidSkipped    > 0 ? invalidSkipped + " invalid"        : null,
                        duplicateSkipped  > 0 ? duplicateSkipped + " duplicate"    : null)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(", "));
        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_CREATED,
                "Job created with " + rows.size() + " recipient(s), attachment=" + attType
                + ", template=\"" + emailTemplate.getName() + "\""
                + (skips.isEmpty() ? "" : " (skipped: " + skips + ")"));
        if (scheduled)
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_SCHEDULED,
                    "Scheduled to send at " + scheduledAt);
        job = jobRepo.save(job);
        log.info("BulkEmailJob '{}' created: {} rows, attachment={}", job.getId(), rows.size(), attType);

        // Unified audit log (visible on the main Audit Log page)
        auditLogService.log(
                job.getId(), job.getLabel(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("rows", rows.size(), "attachment", attType.name(),
                           "emailTemplate", emailTemplate.getName()),
                principal.getUsername(), principal.getOrgId());

        // Delegate to BulkEmailProcessor — cross-bean call so @Async proxy is honoured.
        // Scheduled jobs are left for BulkEmailScheduler to pick up when due.
        if (!scheduled) bulkEmailProcessor.processJobAsync(job.getId());
        return BulkEmailJobResponse.from(job, false);
    }

    // ── List & get ───────────────────────────────────────────────────────────

    /**
     * Returns a lightweight list of jobs for the caller's role.
     *
     * <p>Heavy fields ({@code rows}, {@code emailTemplateHtml}, {@code uploadedPdfData},
     * {@code detailSheetRows}, mapping fields) are excluded via a MongoDB projection so
     * that only the summary fields required by the list view are transferred from Atlas.
     * For a job with 5 000 rows this typically reduces the per-document payload from
     * several hundred KB to under 1 KB — a ~99 % reduction.
     *
     * <ul>
     *   <li>PLATFORM_ADMIN — all jobs across all orgs</li>
     *   <li>ORG_ADMIN      — all jobs within their organisation</li>
     *   <li>ADMIN / USER   — only jobs created by themselves</li>
     * </ul>
     */
    public Page<BulkEmailJobResponse> listJobs(UserDetailsImpl principal, int page, int size) {
        int safeSize = Math.min(size, 50);
        AppUser.Role role = principal.getAppUser().getRole();

        // Build the role-scoped filter criteria
        Criteria criteria = switch (role) {
            case PLATFORM_ADMIN -> new Criteria();                                      // no filter — all jobs
            case ORG_ADMIN      -> Criteria.where("orgId").is(principal.getOrgId());
            default             -> Criteria.where("createdBy").is(principal.getId());
        };

        // Count query (cheap — hits the compound index without loading documents)
        long total = mongoTemplate.count(Query.query(criteria), BulkEmailJob.class);

        if (total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(page, safeSize), 0);
        }

        // Data query with projection — exclude all heavy embedded fields
        Query dataQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) page * safeSize)
                .limit(safeSize);

        for (String field : LIST_EXCLUDE_FIELDS) {
            dataQuery.fields().exclude(field);
        }

        List<BulkEmailJob> jobs = mongoTemplate.find(dataQuery, BulkEmailJob.class);
        List<BulkEmailJobResponse> responses = jobs.stream()
                .map(j -> BulkEmailJobResponse.from(j, false))
                .toList();

        return new PageImpl<>(responses, PageRequest.of(page, safeSize), total);
    }

    /**
     * Returns a single job with per-row status (Recipients tab).
     *
     * <p>Heavy fields that are never displayed in the detail view are excluded
     * from the MongoDB projection so the network payload stays manageable
     * even for jobs with 5 000 rows:
     * <ul>
     *   <li>{@code rows[].data} — raw Excel values (not shown in the UI; only
     *       needed internally by the processor for resend); this single exclusion
     *       typically removes 80–95 % of the document size.</li>
     *   <li>{@code emailTemplateHtml} — full HTML body</li>
     *   <li>{@code uploadedPdfData}   — binary PDF bytes</li>
     *   <li>{@code detailSheetRows}   — all Sheet-2 rows</li>
     * </ul>
     */
    public BulkEmailJobResponse getJob(String jobId, UserDetailsImpl principal) {
        AppUser.Role role  = principal.getAppUser().getRole();
        Criteria criteria = switch (role) {
            case PLATFORM_ADMIN -> Criteria.where("_id").is(jobId);
            case ORG_ADMIN      -> Criteria.where("_id").is(jobId).and("orgId").is(principal.getOrgId());
            default             -> Criteria.where("_id").is(jobId).and("createdBy").is(principal.getId());
        };

        Query q = Query.query(criteria);
        q.fields()
                .exclude("emailTemplateHtml")   // ~50 KB – not displayed
                .exclude("uploadedPdfData")      // binary bytes – not displayed
                .exclude("detailSheetRows")      // Sheet-2 raw rows – not displayed
                .exclude("rows.data");           // raw Excel values per row – biggest item; not shown in Recipients tab

        BulkEmailJob job = mongoTemplate.findOne(q, BulkEmailJob.class);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);
        return BulkEmailJobResponse.from(job, true);
    }

    /**
     * Returns only the audit trail for a job — no rows, no binary data.
     * Uses a targeted projection so the document payload is tiny regardless
     * of how many rows the job contains.
     */
    public List<BulkEmailJobResponse.AuditEventResponse> getJobAudit(
            String jobId, UserDetailsImpl principal) {

        AppUser.Role role = principal.getAppUser().getRole();
        Criteria criteria = switch (role) {
            case PLATFORM_ADMIN -> Criteria.where("_id").is(jobId);
            case ORG_ADMIN      -> Criteria.where("_id").is(jobId).and("orgId").is(principal.getOrgId());
            default             -> Criteria.where("_id").is(jobId).and("createdBy").is(principal.getId());
        };

        Query q = Query.query(criteria);
        q.fields().include("auditEvents");   // include only what we need

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
     * Fetches a projection that excludes all heavy embedded arrays so the
     * response is always under 200 bytes regardless of row count.
     * Used by the progress-bar polling in the UI (every 3 s while active).
     */
    public Map<String, Object> getJobStatus(String jobId, UserDetailsImpl principal) {
        // Resolve access rights using the full document would load 5 000 rows just to check orgId.
        // Instead: load only the access-check fields + counters in one targeted query.
        AppUser.Role role = principal.getAppUser().getRole();

        Criteria idCriteria = Criteria.where("_id").is(jobId);
        Criteria accessCriteria = switch (role) {
            case PLATFORM_ADMIN -> idCriteria;
            case ORG_ADMIN      -> idCriteria.and("orgId").is(principal.getOrgId());
            default             -> idCriteria.and("createdBy").is(principal.getId());
        };

        Query q = Query.query(accessCriteria);
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

        AppUser.Role role = principal.getAppUser().getRole();
        Criteria access = switch (role) {
            case PLATFORM_ADMIN -> Criteria.where("_id").is(jobId);
            case ORG_ADMIN      -> Criteria.where("_id").is(jobId).and("orgId").is(principal.getOrgId());
            default             -> Criteria.where("_id").is(jobId).and("createdBy").is(principal.getId());
        };
        Query q = Query.query(access);
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

    // ── Retry pending rows (after cancellation) ──────────────────────────────

    /**
     * Re-queues all PENDING rows from a CANCELLED job and restarts processing.
     *
     * <p>This is the "resume" path for campaigns that were cancelled mid-flight.
     * SENT rows are untouched; only rows still in PENDING status are re-processed.
     * The job's {@code sentCount} is preserved so the progress display stays accurate.</p>
     *
     * @throws IllegalStateException if the job is not CANCELLED or has no pending rows
     */
    public BulkEmailJobResponse retryPending(String jobId, UserDetailsImpl principal) {
        BulkEmailJob job = resolveJob(jobId, principal);

        if (job.getStatus() != BulkEmailJob.JobStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Only CANCELLED jobs can have their pending rows retried. Current status: "
                    + job.getStatus());
        }

        long pendingCount = job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.PENDING)
                .count();

        if (pendingCount == 0)
            throw new IllegalStateException("No pending rows to retry — all rows were already sent or failed");

        // Pending rows keep their PENDING status; no mutation needed on the rows themselves.
        // Just reset the job-level counters and status so the processor picks them up.
        job.setPendingCount((int) pendingCount);
        job.setStatus(BulkEmailJob.JobStatus.PENDING);
        job.setCompletedAt(null);

        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.RETRY_PENDING,
                "Retry pending initiated — " + pendingCount + " pending row(s) will be sent "
                + "(already sent: " + job.getSentCount() + ", failed: " + job.getFailedCount() + ")");
        job = jobRepo.save(job);
        log.info("Retry-pending initiated for job '{}': {} pending row(s) will be processed", jobId, pendingCount);

        auditLogService.log(
                job.getId(), job.getLabel(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.BULK_EMAIL,
                0, Map.of("pendingCount", pendingCount, "sentCount", job.getSentCount()),
                principal.getUsername(), job.getOrgId());

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

    // ── Re-engagement: follow-up campaign to non-openers / non-clickers ────────

    public enum ResendSegment { UNOPENED, UNCLICKED }

    /**
     * Creates a NEW campaign that re-sends to the recipients of {@code jobId} who were sent
     * successfully but did not open (or did not click). The original campaign is untouched;
     * the follow-up gets fresh tracking tokens and its own analytics. Unsubscribed and
     * now-suppressed addresses are excluded.
     */
    public BulkEmailJobResponse resendToSegment(String jobId, ResendSegment segment,
                                                String label, UserDetailsImpl principal) {
        BulkEmailJob src = resolveJob(jobId, principal);
        if (src.getStatus() != BulkEmailJob.JobStatus.COMPLETED
                && src.getStatus() != BulkEmailJob.JobStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Follow-up sends require a finished campaign (COMPLETED or PARTIAL). Current: " + src.getStatus());
        }

        java.util.Set<String> suppressed = suppressionRepo.findByOrgId(src.getOrgId()).stream()
                .map(s -> s.getEmail() == null ? "" : s.getEmail().trim().toLowerCase())
                .filter(e -> !e.isEmpty()).collect(java.util.stream.Collectors.toSet());

        List<BulkEmailJob.BulkEmailRow> newRows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int idx = 0;
        for (BulkEmailJob.BulkEmailRow r : src.getRows()) {
            boolean matches = r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.SENT
                    && !r.isUnsubscribed()
                    && (segment == ResendSegment.UNOPENED ? r.getOpenCount() == 0 : r.getClickCount() == 0);
            if (!matches) continue;
            String email = r.getRecipientEmail();
            if (email == null || email.isBlank()) continue;
            String key = email.trim().toLowerCase();
            if (suppressed.contains(key) || !seen.add(key)) continue;
            newRows.add(BulkEmailJob.BulkEmailRow.builder()
                    .rowIndex(idx++)
                    .recipientEmail(email.trim())
                    .recipientName(r.getRecipientName())
                    .data(new HashMap<>(r.getData() != null ? r.getData() : Map.of()))
                    .status(BulkEmailJob.BulkEmailRow.RowStatus.PENDING)
                    .trackingId(newTrackingId())
                    .build());
        }
        if (newRows.isEmpty())
            throw new IllegalStateException("No "
                    + (segment == ResendSegment.UNOPENED ? "non-openers" : "non-clickers") + " to email");

        String defaultLabel = (segment == ResendSegment.UNOPENED ? "Re-engage non-openers" : "Re-engage non-clickers")
                + " — " + src.getLabel();
        BulkEmailJob job = BulkEmailJob.builder()
                .createdBy(principal.getId())
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
                .detailSheetRows(src.getDetailSheetRows() != null ? src.getDetailSheetRows() : List.of())
                .detailSheetColumns(src.getDetailSheetColumns() != null ? src.getDetailSheetColumns() : List.of())
                .detailSheetIdColumn(src.getDetailSheetIdColumn())
                .mainSheetIdColumn(src.getMainSheetIdColumn())
                .detailSheetFileName(src.getDetailSheetFileName())
                .includeExcelSheet(src.isIncludeExcelSheet())
                .status(BulkEmailJob.JobStatus.PENDING)
                .totalCount(newRows.size())
                .pendingCount(newRows.size())
                .rows(newRows)
                .build();
        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.RESEND_SEGMENT,
                "Follow-up to " + newRows.size() + " "
                + (segment == ResendSegment.UNOPENED ? "non-opener" : "non-clicker")
                + "(s) from campaign " + src.getId());
        job = jobRepo.save(job);
        log.info("Segment resend '{}' ({}) created from '{}': {} recipients",
                job.getId(), segment, src.getId(), newRows.size());

        auditLogService.log(job.getId(), job.getLabel(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.BULK_EMAIL, 0,
                Map.of("segment", segment.name(), "recipients", newRows.size(), "sourceJob", src.getId()),
                principal.getUsername(), principal.getOrgId());

        bulkEmailProcessor.processJobAsync(job.getId());
        return BulkEmailJobResponse.from(job, false);
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
