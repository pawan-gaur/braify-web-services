package com.braify.dto;

import lombok.Data;

@Data
public class OrgBrandingRequest {

    /** Base64 data-URL of the org logo (e.g. {@code data:image/png;base64,...}). Null = remove logo. */
    private String logoBase64;

    /**
     * CSS hex colour string, e.g. {@code #1a73e8}.
     * Must match {@code ^#[0-9A-Fa-f]{6}$}.
     */
    private String primaryColor;

    /** Display name shown in the "From:" field of outbound emails. */
    private String emailSenderName;

    /** Reply-to email address for outbound emails. */
    private String emailReplyTo;

    /** Footer text (max 500 chars) appended to PDFs and emails. */
    private String footerText;
}
