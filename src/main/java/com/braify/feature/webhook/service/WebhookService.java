package com.braify.feature.webhook.service;

import com.braify.config.EncryptionService;
import com.braify.config.infra.net.OutboundTargetGuard;
import com.braify.feature.billing.service.PlanAccessService;
import com.braify.feature.events.PlatformEvent;
import com.braify.feature.events.PlatformEventTypes;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.webhook.model.WebhookDelivery;
import com.braify.feature.webhook.model.WebhookEndpoint;
import com.braify.feature.webhook.repository.WebhookDeliveryRepository;
import com.braify.feature.webhook.repository.WebhookEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Outgoing webhooks: endpoint management, fan-out of {@link PlatformEvent}s into a persistent
 * delivery outbox, and signed delivery with exponential back-off.
 *
 * <p>Each POST carries {@code Braify-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}
 * plus {@code Braify-Event} and {@code Braify-Delivery} headers, so receivers can verify origin and
 * de-duplicate. Endpoint URLs must be public http(s) addresses (SSRF guard, re-checked on send).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    public static final int MAX_ENDPOINTS_PER_ORG = 10;
    /** Back-off after attempt N (1-based): 1m, 5m, 30m, 2h, 6h, 12h — then FAILED. */
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12)};

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final OrganizationRepository    orgRepository;
    private final PlanAccessService         planAccessService;
    private final EncryptionService         encryptionService;
    private final MongoTemplate             mongoTemplate;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record CreatedEndpoint(WebhookEndpoint endpoint, String secret) {}

    // ── Endpoint management ────────────────────────────────────────────────

    public List<WebhookEndpoint> list(String orgId) {
        return endpointRepository.findByOrgIdOrderByCreatedAtDesc(orgId);
    }

    public CreatedEndpoint create(String orgId, String url, String description, List<String> events, String createdBy) {
        planAccessService.requireWebhooks(orgId);
        if (endpointRepository.countByOrgId(orgId) >= MAX_ENDPOINTS_PER_ORG) {
            throw new IllegalStateException("You can have up to " + MAX_ENDPOINTS_PER_ORG + " webhook endpoints.");
        }
        String cleanUrl = validateUrl(url);
        String secret = newSecret();
        WebhookEndpoint ep = WebhookEndpoint.builder()
                .orgId(orgId).url(cleanUrl).description(trim(description, 200))
                .events(validateEvents(events)).secretEncrypted(encryptionService.encrypt(secret))
                .active(true).createdBy(createdBy).updatedAt(LocalDateTime.now())
                .build();
        return new CreatedEndpoint(endpointRepository.save(ep), secret);
    }

    public WebhookEndpoint update(String orgId, String id, String url, String description, List<String> events, Boolean active) {
        WebhookEndpoint ep = require(orgId, id);
        if (url != null) ep.setUrl(validateUrl(url));
        if (description != null) ep.setDescription(trim(description, 200));
        if (events != null) ep.setEvents(validateEvents(events));
        if (active != null) {
            if (active) planAccessService.requireWebhooks(orgId);
            ep.setActive(active);
            if (active) ep.setConsecutiveFailures(0);
        }
        ep.setUpdatedAt(LocalDateTime.now());
        return endpointRepository.save(ep);
    }

    public void delete(String orgId, String id) {
        endpointRepository.delete(require(orgId, id));
    }

    public String rotateSecret(String orgId, String id) {
        WebhookEndpoint ep = require(orgId, id);
        String secret = newSecret();
        ep.setSecretEncrypted(encryptionService.encrypt(secret));
        ep.setUpdatedAt(LocalDateTime.now());
        endpointRepository.save(ep);
        return secret;
    }

    /** Queues a {@code ping} for one endpoint (delivered within seconds by the scheduler). */
    public WebhookDelivery sendTest(String orgId, String id) {
        planAccessService.requireWebhooks(orgId);
        WebhookEndpoint ep = require(orgId, id);
        PlatformEvent ping = PlatformEvent.of(PlatformEventTypes.PING, orgId, null, "Test event", null,
                Map.of("message", "Hello from Braify — your webhook endpoint is reachable."));
        return enqueue(ep, ping);
    }

    public Page<WebhookDelivery> deliveries(String orgId, String endpointId, int page, int size) {
        require(orgId, endpointId);
        return deliveryRepository.findByEndpointIdOrderByCreatedAtDesc(endpointId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    public WebhookDelivery retry(String orgId, String deliveryId) {
        WebhookDelivery d = deliveryRepository.findById(deliveryId)
                .filter(x -> orgId.equals(x.getOrgId()))
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));
        d.setStatus(WebhookDelivery.Status.PENDING);
        d.setNextAttemptAt(LocalDateTime.now());
        return deliveryRepository.save(d);
    }

    // ── Fan-out ────────────────────────────────────────────────────────────

    /** Queues a delivery for every active endpoint of the org that subscribes to the event. */
    @EventListener
    public void onEvent(PlatformEvent event) {
        // Never throw: listeners run in sequence, so an exception here would skip the others.
        try {
            fanOut(event);
        } catch (Exception e) {
            log.warn("Webhook fan-out failed for event {} ({}): {}", event.id(), event.type(), e.getMessage());
        }
    }

    private void fanOut(PlatformEvent event) {
        if (PlatformEventTypes.PING.equals(event.type())) return;
        List<WebhookEndpoint> endpoints = endpointRepository.findByOrgIdAndActiveTrue(event.orgId());
        if (endpoints.isEmpty()) return;
        // Downgraded to Free (and not grandfathered) → stop delivering.
        if (!planAccessService.webhooksEnabled(orgRepository.findById(event.orgId()).orElse(null))) return;
        for (WebhookEndpoint ep : endpoints) {
            if (ep.getEvents() == null || ep.getEvents().isEmpty() || ep.getEvents().contains(event.type())) {
                enqueue(ep, event);
            }
        }
    }

    private WebhookDelivery enqueue(WebhookEndpoint ep, PlatformEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id());
        body.put("type", event.type());
        body.put("createdAt", event.occurredAt().toString());
        body.put("orgId", event.orgId());
        body.put("data", event.data());
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (Exception e) { throw new IllegalStateException("Could not serialise webhook payload", e); }

        LocalDateTime now = LocalDateTime.now();
        return deliveryRepository.save(WebhookDelivery.builder()
                .endpointId(ep.getId()).orgId(ep.getOrgId())
                .eventId(event.id()).eventType(event.type()).payload(json)
                .status(WebhookDelivery.Status.PENDING).nextAttemptAt(now)
                .createdAt(now).expiresAt(now.plusDays(30))
                .build());
    }

    // ── Delivery ───────────────────────────────────────────────────────────

    /**
     * Sends run here, never on the (single) scheduler thread: one unreachable endpoint (up to ~20s
     * per attempt) must not delay reminders, expiry or billing jobs. Bounded queue; rows are only
     * claimed while there's room, and the 5-minute lease re-queues anything a crash loses.
     */
    private final java.util.concurrent.ThreadPoolExecutor senders = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(100),
            r -> { Thread t = new Thread(r, "webhook-send"); t.setDaemon(true); return t; });

    @jakarta.annotation.PreDestroy
    void shutdownSenders() { senders.shutdown(); }

    /** Claims due rows (called by the scheduler) and hands them to the sender pool. Each row is claimed atomically. */
    public int deliverDue(int max) {
        int n = 0;
        for (int i = 0; i < max && senders.getQueue().remainingCapacity() > 0; i++) {
            LocalDateTime now = LocalDateTime.now();
            WebhookDelivery d = mongoTemplate.findAndModify(
                    new Query(Criteria.where("status").is(WebhookDelivery.Status.PENDING).and("nextAttemptAt").lte(now))
                            .with(org.springframework.data.domain.Sort.by("nextAttemptAt")),
                    new Update().set("status", WebhookDelivery.Status.SENDING)
                            .set("nextAttemptAt", now.plusMinutes(5)),   // lease: re-picked if this node dies
                    FindAndModifyOptions.options().returnNew(true),
                    WebhookDelivery.class);
            if (d == null) break;
            final WebhookDelivery claimed = d;
            try {
                senders.execute(() -> {
                    try { attempt(claimed); }
                    catch (Exception e) { log.warn("Webhook delivery {} failed unexpectedly: {}", claimed.getId(), e.getMessage()); }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                break;   // pool full — the lease expires and the row is picked up again later
            }
            n++;
        }
        // Rows stuck in SENDING past their lease go back to PENDING.
        mongoTemplate.updateMulti(
                new Query(Criteria.where("status").is(WebhookDelivery.Status.SENDING).and("nextAttemptAt").lt(LocalDateTime.now())),
                new Update().set("status", WebhookDelivery.Status.PENDING), WebhookDelivery.class);
        return n;
    }

    private void attempt(WebhookDelivery d) {
        WebhookEndpoint ep = endpointRepository.findById(d.getEndpointId()).orElse(null);
        int attemptNo = d.getAttempts() + 1;
        d.setAttempts(attemptNo);
        if (ep == null || !ep.isActive()) {
            finish(d, ep, WebhookDelivery.Status.FAILED, null, ep == null ? "Endpoint deleted" : "Endpoint disabled");
            return;
        }
        Integer code = null;
        String error = null;
        try {
            OutboundTargetGuard.assertPublicHttpUrl(ep.getUrl());
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String sig = hmacHex(encryptionService.decryptSafe(ep.getSecretEncrypted()), ts + "." + d.getPayload());
            HttpRequest req = HttpRequest.newBuilder(URI.create(ep.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Braify-Webhooks/1.0")
                    .header("Braify-Event", d.getEventType())
                    .header("Braify-Delivery", d.getId())
                    .header("Braify-Signature", "t=" + ts + ",v1=" + sig)
                    .POST(HttpRequest.BodyPublishers.ofString(d.getPayload(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            code = res.statusCode();
            if (code >= 200 && code < 300) {
                finish(d, ep, WebhookDelivery.Status.SUCCEEDED, code, null);
                return;
            }
            error = "HTTP " + code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (attemptNo > BACKOFF.length) {
            finish(d, ep, WebhookDelivery.Status.FAILED, code, error);
        } else {
            d.setStatus(WebhookDelivery.Status.PENDING);
            d.setNextAttemptAt(LocalDateTime.now().plus(BACKOFF[attemptNo - 1]));
            d.setLastStatusCode(code);
            d.setLastError(trim(error, 300));
            deliveryRepository.save(d);
            recordEndpoint(ep, code, false);
        }
    }

    private void finish(WebhookDelivery d, WebhookEndpoint ep, WebhookDelivery.Status status, Integer code, String error) {
        d.setStatus(status);
        d.setLastStatusCode(code);
        d.setLastError(trim(error, 300));
        if (status == WebhookDelivery.Status.SUCCEEDED) d.setDeliveredAt(LocalDateTime.now());
        deliveryRepository.save(d);
        if (ep != null) recordEndpoint(ep, code, status == WebhookDelivery.Status.SUCCEEDED);
    }

    private void recordEndpoint(WebhookEndpoint ep, Integer code, boolean ok) {
        Update u = new Update().set("lastDeliveryAt", LocalDateTime.now()).set("lastStatusCode", code);
        if (ok) u.set("consecutiveFailures", 0); else u.inc("consecutiveFailures", 1);
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(ep.getId())), u, WebhookEndpoint.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private WebhookEndpoint require(String orgId, String id) {
        return endpointRepository.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook endpoint not found"));
    }

    private static String validateUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Endpoint URL is required");
        String u = url.trim();
        if (u.length() > 2000) throw new IllegalArgumentException("Endpoint URL is too long");
        try {
            OutboundTargetGuard.assertPublicHttpUrl(u);
        } catch (SecurityException e) {
            throw new IllegalArgumentException("Endpoint URL must be a public http(s) address: " + e.getMessage());
        }
        return u;
    }

    private static List<String> validateEvents(List<String> events) {
        if (events == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String e : events) {
            if (!PlatformEventTypes.ALL.contains(e)) throw new IllegalArgumentException("Unknown event type: " + e);
            if (!out.contains(e)) out.add(e);
        }
        return out;
    }

    private String newSecret() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return "whsec_" + HexFormat.of().formatHex(b);
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
