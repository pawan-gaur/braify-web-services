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
 * Immutable audit-trail entry.  Every action that changes a resource is recorded here.
 * As of the compliance upgrade the document also captures IP address, User-Agent,
 * severity, outcome and a SHA-256 integrity hash for tamper detection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_logs")
public class AuditLog {

    // ── Enumerations ──────────────────────────────────────────────────────────

    public enum Action {
        CREATED, UPDATED, DELETED, RESTORED,
        PASSWORD_CHANGED, AVATAR_UPDATED,
        DEACTIVATED, ACTIVATED,
        SESSION_REVOKED, SENT,
        FEATURES_UPDATED,     // org feature assignment changed
        CANCELLED,            // e-sign document cancelled
        SUBSCRIPTION_CHANGED, // org subscription plan changed
        BRANDING_UPDATED,     // org branding settings changed
        QUOTA_EXCEEDED,       // a quota limit was hit (recorded as FAILURE)
        TEMPLATE_SHARED,      // template shared with another org
        TEMPLATE_UNSHARED     // template share revoked
    }

    /** Distinguishes which resource type generated this entry. */
    public enum ResourceType {
        TEMPLATE,        // PDF template
        EMAIL_TEMPLATE,  // Email template
        USER,            // User / profile actions
        ORGANIZATION,    // Organization-level actions
        E_SIGN,          // E-Sign document lifecycle
        SHARING,         // Org-to-org template sharing
        API_KEY          // Organisation API key lifecycle
    }

    /** Compliance risk level — auto-assigned from the action. */
    public enum Severity { INFO, WARNING, CRITICAL }

    /** Whether the action completed successfully or produced an error. */
    public enum Outcome { SUCCESS, FAILURE }

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    private String id;

    // ── Organisation scope ────────────────────────────────────────────────────

    /**
     * Organisation that owns the affected resource.
     * Populated on all new entries; null for legacy entries written before this
     * field existed.  Enables PLATFORM_ADMIN to filter the audit log by org.
     */
    @Indexed
    private String organizationId;

    // ── Resource identification ───────────────────────────────────────────────

    /** Resource type (default: TEMPLATE for backward-compat with old documents). */
    @Builder.Default
    private ResourceType resourceType = ResourceType.TEMPLATE;

    /**
     * ID of the affected resource.
     * Field name kept as "templateId" for backward-compat with existing documents.
     */
    @Indexed
    private String templateId;

    /** Display name of the resource at the time of the action. */
    private String templateName;

    // ── Event metadata ────────────────────────────────────────────────────────

    private Action action;

    /** Version that resulted from this action (0 when not applicable / DELETED). */
    private int versionNumber;

    /** Email of the acting user. */
    private String performedBy;

    /** Stable user-ID snapshot (does not change if the user renames or changes e-mail). */
    private String performedByUserId;

    /** Display name snapshot at the time of the action. */
    private String performedByName;

    /** Changed fields: fieldName → { from, to }.  Populated on UPDATED only. */
    private Map<String, Object> changes;

    @Indexed
    @CreatedDate
    private LocalDateTime timestamp;

    // ── Compliance fields ─────────────────────────────────────────────────────

    /** Client IP address.  X-Forwarded-For aware; picks the first hop. */
    private String ipAddress;

    /** HTTP User-Agent header of the requesting client. */
    private String userAgent;

    /** JWT JTI claim — correlates all actions that belong to the same session. */
    private String sessionId;

    /** Optional human-readable justification for the action (supplied by caller). */
    private String reason;

    /** Risk level; auto-assigned from the action when not explicitly provided. */
    @Builder.Default
    private Severity severity = Severity.INFO;

    /** Whether the action completed successfully or produced an error. */
    @Builder.Default
    private Outcome outcome = Outcome.SUCCESS;

    /** Error description when {@code outcome == FAILURE}. */
    private String failureReason;

    /**
     * SHA-256 tamper-evidence hash.
     * Input: {@code resourceId|action|resourceType|performedBy|organizationId|timestamp}.
     * Computed before persisting; re-verify at any time to detect tampering.
     */
    private String integrityHash;
}
