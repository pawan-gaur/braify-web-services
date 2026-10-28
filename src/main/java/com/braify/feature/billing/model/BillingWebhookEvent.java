package com.braify.feature.billing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** Processed payment-provider webhook events — makes webhook handling idempotent (retries are skipped). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "billing_webhook_events")
public class BillingWebhookEvent {
    @Id
    private String id;          // provider event id (e.g. evt_…)
    private String type;
    private String orgId;
    private LocalDateTime receivedAt;
}
