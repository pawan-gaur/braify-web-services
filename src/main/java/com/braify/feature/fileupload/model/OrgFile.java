package com.braify.feature.fileupload.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Persisted metadata for a file uploaded to an organisation's cloud storage.
 *
 * <p>The actual bytes live in the org's configured cloud bucket.
 * This document records provenance, routing keys for retrieval,
 * quota-relevant sizes, and soft-delete state.
 *
 * <p>The human-readable {@code fileId} follows the pattern
 * {@code F<yyyyMMdd><10-digit-sequence>}, e.g. {@code F202605200000000001}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "org_files")
@CompoundIndexes({
    @CompoundIndex(name = "org_status_created",
                   def = "{'organizationId':1,'status':1,'createdAt':-1}"),
    @CompoundIndex(name = "org_doctype_created",
                   def = "{'organizationId':1,'documentType':1,'createdAt':-1}")
})
public class OrgFile {

    @Id
    private String id;

    /** Human-readable ID in format F<yyyyMMdd><zero-padded-seq> — unique, immutable. */
    @Indexed(unique = true)
    private String fileId;

    @Indexed
    private String organizationId;

    /** Email of the user (or "api-key:<keyPrefix>") who uploaded the file. */
    private String uploadedBy;

    /** ID of the AppUser who uploaded the file; null for API-key uploads. */
    private String createdBy;

    /** The original filename as provided by the client. */
    private String originalFilename;

    /**
     * Object key / blob path used to retrieve the file from cloud storage.
     * For S3: {@code <prefix>/<orgId>/<fileId>/<originalFilename>}
     */
    private String storageKey;

    /** Cloud bucket / container / GCS bucket name. */
    private String bucket;

    /** Which cloud provider hosts this file. */
    private CloudProvider cloudProvider;

    /** MIME type (e.g. {@code application/pdf}, {@code image/png}). */
    private String contentType;

    /** Raw file size in bytes. */
    private long fileSizeBytes;

    /** File size in MB (rounded to 3 dp) — denormalised for quota queries. */
    private double fileSizeMb;

    /** Virtual folder path within the bucket (e.g. {@code invoices/2026}). */
    private String folder;

    /** Business document classification chosen by the caller. */
    private DocumentType documentType;

    /** Optional expiry date for time-sensitive documents. */
    private LocalDate documentExpiryDate;

    /** Free-text description. */
    private String description;

    /** Optional tags for search / filtering. */
    private List<String> tags;

    @Builder.Default
    private FileStatus status = FileStatus.ACTIVE;

    /** Number of times a download URL has been generated for this file. */
    @Builder.Default
    private long downloadCount = 0;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** Set when {@code status} transitions to {@code DELETED}. */
    private LocalDateTime deletedAt;

    // ── Enumerations ──────────────────────────────────────────────────────────

    public enum CloudProvider { AWS, AZURE, GCP }

    public enum FileStatus { ACTIVE, ARCHIVED, DELETED }

    public enum DocumentType {
        CONTRACT, INVOICE, REPORT, RECEIPT, CERTIFICATE,
        IDENTITY, POLICY, AGREEMENT, PRESENTATION, SPREADSHEET,
        IMAGE, VIDEO, AUDIO, OTHER
    }
}
