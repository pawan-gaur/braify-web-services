package com.braify.config.infra.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.Attachment;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class EmailDispatcher {

    private static final String DEFAULT_SUBJECT = "Eden Care Email";

    @Value("${resend.api-key:}")
    private String apiKey;

    @Value("${resend.from-email:}")
    private String fromEmail;

    @Value("${emailDispatcher.template-bucket-url:}")
    private String templateBucketUrl;

    public CreateEmailResponse sendEmail(String email, String templateName, Map<String, Object> placeholders) {
        return sendTemplatedEmail(email, templateName, DEFAULT_SUBJECT, placeholders, Collections.emptyList());
    }

    public CreateEmailResponse sendEmail(String email,
                                         String templateName,
                                         Map<String, Object> placeholders,
                                         byte[] attachmentData,
                                         String attachmentFileName) {
        List<Attachment> attachments = new ArrayList<>();
        buildAttachment(attachmentFileName, attachmentData).ifPresent(attachments::add);
        return sendTemplatedEmail(email, templateName, DEFAULT_SUBJECT, placeholders, attachments);
    }

    public CreateEmailResponse sendMultipleAttachmentEmail(String email,
                                                          String templateName,
                                                          Map<String, Object> placeholders,
                                                          Map<String, byte[]> attachmentsData) {
        List<Attachment> attachments = new ArrayList<>();
        if (attachmentsData != null) {
            attachmentsData.forEach((fileName, fileBytes) ->
                    buildAttachment(fileName, fileBytes).ifPresent(attachments::add));
        }
        return sendTemplatedEmail(email, templateName, DEFAULT_SUBJECT, placeholders, attachments);
    }

    public CreateEmailResponse sendCareEmail(String email, String templateName, Map<String, Object> placeholders) {
        return sendTemplatedEmail(email, templateName, DEFAULT_SUBJECT, placeholders, Collections.emptyList());
    }

    public CreateEmailResponse sendHtmlEmail(String email,
                                             String subject,
                                             String html,
                                             Map<String, Object> placeholders) {
        return sendHtmlEmail(email, subject, html, placeholders, null);
    }

    /**
     * Sends an HTML email with an optional sender display-name override.
     * The email address portion of {@code resend.from-email} is preserved;
     * only the "Display Name" part is replaced with {@code senderDisplayName}.
     *
     * @param senderDisplayName  display name shown in the "From:" field (e.g. the org name).
     *                           Pass {@code null} to use the value from {@code resend.from-email} unchanged.
     */
    public CreateEmailResponse sendHtmlEmail(String email,
                                             String subject,
                                             String html,
                                             Map<String, Object> placeholders,
                                             String senderDisplayName) {
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject,
                safePlaceholders(placeholders));
        String renderedHtml = replacePlaceholders(html, safePlaceholders(placeholders));

        return send(email, renderedSubject, renderedHtml, Collections.emptyList(), senderDisplayName);
    }

    /**
     * Sends an HTML email (no S3 template) with a single binary attachment.
     * Use this when the HTML is already built inline and you also need to attach a file.
     */
    public CreateEmailResponse sendHtmlEmailWithAttachment(String email,
                                                           String subject,
                                                           String html,
                                                           Map<String, Object> placeholders,
                                                           byte[] attachmentData,
                                                           String attachmentFileName) {
        return sendHtmlEmailWithAttachment(
                email, subject, html, placeholders, attachmentData, attachmentFileName, null);
    }

    /**
     * Sends an HTML email with a single binary attachment and an optional sender display-name override.
     *
     * @param senderDisplayName  display name shown in the "From:" field (e.g. the org name).
     *                           Pass {@code null} to use the value from {@code resend.from-email} unchanged.
     */
    public CreateEmailResponse sendHtmlEmailWithAttachment(String email,
                                                           String subject,
                                                           String html,
                                                           Map<String, Object> placeholders,
                                                           byte[] attachmentData,
                                                           String attachmentFileName,
                                                           String senderDisplayName) {
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject,
                safePlaceholders(placeholders));
        String renderedHtml = replacePlaceholders(html, safePlaceholders(placeholders));

        List<Attachment> attachments = new ArrayList<>();
        buildAttachment(attachmentFileName, attachmentData).ifPresent(attachments::add);

        return send(email, renderedSubject, renderedHtml, attachments, senderDisplayName);
    }

    private CreateEmailResponse sendTemplatedEmail(String email,
                                                   String templateName,
                                                   String subject,
                                                   Map<String, Object> placeholders,
                                                   List<Attachment> attachments) {
        String htmlTemplateContent = loadHtmlContentFromS3(templateName);
        String renderedHtml = replacePlaceholders(htmlTemplateContent, safePlaceholders(placeholders));
        String renderedSubject = replacePlaceholders(
                isBlank(subject) ? DEFAULT_SUBJECT : subject,
                safePlaceholders(placeholders));

        return send(email, renderedSubject, renderedHtml, attachments);
    }

    private CreateEmailResponse send(String email,
                                     String subject,
                                     String html,
                                     List<Attachment> attachments) {
        return send(email, subject, html, attachments, null);
    }

    private CreateEmailResponse send(String email,
                                     String subject,
                                     String html,
                                     List<Attachment> attachments,
                                     String senderDisplayName) {
        validateEmailSettings();

        if (isBlank(email)) {
            throw new IllegalArgumentException("Recipient email is required");
        }
        if (isBlank(html)) {
            throw new IllegalArgumentException("Email HTML content is required");
        }

        CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                .from(buildFrom(senderDisplayName))
                .to(email)
                .subject(subject)
                .html(html);

        if (attachments != null && !attachments.isEmpty()) {
            builder.attachments(attachments);
        }

        try {
            CreateEmailResponse response = new Resend(apiKey).emails().send(builder.build());
            log.info("Email sent to {} with Resend id {}", email, response != null ? response.getId() : null);
            return response;
        } catch (ResendException e) {
            log.error("Email sent failed to {} with Resend error :  {}", email, e.getMessage());
            throw new IllegalStateException("Could not send email to " + email + ": " + e.getMessage(), e);
        }
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

    private java.util.Optional<Attachment> buildAttachment(String fileName, byte[] fileBytes) {
        if (isBlank(fileName) || fileBytes == null || fileBytes.length == 0) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(Attachment.builder()
                .fileName(fileName)
                .content(Base64.getEncoder().encodeToString(fileBytes))
                .build());
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

    /**
     * Builds the "From" string by keeping the email address from {@code resend.from-email}
     * and replacing the display name portion with {@code displayName}.
     *
     * <p>Examples:
     * <pre>
     *   fromEmail = "Braify &lt;no-reply@example.com&gt;"
     *   displayName = "Acme Corp"  →  "Acme Corp &lt;no-reply@example.com&gt;"
     *   displayName = null          →  "Braify &lt;no-reply@example.com&gt;"  (unchanged)
     * </pre>
     */
    private String buildFrom(String displayName) {
        if (isBlank(displayName)) return fromEmail;

        // Extract the bare email address from "Display Name <addr>" or "addr"
        String address;
        if (fromEmail.contains("<") && fromEmail.contains(">")) {
            address = fromEmail.substring(fromEmail.indexOf('<') + 1, fromEmail.lastIndexOf('>')).trim();
        } else {
            address = fromEmail.trim();
        }
        return displayName.trim() + " <" + address + ">";
    }

    private void validateEmailSettings() {
        if (isBlank(apiKey)) {
            throw new IllegalStateException("resend.api-key is not configured");
        }
        if (isBlank(fromEmail)) {
            throw new IllegalStateException("resend.from-email is not configured");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
