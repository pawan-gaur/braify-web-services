package com.braify.config.infra.sms;

import com.braify.feature.smsconfig.model.OrgSmsConfig.SmsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Sends via the Twilio Messages REST API (no SDK dependency). */
@Slf4j
@Component
public class TwilioSmsSender implements SmsSender {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SmsProvider provider() {
        return SmsProvider.TWILIO;
    }

    @Override
    public SmsSendResult send(ResolvedSmsConfig cfg, OutboundSms sms) {
        String endpoint = "https://api.twilio.com/2010-04-01/Accounts/" + cfg.accountSid() + "/Messages.json";
        String form = "To=" + enc(sms.to())
                + "&From=" + enc(sms.from())
                + "&Body=" + enc(sms.body());
        String auth = Base64.getEncoder().encodeToString(
                (cfg.accountSid() + ":" + cfg.authToken()).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Twilio returned HTTP " + status + ": " + response.body());
            }
            String sid = null;
            try {
                JsonNode node = mapper.readTree(response.body());
                if (node.hasNonNull("sid")) sid = node.get("sid").asText();
            } catch (Exception ignored) { /* id is best-effort */ }
            log.info("SMS sent to {} via Twilio (sid={})", sms.to(), sid);
            return new SmsSendResult(sid, "TWILIO");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted sending SMS to " + sms.to() + " via Twilio", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Twilio send failed to {}: {}", sms.to(), e.getMessage());
            throw new IllegalStateException("Could not send SMS to " + sms.to() + " via Twilio: " + e.getMessage(), e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v != null ? v : "", StandardCharsets.UTF_8);
    }
}
