package com.braify.feature.organization.model;

import com.braify.feature.branding.model.OrgBranding;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.shared.SubscriptionPlan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "organizations")
@CompoundIndexes({
    @CompoundIndex(name = "idx_deleted_name", def = "{'deleted': 1, 'name': 1}")
})
public class Organization {

    @Id
    private String id;

    private String name;

    /** Unique short identifier, e.g. "acme-corp" */
    @Indexed(unique = true)
    private String code;

    private String description;

    /**
     * Feature keys enabled for this organisation.
     * Values must match the Feature enum: PDF_TEMPLATES, EMAIL_TEMPLATES, E_SIGN.
     * Platform admins bypass this list and always have access to everything.
     */
    @Builder.Default
    private List<String> features = new ArrayList<>();

    // ── Subscription ──────────────────────────────────────────────────────────

    /** Current subscription tier; defaults to FREE for all new organisations. */
    @Builder.Default
    private SubscriptionPlan subscriptionPlan = SubscriptionPlan.FREE;

    /** When the current plan was assigned. */
    private LocalDateTime planAssignedAt;

    /** Email of the Platform Admin who assigned the plan. */
    private String planAssignedBy;

    /**
     * Optional plan expiry date.  Null means the plan never expires.
     * When set, QuotaService will fall back to FREE limits after this date.
     */
    private LocalDateTime planExpiresAt;

    // ── Branding ──────────────────────────────────────────────────────────────

    /**
     * Org-level branding settings (logo, colours, email sender, footer text).
     * Stored as an embedded BSON object; null until the org configures branding.
     */
    private OrgBranding branding;

    // ── Cloud Storage ─────────────────────────────────────────────────────────

    /**
     * Cloud storage configuration (AWS / Azure / GCP credentials, bucket, upload policy).
     * Stored as an embedded BSON object; null until the org configures cloud storage.
     * Sensitive credential fields are masked in all API responses.
     */
    private OrgCloudConfig cloudConfig;

    // ── Status ────────────────────────────────────────────────────────────────

    private boolean active = true;
    private boolean deleted = false;
    private LocalDateTime deletedAt;

    // ── Auditing ──────────────────────────────────────────────────────────────

    /** ID of the AppUser (PLATFORM_ADMIN) who originally created this organisation. */
    private String createdBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
