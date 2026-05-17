package com.braify.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubscriptionRequest {

    /** Plan key: FREE | PROFESSIONAL | ENTERPRISE */
    private String subscriptionPlan;

    /**
     * Optional expiry date for the plan.
     * Null means the plan never expires.
     * After expiry, QuotaService falls back to FREE limits.
     */
    private LocalDateTime planExpiresAt;
}
