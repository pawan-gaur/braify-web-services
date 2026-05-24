package com.braify.feature.esign.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result for a single row in a bulk e-sign create/send operation.
 *
 * <ul>
 *   <li>{@code success = true, status = "PENDING"}  — document created and invitation sent</li>
 *   <li>{@code success = true, status = "DRAFT"}    — document created; send skipped or failed</li>
 *   <li>{@code success = false, documentId = null}  — document creation itself failed</li>
 *   <li>{@code success = false, documentId = "..."}  — created OK but send failed (DRAFT remains)</li>
 * </ul>
 */
@Data
@Builder
public class BulkDocumentResult {

    /** Zero-based index of this row in the original request list. */
    private int     rowIndex;

    /** Document title from the request. */
    private String  title;

    /** Client email from the request. */
    private String  clientEmail;

    /** {@code true} if this row completed successfully (created + sent when requested). */
    private boolean success;

    /** ID of the created document, or {@code null} if creation failed. */
    private String  documentId;

    /** Final document status: {@code "PENDING"}, {@code "DRAFT"}, or {@code null} on create failure. */
    private String  status;

    /** Human-readable error message when {@code success = false}. */
    private String  error;
}
