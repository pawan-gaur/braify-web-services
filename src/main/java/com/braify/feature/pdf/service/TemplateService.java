package com.braify.feature.pdf.service;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.model.TemplateVersion;
import com.braify.feature.pdf.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
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
        log.debug("findAll templates for user='{}' org='{}'", currentUser.getEmail(), currentUser.getOrganizationId());
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            return templateRepository.findByDeletedFalseOrderByUpdatedAtDesc();
        }
        return templateRepository.findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(
                currentUser.getOrganizationId());
    }

    /** Throws if the template is deleted, not found, or inaccessible to the user. */
    public Template findById(String id, AppUser currentUser) {
        log.debug("findById template id='{}' user='{}'", id, currentUser.getEmail());
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            return templateRepository.findByIdAndDeletedFalse(id)
                    .orElseThrow(() -> {
                        log.warn("Template not found: id='{}'", id);
                        return new RuntimeException("Template not found: " + id);
                    });
        }
        return templateRepository.findByIdAndOrganizationIdAndDeletedFalse(
                        id, currentUser.getOrganizationId())
                .orElseThrow(() -> {
                    log.warn("Template not found: id='{}' org='{}'", id, currentUser.getOrganizationId());
                    return new RuntimeException("Template not found: " + id);
                });
    }

    // ── Create ───────────────────────────────────────────────────────────────

    public Template create(Template template, AppUser currentUser) {
        log.info("Creating template name='{}' by '{}'", template.getName(), currentUser.getEmail());
        template.setId(null);
        template.setDeleted(false);
        template.setCurrentVersion(0);
        template.setOrganizationId(currentUser.getOrganizationId());
        template.setCreatedBy(currentUser.getId());
        template.setCode(normalizeCode(template.getCode()));
        if (template.getCode() != null)
            assertCodeAvailable(currentUser.getOrganizationId(), template.getCode());
        template.setPlaceholders(
                placeholderService.extractPlaceholders(template.getHtmlContent()));

        Template saved = templateRepository.save(template);

        // Version snapshot v1
        versionService.snapshot(saved, "Initial version", currentUser.getId());
        templateRepository.save(saved);   // persist currentVersion=1

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(), null,
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Template created: id='{}' name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    // ── Update ───────────────────────────────────────────────────────────────

    public Template update(String id, Template incoming, AppUser currentUser) {
        log.info("Updating template id='{}' by '{}'", id, currentUser.getEmail());
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
        // Only touch the code when a new, non-blank one is supplied (so ordinary builder saves,
        // which omit it, never wipe an existing code). A changed code is uniqueness-checked.
        String incomingCode = normalizeCode(incoming.getCode());
        if (incomingCode != null && !incomingCode.equals(existing.getCode())) {
            assertCodeAvailable(existing.getOrganizationId(), incomingCode);
            existing.setCode(incomingCode);
        }
        existing.setPlaceholders(
                placeholderService.extractPlaceholders(incoming.getHtmlContent()));

        Template saved = templateRepository.save(existing);

        versionService.snapshot(saved, "Updated", currentUser.getId());
        templateRepository.save(saved);   // persist new currentVersion

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(), changes,
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Template '{}' updated to version {}", id, saved.getCurrentVersion());
        return saved;
    }

    // ── Clone ────────────────────────────────────────────────────────────────

    /**
     * Duplicates a template into the caller's org — copies all content, appends {@code _clone}
     * to the code and name (or uses the supplied overrides). Rejects a duplicate code with 400
     * so the user can rename.
     */
    public Template clone(String id, String codeOverride, String nameOverride, AppUser currentUser) {
        log.info("Cloning template id='{}' by '{}'", id, currentUser.getEmail());
        Template src = findById(id, currentUser);
        assertAccess(currentUser, src.getOrganizationId());

        Template copy = new Template();
        copy.setName(nameOverride != null && !nameOverride.isBlank()
                ? nameOverride.trim() : src.getName() + "_clone");
        copy.setDescription(src.getDescription());
        copy.setType(src.getType());
        copy.setHtmlContent(src.getHtmlContent());
        copy.setCssContent(src.getCssContent());
        copy.setPageSize(src.getPageSize());
        copy.setOrientation(src.getOrientation());
        copy.setMarginTop(src.getMarginTop());
        copy.setMarginBottom(src.getMarginBottom());
        copy.setMarginLeft(src.getMarginLeft());
        copy.setMarginRight(src.getMarginRight());
        copy.setPlaceholders(src.getPlaceholders());
        copy.setGjsData(src.getGjsData());
        copy.setOrganizationId(currentUser.getOrganizationId());
        copy.setCreatedBy(currentUser.getId());

        String base = (codeOverride != null && !codeOverride.isBlank())
                ? codeOverride.trim()
                : (src.getCode() != null && !src.getCode().isBlank() ? src.getCode() + "_clone" : null);
        copy.setCode(normalizeCode(base));
        if (copy.getCode() != null)
            assertCodeAvailable(currentUser.getOrganizationId(), copy.getCode());

        Template saved = templateRepository.save(copy);
        versionService.snapshot(saved, "Cloned from " + src.getName(), currentUser.getId());
        templateRepository.save(saved);

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(), Map.of("clonedFrom", src.getId()),
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Template cloned: '{}' → '{}' (code='{}')", src.getId(), saved.getId(), saved.getCode());
        return saved;
    }

    private String normalizeCode(String code) {
        return (code == null || code.isBlank()) ? null : code.trim();
    }

    private void assertCodeAvailable(String orgId, String code) {
        if (templateRepository.existsByOrganizationIdAndCodeAndDeletedFalse(orgId, code))
            throw new IllegalArgumentException(
                    "Template code \"" + code + "\" already exists in your organisation. Please choose a different code.");
    }

    // ── Soft Delete ──────────────────────────────────────────────────────────

    public void delete(String id, AppUser currentUser) {
        log.info("Deleting template id='{}' by '{}'", id, currentUser.getEmail());
        if (currentUser.getRole() == AppUser.Role.USER) {
            log.warn("Delete rejected — USER role cannot delete templates, caller='{}'", currentUser.getEmail());
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
        log.info("Template '{}' deleted", id);
    }

    // ── Restore version ──────────────────────────────────────────────────────

    public Template restoreVersion(String templateId, int versionNumber, AppUser currentUser) {
        log.info("Restoring template id='{}' to version {} by '{}'", templateId, versionNumber, currentUser.getEmail());
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

        versionService.snapshot(saved, "Restored from v" + versionNumber, currentUser.getId());
        templateRepository.save(saved);

        auditLogService.log(
                saved.getId(), saved.getName(),
                AuditLog.Action.RESTORED, AuditLog.ResourceType.TEMPLATE,
                saved.getCurrentVersion(),
                Map.of("restoredFromVersion", versionNumber),
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Template '{}' restored to version {} (new version={})", templateId, versionNumber, saved.getCurrentVersion());
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
