package com.braify.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrgBrandingResponse {

    private String organizationId;
    private String organizationName;

    private String logoBase64;
    private String primaryColor;
    private String emailSenderName;
    private String emailReplyTo;
    private String footerText;

    /** True when at least one branding field has been configured. */
    private boolean configured;
}
