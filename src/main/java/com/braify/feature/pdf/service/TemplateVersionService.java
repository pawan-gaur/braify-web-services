package com.braify.feature.pdf.service;

import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.model.TemplateVersion;
import com.braify.feature.pdf.repository.TemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateVersionService {

    private final TemplateVersionRepository versionRepository;

    /**
     * Snapshot the current state of a template as a new version.
     * The version number is  max(existing) + 1,  or 1 for brand-new templates.
     *
     * @param template    the template to snapshot
     * @param changeNote  human-readable note describing the change
     * @param createdById userId of the AppUser triggering the snapshot
     */
    public TemplateVersion snapshot(Template template, String changeNote, String createdById) {
        int nextVersion = versionRepository.countByTemplateId(template.getId()) + 1;

        TemplateVersion v = TemplateVersion.builder()
                .templateId(template.getId())
                .version(nextVersion)
                .name(template.getName())
                .description(template.getDescription())
                .htmlContent(template.getHtmlContent())
                .cssContent(template.getCssContent())
                .gjsData(template.getGjsData())
                .pageSize(template.getPageSize())
                .orientation(template.getOrientation())
                .marginTop(template.getMarginTop())
                .marginBottom(template.getMarginBottom())
                .marginLeft(template.getMarginLeft())
                .marginRight(template.getMarginRight())
                .placeholders(template.getPlaceholders())
                .savedBy(createdById != null ? createdById : "system")
                .createdBy(createdById)
                .changeNote(changeNote)
                .build();

        TemplateVersion saved = versionRepository.save(v);

        // Keep currentVersion in sync on the template itself
        template.setCurrentVersion(nextVersion);
        log.debug("Template '{}' snapshot saved as version {}", template.getId(), nextVersion);
        return saved;
    }

    /** Backward-compatible overload (no caller context). */
    public TemplateVersion snapshot(Template template, String changeNote) {
        return snapshot(template, changeNote, null);
    }

    /** List all versions for a template, newest first. */
    public List<TemplateVersion> getVersions(String templateId) {
        log.debug("getVersions templateId='{}'", templateId);
        return versionRepository.findByTemplateIdOrderByVersionDesc(templateId);
    }

    /**
     * Retrieve a specific version.
     *
     * @throws RuntimeException if not found
     */
    public TemplateVersion getVersion(String templateId, int version) {
        log.debug("getVersion templateId='{}' version={}", templateId, version);
        return versionRepository
                .findByTemplateIdAndVersion(templateId, version)
                .orElseThrow(() -> {
                    log.warn("Template version {} not found for id='{}'", version, templateId);
                    return new RuntimeException(
                            "Version " + version + " not found for template " + templateId);
                });
    }
}
