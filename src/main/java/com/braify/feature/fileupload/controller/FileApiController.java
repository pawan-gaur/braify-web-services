package com.braify.feature.fileupload.controller;

import com.braify.feature.fileupload.dto.FileDownloadResponse;
import com.braify.feature.fileupload.dto.FileListResponse;
import com.braify.feature.fileupload.dto.FileMetadataRequest;
import com.braify.feature.fileupload.dto.FileUploadResponse;
import com.braify.feature.fileupload.service.FileService;
import com.braify.feature.quota.service.QuotaService;
import com.braify.security.ApiKeyPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * API-key-authenticated file endpoints.
 *
 * <p>Mirrors {@link FileController} but authenticates via the
 * {@code X-API-Key} header.  The {@link com.braify.security.ApiKeyAuthFilter}
 * resolves the key to an {@link ApiKeyPrincipal} before any method here is invoked.
 *
 * <p>Base path: {@code /api/external/files/{orgId}}
 *
 * <ul>
 *   <li>POST   /upload            — upload a file</li>
 *   <li>GET    /{fileId}/download — generate a pre-signed download URL</li>
 *   <li>GET    /                  — paginated file list</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/external/files/{orgId}")
@RequiredArgsConstructor
@Tag(name = "External File API", description = "File upload/download via API key (X-API-Key header)")
public class FileApiController {

    private final FileService  fileService;
    private final QuotaService quotaService;

    // ── Upload ────────────────────────────────────────────────────────────────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload file (API key)",
               description = "Uploads a file to the organisation's cloud storage using an API key for auth.")
    public ResponseEntity<FileUploadResponse> upload(
            @PathVariable String orgId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String documentExpiryDate,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<String> tags) {

        ApiKeyPrincipal principal = requirePrincipal(orgId);
        quotaService.incrementApiCall(orgId);

        FileMetadataRequest meta = new FileMetadataRequest();
        meta.setFolder(folder);
        meta.setDocumentType(documentType);
        meta.setDocumentExpiryDate(parseDate(documentExpiryDate));
        meta.setDescription(description);
        meta.setTags(tags);

        String uploaderIdent = "api-key:" + principal.keyPrefix();

        try {
            FileUploadResponse response = fileService.upload(orgId, file, meta, uploaderIdent);
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
            log.error("API key file upload failed for org={}: {}", orgId, msg, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "File upload failed: " + msg);
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @GetMapping("/{fileId}/download")
    @Operation(summary = "Get download URL (API key)",
               description = "Returns a time-limited pre-signed URL for the specified file.")
    public ResponseEntity<FileDownloadResponse> download(
            @PathVariable String orgId,
            @PathVariable String fileId) {

        ApiKeyPrincipal principal = requirePrincipal(orgId);
        quotaService.incrementApiCall(orgId);

        try {
            String ident = "api-key:" + principal.keyPrefix();
            return ResponseEntity.ok(fileService.download(orgId, fileId, ident));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if ("Cloud setup not exists".equals(msg)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
            }
            if (msg != null && msg.contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
            }
            log.error("API key download failed for org={} file={}: {}", orgId, fileId, msg, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Download URL generation failed: " + msg);
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List files (API key)",
               description = "Returns a paginated list of files for the organisation.")
    public ResponseEntity<FileListResponse> listFiles(
            @PathVariable String orgId,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "20")   int    size,
            @RequestParam(required = false)      String documentType,
            @RequestParam(required = false)      String status,
            @RequestParam(required = false)      String keyword,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {

        requirePrincipal(orgId);
        return ResponseEntity.ok(fileService.listFiles(orgId, page, size, documentType,
                status, keyword, sortBy, sortDir));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the {@link ApiKeyPrincipal} from the SecurityContext and verifies
     * that the key belongs to the requested org.
     */
    private ApiKeyPrincipal requirePrincipal(String orgId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ApiKeyPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key required");
        }
        if (!orgId.equals(principal.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "API key does not belong to organization: " + orgId);
        }
        return principal;
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
