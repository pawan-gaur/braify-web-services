package com.braify.feature.email.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.email.dto.SendEmailRequest;
import com.braify.feature.email.dto.SendEmailResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.model.EmailTemplateVersion;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.pdf.service.PlaceholderService;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository      emailTemplateRepository;
    private final PlaceholderService           placeholderService;
    private final EmailTemplateVersionService  versionService;
    private final AuditLogService              auditLogService;
    private final EmailDispatcher              emailDispatcher;

    private static final AuditLog.ResourceType RESOURCE = AuditLog.ResourceType.EMAIL_TEMPLATE;

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<EmailTemplate> findAll(AppUser currentUser) {
        log.debug("findAll email templates for user='{}' org='{}'", currentUser.getEmail(), currentUser.getOrganizationId());
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            return emailTemplateRepository.findByDeletedFalseOrderByUpdatedAtDesc();
        }
        return emailTemplateRepository.findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(
                currentUser.getOrganizationId());
    }

    public EmailTemplate findById(String id, AppUser currentUser) {
        log.debug("findById email template id='{}' user='{}'", id, currentUser.getEmail());
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            return emailTemplateRepository.findByIdAndDeletedFalse(id)
                    .orElseThrow(() -> {
                        log.warn("Email template not found: id='{}'", id);
                        return new RuntimeException("Email template not found: " + id);
                    });
        }
        return emailTemplateRepository.findByIdAndOrganizationIdAndDeletedFalse(
                        id, currentUser.getOrganizationId())
                .orElseThrow(() -> {
                    log.warn("Email template not found: id='{}' org='{}'", id, currentUser.getOrganizationId());
                    return new RuntimeException("Email template not found: " + id);
                });
    }

    // ── Create ───────────────────────────────────────────────────────────────

    public EmailTemplate create(EmailTemplate template, AppUser currentUser) {
        log.info("Creating email template name='{}' by '{}'", template.getName(), currentUser.getEmail());
        template.setId(null);
        template.setDeleted(false);
        template.setCurrentVersion(0);
        template.setOrganizationId(currentUser.getOrganizationId());
        template.setPlaceholders(
                placeholderService.extractPlaceholders(template.getHtmlContent()));

        EmailTemplate saved = emailTemplateRepository.save(template);

        versionService.snapshot(saved, "Initial version");
        emailTemplateRepository.save(saved);

        auditLogService.log(saved.getId(), saved.getName(),
                AuditLog.Action.CREATED, RESOURCE, saved.getCurrentVersion(), null,
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Email template created: id='{}' name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    // ── Update ───────────────────────────────────────────────────────────────

    public EmailTemplate update(String id, EmailTemplate incoming, AppUser currentUser) {
        log.info("Updating email template id='{}' by '{}'", id, currentUser.getEmail());
        EmailTemplate existing = findById(id, currentUser);
        assertAccess(currentUser, existing.getOrganizationId());

        Map<String, Object> changes = buildChanges(existing, incoming);

        existing.setName(incoming.getName());
        existing.setDescription(incoming.getDescription());
        existing.setSubject(incoming.getSubject());
        existing.setPreviewText(incoming.getPreviewText());
        existing.setFromName(incoming.getFromName());
        existing.setHtmlContent(incoming.getHtmlContent());
        existing.setCssContent(incoming.getCssContent());
        existing.setGjsData(incoming.getGjsData());
        existing.setPlaceholders(
                placeholderService.extractPlaceholders(incoming.getHtmlContent()));

        EmailTemplate saved = emailTemplateRepository.save(existing);

        versionService.snapshot(saved, "Updated");
        emailTemplateRepository.save(saved);

        auditLogService.log(saved.getId(), saved.getName(),
                AuditLog.Action.UPDATED, RESOURCE, saved.getCurrentVersion(), changes,
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Email template '{}' updated to version {}", id, saved.getCurrentVersion());
        return saved;
    }

    // ── Soft Delete ──────────────────────────────────────────────────────────

    public void delete(String id, AppUser currentUser) {
        log.info("Deleting email template id='{}' by '{}'", id, currentUser.getEmail());
        if (currentUser.getRole() == AppUser.Role.USER) {
            log.warn("Delete rejected — USER role cannot delete email templates, caller='{}'", currentUser.getEmail());
            throw new RuntimeException("Users cannot delete email templates");
        }
        EmailTemplate existing = findById(id, currentUser);
        assertAccess(currentUser, existing.getOrganizationId());

        existing.setDeleted(true);
        existing.setDeletedAt(LocalDateTime.now());
        emailTemplateRepository.save(existing);

        auditLogService.log(existing.getId(), existing.getName(),
                AuditLog.Action.DELETED, RESOURCE, existing.getCurrentVersion(), null,
                currentUser.getEmail(), existing.getOrganizationId());
        log.info("Email template '{}' deleted", id);
    }

    // ── Restore version ──────────────────────────────────────────────────────

    public EmailTemplate restoreVersion(String id, int versionNumber, AppUser currentUser) {
        log.info("Restoring email template id='{}' to version {} by '{}'", id, versionNumber, currentUser.getEmail());
        EmailTemplate existing = findById(id, currentUser);
        assertAccess(currentUser, existing.getOrganizationId());

        EmailTemplateVersion snapshot = versionService.getVersion(id, versionNumber);

        existing.setName(snapshot.getName());
        existing.setDescription(snapshot.getDescription());
        existing.setSubject(snapshot.getSubject());
        existing.setPreviewText(snapshot.getPreviewText());
        existing.setFromName(snapshot.getFromName());
        existing.setHtmlContent(snapshot.getHtmlContent());
        existing.setCssContent(snapshot.getCssContent());
        existing.setGjsData(snapshot.getGjsData());
        existing.setPlaceholders(snapshot.getPlaceholders());

        EmailTemplate saved = emailTemplateRepository.save(existing);

        versionService.snapshot(saved, "Restored from v" + versionNumber);
        emailTemplateRepository.save(saved);

        auditLogService.log(saved.getId(), saved.getName(),
                AuditLog.Action.RESTORED, RESOURCE, saved.getCurrentVersion(),
                Map.of("restoredFromVersion", versionNumber),
                currentUser.getEmail(), saved.getOrganizationId());

        log.info("Email template '{}' restored to version {} (new version={})", id, versionNumber, saved.getCurrentVersion());
        return saved;
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    /**
     * Sends the rendered HTML of an email template to the specified recipient
     * via Resend, substituting any supplied placeholder values, and writes a
     * SENT audit-log entry.
     *
     * @param id      Email template ID
     * @param req     Recipient, optional subject override, placeholder map
     * @param caller  Authenticated user performing the action
     * @return        Resend message ID + metadata
     */
    public SendEmailResponse sendEmail(String id, SendEmailRequest req, AppUser caller) {
        log.info("Sending email from template id='{}' to='{}' by '{}'", id, req.getTo(), caller.getEmail());
        if (req.getTo() == null || req.getTo().isBlank()) {
            log.warn("Send email rejected for template '{}': no recipient address", id);
            throw new IllegalArgumentException("Recipient email address is required");
        }

        EmailTemplate template = findById(id, caller);

        // Resolve subject: caller override → template subject → generic fallback
        String subject = (req.getSubject() != null && !req.getSubject().isBlank())
                ? req.getSubject()
                : (template.getSubject() != null && !template.getSubject().isBlank())
                        ? template.getSubject()
                        : "Message from " + (template.getFromName() != null ? template.getFromName() : "PDF Builder");

        // Convert Map<String,String> → Map<String,Object> for EmailDispatcher
        Map<String, Object> placeholders = req.getPlaceholders() != null
                ? Collections.unmodifiableMap(req.getPlaceholders())
                : Collections.emptyMap();

        CreateEmailResponse resendResponse =
                emailDispatcher.sendHtmlEmail(req.getTo(), subject, template.getHtmlContent(), placeholders);

        // Audit the send event
        auditLogService.log(
                template.getId(),
                template.getName(),
                AuditLog.Action.SENT,
                RESOURCE,
                template.getCurrentVersion(),
                Map.of("to", req.getTo(), "subject", subject),
                caller.getEmail(), template.getOrganizationId());

        log.info("Email sent via template '{}' to='{}' subject='{}'", id, req.getTo(), subject);
        return SendEmailResponse.builder()
                .messageId(resendResponse != null ? resendResponse.getId() : null)
                .to(req.getTo())
                .subject(subject)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertAccess(AppUser user, String resourceOrgId) {
        if (user.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (resourceOrgId != null && !resourceOrgId.equals(user.getOrganizationId())) {
            throw new RuntimeException("Access denied");
        }
    }

    private Map<String, Object> buildChanges(EmailTemplate old, EmailTemplate incoming) {
        Map<String, Object> changes = new LinkedHashMap<>();
        diffField(changes, "name",        old.getName(),        incoming.getName());
        diffField(changes, "subject",     old.getSubject(),     incoming.getSubject());
        diffField(changes, "fromName",    old.getFromName(),    incoming.getFromName());
        diffField(changes, "description", old.getDescription(), incoming.getDescription());
        return changes.isEmpty() ? null : changes;
    }

    private void diffField(Map<String, Object> out, String field,
                           Object oldVal, Object newVal) {
        if (oldVal == null && newVal == null) return;
        if (oldVal != null && oldVal.equals(newVal)) return;
        out.put(field, Map.of(
                "from", oldVal != null ? oldVal : "",
                "to",   newVal != null ? newVal : ""));
    }
}
