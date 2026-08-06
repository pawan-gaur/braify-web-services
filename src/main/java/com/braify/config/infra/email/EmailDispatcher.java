package com.braify.config.infra.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Central outbound-email entry point.
 *
 * <p>The provider (Resend / SendGrid / Mailgun / SMTP) and credentials are resolved
 * per-organisation by {@link EmailConfigResolver}: the org's own config, else the
 * platform-admin default, else the built-in Resend credentials from
 * {@code application.yml}. Every public method therefore takes an {@code orgId}
 * (nullable — {@code null} skips the org layer and uses the platform/global default).
 *
 * <p>Overloads that accept an {@code htmlTransform} apply it to the final HTML
 * <em>after</em> placeholder substitution. Bulk email uses this to inject its
 * open-tracking pixel and rewrite links (which may themselves contain placeholders,
 * so the transform must run once the real URLs are resolved). Pass {@code null} for none.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDispatcher {

    private static final String DEFAULT_SUBJECT = "Braify Email";

    private final EmailConfigResolver  configResolver;
    private final EmailSenderFactory   senderFactory;

    @Value("${emailDispatcher.template-bucket-url:}")
    private String templateBucketUrl;

    // ── S3-template sends ──────────────────────────────────────────────────────

    public EmailSendResult sendEmail(String orgId, String email, String templateName,
                                     Map<String, Object> placeholders) {
        return sendTemplatedEmail(orgId, email, templateName, DEFAULT_SUBJECT, placeholders, Collections.emptyList());
    }

    public EmailSendResult sendEmail(String orgId, String email, String templateName,
                                     Map<String, Object> placeholders,
                                     byte[] attachmentData, String attachmentFileName) {
        List<OutboundEmail.Attachment> attachments = new ArrayList<>();
        buildAttachment(attachmentFileName, attachmentData).ifPresent(attachments::add);
        return sendTemplatedEmail(orgId, email, templateName, DEFAULT_SUBJECT, placeholders, attachments);
    }

    public EmailSendResult sendMultipleAttachmentEmail(String orgId, String email, String templateName,
                                                       Map<String, Object> placeholders,
                                                       Map<String, byte[]> attachmentsData) {
        List<OutboundEmail.Attachment> attachments = new ArrayList<>();
        if (attachmentsData != null) {
            attachmentsData.forEach((fileName, fileBytes) ->
                    buildAttachment(fileName, fileBytes).ifPresent(attachments::add));
        }
        return sendTemplatedEmail(orgId, email, templateName, DEFAULT_SUBJECT, placeholders, attachments);
    }

    public EmailSendResult sendCareEmail(String orgId, String email, String templateName,
                                         Map<String, Object> placeholders) {
        return sendTemplatedEmail(orgId, email, templateName, DEFAULT_SUBJECT, placeholders, Collections.emptyList());
    }

    // ── Inline HTML sends ──────────────────────────────────────────────────────

    public EmailSendResult sendHtmlEmail(String orgId, String email, String subject,
                                         String html, Map<String, Object> placeholders) {
        return sendHtmlEmail(orgId, email, subject, html, placeholders, null);
    }

    /**
     * Sends an HTML email with an optional sender display-name override.
     * The address of the resolved config is preserved; only the display name is swapped.
     *
     * @param senderDisplayName display name for the "From:" field (e.g. the org name);
     *                          {@code null} uses the resolved config's own name.
     */
    public EmailSendResult sendHtmlEmail(String orgId, String email, String subject,
                                         String html, Map<String, Object> placeholders,
                                         String senderDisplayName) {
        return sendHtmlEmail(orgId, email, null, subject, html, placeholders, senderDisplayName);
    }

    /** Sends an HTML email with optional CC recipients and a display-name override. */
    public EmailSendResult sendHtmlEmail(String orgId, String email, List<String> ccEmails,
                                         String subject, String html, Map<String, Object> placeholders,
                                         String senderDisplayName) {
        return sendHtmlEmail(orgId, email, ccEmails, subject, html, placeholders, senderDisplayName, null);
    }

    /**
     * Full inline-HTML overload with an optional {@code htmlTransform} applied to the
     * final HTML after placeholder substitution (e.g. bulk-email open-pixel + link rewrite).
     */
    public EmailSendResult sendHtmlEmail(String orgId, String email, List<String> ccEmails,
                                         String subject, String html, Map<String, Object> placeholders,
                                         String senderDisplayName, UnaryOperator<String> htmlTransform) {
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject, safePlaceholders(placeholders));
        String renderedHtml = applyTransform(
                replacePlaceholders(html, safePlaceholders(placeholders)), htmlTransform);
        return send(orgId, email, ccEmails, renderedSubject, renderedHtml, Collections.emptyList(), senderDisplayName);
    }

    // ── Inline HTML sends with attachments ─────────────────────────────────────

    public EmailSendResult sendHtmlEmailWithAttachment(String orgId, String email, String subject,
                                                       String html, Map<String, Object> placeholders,
                                                       byte[] attachmentData, String attachmentFileName) {
        return sendHtmlEmailWithAttachment(orgId, email, subject, html, placeholders,
                attachmentData, attachmentFileName, null);
    }

    public EmailSendResult sendHtmlEmailWithAttachment(String orgId, String email, String subject,
                                                       String html, Map<String, Object> placeholders,
                                                       byte[] attachmentData, String attachmentFileName,
                                                       String senderDisplayName) {
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject, safePlaceholders(placeholders));
        String renderedHtml = replacePlaceholders(html, safePlaceholders(placeholders));

        List<OutboundEmail.Attachment> attachments = new ArrayList<>();
        buildAttachment(attachmentFileName, attachmentData).ifPresent(attachments::add);

        return send(orgId, email, null, renderedSubject, renderedHtml, attachments, senderDisplayName);
    }

    /** Sends an HTML email with multiple binary attachments and optional CC recipients. */
    public EmailSendResult sendHtmlEmailWithAttachments(String orgId, String email, List<String> ccEmails,
                                                        String subject, String html, Map<String, Object> placeholders,
                                                        Map<String, byte[]> attachmentsMap, String senderDisplayName) {
        return sendHtmlEmailWithAttachments(orgId, email, ccEmails, subject, html, placeholders,
                attachmentsMap, senderDisplayName, null);
    }

    /** As above, with an optional {@code htmlTransform} applied after placeholder substitution. */
    public EmailSendResult sendHtmlEmailWithAttachments(String orgId, String email, List<String> ccEmails,
                                                        String subject, String html, Map<String, Object> placeholders,
                                                        Map<String, byte[]> attachmentsMap, String senderDisplayName,
                                                        UnaryOperator<String> htmlTransform) {
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject, safePlaceholders(placeholders));
        String renderedHtml = applyTransform(
                replacePlaceholders(html, safePlaceholders(placeholders)), htmlTransform);

        List<OutboundEmail.Attachment> attachments = new ArrayList<>();
        if (attachmentsMap != null) {
            attachmentsMap.forEach((fileName, fileBytes) ->
                    buildAttachment(fileName, fileBytes).ifPresent(attachments::add));
        }
        return send(orgId, email, ccEmails, renderedSubject, renderedHtml, attachments, senderDisplayName);
    }

    /** Convenience overload with no CC recipients. */
    public EmailSendResult sendHtmlEmailWithAttachments(String orgId, String email, String subject,
                                                        String html, Map<String, Object> placeholders,
                                                        Map<String, byte[]> attachmentsMap, String senderDisplayName) {
        return sendHtmlEmailWithAttachments(orgId, email, null, subject, html, placeholders,
                attachmentsMap, senderDisplayName);
    }

    private String applyTransform(String html, UnaryOperator<String> htmlTransform) {
        return htmlTransform == null ? html : htmlTransform.apply(html);
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private EmailSendResult sendTemplatedEmail(String orgId, String email, String templateName,
                                               String subject, Map<String, Object> placeholders,
                                               List<OutboundEmail.Attachment> attachments) {
        String htmlTemplateContent = loadHtmlContentFromS3(templateName);
        String renderedHtml = replacePlaceholders(htmlTemplateContent, safePlaceholders(placeholders));
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject, safePlaceholders(placeholders));
        return send(orgId, email, null, renderedSubject, renderedHtml, attachments, null);
    }

    /** The single choke point: resolve config, build the message, dispatch to the provider adapter. */
    private EmailSendResult send(String orgId, String email, List<String> ccEmails,
                                 String subject, String html,
                                 List<OutboundEmail.Attachment> attachments, String senderDisplayName) {
        if (isBlank(email)) {
            throw new IllegalArgumentException("Recipient email is required");
        }
        if (isBlank(html)) {
            throw new IllegalArgumentException("Email HTML content is required");
        }

        ResolvedEmailConfig cfg = configResolver.resolve(orgId);

        String fromName = !isBlank(senderDisplayName) ? senderDisplayName.trim() : cfg.fromName();

        OutboundEmail message = new OutboundEmail(
                cfg.fromEmail(), fromName, cfg.replyTo(),
                email, ccEmails, subject, html, attachments);

        EmailSendResult result = senderFactory.get(cfg.provider()).send(cfg, message);
        log.debug("Email dispatched via {} (source={}) to {}", cfg.provider(), cfg.source(), email);
        return result;
    }

    private String loadHtmlContentFromS3(String templateName) {
        if (isBlank(templateBucketUrl)) {
            throw new IllegalStateException("emailDispatcher.template-bucket-url is not configured");
        }
        if (isBlank(templateName)) {
            throw new IllegalArgumentException("Template name is required");
        }

        String normalizedBaseUrl = templateBucketUrl.endsWith("/") ? templateBucketUrl : templateBucketUrl + "/";
        URI templateUri = UriComponentsBuilder
                .fromUriString(normalizedBaseUrl)
                .path(templateName + ".html")
                .build()
                .toUri();

        try (var in = templateUri.toURL().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load email template " + templateName + " from " + templateUri, e);
        }
    }

    private Optional<OutboundEmail.Attachment> buildAttachment(String fileName, byte[] fileBytes) {
        if (isBlank(fileName) || fileBytes == null || fileBytes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new OutboundEmail.Attachment(fileName, fileBytes));
    }

    private String replacePlaceholders(String template, Map<String, Object> values) {
        String rendered = Objects.toString(template, "");
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", Objects.toString(entry.getValue(), ""));
        }
        return rendered;
    }

    private Map<String, Object> safePlaceholders(Map<String, Object> placeholders) {
        return placeholders != null ? placeholders : Collections.emptyMap();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
