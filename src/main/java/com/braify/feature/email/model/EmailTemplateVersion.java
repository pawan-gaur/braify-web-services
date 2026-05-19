package com.braify.feature.email.model;

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
 * Point-in-time snapshot of an EmailTemplate saved on every create / update / restore.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "email_template_versions")
public class EmailTemplateVersion {

    @Id
    private String id;

    @Indexed
    private String emailTemplateId;

    private int version;

    // ── Full snapshot ────────────────────────────────────────────────────────
    private String name;
    private String description;
    private String subject;
    private String previewText;
    private String fromName;
    private String htmlContent;
    private String cssContent;
    private String gjsData;
    private List<String> placeholders;

    // ── Metadata ─────────────────────────────────────────────────────────────
    private String savedBy;
    private String changeNote;

    @CreatedDate
    private LocalDateTime savedAt;
}
