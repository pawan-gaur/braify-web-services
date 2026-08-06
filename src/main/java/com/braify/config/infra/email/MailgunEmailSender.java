package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Sends via the Mailgun Messages REST API (multipart, no SDK dependency). */
@Slf4j
@Component
public class MailgunEmailSender implements EmailSender {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public EmailProvider provider() {
        return EmailProvider.MAILGUN;
    }

    @Override
    public EmailSendResult send(ResolvedEmailConfig cfg, OutboundEmail email) {
        String base = "EU".equalsIgnoreCase(cfg.mailgunRegion())
                ? "https://api.eu.mailgun.net/v3/"
                : "https://api.mailgun.net/v3/";
        String endpoint = base + cfg.mailgunDomain() + "/messages";

        String boundary = "----BraifyMailgun" + Long.toHexString(email.hashCode()) + "x" + email.to().hashCode();

        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"from", email.formattedFrom()});
        fields.add(new String[]{"to", email.to()});
        if (email.hasCc()) {
            fields.add(new String[]{"cc", String.join(",", email.cc())});
        }
        fields.add(new String[]{"subject", email.subject()});
        fields.add(new String[]{"html", email.html()});
        if (email.hasReplyTo()) {
            fields.add(new String[]{"h:Reply-To", email.replyTo()});
        }

        byte[] body = buildMultipart(boundary, fields, email.attachments());
        String auth = Base64.getEncoder().encodeToString(("api:" + cfg.apiKey()).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Mailgun returned HTTP " + status + ": " + response.body());
            }
            String id = null;
            try {
                JsonNode node = mapper.readTree(response.body());
                if (node.hasNonNull("id")) id = node.get("id").asText();
            } catch (Exception ignored) { /* id is best-effort */ }
            log.info("Email sent to {} via Mailgun (id={})", email.to(), id);
            return new EmailSendResult(id, "MAILGUN");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted sending email to " + email.to() + " via Mailgun", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Mailgun send failed to {}: {}", email.to(), e.getMessage());
            throw new IllegalStateException("Could not send email to " + email.to() + " via Mailgun: " + e.getMessage(), e);
        }
    }

    private static byte[] buildMultipart(String boundary, List<String[]> fields, List<OutboundEmail.Attachment> attachments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (String[] field : fields) {
                writeAscii(out, "--" + boundary + "\r\n");
                writeAscii(out, "Content-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n");
                out.write(field[1].getBytes(StandardCharsets.UTF_8));
                writeAscii(out, "\r\n");
            }
            if (attachments != null) {
                for (OutboundEmail.Attachment a : attachments) {
                    writeAscii(out, "--" + boundary + "\r\n");
                    writeAscii(out, "Content-Disposition: form-data; name=\"attachment\"; filename=\""
                            + a.fileName() + "\"\r\n");
                    writeAscii(out, "Content-Type: application/octet-stream\r\n\r\n");
                    out.write(a.content());
                    writeAscii(out, "\r\n");
                }
            }
            writeAscii(out, "--" + boundary + "--\r\n");
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Mailgun multipart body: " + e.getMessage(), e);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }
}
