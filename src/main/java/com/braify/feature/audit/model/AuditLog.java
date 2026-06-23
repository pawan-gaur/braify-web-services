package com.braify.feature.audit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Immutable audit-trail entry.  Every action that changes a resource is recorded here.
 * As of the compliance upgrade the document also captures IP address, User-Agent,
 * severity, outcome and a SHA-256 integrity hash for tamper detection.
 *
 * <p><b>Visibility rules</b>
 * <ul>
 *   <li>PLATFORM_ADMIN — sees all entries across all organisations</li>
 *   <li>ORG_ADMIN      — sees all entries within their own organisation
 *                        (performedByRole = ORG_ADMIN | ADMIN | USER)</li>
 *   <li>ADMIN          — sees only ADMIN + USER entries within their org
 *                        (ORG_ADMIN actions are hidden)</li>
 *   <li>USER           — sees only their own entries</li>
 * </ul>
 *
 * <p>The {@code performedByRole} field is the authoritative source for role-based
 * filtering.  Storing the role at write-time means visibility is always evaluated
 * against the role the user <em>held when they acted</em>, not their current role.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_logs")
@CompoundIndexes({
    // Dominant read: org-scoped feed sorted newest-first (dashboard + audit page)
    @CompoundIndex(name = "idx_org_timestamp", def = "{'organizationId':1,'timestamp':-1}")
})
public class AuditLog {

    // ── Enumerations ──────────────────────────────────────────────────────────

    public enum Action {
        // Generic CRUD
        CREATED, UPDATED, DELETED, RESTORED,
        READ,                   // read / download access

        // User profile
        PASSWORD_CHANGED, AVATAR_UPDATED,
        DEACTIVATED, ACTIVATED,
        MFA_ENABLED, MFA_DISABLED, MFA_RECOVERY_USED,   // multi-factor auth

        // Session
        LOGIN,                  // user authenticated
        LOGOUT,                 // user logged out / session revoked
        SESSION_REVOKED,        // admin-forced revocation

        // E-Sign
        SENT,                   // document sent to client
        CANCELLED,              // e-sign document cancelled

        // API Keys
        API_KEY_CREATED,        // new API key provisioned
        API_KEY_REVOKED,        // API key permanently deactivated
        API_KEY_TOGGLED,        // API key enabled/disabled

        // Org-level
        FEATURES_UPDATED,       // org feature assignment changed
        MFA_POLICY_CHANGED,     // org MFA policy changed by PLATFORM_ADMIN
        PLATFORM_SETTINGS_UPDATED, // platform-wide security/access policies changed by PLATFORM_ADMIN
        SUBSCRIPTION_CHANGED,   // org subscription plan changed
        BRANDING_UPDATED,       // org branding settings changed
        QUOTA_EXCEEDED,         // a quota limit was hit (recorded as FAILURE)

        // Sharing
        TEMPLATE_SHARED,        // template shared with another org
        TEMPLATE_UNSHARED       // template share revoked
    }

    /** Distinguishes which resource type generated this entry. */
    public enum ResourceType {
        TEMPLATE,        // PDF template
        EMAIL_TEMPLATE,  // Email template
        USER,            // User / profile actions
        ORGANIZATION,    // Organization-level actions
        E_SIGN,          // E-Sign document lifecycle
        SHARING,         // Org-to-org template sharing
        API_KEY,         // Organisation API key lifecycle
        DOCUMENT,        // Uploaded file / document (legacy alias for FILE)
        FILE,            // File storage operations
        SESSION,         // Login / logout / session management
        BULK_EMAIL,      // Bulk email job lifecycle
        CLOUD_CONFIG,    // Cloud storage configuration
        PLATFORM         // Platform-wide settings (security & access policies)
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

    /** Primary user identifier — the userId of the acting user (stable across renames). */
    private String performedByUserId;

    /**
     * Email of the acting user at the time of the action (display / search convenience).
     * Use {@code performedByUserId} as the stable key; this field may change if the
     * user updates their e-mail address.
     */
    private String performedBy;

    /** Display name snapshot at the time of the action. */
    private String performedByName;

    /**
     * Role the acting user held <em>at the time of the action</em>.
     * Stored as a string (e.g. "PLATFORM_ADMIN", "ORG_ADMIN", "ADMIN", "USER")
     * so that visibility filtering is evaluated against the historical role, not
     * the user's current role after a potential role change.
     */
    @Indexed
    private String performedByRole;

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
