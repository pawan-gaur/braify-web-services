package com.braify.feature.esign.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateDocumentRequest {
    @NotBlank
    private String title;
    @NotBlank
    private String sourceType;   // "TEMPLATE" | "UPLOAD"
    private String templateId;   // required when sourceType == TEMPLATE
    private String pdfBase64;    // required when sourceType == UPLOAD

    /**
     * Single-signer client (legacy / bulk). Optional when {@link #signatories} is provided —
     * in that case the document mirrors the first signatory into these fields. When no
     * signatories are given these must be present (validated in the service).
     */
    @Email
    private String clientEmail;
    private String clientName;

    /**
     * Multi-party signatories. When non-empty the document is created with these signers
     * (each gets their own signing link); {@link #signingMode} controls ordering.
     */
    private List<SignatoryRequest> signatories;

    /** "PARALLEL" (default) or "SEQUENTIAL". */
    private String signingMode;

    private int    tokenValidDays = 7;
    /** Optional — links this document to a bulk batch created via {@code POST /api/esign/batches/init}. */
    private String bulkBatchId;

    /**
     * Optional — ID of an {@link com.braify.feature.email.model.EmailTemplate} to use for the
     * signing invitation email instead of the default hardcoded HTML.
     * Placeholders {@code {{clientName}}}, {@code {{documentTitle}}}, {@code {{signingLink}}},
     * and {@code {{orgName}}} are substituted automatically.
     */
    private String emailTemplateId;

    /**
     * Optional — list of CC email addresses included in the signing invitation.
     * For bulk send these are populated per-row from the Excel sheet's CC column.
     */
    private List<String> ccEmails;

    /**
     * Optional — recipients who receive a copy of the FINAL signed PDF by email once
     * signing is complete (distinct from {@link #ccEmails}, which CCs the invitation).
     */
    private List<String> completionCcEmails;

    /** A single signatory in a multi-party signing request. */
    @Data
    public static class SignatoryRequest {
        @NotBlank
        private String name;
        @NotBlank @Email
        private String email;
        /** 1-based signing order; used for SEQUENTIAL mode. Defaults to list position if omitted. */
        private Integer signingOrder;
    }
}
