package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends via the SendGrid v3 Mail Send REST API (no SDK dependency). */
@Slf4j
@Component
public class SendGridEmailSender implements EmailSender {

    private static final String ENDPOINT = "https://api.sendgrid.com/v3/mail/send";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public EmailProvider provider() {
        return EmailProvider.SENDGRID;
    }

    @Override
    public EmailSendResult send(ResolvedEmailConfig cfg, OutboundEmail email) {
        Map<String, Object> personalization = new LinkedHashMap<>();
        personalization.put("to", List.of(Map.of("email", email.to())));
        if (email.hasCc()) {
            personalization.put("cc", email.cc().stream().map(c -> Map.of("email", c)).toList());
        }

        Map<String, Object> from = new LinkedHashMap<>();
        from.put("email", cfg.fromEmail());
        if (email.hasFromName()) {
            from.put("name", email.fromName());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("personalizations", List.of(personalization));
        body.put("from", from);
        body.put("subject", email.subject());
        body.put("content", List.of(Map.of("type", "text/html", "value", email.html())));
        if (email.hasReplyTo()) {
            body.put("reply_to", Map.of("email", email.replyTo()));
        }
        if (email.hasAttachments()) {
            List<Map<String, Object>> attachments = new ArrayList<>();
            for (OutboundEmail.Attachment a : email.attachments()) {
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("content", Base64.getEncoder().encodeToString(a.content()));
                att.put("filename", a.fileName());
                att.put("type", "application/octet-stream");
                att.put("disposition", "attachment");
                attachments.add(att);
            }
            body.put("attachments", attachments);
        }

        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("SendGrid returned HTTP " + status + ": " + response.body());
            }
            String messageId = response.headers().firstValue("X-Message-Id").orElse(null);
            log.info("Email sent to {} via SendGrid (id={})", email.to(), messageId);
            return new EmailSendResult(messageId, "SENDGRID");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted sending email to " + email.to() + " via SendGrid", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("SendGrid send failed to {}: {}", email.to(), e.getMessage());
            throw new IllegalStateException("Could not send email to " + email.to() + " via SendGrid: " + e.getMessage(), e);
        }
    }
}
