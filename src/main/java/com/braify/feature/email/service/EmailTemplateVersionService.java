package com.braify.feature.email.service;

import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.model.EmailTemplateVersion;
import com.braify.feature.email.repository.EmailTemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTemplateVersionService {

    private final EmailTemplateVersionRepository versionRepository;

    /**
     * Snapshot the current state of an email template as a new version.
     *
     * @param template    the email template to snapshot
     * @param changeNote  human-readable note describing the change
     * @param createdById userId of the AppUser triggering the snapshot
     */
    public EmailTemplateVersion snapshot(EmailTemplate template, String changeNote, String createdById) {
        int nextVersion = versionRepository.countByEmailTemplateId(template.getId()) + 1;

        EmailTemplateVersion v = EmailTemplateVersion.builder()
                .emailTemplateId(template.getId())
                .version(nextVersion)
                .name(template.getName())
                .description(template.getDescription())
                .subject(template.getSubject())
                .previewText(template.getPreviewText())
                .fromName(template.getFromName())
                .htmlContent(template.getHtmlContent())
                .cssContent(template.getCssContent())
                .gjsData(template.getGjsData())
                .placeholders(template.getPlaceholders())
                .savedBy(createdById != null ? createdById : "system")
                .createdBy(createdById)
                .changeNote(changeNote)
                .build();

        EmailTemplateVersion saved = versionRepository.save(v);
        template.setCurrentVersion(nextVersion);
        log.debug("Email template '{}' snapshot saved as version {}", template.getId(), nextVersion);
        return saved;
    }

    /** Backward-compatible overload (no caller context). */
    public EmailTemplateVersion snapshot(EmailTemplate template, String changeNote) {
        return snapshot(template, changeNote, null);
    }

    /** All versions for an email template, newest first. */
    public List<EmailTemplateVersion> getVersions(String emailTemplateId) {
        log.debug("getVersions emailTemplateId='{}'", emailTemplateId);
        return versionRepository.findByEmailTemplateIdOrderByVersionDesc(emailTemplateId);
    }

    public EmailTemplateVersion getVersion(String emailTemplateId, int version) {
        log.debug("getVersion emailTemplateId='{}' version={}", emailTemplateId, version);
        return versionRepository
                .findByEmailTemplateIdAndVersion(emailTemplateId, version)
                .orElseThrow(() -> {
                    log.warn("Email template version {} not found for id='{}'", version, emailTemplateId);
                    return new RuntimeException(
                            "Email template version " + version + " not found for " + emailTemplateId);
                });
    }
}
