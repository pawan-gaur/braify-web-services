package com.braify.feature.branding.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class OrgBrandingRequest {

    /** Base64 data-URL of the org logo (e.g. {@code data:image/png;base64,...}). Null = remove logo. */
    private String logoBase64;

    /**
     * Primary CSS hex colour string, e.g. {@code #6366f1}.
     * Must match {@code ^#[0-9A-Fa-f]{6}$}.
     */
    private String primaryColor;

    /**
     * Secondary / accent CSS hex colour string.
     * Must match {@code ^#[0-9A-Fa-f]{6}$} when provided.
     */
    private String accentColor;

    /** Display name shown in the "From:" field of outbound emails. */
    private String emailSenderName;

    /** Reply-to email address for outbound emails. */
    private String emailReplyTo;

    /** Footer text (max 500 chars) appended to PDFs and emails. */
    private String footerText;

    /**
     * Per-feature role whitelist.
     * Key = feature key (e.g. "PDF_TEMPLATES"), value = list of allowed role names.
     * Null or omitted = all roles have access (existing behaviour preserved).
     * ORG_ADMIN is always injected server-side regardless of this map.
     */
    private Map<String, List<String>> featureRoleAccess;
}
