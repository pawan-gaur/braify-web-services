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

/** Sends via the Vonage (Nexmo) SMS REST API (no SDK dependency). */
@Slf4j
@Component
public class VonageSmsSender implements SmsSender {

    private static final String ENDPOINT = "https://rest.nexmo.com/sms/json";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SmsProvider provider() {
        return SmsProvider.VONAGE;
    }

    @Override
    public SmsSendResult send(ResolvedSmsConfig cfg, OutboundSms sms) {
        String form = "api_key=" + enc(cfg.apiKey())
                + "&api_secret=" + enc(cfg.apiSecret())
                + "&to=" + enc(stripPlus(sms.to()))
                + "&from=" + enc(sms.from())
                + "&text=" + enc(sms.body());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Vonage returned HTTP " + status + ": " + response.body());
            }
            // Vonage returns 200 even on logical failures — inspect messages[0].status.
            JsonNode first = mapper.readTree(response.body()).path("messages").path(0);
            String msgStatus = first.path("status").asText("");
            if (!"0".equals(msgStatus)) {
                String err = first.path("error-text").asText("unknown error");
                throw new IllegalStateException("Vonage rejected the message (status " + msgStatus + "): " + err);
            }
            String id = first.hasNonNull("message-id") ? first.get("message-id").asText() : null;
            log.info("SMS sent to {} via Vonage (id={})", sms.to(), id);
            return new SmsSendResult(id, "VONAGE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted sending SMS to " + sms.to() + " via Vonage", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Vonage send failed to {}: {}", sms.to(), e.getMessage());
            throw new IllegalStateException("Could not send SMS to " + sms.to() + " via Vonage: " + e.getMessage(), e);
        }
    }

    private static String stripPlus(String v) {
        return v != null && v.startsWith("+") ? v.substring(1) : v;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v != null ? v : "", StandardCharsets.UTF_8);
    }
}
