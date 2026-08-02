package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tracks a bulk-email send job — config, progress counters, and per-row results.
 * Rows are embedded for atomic access; max 5 000 rows per job.
 *
 * <h3>Index strategy</h3>
 * <ul>
 *   <li>Compound indexes on the three list-query patterns (user / org / all) each
 *       include {@code createdAt DESC} so MongoDB can satisfy the sort from the index
 *       without a separate in-memory sort pass.</li>
 *   <li>Heavy fields ({@code rows}, {@code emailTemplateHtml}, {@code uploadedPdfData},
 *       {@code detailSheetRows}) are excluded from list projections in the service layer.</li>
 * </ul>
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "bulk_email_jobs")
@CompoundIndexes({
    // USER-scoped list (ADMIN/USER role) — most common query
    @CompoundIndex(name = "idx_createdBy_createdAt", def = "{'createdBy': 1, 'createdAt': -1}"),
    // ORG-scoped list (ORG_ADMIN role)
    @CompoundIndex(name = "idx_orgId_createdAt",     def = "{'orgId': 1,     'createdAt': -1}"),
    // PLATFORM_ADMIN full-scan, still benefits from a createdAt index for the sort
    @CompoundIndex(name = "idx_createdAt_desc",      def = "{'createdAt': -1}"),
    // Open/click/unsubscribe tracking hits look a recipient up by its opaque token —
    // index the embedded rows.trackingId so each tracking pixel/redirect is an O(1) find.
    @CompoundIndex(name = "idx_rows_trackingId",     def = "{'rows.trackingId': 1}"),
})
public class BulkEmailJob {

    @Id private String id;

    @CreatedBy
    @Indexed private String createdBy;   // userId
    @Indexed private String orgId;
    private String orgName;              // display name used as email "From:" sender
    private String label;

    /* ── Email template (cached at creation time) ───────────────────────── */
    private String emailTemplateId;
    private String emailTemplateName;
    private String emailTemplateSubject;
    private String emailTemplateHtml;    // cached HTML so template edits don't affect in-flight jobs

    /* ── Column mapping ──────────────────────────────────────────────────── */
    private String emailColumn;          // XLSX column name that holds recipient email
    private String nameColumn;           // optional — recipient display name
    private Map<String, String> columnMapping;   // emailTemplatePlaceholder → xlsxColumn
    /** XLSX column names whose per-row values are added as CC recipients. */
    @Builder.Default
    private List<String> ccColumns = new ArrayList<>();

    /* ── Attachment config ───────────────────────────────────────────────── */
    public enum AttachmentType { NONE, UPLOAD, PDF_TEMPLATE, EXTERNAL_API, EXCEL_SHEET }
    @Builder.Default
    private AttachmentType attachmentType = AttachmentType.NONE;

    // UPLOAD — same PDF for all recipients
    private byte[]  uploadedPdfData;
    private String  uploadedPdfName;

    // PDF_TEMPLATE — generate per-recipient from a PDF template
    private String pdfTemplateId;
    private String pdfTemplateName;
    private Map<String, String> pdfColumnMapping;  // pdfVar → xlsxColumn

    // EXTERNAL_API — fetch PDF per-recipient from an HTTP endpoint
    private String externalApiUrl;        // may contain {{col}} substitutions
    private String externalApiMethod;     // GET or POST
    private String externalApiHeaders;    // JSON string of header key→value pairs
    private String externalApiBody;       // POST body template with {{col}} placeholders

    // EXCEL_SHEET — generate per-recipient Excel from Sheet 2 of the uploaded workbook
    @Builder.Default
    private List<Map<String, String>> detailSheetRows = new ArrayList<>(); // all Sheet 2 rows
    @Builder.Default
    private List<String> detailSheetColumns = new ArrayList<>();            // Sheet 2 column names
    private String detailSheetIdColumn;     // Sheet 2 column to match against
    private String mainSheetIdColumn;       // Sheet 1 column that holds the matching key
    private String detailSheetFileName;     // e.g. "Statement_{{name}}.xlsx"

    /**
     * When {@code true}, a per-recipient Excel attachment (from Sheet-2 data) is generated
     * in addition to whatever {@code attachmentType} specifies.  Allows combining, e.g.,
     * a PDF Template attachment with an Excel Sheet-2 attachment in the same email.
     * Ignored when {@code attachmentType} is already {@code EXCEL_SHEET}.
     */
    @Builder.Default private boolean includeExcelSheet = false;

    /* ── Progress ────────────────────────────────────────────────────────── */
    public enum JobStatus { SCHEDULED, PENDING, PROCESSING, COMPLETED, PARTIAL, FAILED, CANCELLED }
    @Builder.Default private JobStatus status       = JobStatus.PENDING;
    @Builder.Default private int        totalCount   = 0;
    @Builder.Default private int        sentCount    = 0;
    @Builder.Default private int        failedCount  = 0;
    @Builder.Default private int        pendingCount = 0;

    /** When set and in the future at creation, the job waits in {@code SCHEDULED} until a
     *  poller dispatches it. Null = send immediately. */
    private LocalDateTime scheduledAt;

    /** Recipients dropped at build time for bad address format / being duplicates within the list. */
    @Builder.Default private int invalidSkippedCount   = 0;
    @Builder.Default private int duplicateSkippedCount = 0;

    /* ── Engagement tracking (denormalised counters) ─────────────────────────
     * Maintained incrementally by the tracking endpoints so the list view can show
     * engagement without loading every row. {@code openedCount}/{@code clickedCount}
     * are distinct-recipient counters (best-effort; the analytics endpoint recomputes
     * them exactly from the event log). {@code totalOpens}/{@code totalClicks} are raw
     * hit counts (opens are inflated by mail-client prefetch — clicks are the reliable
     * engagement signal). */
    @Builder.Default private int totalOpens        = 0;
    @Builder.Default private int totalClicks       = 0;
    @Builder.Default private int openedCount       = 0;   // distinct recipients who opened ≥1
    @Builder.Default private int clickedCount      = 0;   // distinct recipients who clicked ≥1
    @Builder.Default private int unsubscribedCount = 0;
    /** Recipients dropped at build time because their address is on the org suppression list. */
    @Builder.Default private int suppressedCount   = 0;

    @CreatedDate private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    /* ── Per-row results ─────────────────────────────────────────────────── */
    @Builder.Default
    private List<BulkEmailRow> rows = new ArrayList<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BulkEmailRow {
        private int    rowIndex;
        private String recipientEmail;
        private String recipientName;
        private Map<String, String> data;   // original XLSX row (for resend)
        public enum RowStatus { PENDING, SENT, FAILED }
        @Builder.Default private RowStatus status = RowStatus.PENDING;
        private String error;
        private String messageId;           // Resend API message ID on success
        private LocalDateTime sentAt;

        /* ── Engagement tracking ─────────────────────────────────────────────
         * {@code trackingId} is an opaque, unguessable per-recipient token embedded in
         * the open pixel and click/unsubscribe links. It is the join key the public
         * /api/track endpoints use to attribute a hit back to this recipient. */
        private String trackingId;
        @Builder.Default private int openCount  = 0;
        private LocalDateTime firstOpenedAt;
        private LocalDateTime lastOpenedAt;
        @Builder.Default private int clickCount = 0;
        private LocalDateTime firstClickedAt;
        private LocalDateTime lastClickedAt;
        @Builder.Default private boolean unsubscribed = false;
        private LocalDateTime unsubscribedAt;
    }

    /* ── Audit trail ─────────────────────────────────────────────────────── */
    @Builder.Default
    private List<BulkEmailAuditEvent> auditEvents = new ArrayList<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BulkEmailAuditEvent {
        public enum EventType {
            JOB_CREATED, JOB_SCHEDULED, PROCESSING_STARTED,
            JOB_COMPLETED, JOB_PARTIAL, JOB_FAILED, JOB_CANCELLED,
            RESEND_CREATED, RESEND_SEGMENT, RETRY_PENDING, RESUMED
        }
        private EventType     type;
        private String        description;
        private LocalDateTime timestamp;
    }
}
