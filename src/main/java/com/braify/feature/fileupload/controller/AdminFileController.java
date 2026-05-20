package com.braify.feature.fileupload.controller;

import com.braify.feature.fileupload.dto.FileListResponse;
import com.braify.feature.fileupload.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Platform Admin — cross-organisation file management endpoints.
 *
 * <p>Base path: {@code /api/admin/files}
 *
 * <ul>
 *   <li>GET / — paginated file list across all orgs with optional filters</li>
 * </ul>
 *
 * <p>All endpoints are restricted to {@code PLATFORM_ADMIN} role.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/files")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "Admin File Management", description = "Cross-org file dashboard for Platform Admins")
public class AdminFileController {

    private final FileService fileService;

    /**
     * Lists files across all organisations.
     *
     * <p>Optional query parameters:
     * <ul>
     *   <li>{@code orgId}        — restrict to a single organisation</li>
     *   <li>{@code documentType} — filter by document type</li>
     *   <li>{@code status}       — filter by file status (ACTIVE / ARCHIVED)</li>
     *   <li>{@code keyword}      — search in filename, description, tags</li>
     *   <li>{@code sortBy}       — field to sort (default: createdAt)</li>
     *   <li>{@code sortDir}      — ASC / DESC (default: DESC)</li>
     *   <li>{@code page}         — 0-based page number (default: 0)</li>
     *   <li>{@code size}         — page size (default: 20, max: 100)</li>
     * </ul>
     *
     * <p>Response includes {@code orgStats} — a per-org storage breakdown sorted by usage.
     */
    @GetMapping
    @Operation(summary = "List all files (admin)",
               description = "Paginated cross-org file list with per-org storage stats. " +
                             "Optionally filter by a specific orgId, documentType, status, or keyword.")
    public ResponseEntity<FileListResponse> listAllFiles(
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "20")   int    size,
            @RequestParam(required = false)      String orgId,
            @RequestParam(required = false)      String documentType,
            @RequestParam(required = false)      String status,
            @RequestParam(required = false)      String keyword,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {

        log.debug("GET /api/admin/files page={} size={} orgId={} documentType={} status={} keyword='{}'",
                page, size, orgId, documentType, status, keyword);
        return ResponseEntity.ok(
                fileService.listAllFiles(page, size, orgId, documentType, status, keyword, sortBy, sortDir));
    }
}
