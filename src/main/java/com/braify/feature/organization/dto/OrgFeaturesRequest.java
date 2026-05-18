package com.braify.feature.organization.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for the dedicated feature-management endpoints:
 *   GET  /api/organizations/{id}/features
 *   PUT  /api/organizations/{id}/features
 */
@Data
public class OrgFeaturesRequest {

    /** Ordered list of feature keys to enable for the organisation. */
    private List<String> features = new ArrayList<>();
}
