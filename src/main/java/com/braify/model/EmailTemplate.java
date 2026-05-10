package com.braify.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(collection = "email_templates")
public class EmailTemplate {

    @Id
    private String id;

    // ── Identity ──────────────────────────────────────────────────────────────
    private String name;
    private String organizationId;
    private String description;

    // ── Email-specific metadata ───────────────────────────────────────────────
    /** Value that appears in the email Subject header */
    private String subject;

    /**
     * Preheader / preview text — shown by email clients below the subject line
     * in the inbox list view (typically 85-100 chars max).
     */
    private String previewText;

    /** "From" display name (e.g. "Eden Care Medical") */
    private String fromName;

    // ── Content (from GrapesJS) ───────────────────────────────────────────────
    private String htmlContent;
    private String cssContent;
    private String gjsData;   // full GrapesJS project JSON for re-editing

    // ── Dynamic placeholders ──────────────────────────────────────────────────
    /** Extracted {{placeholder}} names */
    private List<String> placeholders;

    // ── Version & soft-delete ─────────────────────────────────────────────────
    private int currentVersion = 0;
    private boolean deleted = false;
    private LocalDateTime deletedAt;

    // ── Auditing ──────────────────────────────────────────────────────────────
    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
