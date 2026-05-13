package com.braify.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrganizationRequest {
    private String name;
    private String code;
    private String description;

    /**
     * Feature keys to assign on create or update.
     * Unknown keys are silently ignored (sanitised by Feature.sanitise()).
     */
    private List<String> features = new ArrayList<>();
}
