package com.braify.feature.email.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(collection = "email_templates")
@CompoundIndexes({
    @CompoundIndex(name = "idx_org_deleted_updated", def = "{'organizationId':1,'deleted':1,'updatedAt':-1}")
})
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

    // ── Sharing provenance ────────────────────────────────────────────────────

    /** ID of the original template this was forked from (null when not a fork). */
    private String sourceTemplateId;

    /** Organisation that shared/forked this template (null when not a fork). */
    private String sourceOrgId;

    /** True when this document is a fork created by the sharing system. */
    private boolean forked = false;

    // ── Auditing ──────────────────────────────────────────────────────────────

    /** ID of the AppUser who originally created this email template. */
    @CreatedBy
    private String createdBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
