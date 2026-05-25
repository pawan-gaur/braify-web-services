package com.braify.feature.esign.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Aggregate result returned by {@code POST /api/esign/documents/bulk}.
 * Contains per-row results plus summary counters.
 */
@Data
@Builder
public class BulkCreateDocumentResponse {

    /** Total rows submitted in the request. */
    private int totalRequested;

    /** Rows where a document was successfully created (may or may not be sent). */
    private int totalCreated;

    /**
     * Rows where the document was successfully created AND the signing
     * invitation was sent (status = PENDING).
     */
    private int totalSent;

    /**
     * Rows that failed — either the document could not be created, or
     * it was created but the send failed (quota, email error, etc.).
     */
    private int totalFailed;

    /** ID of the {@link com.braify.feature.esign.model.ESignBulkBatch} created for this operation. */
    private String batchId;

    /** Per-row results in the same order as the original request. */
    private List<BulkDocumentResult> results;
}
