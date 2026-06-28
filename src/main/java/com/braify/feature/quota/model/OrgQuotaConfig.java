package com.braify.feature.quota.model;

import com.braify.shared.SubscriptionPlan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Stores the quota limits that apply to one organisation.
 * Written by QuotaService.resetToDefaults() when a plan is assigned,
 * and can be further overridden by a Platform Admin.
 *
 * All limit fields use -1 to represent "unlimited".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "org_quota_configs")
public class OrgQuotaConfig {

    @Id
    private String id;

    @Indexed(unique = true)
    private String organizationId;

    /** Max active users allowed in the org (-1 = unlimited). */
    @Builder.Default
    private int maxUsers = SubscriptionPlan.FREE.defaultMaxUsers;

    /** Max documents (PDF generations + e-sign sends) per calendar month (-1 = unlimited). */
    @Builder.Default
    private int maxDocsPerMonth = SubscriptionPlan.FREE.defaultMaxDocsPerMonth;

    /** Max total storage in MB (-1 = unlimited). */
    @Builder.Default
    private long maxStorageMb = SubscriptionPlan.FREE.defaultMaxStorageMb;

    /** Max API calls per calendar month (-1 = unlimited). */
    @Builder.Default
    private int maxApiCallsPerMonth = SubscriptionPlan.FREE.defaultMaxApiCallsPerMonth;

    /** Id of the user who created this config (audited automatically on insert). */
    @CreatedBy
    private String createdBy;

    /** Email of the Platform Admin who last modified this config. */
    private String updatedBy;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
