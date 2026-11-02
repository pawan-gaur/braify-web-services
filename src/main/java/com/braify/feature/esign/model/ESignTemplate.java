package com.braify.feature.esign.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable e-sign setup: a source PDF plus pre-placed fields assigned to signer <em>roles</em>
 * (e.g. "Employee", "Manager") instead of concrete people. "Use template" creates an ordinary
 * DRAFT {@link ESignDocument} with the roles filled by real signatories, so the send / sign flow
 * is unchanged. Shared across the organisation.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_templates")
public class ESignTemplate {

    @Id private String id;
    @Indexed private String orgId;

    private String name;
    private String description;
    /** Default title for documents created from this template. */
    private String documentTitle;

    /* ── Source PDF (copied into org storage; independent of the original document) ── */
    private String pdfBucket;
    private String pdfKey;
    private String pdfProvider;
    private String pdfHash;

    @Builder.Default
    private ESignDocument.SigningMode signingMode = ESignDocument.SigningMode.PARALLEL;
    /** Signer roles in signing order (index = role index referenced by {@link Field#getRoleIndex()}). */
    @Builder.Default
    private List<String> roles = new ArrayList<>();
    @Builder.Default
    private List<Field> fields = new ArrayList<>();

    private String emailTemplateId;
    private List<String> completionCcEmails;

    private String createdBy;        // userId
    private String createdByEmail;
    @Builder.Default
    private int useCount = 0;
    private LocalDateTime lastUsedAt;
    @CreatedDate      private LocalDateTime createdAt;
    @LastModifiedDate private LocalDateTime updatedAt;

    /** A field placement; mirrors {@link ESignSignatureField} minus any signer-supplied value. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Field {
        /** Index into {@link #roles}; ignored for CREATOR fields. */
        private int     roleIndex;
        private String  filledBy;        // SIGNER | CREATOR
        private int     page;
        private double  x, y, width, height;
        private String  fieldType;
        private String  label;
        private boolean required;
        private Integer fontSize;
        /** Author pre-filled value (CREATOR fields only, e.g. a company stamp). */
        private String  value;
        private String  signingMethod;
    }
}
