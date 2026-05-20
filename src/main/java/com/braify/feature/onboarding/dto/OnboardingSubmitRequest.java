package com.braify.feature.onboarding.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class OnboardingSubmitRequest {
    @NotBlank
    private String applicantName;
    @NotBlank @Email
    private String applicantEmail;
    @NotBlank
    private String organizationName;
    private String description;
    private String address;
    private String state;
    private String region;
    private String country;
    private List<String> requestedFeatures;
}
