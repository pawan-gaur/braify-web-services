package com.braify.dto;

import com.braify.model.SubscriptionPlan;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SubscriptionResponse {

    private String           organizationId;
    private String           organizationName;
    private SubscriptionPlan subscriptionPlan;
    private String           planLabel;
    private LocalDateTime    planAssignedAt;
    private String           planAssignedBy;
    private LocalDateTime    planExpiresAt;

    /** Default quota limits that ship with the current plan. */
    private int  defaultMaxUsers;
    private int  defaultMaxDocsPerMonth;
    private long defaultMaxStorageMb;
    private int  defaultMaxApiCallsPerMonth;
}
