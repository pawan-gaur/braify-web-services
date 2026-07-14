package com.braify.feature.branding.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Org-level branding settings embedded inside the {@link Organization} document.
 * Stored as a nested BSON object — no separate collection needed.
 *
 * <ul>
 *   <li>{@code logoBase64}        — full data-URL (e.g. {@code data:image/png;base64,...})</li>
 *   <li>{@code primaryColor}      — CSS hex colour used in PDF headers, emails, and the app UI</li>
 *   <li>{@code accentColor}       — secondary CSS hex colour for highlights</li>
 *   <li>{@code emailSenderName}   — display name for outbound e-sign / notification emails</li>
 *   <li>{@code emailReplyTo}      — reply-to address for outbound emails</li>
 *   <li>{@code footerText}        — plain-text legal disclaimer / tagline (max 500 chars)</li>
 *   <li>{@code featureRoleAccess} — per-feature role whitelist, e.g.
 *       {@code {"PDF_TEMPLATES": ["ORG_ADMIN","ADMIN"], "E_SIGN": ["ORG_ADMIN"]}}.
 *       If a feature is absent from the map, all roles with the org feature enabled may access it.
 *       ORG_ADMIN always has access regardless.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgBranding {

    /**
     * Base64 data-URL of the organisation logo. Used for in-app display and as the source
     * for a new upload. When the org has cloud storage, the logo is offloaded to the bucket
     * on save and this may be cleared in favour of {@link #logoUrl}. Data URLs do NOT render
     * in email clients (Gmail blocks {@code data:} images) — emails use {@link #logoUrl}.
     */
    private String logoBase64;

    /**
     * Stable, public https URL for the logo, served by the backend
     * ({@code /api/public/branding/{orgId}/logo}) which streams the bytes from cloud storage.
     * This is what renders in emails. Null when no logo is set.
     */
    private String logoUrl;

    // Cloud storage reference for the offloaded logo bytes (null when logo lives only as base64).
    private String logoBucket;
    private String logoKey;
    private String logoProvider;     // AWS | AZURE | GCP
    private String logoContentType;  // e.g. image/png

    /**
     * Primary brand colour as a CSS hex string, e.g. {@code #6366f1}.
     * Injected as {@code --brand-primary} CSS variable in PDFs, emails, and the web app.
     */
    private String primaryColor;

    /**
     * Secondary / accent brand colour as a CSS hex string.
     * Injected as {@code --brand-accent} CSS variable.
     */
    private String accentColor;

    /** Display name shown in the "From:" field of outbound emails. */
    private String emailSenderName;

    /** Reply-to email address for outbound emails. */
    private String emailReplyTo;

    /**
     * Footer text appended to every generated PDF and outbound email.
     * Typically a legal disclaimer or company tagline (max 500 characters).
     */
    private String footerText;

    /**
     * Per-feature role whitelist for this organisation.
     *
     * <p>Key   = feature key, e.g. {@code "PDF_TEMPLATES"}<br>
     * Value  = list of role names allowed to access that feature, e.g.
     *          {@code ["ORG_ADMIN", "ADMIN"]}.
     *
     * <p>Rules:
     * <ul>
     *   <li>If this map is {@code null} or a feature key is absent → all roles may access it.</li>
     *   <li>{@code ORG_ADMIN} always has access and cannot be removed from any feature.</li>
     *   <li>Only roles present in the list can access the feature; others are blocked.</li>
     * </ul>
     */
    private Map<String, List<String>> featureRoleAccess;

    /** ID of the AppUser who first configured this branding; preserved across subsequent updates. */
    private String createdBy;
}
