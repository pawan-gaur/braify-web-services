package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tracks a bulk-email send job — config, progress counters, and per-row results.
 * Rows are embedded for atomic access; max 500 rows per job.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "bulk_email_jobs")
public class BulkEmailJob {

    @Id private String id;

    @Indexed private String createdBy;   // userId
    private String orgId;
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

    /* ── Attachment config ───────────────────────────────────────────────── */
    public enum AttachmentType { NONE, UPLOAD, PDF_TEMPLATE, EXTERNAL_API }
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

    /* ── Progress ────────────────────────────────────────────────────────── */
    public enum JobStatus { PENDING, PROCESSING, COMPLETED, PARTIAL, FAILED, CANCELLED }
    @Builder.Default private JobStatus status       = JobStatus.PENDING;
    @Builder.Default private int        totalCount   = 0;
    @Builder.Default private int        sentCount    = 0;
    @Builder.Default private int        failedCount  = 0;
    @Builder.Default private int        pendingCount = 0;

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
    }

    /* ── Audit trail ─────────────────────────────────────────────────────── */
    @Builder.Default
    private List<BulkEmailAuditEvent> auditEvents = new ArrayList<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BulkEmailAuditEvent {
        public enum EventType {
            JOB_CREATED, PROCESSING_STARTED,
            JOB_COMPLETED, JOB_PARTIAL, JOB_FAILED, JOB_CANCELLED,
            RESEND_CREATED
        }
        private EventType     type;
        private String        description;
        private LocalDateTime timestamp;
    }
}
