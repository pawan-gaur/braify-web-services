package com.braify.feature.onboarding.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents an organisation onboarding request submitted from the public "Get Started" form.
 * A Platform Admin reviews the request and can Approve, Reject, or ask for more Information.
 * On approval, an Organisation and its first ORG_ADMIN user are automatically created.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "onboarding_requests")
public class OnboardingRequest {

    public enum Status {
        PENDING,       // freshly submitted, awaiting PA review
        APPROVED,      // org + user created, invite email sent
        REJECTED,      // rejected with optional reason
        INFO_REQUIRED  // PA needs more details from the applicant
    }

    @Id
    private String id;

    // ── Applicant ─────────────────────────────────────────────────────────────
    private String applicantName;

    @Indexed(unique = true)
    private String applicantEmail;

    // ── Organisation details ──────────────────────────────────────────────────
    private String organizationName;
    private String description;
    private String address;
    private String state;
    private String region;
    private String country;

    // ── Requested features ────────────────────────────────────────────────────
    private List<String> requestedFeatures;

    // ── Review outcome ────────────────────────────────────────────────────────
    @Builder.Default
    private Status status = Status.PENDING;

    /** Note written by the reviewing Platform Admin (reason for rejection / info request). */
    private String reviewNote;

    /** Email of the Platform Admin who last reviewed this request. */
    private String reviewedBy;

    private LocalDateTime reviewedAt;

    /**
     * Features actually granted on approval — may differ from {@link #requestedFeatures}
     * if the PA adjusted them during review.
     */
    private List<String> approvedFeatures;

    /** ID of the Organisation created when this request was approved. */
    private String createdOrganizationId;

    /** Who created this record — the applicant's email (public self-signup form). */
    private String createdBy;

    @CreatedDate
    private LocalDateTime submittedAt;
}
