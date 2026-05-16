package com.braify.dto;

import com.braify.model.SharedTemplate;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SharingResponse {

    private String                    id;
    private String                    templateId;
    private String                    templateName;
    private SharedTemplate.TemplateType templateType;

    private String sourceOrgId;
    private String sourceOrgName;
    private String targetOrgId;
    private String targetOrgName;

    private SharedTemplate.Permission permission;
    private SharedTemplate.Status     status;

    private String        sharedBy;
    private LocalDateTime sharedAt;
    private String        note;

    /** For EDIT shares: the ID of the forked copy in the target org. */
    private String forkedTemplateId;

    private LocalDateTime revokedAt;
    private String        revokedBy;
}
