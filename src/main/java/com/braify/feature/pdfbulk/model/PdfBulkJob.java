package com.braify.feature.pdfbulk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk PDF generation: one PDF template rendered once per spreadsheet row, packed into a ZIP.
 * The ZIP is saved to the org's Files (folder {@code bulk-pdf/}); when storage isn't available
 * it's kept on the server for {@link #TEMP_RETENTION_HOURS} hours instead.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "pdf_bulk_jobs")
@CompoundIndex(name = "idx_org_created", def = "{'orgId':1,'createdAt':-1}")
public class PdfBulkJob {

    public static final int TEMP_RETENTION_HOURS = 24;

    public enum Status { QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED }

    @Id private String id;
    private String orgId;
    private String createdBy;
    private String createdByEmail;

    private String templateId;
    private String templateName;
    private String label;
    private String filenamePattern;

    @Builder.Default
    private Status status = Status.QUEUED;
    private int total;
    private int succeeded;
    private int failed;
    @Builder.Default
    private List<RowError> errors = new ArrayList<>();   // capped
    private String error;                                // job-level failure

    /* ── Output ── */
    private String zipFilename;
    private long   zipSizeBytes;
    private String fileId;        // OrgFile id when saved to Files
    @JsonIgnore
    private String tempZipPath;   // fallback: server temp file (expires) — never sent to clients
    private boolean expired;

    /** True while a ZIP can be downloaded (from Files or the temporary copy). Not persisted. */
    @JsonProperty("downloadable")
    public boolean isDownloadable() {
        return fileId != null || tempZipPath != null;
    }

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RowError {
        private int    row;       // 1-based data row
        private String message;
    }
}
