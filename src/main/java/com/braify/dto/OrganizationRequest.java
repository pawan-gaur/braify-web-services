package com.braify.dto;

import lombok.Data;

@Data
public class OrganizationRequest {
    private String name;
    private String slug;
    private String description;
}
