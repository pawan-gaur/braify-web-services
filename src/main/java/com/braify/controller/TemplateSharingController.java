package com.braify.controller;

import com.braify.dto.SharingRequest;
import com.braify.dto.SharingResponse;
import com.braify.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.service.TemplateSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Template Sharing", description = "Share PDF and Email templates across organisations with VIEW / USE / EDIT permissions. EDIT shares fork the template into the target org (independent copy). Revoking an EDIT share soft-deletes the fork.")
@RestController
@RequestMapping("/api/sharing")
@RequiredArgsConstructor
public class TemplateSharingController {

    private final TemplateSharingService sharingService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(
        summary = "Share a template",
        description = "Shares a template with a target organisation at the specified permission level.\n\n" +
                      "**Permission levels:**\n" +
                      "- `VIEW` — target org can preview the template\n" +
                      "- `USE` — target org can generate PDFs / send emails using the template\n" +
                      "- `EDIT` — a fork (independent copy) is created in the target org; they can modify it freely\n\n" +
                      "ORG_ADMIN can only share their own org's templates. PLATFORM_ADMIN can share any.\n\n" +
                      "**Deduplication:** Only one active share per template→org pair is allowed. Revoke the existing share first.\n\n" +
                      "Body: `{ templateId, templateType: TEMPLATE|EMAIL_TEMPLATE, targetOrgId, permission: VIEW|USE|EDIT, note? }`"
    )
    @ApiResponse(responseCode = "200", description = "Share created")
    @ApiResponse(responseCode = "400", description = "Already shared / validation error")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<SharingResponse> share(@RequestBody SharingRequest req,
                                                  Authentication auth) {
        return ResponseEntity.ok(sharingService.shareTemplate(req, currentUser(auth)));
    }

    @Operation(
        summary = "Revoke a share",
        description = "Revokes an active share. For EDIT permission shares, the forked copy in the target org is soft-deleted. " +
                      "Only the source org's ORG_ADMIN (or PLATFORM_ADMIN) can revoke."
    )
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "400", description = "Share already revoked")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<Void> revoke(
            @Parameter(description = "Share ID") @PathVariable String id,
            Authentication auth) {
        sharingService.revokeShare(id, currentUser(auth));
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Templates shared with my org",
        description = "Returns all active shares where the caller's organisation is the **target** (receiver). " +
                      "Available to all authenticated users. PLATFORM_ADMIN returns an empty list (no org)."
    )
    @ApiResponse(responseCode = "200", description = "List of received shares")
    @GetMapping("/received")
    public ResponseEntity<List<SharingResponse>> received(Authentication auth) {
        return ResponseEntity.ok(sharingService.getReceivedShares(currentUser(auth)));
    }

    @Operation(
        summary = "Templates my org has shared out",
        description = "Returns all active shares where the caller's organisation is the **source** (sender). " +
                      "ORG_ADMIN and PLATFORM_ADMIN only."
    )
    @ApiResponse(responseCode = "200", description = "List of sent shares")
    @GetMapping("/sent")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<List<SharingResponse>> sent(Authentication auth) {
        return ResponseEntity.ok(sharingService.getSentShares(currentUser(auth)));
    }

    @Operation(
        summary = "Active shares for a specific template",
        description = "Returns all active shares for the given template ID. Used by the Share modal to display who the template is already shared with. " +
                      "ORG_ADMIN sees only shares originating from their own org; PLATFORM_ADMIN sees all."
    )
    @ApiResponse(responseCode = "200", description = "List of active shares")
    @GetMapping("/template/{templateId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<List<SharingResponse>> forTemplate(
            @Parameter(description = "Template ID") @PathVariable String templateId,
            Authentication auth) {
        return ResponseEntity.ok(sharingService.getSharesForTemplate(templateId, currentUser(auth)));
    }
}
