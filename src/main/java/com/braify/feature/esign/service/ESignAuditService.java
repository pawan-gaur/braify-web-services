package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.repository.ESignAuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignAuditService {

    private final ESignAuditEventRepository auditRepo;

    /**
     * Appends an audit event — never updates or deletes.
     */
    public ESignAuditEvent log(String documentId,
                               String actor,
                               ESignAuditEvent.ActorType actorType,
                               ESignAuditEvent.EventType event,
                               String ipAddress,
                               String userAgent,
                               Map<String, Object> metadata) {
        // createdBy records the userId of the platform user who triggered the event;
        // for CLIENT and SYSTEM events the actor is not an AppUser, so we leave it null.
        String createdBy = (actorType == ESignAuditEvent.ActorType.CREATOR) ? actor : null;

        ESignAuditEvent entry = ESignAuditEvent.builder()
                .documentId(documentId)
                .actor(actor)
                .actorType(actorType)
                .event(event)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .metadata(metadata)
                .createdBy(createdBy)
                .build();
        ESignAuditEvent saved = auditRepo.save(entry);
        log.debug("ESign audit: event={} document='{}' actor='{}'", event, documentId, actor);
        return saved;
    }

    /** Convenience overload — no metadata. */
    public ESignAuditEvent log(String documentId,
                               String actor,
                               ESignAuditEvent.ActorType actorType,
                               ESignAuditEvent.EventType event,
                               String ipAddress,
                               String userAgent) {
        return log(documentId, actor, actorType, event, ipAddress, userAgent, null);
    }

    /** Fire-and-forget variant (runs on async thread pool). */
    @Async
    public void logAsync(String documentId,
                         String actor,
                         ESignAuditEvent.ActorType actorType,
                         ESignAuditEvent.EventType event,
                         String ipAddress,
                         String userAgent,
                         Map<String, Object> metadata) {
        log(documentId, actor, actorType, event, ipAddress, userAgent, metadata);
    }

    public List<ESignAuditEvent> getAuditTrail(String documentId) {
        return auditRepo.findByDocumentIdOrderByTimestampAsc(documentId);
    }
}
