package com.braify.feature.webhook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One event → one endpoint delivery attempt record (outbox row; retried with backoff). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "webhook_deliveries")
@CompoundIndexes({
    @CompoundIndex(name = "idx_due", def = "{'status': 1, 'nextAttemptAt': 1}"),
    @CompoundIndex(name = "idx_endpoint_created", def = "{'endpointId': 1, 'createdAt': -1}")
})
public class WebhookDelivery {

    public enum Status { PENDING, SENDING, SUCCEEDED, FAILED }

    @Id
    private String id;

    private String endpointId;
    private String orgId;
    private String eventId;
    private String eventType;

    /** Exact JSON body that is signed and POSTed. */
    private String payload;

    @Builder.Default
    private Status status = Status.PENDING;
    private int attempts;
    private LocalDateTime nextAttemptAt;
    private Integer lastStatusCode;
    private String lastError;

    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;

    /** Rows are purged after 30 days. */
    @Indexed(expireAfterSeconds = 0)
    private LocalDateTime expiresAt;
}
