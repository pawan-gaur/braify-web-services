package com.braify.feature.esign.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Immutable audit log for e-signature events.
 * The application NEVER updates or deletes records from this collection.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_audit_events")
public class ESignAuditEvent {

    @Id private String id;

    @Indexed private String documentId;

    /* ── Who ───────────────────────────────────────────────────────────── */
    public enum ActorType { CREATOR, CLIENT, SYSTEM }
    private String    actor;       // userId, clientEmail, or "SYSTEM"
    private ActorType actorType;
    private String    ipAddress;
    private String    userAgent;

    /* ── What ──────────────────────────────────────────────────────────── */
    public enum EventType {
        DOCUMENT_CREATED,
        FIELDS_SAVED,
        DOCUMENT_SENT,
        LINK_OPENED,            // client clicked email link
        DOCUMENT_VIEWED,        // client scrolled to end / confirmed review
        SIGNING_STARTED,
        FIELD_SIGNED,           // one field completed
        DOCUMENT_SUBMITTED,
        PDF_GENERATED,
        COMPLETION_EMAIL_SENT,
        DOCUMENT_DOWNLOADED,    // creator downloaded final PDF
        CLIENT_ATTACHMENT_UPLOADED, // client uploaded a supporting document post-signing
        LINK_EXPIRED,
        DOCUMENT_CANCELLED
    }
    private EventType event;

    /** Flexible context bag — keys vary per event type */
    private Map<String, Object> metadata;

    @CreatedDate @Indexed
    private LocalDateTime timestamp;
}
