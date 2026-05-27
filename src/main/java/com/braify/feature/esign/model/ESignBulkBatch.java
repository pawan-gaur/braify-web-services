package com.braify.feature.esign.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Tracks a single bulk-send operation submitted via {@code POST /api/esign/documents/bulk}.
 * One batch corresponds to one spreadsheet / one bulk-send form submission.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_bulk_batches")
public class ESignBulkBatch {

    @Id private String id;

    /** User who submitted the bulk send */
    @Indexed private String createdBy;
    private String orgId;

    /** Optional human-readable label (defaults to "Bulk Send – <timestamp>") */
    private String label;

    /* ── Counters ─────────────────────────────────────────────────────────── */
    private int totalRequested;
    private int totalCreated;
    private int totalSent;
    private int totalFailed;

    /* ── Configuration ────────────────────────────────────────────────────── */
    /**
     * When {@code true}, clients are shown an optional file-upload section after
     * signing so they can attach supporting documents (ID proof, etc.).
     * Set by the creator at batch-init time; propagated to every document in the batch.
     */
    @Builder.Default
    private boolean allowClientUpload = false;

    /* ── Batch status ─────────────────────────────────────────────────────── */
    public enum Status { PROCESSING, COMPLETED, PARTIAL, FAILED }
    @Builder.Default
    private Status status = Status.PROCESSING;

    /* ── Timestamps ───────────────────────────────────────────────────────── */
    @CreatedDate      private LocalDateTime createdAt;
    @LastModifiedDate private LocalDateTime updatedAt;
}
