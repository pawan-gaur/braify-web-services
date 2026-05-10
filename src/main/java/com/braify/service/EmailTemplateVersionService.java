package com.braify.service;

import com.braify.model.EmailTemplate;
import com.braify.model.EmailTemplateVersion;
import com.braify.repository.EmailTemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmailTemplateVersionService {

    private final EmailTemplateVersionRepository versionRepository;

    /** Snapshot the current state of an email template as a new version. */
    public EmailTemplateVersion snapshot(EmailTemplate template, String changeNote) {
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
                .savedBy("system")
                .changeNote(changeNote)
                .build();

        EmailTemplateVersion saved = versionRepository.save(v);
        template.setCurrentVersion(nextVersion);
        return saved;
    }

    /** All versions for an email template, newest first. */
    public List<EmailTemplateVersion> getVersions(String emailTemplateId) {
        return versionRepository.findByEmailTemplateIdOrderByVersionDesc(emailTemplateId);
    }

    public EmailTemplateVersion getVersion(String emailTemplateId, int version) {
        return versionRepository
                .findByEmailTemplateIdAndVersion(emailTemplateId, version)
                .orElseThrow(() -> new RuntimeException(
                        "Email template version " + version + " not found for " + emailTemplateId));
    }
}
