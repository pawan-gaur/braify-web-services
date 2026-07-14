package com.braify.feature.branding.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class OrgBrandingResponse {

    private String organizationId;
    private String organizationName;

    private String logoBase64;
    /** Stable public URL for the logo (served from cloud); what emails/PDFs use. Null when no logo. */
    private String logoUrl;
    private String primaryColor;
    private String accentColor;
    private String emailSenderName;
    private String emailReplyTo;
    private String footerText;

    /**
     * Per-feature role whitelist as saved.
     * Null when no access restrictions have been configured.
     */
    private Map<String, List<String>> featureRoleAccess;

    /** True when at least one branding field has been configured. */
    private boolean configured;
}
