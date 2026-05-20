package com.braify.feature.fileupload.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Optional metadata that can accompany a file upload (form fields sent with
 * the multipart upload request).
 */
@Data
public class FileMetadataRequest {

    /** Virtual folder path, e.g. {@code invoices/2026}. */
    private String folder;

    /**
     * Business document type.  Accepted values match
     * {@link com.braify.feature.fileupload.model.OrgFile.DocumentType} enum names
     * (case-insensitive).
     */
    private String documentType;

    /** Optional expiry date for the document. */
    private LocalDate documentExpiryDate;

    /** Free-text description. */
    private String description;

    /** Optional tags for search / classification. */
    private List<String> tags;
}
