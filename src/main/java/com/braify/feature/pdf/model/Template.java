package com.braify.feature.pdf.model;

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
@Document(collection = "templates")
@CompoundIndexes({
    @CompoundIndex(name = "idx_org_deleted_updated", def = "{'organizationId':1,'deleted':1,'updatedAt':-1}")
})
public class Template {

    @Id
    private String id;

    private String name;
    private String organizationId;
    private String description;

    /** Raw HTML from GrapesJS canvas */
    private String htmlContent;

    /** CSS from GrapesJS style manager */
    private String cssContent;

    /** Page settings */
    private String pageSize = "A4";
    private String orientation = "portrait";
    private Integer marginTop = 20;
    private Integer marginBottom = 20;
    private Integer marginLeft = 15;
    private Integer marginRight = 15;

    /** Extracted placeholder names e.g. ["invoiceNumber", "user.name"] */
    private List<String> placeholders;

    /** GrapesJS full project JSON (for re-editing the template) */
    private String gjsData;

    // ── Version tracking ─────────────────────────────────────────────────────
    /** Latest saved version number; kept in sync by TemplateVersionService */
    private int currentVersion = 0;

    // ── Soft delete ───────────────────────────────────────────────────────────
    /** true once the template has been "deleted" by the user */
    private boolean deleted = false;

    /** Timestamp of the soft-delete (null while active) */
    private LocalDateTime deletedAt;

    // ── Sharing provenance ────────────────────────────────────────────────────

    /** ID of the original template this was forked from (null when not a fork). */
    private String sourceTemplateId;

    /** Organisation that shared/forked this template (null when not a fork). */
    private String sourceOrgId;

    /** True when this document is a fork created by the sharing system. */
    private boolean forked = false;

    // ── Auditing ──────────────────────────────────────────────────────────────

    /** ID of the AppUser who originally created this template. */
    @CreatedBy
    private String createdBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
