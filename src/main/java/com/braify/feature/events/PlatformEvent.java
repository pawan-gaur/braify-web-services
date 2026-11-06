package com.braify.feature.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something that happened in an organisation (document signed, campaign finished, …).
 * Published in-process; outgoing webhooks and in-app notifications subscribe to it.
 *
 * @param type          one of {@link PlatformEventTypes}
 * @param orgId         owning organisation (events without one are not delivered to tenants)
 * @param ownerUserId   user to notify in-app (e.g. the document's creator); may be null
 * @param title         short human-readable summary, used by notifications
 * @param link          app path for notifications (e.g. "/esign/123")
 * @param data          event payload delivered to webhooks (no secrets, no file bytes)
 */
public record PlatformEvent(String id, String type, String orgId, String ownerUserId,
                            String title, String link, Map<String, Object> data, Instant occurredAt) {

    public static PlatformEvent of(String type, String orgId, String ownerUserId,
                                   String title, String link, Map<String, Object> data) {
        return new PlatformEvent("evt_" + UUID.randomUUID().toString().replace("-", ""), type, orgId,
                ownerUserId, title, link, data != null ? data : Map.of(), Instant.now());
    }
}
