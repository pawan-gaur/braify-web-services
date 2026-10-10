package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One recipient of a bulk-email campaign, stored as its own document (one per recipient) rather
 * than embedded in {@link BulkEmailJob}. Keeping recipients out of the job document means:
 * <ul>
 *   <li>a campaign's size is no longer bounded by MongoDB's 16 MB document limit;</li>
 *   <li>sending progress is persisted with small targeted updates instead of rewriting a
 *       multi-megabyte document every few rows;</li>
 *   <li>the processor pages through recipients, so memory stays flat regardless of list size;</li>
 *   <li>engagement tracking updates one small document per hit.</li>
 * </ul>
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "bulk_email_recipients")
@CompoundIndexes({
    // One document per (job, position) — the idempotency key for creation and migration.
    @CompoundIndex(name = "uq_job_seq",         def = "{'jobId': 1, 'seq': 1}", unique = true),
    // Processor paging ("next PENDING rows after seq N") and per-status counters.
    @CompoundIndex(name = "idx_job_status_seq", def = "{'jobId': 1, 'status': 1, 'seq': 1}"),
})
public class BulkEmailRecipient {

    @Id private String id;

    private String jobId;
    private String orgId;

    /** 0-based position within the job. Stable display/processing order; unique per job. */
    private int seq;
    /** Original spreadsheet row index (what the Recipients tab shows as "#"). */
    private int rowIndex;

    private String recipientEmail;
    private String recipientName;
    /** The original spreadsheet row — drives placeholders, CC columns and attachments. */
    private Map<String, String> data;

    public enum Status { PENDING, SENT, FAILED }
    @Builder.Default private Status status = Status.PENDING;
    private String error;
    private String messageId;           // email-provider message id on success
    private LocalDateTime sentAt;

    /* ── Engagement tracking ───────────────────────────────────────────────
     * {@code trackingId} is the opaque, unguessable per-recipient token embedded in the open
     * pixel and click/unsubscribe links; the public /api/track endpoints resolve hits by it. */
    @Indexed(sparse = true) private String trackingId;
    @Builder.Default private int openCount  = 0;
    private LocalDateTime firstOpenedAt;
    private LocalDateTime lastOpenedAt;
    @Builder.Default private int clickCount = 0;
    private LocalDateTime firstClickedAt;
    private LocalDateTime lastClickedAt;
    @Builder.Default private boolean unsubscribed = false;
    private LocalDateTime unsubscribedAt;
}
