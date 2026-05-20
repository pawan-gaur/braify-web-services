package com.braify.feature.fileupload.controller;

import com.braify.feature.fileupload.dto.FileDownloadResponse;
import com.braify.feature.fileupload.dto.FileListResponse;
import com.braify.feature.fileupload.dto.FileMetadataRequest;
import com.braify.feature.fileupload.dto.FileUploadResponse;
import com.braify.feature.fileupload.service.FileService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * JWT-authenticated file management endpoints.
 *
 * <p>Base path: {@code /api/organizations/{orgId}/files}
 *
 * <ul>
 *   <li>POST   /upload             — upload a file to the org's cloud storage</li>
 *   <li>GET    /{fileId}/download  — generate a pre-signed download URL</li>
 *   <li>GET    /                   — paginated file dashboard with filters</li>
 *   <li>GET    /{fileId}           — get single file metadata</li>
 *   <li>DELETE /{fileId}           — soft-delete a file</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/organizations/{orgId}/files")
@RequiredArgsConstructor
@Tag(name = "File Management", description = "Upload, download and manage files stored in org cloud storage")
public class FileController {

    private final FileService fileService;

    /** Resolves the AppUser from the JWT principal stored by {@link com.braify.security.JwtAuthFilter}. */
    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Uploads a file to the org's configured cloud storage.
     *
     * <p>Accepts multipart/form-data with the following parts:
     * <ul>
     *   <li>{@code file} (required) — the file binary</li>
     *   <li>{@code folder} (optional) — virtual folder path</li>
     *   <li>{@code documentType} (optional) — e.g. INVOICE, CONTRACT</li>
     *   <li>{@code documentExpiryDate} (optional) — ISO date yyyy-MM-dd</li>
     *   <li>{@code description} (optional)</li>
     *   <li>{@code tags} (optional, repeatable)</li>
     * </ul>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ORG_USER')")
    @Operation(summary = "Upload file",
               description = "Uploads a file to the organisation's configured cloud storage. " +
                             "Returns file metadata including the assigned fileId.")
    public ResponseEntity<FileUploadResponse> upload(
            @PathVariable String orgId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String documentExpiryDate,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<String> tags,
            Authentication auth) {

        AppUser caller = currentUser(auth);
        assertOrgAccess(caller, orgId);

        FileMetadataRequest meta = new FileMetadataRequest();
        meta.setFolder(folder);
        meta.setDocumentType(documentType);
        meta.setDocumentExpiryDate(parseDate(documentExpiryDate));
        meta.setDescription(description);
        meta.setTags(tags);

        try {
            FileUploadResponse response = fileService.upload(orgId, file, meta, caller.getEmail());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if ("Cloud setup not exists".equals(msg)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
            }
            if (msg != null && msg.startsWith("File type")) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, msg);
            }
            if (msg != null && msg.startsWith("File size")) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, msg);
            }
            if (msg != null && msg.contains("quota")) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, msg);
            }
            log.error("File upload failed for org={}: {}", orgId, msg, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "File upload failed: " + msg);
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @GetMapping("/{fileId}/download")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ORG_USER')")
    @Operation(summary = "Get download URL",
               description = "Returns a time-limited pre-signed URL for downloading the specified file.")
    public ResponseEntity<FileDownloadResponse> download(
            @PathVariable String orgId,
            @PathVariable String fileId,
            Authentication auth) {

        AppUser caller = currentUser(auth);
        assertOrgAccess(caller, orgId);

        try {
            return ResponseEntity.ok(fileService.download(orgId, fileId, caller.getEmail()));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if ("Cloud setup not exists".equals(msg)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
            }
            if (msg != null && msg.contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
            }
            log.error("Download URL generation failed for org={} file={}: {}", orgId, fileId, msg, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Download URL generation failed: " + msg);
        }
    }

    // ── List / Dashboard ──────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ORG_USER')")
    @Operation(summary = "List files",
               description = "Paginated file dashboard with optional filters for documentType, status, and keyword search.")
    public ResponseEntity<FileListResponse> listFiles(
            @PathVariable String orgId,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "20")   int    size,
            @RequestParam(required = false)      String documentType,
            @RequestParam(required = false)      String status,
            @RequestParam(required = false)      String keyword,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir,
            Authentication auth) {

        assertOrgAccess(currentUser(auth), orgId);
        return ResponseEntity.ok(fileService.listFiles(orgId, page, size, documentType,
                status, keyword, sortBy, sortDir));
    }

    // ── Get single file metadata ──────────────────────────────────────────────

    @GetMapping("/{fileId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ORG_USER')")
    @Operation(summary = "Get file metadata",
               description = "Returns metadata for a single file.")
    public ResponseEntity<FileUploadResponse> getFile(
            @PathVariable String orgId,
            @PathVariable String fileId,
            Authentication auth) {

        assertOrgAccess(currentUser(auth), orgId);
        try {
            return ResponseEntity.ok(fileService.getFile(orgId, fileId));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/{fileId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    @Operation(summary = "Delete file",
               description = "Soft-deletes a file (marks it as DELETED). Cloud storage object is retained.")
    public ResponseEntity<Void> deleteFile(
            @PathVariable String orgId,
            @PathVariable String fileId,
            Authentication auth) {

        AppUser caller = currentUser(auth);
        assertOrgAccess(caller, orgId);
        try {
            fileService.deleteFile(orgId, fileId, caller.getEmail());
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Enforces org isolation — a non-PLATFORM_ADMIN user can only access their own org.
     */
    private void assertOrgAccess(AppUser caller, String orgId) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("You can only access your own organisation's files");
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
