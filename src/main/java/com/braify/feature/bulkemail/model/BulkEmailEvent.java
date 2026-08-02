package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Append-only log of a single engagement event (open / click / unsubscribe) recorded
 * by the public tracking endpoints.
 *
 * <p>Events live in their own collection — rather than embedded on {@link BulkEmailJob} —
 * so that high-volume opens (mail clients such as Apple Mail pre-fetch the pixel and can
 * generate many per recipient) never risk pushing a job document past MongoDB's 16&nbsp;MB
 * limit. The job keeps only cheap denormalised counters; this collection powers the
 * analytics timeline, top-clicked-links, and per-recipient drill-down.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "bulk_email_events")
@CompoundIndexes({
    // Analytics queries are always scoped to one job, ordered/aggregated by time.
    @CompoundIndex(name = "idx_job_type_ts", def = "{'jobId': 1, 'type': 1, 'timestamp': 1}"),
})
public class BulkEmailEvent {

    public enum Type { OPEN, CLICK, UNSUBSCRIBE }

    @Id private String id;

    private String  jobId;
    private String  orgId;
    private String  trackingId;
    private String  recipientEmail;
    private int     rowIndex;

    private Type    type;
    private String  url;            // populated for CLICK — the destination that was clicked

    private LocalDateTime timestamp;
    private String  ip;             // best-effort client IP (X-Forwarded-For aware)
    private String  userAgent;
}
