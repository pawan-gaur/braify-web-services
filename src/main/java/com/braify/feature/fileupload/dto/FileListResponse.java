package com.braify.feature.fileupload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated response for the file dashboard / list endpoint.
 * Used for both org-scoped requests and the Platform Admin cross-org view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileListResponse {

    private List<FileUploadResponse> files;
    private long   totalElements;
    private int    totalPages;
    private int    currentPage;
    private int    pageSize;
    private boolean last;

    /** Total number of active files for this org (or all orgs for admin). */
    private long totalActiveFiles;

    /** Total storage used by active files in MB. */
    private double totalStorageMb;

    /**
     * Per-organisation storage breakdown — populated only for Platform Admin
     * cross-org requests; {@code null} for regular org-scoped responses.
     */
    private List<OrgStorageStat> orgStats;
}
