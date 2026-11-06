package com.braify.feature.sharing.service;

import com.braify.feature.sharing.dto.SharingRequest;
import com.braify.feature.sharing.dto.SharingResponse;
import com.braify.feature.sharing.model.SharedTemplate;
import com.braify.feature.sharing.repository.SharedTemplateRepository;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final MongoTemplate            mongoTemplate;

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
                .createdBy(caller.getId())
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
        return enrichAll(sharingRepo.findByTargetOrgIdAndStatusOrderBySharedAtDesc(orgId, SharedTemplate.Status.ACTIVE));
    }

    /** Templates the caller's org has shared OUT to other orgs. */
    public List<SharingResponse> getSentShares(AppUser caller) {
        String orgId = caller.getOrganizationId();
        if (orgId == null) return List.of();
        return enrichAll(sharingRepo.findBySourceOrgIdAndStatusOrderBySharedAtDesc(orgId, SharedTemplate.Status.ACTIVE));
    }

    /** All active shares for a specific template (used by the Share modal to show existing shares). */
    public List<SharingResponse> getSharesForTemplate(String templateId, AppUser caller) {
        return enrichAll(sharingRepo.findByTemplateIdAndStatus(templateId, SharedTemplate.Status.ACTIVE)
                .stream()
                .filter(s -> caller.getRole() == AppUser.Role.PLATFORM_ADMIN
                             || s.getSourceOrgId().equals(caller.getOrganizationId()))
                .collect(Collectors.toList()));
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
        return enrichAll(List.of(share)).get(0);
    }

    /**
     * Enriches shares with template + org display names using batched, name-only
     * lookups: at most one query each for PDF templates, email templates and orgs,
     * regardless of the number of shares (was 3 full-document findById per share).
     * A missing record or null name falls back to the id, as before.
     */
    private List<SharingResponse> enrichAll(List<SharedTemplate> shares) {
        if (shares.isEmpty()) return List.of();
        Set<String> pdfIds = new HashSet<>(), emailIds = new HashSet<>(), orgIds = new HashSet<>();
        for (SharedTemplate s : shares) {
            if (s.getTemplateId() != null) {
                (s.getTemplateType() == SharedTemplate.TemplateType.TEMPLATE ? pdfIds : emailIds).add(s.getTemplateId());
            }
            if (s.getSourceOrgId() != null) orgIds.add(s.getSourceOrgId());
            if (s.getTargetOrgId() != null) orgIds.add(s.getTargetOrgId());
        }
        Map<String, String> pdfNames   = namesById(pdfIds,   Template.class,      Template::getId,      Template::getName);
        Map<String, String> emailNames = namesById(emailIds, EmailTemplate.class, EmailTemplate::getId, EmailTemplate::getName);
        Map<String, String> orgNames   = namesById(orgIds,   Organization.class,  Organization::getId,  Organization::getName);

        return shares.stream().map(share -> {
            Map<String, String> tplNames = share.getTemplateType() == SharedTemplate.TemplateType.TEMPLATE
                    ? pdfNames : emailNames;
            return toResponse(share,
                    tplNames.getOrDefault(share.getTemplateId(), share.getTemplateId()),
                    orgNames.getOrDefault(share.getSourceOrgId(), share.getSourceOrgId()),
                    orgNames.getOrDefault(share.getTargetOrgId(), share.getTargetOrgId()));
        }).collect(Collectors.toList());
    }

    /** One {@code _id $in} query with a name-only projection → id → name (null names omitted). */
    private <T> Map<String, String> namesById(Collection<String> ids, Class<T> type,
                                              java.util.function.Function<T, String> idFn,
                                              java.util.function.Function<T, String> nameFn) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        Query q = new Query(Criteria.where("_id").in(ids));
        q.fields().include("name");
        for (T doc : mongoTemplate.find(q, type)) {
            String name = nameFn.apply(doc);
            if (idFn.apply(doc) != null && name != null) out.put(idFn.apply(doc), name);
        }
        return out;
    }

    private SharingResponse toResponse(SharedTemplate share, String templateName,
                                       String sourceOrgName, String targetOrgName) {
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
