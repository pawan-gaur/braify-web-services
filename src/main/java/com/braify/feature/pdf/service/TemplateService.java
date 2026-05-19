package com.braify.feature.pdf.service;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.model.TemplateVersion;
import com.braify.feature.pdf.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository      templateRepository;
    private final PlaceholderService      placeholderService;
    private final TemplateVersionService  versionService;
    private final AuditLogService         auditLogService;

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Returns only non-deleted templates, filtered by org for non-platform admins. */
    public List<Template> findAll(AppUser currentUser) {
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            return templateRepository.findByDeletedFalseOrderByUpdatedAtDesc();
        }
        return templateRepository.findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(
                currentUser.getOrganizationId());
    }

    /** Throws if the template is deleted, not found, or inaccessible to the user. */
    public Template findById(String id, AppUser currentUser) {
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            return templateRepository.findByIdAndDeletedFalse(id)
                    .orElseThrow(() -> new RuntimeException("Template not found: " + id));
        }
        return templateRepository.findByIdAndOrganizationIdAndDeletedFalse(
                        id, currentUser.getOrganizationId())
                .orElseThrow(() -> new RuntimeException("Template not found: " + id));
    }

    // ── Create ───────────────────────────────────────────────────────────────

    public Template create(Template template, AppUser currentUser) {
        template.setId(null);
        template.setDeleted(false);
        template.setCurrentVersion(0);
        template.setOrganizationId(currentUser.getOrganizationId());
        template.setPlaceholders(
                placeholderService.extractPlaceholders(template.getHtmlContent()));

        Template saved = templateRepository.save(template);

        // Version snapshot v1
        versionService.snapshot(saved, "Initial version");
        templateRepository.save(saved);   // persist currentVersion=1

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(), null,
                currentUser.getEmail(), saved.getOrganizationId());

        return saved;
    }

    // ── Update ───────────────────────────────────────────────────────────────

    public Template update(String id, Template incoming, AppUser currentUser) {
        Template existing = findById(id, currentUser);
        assertAccess(currentUser, existing.getOrganizationId());

        Map<String, Object> changes = buildChanges(existing, incoming);

        existing.setName(incoming.getName());
        existing.setDescription(incoming.getDescription());
        existing.setHtmlContent(incoming.getHtmlContent());
        existing.setCssContent(incoming.getCssContent());
        existing.setGjsData(incoming.getGjsData());
        existing.setPageSize(incoming.getPageSize());
        existing.setOrientation(incoming.getOrientation());
        existing.setMarginTop(incoming.getMarginTop());
        existing.setMarginBottom(incoming.getMarginBottom());
        existing.setMarginLeft(incoming.getMarginLeft());
        existing.setMarginRight(incoming.getMarginRight());
        existing.setPlaceholders(
                placeholderService.extractPlaceholders(incoming.getHtmlContent()));

        Template saved = templateRepository.save(existing);

        versionService.snapshot(saved, "Updated");
        templateRepository.save(saved);   // persist new currentVersion

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(), changes,
                currentUser.getEmail(), saved.getOrganizationId());

        return saved;
    }

    // ── Soft Delete ──────────────────────────────────────────────────────────

    public void delete(String id, AppUser currentUser) {
        if (currentUser.getRole() == AppUser.Role.USER) {
            throw new RuntimeException("Users cannot delete templates");
        }
        Template existing = findById(id, currentUser);
        assertAccess(currentUser, existing.getOrganizationId());

        existing.setDeleted(true);
        existing.setDeletedAt(LocalDateTime.now());
        templateRepository.save(existing);

        auditLogService.log(
                existing.getId(), existing.getName(),
                AuditLog.Action.DELETED, AuditLog.ResourceType.TEMPLATE,
                existing.getCurrentVersion(), null,
                currentUser.getEmail(), existing.getOrganizationId());
    }

    // ── Restore version ──────────────────────────────────────────────────────

    public Template restoreVersion(String templateId, int versionNumber, AppUser currentUser) {
        Template existing = findById(templateId, currentUser);
        assertAccess(currentUser, existing.getOrganizationId());

        TemplateVersion snapshot = versionService.getVersion(templateId, versionNumber);

        existing.setName(snapshot.getName());
        existing.setDescription(snapshot.getDescription());
        existing.setHtmlContent(snapshot.getHtmlContent());
        existing.setCssContent(snapshot.getCssContent());
        existing.setGjsData(snapshot.getGjsData());
        existing.setPageSize(snapshot.getPageSize());
        existing.setOrientation(snapshot.getOrientation());
        existing.setMarginTop(snapshot.getMarginTop());
        existing.setMarginBottom(snapshot.getMarginBottom());
        existing.setMarginLeft(snapshot.getMarginLeft());
        existing.setMarginRight(snapshot.getMarginRight());
        existing.setPlaceholders(snapshot.getPlaceholders());

        Template saved = templateRepository.save(existing);

        versionService.snapshot(saved, "Restored from v" + versionNumber);
        templateRepository.save(saved);

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.RESTORED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(),
                Map.of("restoredFromVersion", versionNumber),
                currentUser.getEmail(), saved.getOrganizationId());

        return saved;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertAccess(AppUser user, String resourceOrgId) {
        if (user.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (resourceOrgId != null && !resourceOrgId.equals(user.getOrganizationId())) {
            throw new RuntimeException("Access denied");
        }
    }

    private Map<String, Object> buildChanges(Template old, Template incoming) {
        Map<String, Object> changes = new LinkedHashMap<>();
        diffField(changes, "name",        old.getName(),        incoming.getName());
        diffField(changes, "description", old.getDescription(), incoming.getDescription());
        diffField(changes, "pageSize",    old.getPageSize(),    incoming.getPageSize());
        diffField(changes, "orientation", old.getOrientation(), incoming.getOrientation());
        return changes.isEmpty() ? null : changes;
    }

    private void diffField(Map<String, Object> out, String field,
                           Object oldVal, Object newVal) {
        if (oldVal == null && newVal == null) return;
        if (oldVal != null && oldVal.equals(newVal)) return;
        out.put(field, Map.of("from", oldVal != null ? oldVal : "", "to", newVal != null ? newVal : ""));
    }
}
