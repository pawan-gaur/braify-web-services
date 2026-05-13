package com.braify.dto;

import lombok.Data;

import java.util.List;

@Data
public class OnboardingReviewRequest {
    /** APPROVE | REJECT | INFO_REQUIRED */
    private String action;

    /** Optional note to the applicant (reason for rejection / info needed). */
    private String note;

    /**
     * Features to grant on approval — Platform Admin can adjust from what was requested.
     * Required when action = APPROVE.
     */
    private List<String> approvedFeatures;
}
