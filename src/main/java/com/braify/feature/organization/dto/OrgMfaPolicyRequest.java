package com.braify.feature.organization.dto;

import com.braify.feature.organization.model.Organization;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Body for PUT /api/organizations/{id}/mfa-policy (PLATFORM_ADMIN). */
@Data
public class OrgMfaPolicyRequest {
    @NotNull
    private Organization.MfaPolicy policy;
}
