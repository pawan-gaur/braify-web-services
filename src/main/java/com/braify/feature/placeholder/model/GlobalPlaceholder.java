package com.braify.feature.placeholder.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Org-level reusable placeholder. When a template (email or PDF) references
 * {@code {{key}}}, its value is automatically substituted at render/send time
 * unless the caller supplies an explicit non-blank value for the same key.
 *
 * <p>One document per (organizationId, key) pair — the compound unique index
 * prevents duplicate keys within an organisation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "global_placeholders")
@CompoundIndexes({
    @CompoundIndex(name = "idx_org_key_unique", def = "{'organizationId':1,'key':1}", unique = true)
})
public class GlobalPlaceholder {

    /** The value substituted into a {@code {{logo}}} placeholder is a URL/data-URL. */
    public enum Type { TEXT, IMAGE }

    @Id
    private String id;

    private String organizationId;

    /** Placeholder token as used in templates, e.g. {@code organization_name} → {@code {{organization_name}}}. */
    private String key;

    /** Value substituted for the placeholder. For IMAGE type this is a URL or data-URL. */
    private String value;

    /** Friendly label shown in the management UI (optional). */
    private String label;

    /** Rendering hint for the UI; does not affect substitution. */
    @Builder.Default
    private Type type = Type.TEXT;

    @CreatedBy
    private String createdBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
