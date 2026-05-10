package com.braify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Immutable audit-trail entry shared by both PDF templates and email templates.
 * One document is written for every CREATED / UPDATED / DELETED / RESTORED action.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_logs")
public class AuditLog {

    public enum Action {
        CREATED, UPDATED, DELETED, RESTORED,
        PASSWORD_CHANGED, AVATAR_UPDATED,
        DEACTIVATED, ACTIVATED,
        SESSION_REVOKED, SENT
    }

    /** Distinguishes which resource type generated this entry. */
    public enum ResourceType {
        TEMPLATE,        // PDF template
        EMAIL_TEMPLATE,  // Email template
        USER             // User / profile actions
    }

    @Id
    private String id;

    // ── Resource identification ───────────────────────────────────────────────

    /** The resourceType this log entry belongs to (default: TEMPLATE for backward-compat). */
    @Builder.Default
    private ResourceType resourceType = ResourceType.TEMPLATE;

    /**
     * ID of the affected resource (templateId or emailTemplateId).
     * Field kept as "templateId" for backward-compatibility with existing documents.
     */
    @Indexed
    private String templateId;

    /** Display name of the resource at the time of the action. */
    private String templateName;

    // ── Event metadata ────────────────────────────────────────────────────────
    private Action action;

    /** Version that resulted from this action (0 for DELETE). */
    private int versionNumber;

    /** Who performed the action — replace with auth principal in the future. */
    private String performedBy;

    /** Changed fields: name → { from, to }.  Populated on UPDATED only. */
    private Map<String, Object> changes;

    @CreatedDate
    private LocalDateTime timestamp;
}
