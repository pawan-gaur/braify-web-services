package com.braify.feature.webhook.controller;

import com.braify.feature.events.PlatformEventTypes;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.webhook.model.WebhookDelivery;
import com.braify.feature.webhook.model.WebhookEndpoint;
import com.braify.feature.webhook.service.WebhookService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Outgoing webhooks for an organisation. Signing secrets are returned once (create / rotate). */
@Tag(name = "Webhooks", description = "Outgoing webhooks — e-sign, bulk email, bulk PDF and file events.")
@RestController
@RequestMapping("/api/organizations/{orgId}/webhooks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
public class WebhookController {

    private final WebhookService service;

    @Operation(summary = "Subscribable event types")
    @GetMapping("/event-types")
    public List<String> eventTypes(@PathVariable String orgId, Authentication auth) {
        assertOrg(auth, orgId);
        return PlatformEventTypes.ALL;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String orgId, Authentication auth) {
        assertOrg(auth, orgId);
        return service.list(orgId).stream().map(WebhookController::view).toList();
    }

    @Operation(summary = "Create endpoint", description = "Body: `{ url, description?, events?[] }` (empty events = all). Returns the endpoint and its signing `secret` (shown once).")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String orgId, @RequestBody Map<String, Object> body,
                                                      Authentication auth) {
        AppUser u = assertOrg(auth, orgId);
        var created = service.create(orgId, str(body.get("url")), str(body.get("description")),
                events(body.get("events")), u.getEmail());
        Map<String, Object> m = view(created.endpoint());
        m.put("secret", created.secret());
        return ResponseEntity.ok(m);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String orgId, @PathVariable String id,
                                      @RequestBody Map<String, Object> body, Authentication auth) {
        assertOrg(auth, orgId);
        Boolean active = body.get("active") instanceof Boolean b ? b : null;
        return view(service.update(orgId, id, str(body.get("url")), str(body.get("description")),
                body.containsKey("events") ? events(body.get("events")) : null, active));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String orgId, @PathVariable String id, Authentication auth) {
        assertOrg(auth, orgId);
        service.delete(orgId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rotate the signing secret (returned once)")
    @PostMapping("/{id}/rotate-secret")
    public Map<String, String> rotate(@PathVariable String orgId, @PathVariable String id, Authentication auth) {
        assertOrg(auth, orgId);
        return Map.of("secret", service.rotateSecret(orgId, id));
    }

    @Operation(summary = "Send a test (ping) event")
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String orgId, @PathVariable String id, Authentication auth) {
        assertOrg(auth, orgId);
        WebhookDelivery d = service.sendTest(orgId, id);
        return Map.of("deliveryId", d.getId(), "status", d.getStatus().name());
    }

    @GetMapping("/{id}/deliveries")
    public Page<WebhookDelivery> deliveries(@PathVariable String orgId, @PathVariable String id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        assertOrg(auth, orgId);
        return service.deliveries(orgId, id, page, size);
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    public Map<String, String> retry(@PathVariable String orgId, @PathVariable String deliveryId, Authentication auth) {
        assertOrg(auth, orgId);
        return Map.of("status", service.retry(orgId, deliveryId).getStatus().name());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Endpoint as returned by the API — never includes the secret. */
    private static Map<String, Object> view(WebhookEndpoint e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("url", e.getUrl());
        m.put("description", e.getDescription());
        m.put("events", e.getEvents());
        m.put("active", e.isActive());
        m.put("consecutiveFailures", e.getConsecutiveFailures());
        m.put("lastDeliveryAt", e.getLastDeliveryAt());
        m.put("lastStatusCode", e.getLastStatusCode());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private static AppUser assertOrg(Authentication auth, String orgId) {
        AppUser u = ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
        if (u.getRole() != AppUser.Role.PLATFORM_ADMIN && !orgId.equals(u.getOrganizationId())) {
            throw new AccessDeniedException("Access denied");
        }
        return u;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(Object o) {
        if (o == null) return new ArrayList<>();
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        throw new IllegalArgumentException("events must be a list");
    }
}
