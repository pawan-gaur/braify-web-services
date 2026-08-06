package com.braify.config.infra.sms;

import com.braify.feature.smsconfig.model.OrgSmsConfig.SmsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Generic SMS adapter for any REST-based gateway. The admin supplies the endpoint
 * URL, HTTP method, content type, an optional auth header, and a body/query template
 * using {@code {{to}}} / {@code {{from}}} / {@code {{text}}} placeholders.
 *
 * <p>Values are escaped for the chosen content type (JSON-escaped for {@code JSON},
 * URL-encoded for {@code FORM} and for GET query strings). Any 2xx response is a success.
 */
@Slf4j
@Component
public class HttpSmsSender implements SmsSender {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public SmsProvider provider() {
        return SmsProvider.HTTP;
    }

    @Override
    public SmsSendResult send(ResolvedSmsConfig cfg, OutboundSms sms) {
        String method = cfg.httpMethod() != null ? cfg.httpMethod().toUpperCase() : "POST";
        boolean json = !"FORM".equalsIgnoreCase(cfg.contentType());
        boolean isGet = "GET".equals(method);

        // GET always uses URL-encoded query substitution; POST uses JSON- or FORM-encoding.
        String rendered = substitute(cfg.bodyTemplate(), sms, (isGet || !json) ? Escape.URL : Escape.JSON);

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30));
            if (cfg.authHeaderName() != null && !cfg.authHeaderName().isBlank()
                    && cfg.authHeaderValue() != null && !cfg.authHeaderValue().isBlank()) {
                rb.header(cfg.authHeaderName().trim(), cfg.authHeaderValue());
            }

            if (isGet) {
                String url = cfg.apiUrl() + (cfg.apiUrl().contains("?") ? "&" : "?") + rendered;
                rb.uri(URI.create(url)).GET();
            } else {
                rb.uri(URI.create(cfg.apiUrl()))
                  .header("Content-Type", json ? "application/json" : "application/x-www-form-urlencoded")
                  .method(method, HttpRequest.BodyPublishers.ofString(rendered, StandardCharsets.UTF_8));
            }

            HttpResponse<String> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("SMS gateway returned HTTP " + status + ": " + response.body());
            }
            log.info("SMS sent to {} via custom HTTP gateway (status={})", sms.to(), status);
            return new SmsSendResult(null, "HTTP");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted sending SMS to " + sms.to() + " via HTTP gateway", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Custom HTTP SMS send failed to {}: {}", sms.to(), e.getMessage());
            throw new IllegalStateException("Could not send SMS to " + sms.to() + " via HTTP gateway: " + e.getMessage(), e);
        }
    }

    private enum Escape { JSON, URL }

    private static String substitute(String template, OutboundSms sms, Escape mode) {
        String t = template != null ? template : "";
        return t.replace("{{to}}",   esc(sms.to(),   mode))
                .replace("{{from}}", esc(sms.from(), mode))
                .replace("{{text}}", esc(sms.body(), mode));
    }

    private static String esc(String v, Escape mode) {
        String s = v != null ? v : "";
        if (mode == Escape.URL) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
        // JSON string-body escaping (value is placed inside existing quotes in the template)
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
