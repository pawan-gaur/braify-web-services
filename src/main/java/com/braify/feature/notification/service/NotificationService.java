package com.braify.feature.notification.service;

import com.braify.feature.events.PlatformEvent;
import com.braify.feature.events.PlatformEventTypes;
import com.braify.feature.notification.model.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** In-app notifications: created from platform events for the item's owner; read/unread per user. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final MongoTemplate mongoTemplate;

    /** Events worth a bell notification (the owner already knows about the ones they triggered). */
    private static final List<String> NOTIFY = List.of(
            PlatformEventTypes.ESIGN_DOCUMENT_VIEWED, PlatformEventTypes.ESIGN_SIGNER_SIGNED,
            PlatformEventTypes.ESIGN_DOCUMENT_COMPLETED, PlatformEventTypes.ESIGN_DOCUMENT_EXPIRED,
            PlatformEventTypes.BULK_EMAIL_FINISHED, PlatformEventTypes.PDF_BULK_FINISHED);

    @EventListener
    public void onEvent(PlatformEvent event) {
        if (event.ownerUserId() == null || !NOTIFY.contains(event.type())) return;
        try {   // never throw: listeners run in sequence, an exception would skip the others
            notify(event.orgId(), event.ownerUserId(), event.type(), event.title(), event.link());
        } catch (Exception e) {
            log.warn("Notification for event {} failed: {}", event.id(), e.getMessage());
        }
    }

    public void notify(String orgId, String userId, String type, String title, String link) {
        LocalDateTime now = LocalDateTime.now();
        mongoTemplate.insert(Notification.builder()
                .orgId(orgId).userId(userId).type(type).title(title).link(link)
                .createdAt(now).expiresAt(now.plusDays(90)).build());
    }

    public List<Notification> list(String userId, boolean unreadOnly, int limit) {
        Criteria c = Criteria.where("userId").is(userId);
        if (unreadOnly) c = c.and("read").is(false);
        return mongoTemplate.find(new Query(c).with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .with(PageRequest.of(0, Math.min(Math.max(limit, 1), 50))), Notification.class);
    }

    public long unreadCount(String userId) {
        return mongoTemplate.count(new Query(Criteria.where("userId").is(userId).and("read").is(false)), Notification.class);
    }

    public void markRead(String userId, String id) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id).and("userId").is(userId)),
                new Update().set("read", true), Notification.class);
    }

    public void markAllRead(String userId) {
        mongoTemplate.updateMulti(new Query(Criteria.where("userId").is(userId).and("read").is(false)),
                new Update().set("read", true), Notification.class);
    }
}
