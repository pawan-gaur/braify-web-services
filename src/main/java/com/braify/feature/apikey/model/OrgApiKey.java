package com.braify.feature.apikey.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "org_api_keys")
public class OrgApiKey {

    @Id
    private String id;

    private String orgId;

    /** Human-readable label, e.g. "Production Key" */
    private String name;

    /** First 12 chars of the plain key for display, e.g. "brfy_a1b2c3d" */
    private String keyPrefix;

    /** SHA-256 of the full plain key — NEVER returned in responses. Looked up on every
     *  external API request, so it must be indexed (unique). */
    @JsonIgnore
    @Indexed(unique = true)
    private String keyHash;

    /** Subset of the org's enabled features this key may access */
    @Builder.Default
    private Set<String> allowedFeatures = new HashSet<>();

    @Builder.Default
    private boolean active = true;

    @CreatedDate
    private LocalDateTime createdAt;

    /** Email of the user who created this key */
    @CreatedBy
    private String createdBy;

    private LocalDateTime lastUsedAt;

    /** Null means the key never expires */
    private LocalDateTime expiresAt;

    @Builder.Default
    private long totalCalls = 0L;
}
