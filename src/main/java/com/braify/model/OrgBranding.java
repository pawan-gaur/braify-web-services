package com.braify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Org-level branding settings embedded inside the {@link Organization} document.
 * Stored as a nested BSON object — no separate collection needed.
 *
 * <ul>
 *   <li>{@code logoBase64}     — full data-URL (e.g. {@code data:image/png;base64,...})</li>
 *   <li>{@code primaryColor}   — CSS hex colour used in PDF headers and email accents</li>
 *   <li>{@code emailSenderName} — display name for outbound e-sign / notification emails</li>
 *   <li>{@code emailReplyTo}   — reply-to address for outbound emails</li>
 *   <li>{@code footerText}     — plain-text legal disclaimer / tagline (max 500 chars)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgBranding {

    /** Base64 data-URL of the organisation logo; null when not set. */
    private String logoBase64;

    /**
     * Primary brand colour as a CSS hex string, e.g. {@code #1a73e8}.
     * Injected as {@code --brand-color} CSS variable into generated PDFs.
     */
    private String primaryColor;

    /** Display name shown in the "From:" field of outbound emails. */
    private String emailSenderName;

    /** Reply-to email address for outbound emails. */
    private String emailReplyTo;

    /**
     * Footer text appended to every generated PDF and outbound email.
     * Typically a legal disclaimer or company tagline (max 500 characters).
     */
    private String footerText;
}
