package com.braify.feature.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full point-in-time snapshot of a Template saved on every create/update/restore.
 * Stored in the "template_versions" collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "template_versions")
public class TemplateVersion {

    @Id
    private String id;

    /** Reference back to the parent template */
    @Indexed
    private String templateId;

    /** Monotonically increasing version number per template (1, 2, 3…) */
    private int version;

    /* ── Full template snapshot ── */
    private String name;
    private String description;
    private String htmlContent;
    private String cssContent;
    private String gjsData;

    private String pageSize;
    private String orientation;
    private Integer marginTop;
    private Integer marginBottom;
    private Integer marginLeft;
    private Integer marginRight;

    private List<String> placeholders;

    /* ── Metadata ── */
    /** Who performed the save (placeholder for future auth integration) */
    private String savedBy;

    /** Optional human-readable note about what changed */
    private String changeNote;

    /** ID of the AppUser who triggered this snapshot (template creator / updater). */
    private String createdBy;

    @CreatedDate
    private LocalDateTime savedAt;
}
