package com.braify.dto;

import lombok.Data;

import java.util.List;

@Data
public class OnboardingSubmitRequest {
    private String applicantName;
    private String applicantEmail;
    private String organizationName;
    private String description;
    private String address;
    private String state;
    private String region;
    private String country;
    private List<String> requestedFeatures;
}
