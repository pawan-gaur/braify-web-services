package com.braify.feature.webhook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** An organisation's outgoing-webhook subscription (e.g. a Zapier / Make "catch hook" URL). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "webhook_endpoints")
public class WebhookEndpoint {
    @Id
    private String id;

    @Indexed
    private String orgId;

    private String url;
    private String description;

    /** Subscribed event types; empty = all. */
    @Builder.Default
    private List<String> events = new ArrayList<>();

    /** Signing secret, encrypted at rest; shown to the user once (create / rotate). */
    private String secretEncrypted;

    @Builder.Default
    private boolean active = true;

    private int consecutiveFailures;
    private LocalDateTime lastDeliveryAt;
    private Integer lastStatusCode;

    private String createdBy;
    @CreatedDate
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
