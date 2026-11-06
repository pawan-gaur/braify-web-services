package com.braify.feature.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Minimal Stripe REST client (form-encoded API + webhook signature check).
 *
 * <p>Deliberately not the Stripe SDK: only five calls are needed, responses are read as JSON
 * (tolerant to Stripe API-version field moves, e.g. {@code current_period_end} moving from the
 * subscription to its items), and there's no extra dependency to keep in step with Stripe's
 * API versions.
 */
@Slf4j
@Component
public class StripeClient {

    private static final String API = "https://api.stripe.com/v1/";
    /** Max age of a webhook signature (Stripe's recommended replay window). */
    private static final long WEBHOOK_TOLERANCE_SECONDS = 300;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    // ── API calls ─────────────────────────────────────────────────────────────

    public String createCustomer(String email, String name, String orgId) {
        Map<String, String> p = new LinkedHashMap<>();
        if (email != null) p.put("email", email);
        if (name != null)  p.put("name", name);
        p.put("metadata[orgId]", orgId);
        // Idempotent per org: a retried call can't create a second customer.
        return post("customers", p, "customer-" + orgId).path("id").asText();
    }

    /** Hosted Checkout for a subscription; returns the URL to redirect the customer to. */
    public String createCheckoutSession(String customerId, String priceId, String orgId,
                                        String successUrl, String cancelUrl) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mode", "subscription");
        p.put("customer", customerId);
        p.put("client_reference_id", orgId);
        p.put("line_items[0][price]", priceId);
        p.put("line_items[0][quantity]", "1");
        p.put("success_url", successUrl);
        p.put("cancel_url", cancelUrl);
        p.put("allow_promotion_codes", "true");
        p.put("metadata[orgId]", orgId);
        p.put("subscription_data[metadata][orgId]", orgId);
        return post("checkout/sessions", p, UUID.randomUUID().toString()).path("url").asText();
    }

    /** Customer Portal (change plan, card, invoices, cancel); returns its URL. */
    public String createPortalSession(String customerId, String returnUrl) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("customer", customerId);
        p.put("return_url", returnUrl);
        return post("billing_portal/sessions", p, UUID.randomUUID().toString()).path("url").asText();
    }

    public JsonNode getSubscription(String subscriptionId) {
        return get("subscriptions/" + URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8));
    }

    // ── Webhooks ──────────────────────────────────────────────────────────────

    /**
     * Verifies a webhook's {@code Stripe-Signature} header (HMAC-SHA256 of {@code t.payload} with
     * the endpoint secret, constant-time compare, timestamp within tolerance) and returns the
     * parsed event.
     *
     * @throws SecurityException when the signature is missing, wrong or too old
     */
    public JsonNode verifyWebhook(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("stripe.webhook-secret is not configured");
        }
        if (signatureHeader == null || payload == null) throw new SecurityException("Missing Stripe signature");

        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0]))  timestamp = kv[1];
            if ("v1".equals(kv[0])) signatures.add(kv[1]);
        }
        if (timestamp == null || signatures.isEmpty()) throw new SecurityException("Malformed Stripe signature");

        long ts;
        try { ts = Long.parseLong(timestamp); } catch (NumberFormatException e) { throw new SecurityException("Bad timestamp"); }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > WEBHOOK_TOLERANCE_SECONDS) {
            throw new SecurityException("Stripe signature timestamp outside tolerance");
        }

        byte[] expected = hmacSha256(webhookSecret, timestamp + "." + payload);
        boolean match = signatures.stream().anyMatch(sig -> {
            try { return MessageDigest.isEqual(expected, HexFormat.of().parseHex(sig)); }
            catch (IllegalArgumentException e) { return false; }
        });
        if (!match) throw new SecurityException("Invalid Stripe signature");

        try {
            return mapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid webhook payload");
        }
    }

    static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private JsonNode post(String path, Map<String, String> params, String idempotencyKey) {
        String body = params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = base(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req, path);
    }

    private JsonNode get(String path) {
        return send(base(path).GET().build(), path);
    }

    private HttpRequest.Builder base(String path) {
        if (!isConfigured()) throw new IllegalStateException("Billing is not configured (stripe.secret-key).");
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey);
    }

    private JsonNode send(HttpRequest req, String path) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(res.body());
            if (res.statusCode() >= 300) {
                String msg = json.path("error").path("message").asText("Stripe request failed");
                log.error("Stripe {} failed ({}): {}", path, res.statusCode(), msg);
                throw new IllegalStateException("Payment provider error: " + msg);
            }
            return json;
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment provider request interrupted", e);
        } catch (Exception e) {
            log.error("Stripe {} call failed: {}", path, e.getMessage());
            throw new IllegalStateException("Could not reach the payment provider. Please try again.", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
