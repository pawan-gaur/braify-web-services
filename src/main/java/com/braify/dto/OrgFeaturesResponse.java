package com.braify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response body for GET /api/organizations/{id}/features
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgFeaturesResponse {
    private String organizationId;
    private String organizationName;
    private List<String> features;
}
