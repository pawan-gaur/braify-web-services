package com.braify.feature.sharing.dto;

import lombok.Data;

@Data
public class SharingRequest {

    /** ID of the template to share. */
    private String templateId;

    /** TEMPLATE | EMAIL_TEMPLATE */
    private String templateType;

    /** ID of the org to share with. */
    private String targetOrgId;

    /** VIEW | USE | EDIT */
    private String permission;

    /** Optional note to the recipient (max 300 chars). */
    private String note;
}
