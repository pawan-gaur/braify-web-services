package com.braify.feature.storage.controller;

import com.braify.feature.storage.dto.StoredFileResponse;
import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.storage.model.StoredFile;
import com.braify.feature.storage.service.StorageService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
@Tag(name = "Storage", description = "Upload files and get short-lived download URLs by file id. " +
        "Buckets are private; object keys are never exposed.")
public class StorageController {

    private final StorageService storageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
    @Operation(summary = "Upload a file",
               description = "Stores the file in the caller's organisation storage and returns its id. " +
                             "Pass the id to the feature that uses it (e.g. `sourceFileId` when creating an e-sign document). " +
                             "`purpose` is one of ESIGN_SOURCE, BRANDING_LOGO, AVATAR, TEMPLATE_IMAGE, BULK_EMAIL_ATTACHMENT.")
    public ResponseEntity<StoredFileResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam StoragePurpose purpose,
            @Parameter(description = "Platform Admin only: store for this organisation (e.g. its logo). " +
                                     "Others may only pass their own organisation.")
            @RequestParam(required = false) String orgId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        AppUser caller = principal.getAppUser();
        log.info("POST /api/storage/upload purpose={} orgId={} name='{}' size={} by '{}'",
                purpose, orgId, file.getOriginalFilename(), file.getSize(), caller.getEmail());
        // Files belong to the caller's org; a Platform Admin (no org) stores platform-level files
        // unless acting for an organisation.
        String targetOrg = caller.getOrganizationId();
        if (orgId != null && !orgId.isBlank() && !orgId.equals(targetOrg)) {
            if (caller.getRole() != AppUser.Role.PLATFORM_ADMIN)
                throw new org.springframework.security.access.AccessDeniedException("You can only upload to your own organisation");
            targetOrg = orgId;
        }
        StoredFile saved = storageService.upload(targetOrg, purpose, file, caller.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(StoredFileResponse.from(saved));
    }

    @GetMapping("/{fileId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
    @Operation(summary = "Get file metadata")
    public ResponseEntity<StoredFileResponse> get(
            @Parameter(description = "File id") @PathVariable String fileId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(StoredFileResponse.from(ownUpload(fileId, principal.getAppUser())));
    }

    @GetMapping("/{fileId}/url")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','ADMIN','USER')")
    @Operation(summary = "Get a pre-signed download URL",
               description = "Returns `{ url, expiresInSeconds }` for a file you uploaded. The URL is short-lived; ask again when it expires.")
    public ResponseEntity<Map<String, Object>> url(
            @Parameter(description = "File id") @PathVariable String fileId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        StorageService.PresignedUrl u = storageService.presignedUrl(ownUpload(fileId, principal.getAppUser()));
        return ResponseEntity.ok(Map.of("url", u.url(), "expiresInSeconds", u.expiresInSeconds()));
    }

    /**
     * A file the caller uploaded themselves (Platform Admins: any). Files a feature owns — e-sign
     * PDFs, attachments, library files — are only reachable through that feature's endpoints, which
     * apply its own access rules (document participants, feature roles).
     */
    private StoredFile ownUpload(String fileId, AppUser caller) {
        StoredFile f = storageService.getForUser(fileId, caller);
        if (caller.getRole() != AppUser.Role.PLATFORM_ADMIN && !caller.getId().equals(f.getCreatedBy()))
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        return f;
    }
}
