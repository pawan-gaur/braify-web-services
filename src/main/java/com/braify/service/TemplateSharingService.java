package com.braify.service;

import com.braify.dto.SharingRequest;
import com.braify.dto.SharingResponse;
import com.braify.model.*;
import com.braify.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateSharingService {

    private final SharedTemplateRepository sharingRepo;
    private final TemplateRepository       templateRepo;
    private final EmailTemplateRepository  emailTemplateRepo;
    private final OrganizationRepository   orgRepo;
    private final AppUserRepository        userRepo;
    private final AuditLogService          auditLogService;

    // ── Share ─────────────────────────────────────────────────────────────────

    public SharingResponse shareTemplate(SharingRequest req, AppUser caller) {
        // Parse and validate enums
        SharedTemplate.TemplateType type;
        SharedTemplate.Permission   permission;
        try {
            type       = SharedTemplate.TemplateType.valueOf(req.getTemplateType().toUpperCase());
            permission = SharedTemplate.Permission.valueOf(req.getPermission().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid templateType or permission value: " + e.getMessage());
        }

        // Validate note length
        if (req.getNote() != null && req.getNote().length() > 300) {
            throw new RuntimeException("Note must not exceed 300 characters.");
        }

        // Verify source template belongs to caller's org
        assertTemplateOwnership(req.getTemplateId(), type, caller);

        // Validate target org exists and is active
        Organization targetOrg = orgRepo.findById(req.getTargetOrgId())
                .orElseThrow(() -> new RuntimeException("Target organisation not found: " + req.getTargetOrgId()));
        if (!targetOrg.isActive()) {
            throw new RuntimeException("Cannot share with an inactive organisation.");
        }
        if (req.getTargetOrgId().equals(caller.getOrganizationId())) {
            throw new RuntimeException("Cannot share a template with your own organisation.");
        }

        // Deduplication check — prevent multiple active shares to the same org
        sharingRepo.findByTemplateIdAndTargetOrgIdAndStatus(
                req.getTemplateId(), req.getTargetOrgId(), SharedTemplate.Status.ACTIVE)
                .ifPresent(existing -> {
                    throw new RuntimeException(
                            "This template is already shared with '" + targetOrg.getName() +
                            "' (share ID: " + existing.getId() + "). Revoke the existing share first.");
                });

        // For EDIT permission: fork the template into the target org
        String forkedId = null;
        if (permission == SharedTemplate.Permission.EDIT) {
            forkedId = forkTemplate(req.getTemplateId(), type, req.getTargetOrgId(), caller);
        }

        SharedTemplate share = SharedTemplate.builder()
                .sourceOrgId(caller.getOrganizationId())
                .targetOrgId(req.getTargetOrgId())
                .templateId(req.getTemplateId())
                .templateType(type)
                .permission(permission)
                .sharedBy(caller.getEmail())
                .sharedByUserId(caller.getId())
                .note(req.getNote())
                .forkedTemplateId(forkedId)
                .build();

        share = sharingRepo.save(share);
        log.info("Template '{}' shared with org '{}' (permission: {})", req.getTemplateId(), targetOrg.getName(), permission);

        auditLogService.log(
                share.getId(), resolveTemplateName(req.getTemplateId(), type),
                AuditLog.Action.TEMPLATE_SHARED, AuditLog.ResourceType.SHARING,
                0, Map.of("targetOrg", targetOrg.getName(), "permission", permission.name()),
                caller.getEmail(), caller.getOrganizationId());

        return enrich(share);
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    public void revokeShare(String shareId, AppUser caller) {
        SharedTemplate share = sharingRepo.findById(shareId)
                .orElseThrow(() -> new RuntimeException("Share not found: " + shareId));

        if (share.getStatus() == SharedTemplate.Status.REVOKED) {
            throw new RuntimeException("Share is already revoked.");
        }

        // Only the source org's ORG_ADMIN (or PLATFORM_ADMIN) can revoke
        if (caller.getRole() != AppUser.Role.PLATFORM_ADMIN &&
            !share.getSourceOrgId().equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("You can only revoke shares made by your organisation.");
        }

        // Soft-delete the fork if one was created
        if (share.getForkedTemplateId() != null) {
            softDeleteFork(share.getForkedTemplateId(), share.getTemplateType());
        }

        share.setStatus(SharedTemplate.Status.REVOKED);
        share.setRevokedAt(LocalDateTime.now());
        share.setRevokedBy(caller.getEmail());
        sharingRepo.save(share);
        log.info("Share '{}' revoked by '{}'", shareId, caller.getEmail());

        auditLogService.log(
                shareId, resolveTemplateName(share.getTemplateId(), share.getTemplateType()),
                AuditLog.Action.TEMPLATE_UNSHARED, AuditLog.ResourceType.SHARING,
                0, Map.of("targetOrgId", share.getTargetOrgId()),
                caller.getEmail(), caller.getOrganizationId());
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Templates shared INTO the caller's org from other orgs. */
    public List<SharingResponse> getReceivedShares(AppUser caller) {
        String orgId = caller.getRole() == AppUser.Role.PLATFORM_ADMIN
                ? null : caller.getOrganizationId();
        if (orgId == null) return List.of(); // PA has no org
        return sharingRepo.findByTargetOrgIdAndStatusOrderBySharedAtDesc(orgId, SharedTemplate.Status.ACTIVE)
                .stream().map(this::enrich).collect(Collectors.toList());
    }

    /** Templates the caller's org has shared OUT to other orgs. */
    public List<SharingResponse> getSentShares(AppUser caller) {
        String orgId = caller.getOrganizationId();
        if (orgId == null) return List.of();
        return sharingRepo.findBySourceOrgIdAndStatusOrderBySharedAtDesc(orgId, SharedTemplate.Status.ACTIVE)
                .stream().map(this::enrich).collect(Collectors.toList());
    }

    /** All active shares for a specific template (used by the Share modal to show existing shares). */
    public List<SharingResponse> getSharesForTemplate(String templateId, AppUser caller) {
        return sharingRepo.findByTemplateIdAndStatus(templateId, SharedTemplate.Status.ACTIVE)
                .stream()
                .filter(s -> caller.getRole() == AppUser.Role.PLATFORM_ADMIN
                             || s.getSourceOrgId().equals(caller.getOrganizationId()))
                .map(this::enrich)
                .collect(Collectors.toList());
    }

    // ── Fork helpers ──────────────────────────────────────────────────────────

    private String forkTemplate(String sourceId, SharedTemplate.TemplateType type,
                                 String targetOrgId, AppUser caller) {
        if (type == SharedTemplate.TemplateType.TEMPLATE) {
            Template src = templateRepo.findById(sourceId)
                    .orElseThrow(() -> new RuntimeException("Template not found: " + sourceId));
            Template fork = new Template();
            fork.setName(src.getName() + " (shared)");
            fork.setOrganizationId(targetOrgId);
            fork.setDescription(src.getDescription());
            fork.setHtmlContent(src.getHtmlContent());
            fork.setCssContent(src.getCssContent());
            fork.setGjsData(src.getGjsData());
            fork.setPlaceholders(src.getPlaceholders());
            fork.setPageSize(src.getPageSize());
            fork.setOrientation(src.getOrientation());
            fork.setMarginTop(src.getMarginTop());
            fork.setMarginBottom(src.getMarginBottom());
            fork.setMarginLeft(src.getMarginLeft());
            fork.setMarginRight(src.getMarginRight());
            fork.setSourceTemplateId(sourceId);
            fork.setSourceOrgId(caller.getOrganizationId());
            fork.setForked(true);
            return templateRepo.save(fork).getId();
        } else {
            EmailTemplate src = emailTemplateRepo.findById(sourceId)
                    .orElseThrow(() -> new RuntimeException("Email template not found: " + sourceId));
            EmailTemplate fork = new EmailTemplate();
            fork.setName(src.getName() + " (shared)");
            fork.setOrganizationId(targetOrgId);
            fork.setDescription(src.getDescription());
            fork.setSubject(src.getSubject());
            fork.setPreviewText(src.getPreviewText());
            fork.setFromName(src.getFromName());
            fork.setHtmlContent(src.getHtmlContent());
            fork.setCssContent(src.getCssContent());
            fork.setGjsData(src.getGjsData());
            fork.setPlaceholders(src.getPlaceholders());
            fork.setSourceTemplateId(sourceId);
            fork.setSourceOrgId(caller.getOrganizationId());
            fork.setForked(true);
            return emailTemplateRepo.save(fork).getId();
        }
    }

    private void softDeleteFork(String forkedId, SharedTemplate.TemplateType type) {
        if (type == SharedTemplate.TemplateType.TEMPLATE) {
            templateRepo.findById(forkedId).ifPresent(t -> {
                t.setDeleted(true);
                t.setDeletedAt(LocalDateTime.now());
                templateRepo.save(t);
            });
        } else {
            emailTemplateRepo.findById(forkedId).ifPresent(t -> {
                t.setDeleted(true);
                t.setDeletedAt(LocalDateTime.now());
                emailTemplateRepo.save(t);
            });
        }
    }

    // ── Access guard ──────────────────────────────────────────────────────────

    private void assertTemplateOwnership(String templateId, SharedTemplate.TemplateType type, AppUser caller) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        String ownerOrgId = type == SharedTemplate.TemplateType.TEMPLATE
                ? templateRepo.findById(templateId).map(Template::getOrganizationId).orElse(null)
                : emailTemplateRepo.findById(templateId).map(EmailTemplate::getOrganizationId).orElse(null);
        if (!caller.getOrganizationId().equals(ownerOrgId)) {
            throw new AccessDeniedException("You can only share templates that belong to your organisation.");
        }
    }

    // ── Enrichment ───────────────────────────────────────────────────────────

    private SharingResponse enrich(SharedTemplate share) {
        String templateName = resolveTemplateName(share.getTemplateId(), share.getTemplateType());
        String sourceOrgName = orgRepo.findById(share.getSourceOrgId())
                .map(Organization::getName).orElse(share.getSourceOrgId());
        String targetOrgName = orgRepo.findById(share.getTargetOrgId())
                .map(Organization::getName).orElse(share.getTargetOrgId());

        return SharingResponse.builder()
                .id(share.getId())
                .templateId(share.getTemplateId())
                .templateName(templateName)
                .templateType(share.getTemplateType())
                .sourceOrgId(share.getSourceOrgId())
                .sourceOrgName(sourceOrgName)
                .targetOrgId(share.getTargetOrgId())
                .targetOrgName(targetOrgName)
                .permission(share.getPermission())
                .status(share.getStatus())
                .sharedBy(share.getSharedBy())
                .sharedAt(share.getSharedAt())
                .note(share.getNote())
                .forkedTemplateId(share.getForkedTemplateId())
                .revokedAt(share.getRevokedAt())
                .revokedBy(share.getRevokedBy())
                .build();
    }

    private String resolveTemplateName(String id, SharedTemplate.TemplateType type) {
        if (type == SharedTemplate.TemplateType.TEMPLATE) {
            return templateRepo.findById(id).map(Template::getName).orElse(id);
        }
        return emailTemplateRepo.findById(id).map(EmailTemplate::getName).orElse(id);
    }
}
