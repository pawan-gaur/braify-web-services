package com.braify.feature.esign.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BulkCreateDocumentRequest {

    /** Optional human-readable label for the batch (e.g., "Q1 2026 Contracts"). */
    private String label;

    /** Individual document creation requests — max 500 per call. */
    @NotEmpty(message = "At least one document is required")
    @Size(max = 500, message = "Maximum 500 documents per bulk request")
    @Valid
    private List<CreateDocumentRequest> documents;

    /**
     * When {@code true} (default) each document is created AND the signing
     * invitation is sent immediately.  Set to {@code false} to create DRAFT
     * documents only — the caller can send them individually later.
     */
    private boolean sendImmediately = true;

    /**
     * When {@code true}, clients are shown an optional file-upload section after signing.
     * Applied to the whole batch — all documents inherit this setting.
     */
    private boolean allowClientUpload = false;

    /**
     * Restricts which file-extension types the client may upload (case-insensitive, without dot,
     * e.g. {@code ["pdf","jpg","png"]}). An empty / null list means all file types are accepted.
     */
    private List<String> allowedClientUploadFileTypes;
}
