package com.braify.feature.esign.dto;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSignatureField;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class DocumentResponse {
    private String id;
    private String title;
    private String status;
    private String clientEmail;
    private String clientName;
    private String sourceType;
    private String bulkBatchId;
    private List<String> ccEmails;            // CC on the signing invitation
    private List<String> completionCcEmails;  // recipients of the final signed PDF

    /* ── Signatories ───────────────────────────────────────────────────── */
    private String signingMode;               // PARALLEL | SEQUENTIAL
    private List<SignatoryResponse> signatories;
    /** Set only in the signing context — the signatory the current token belongs to. */
    private String currentSignatoryId;
    private String sourcePdfBase64;       // LEGACY: embedded-byte docs only; null for cloud-stored docs
    private String signedPdfBase64;       // LEGACY: embedded-byte docs only; null for cloud-stored docs
    private String sourcePdfUrl;          // pre-signed cloud URL for the source PDF (cloud-stored docs)
    private String signedPdfUrl;          // pre-signed cloud URL for the signed PDF (cloud-stored docs)
    /** True only for the flow participants (creator + signatories); gates the PDF viewer/download. */
    private boolean canViewPdf;
    private String signedPdfHash;
    private boolean allowClientUpload;              // whether client may upload supporting docs after signing
    private List<String> allowedClientUploadFileTypes; // empty = all types accepted
    private LocalDateTime sentAt;
    private LocalDateTime viewedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime tokenExpiresAt;
    private LocalDateTime createdAt;
    private List<FieldResponse> fields;

    @Data @Builder
    public static class FieldResponse {
        private String  id;
        private String  signatoryId;   // which signatory fills this field (null = legacy single-signer)
        private int     page;
        private double  x, y, width, height;
        private String  fieldType;
        private String  label;
        private boolean required;
        private boolean signed;
        private String  signingMethod;
        private String  value;         // signed value (signature image / typed text) — for showing prior signatures
        private LocalDateTime signedAt;
        private String  signedAtDisplay; // signedAt rendered in the signer's timezone, e.g. "Jul 4, 2026 03:50:27 GMT+2"
        private String  signerName;    // resolved name of the signatory who owns/filled the field

        public static FieldResponse from(ESignSignatureField f, String signerName) {
            return FieldResponse.builder()
                    .id(f.getId())
                    .signatoryId(f.getSignatoryId())
                    .page(f.getPage())
                    .x(f.getX()).y(f.getY())
                    .width(f.getWidth()).height(f.getHeight())
                    .fieldType(f.getFieldType().name())
                    .label(f.getLabel())
                    .required(f.isRequired())
                    .signed(f.getValue() != null && !f.getValue().isBlank())
                    .signingMethod(f.getSigningMethod() != null ? f.getSigningMethod().name() : null)
                    .value(f.getValue())
                    .signedAt(f.getSignedAt())
                    .signedAtDisplay(f.getSignedAt() != null
                            ? com.braify.feature.esign.service.ESignTimeFormat.caption(f.getSignedAt(), f.getSignedTimeZone())
                            : null)
                    .signerName(signerName)
                    .build();
        }
    }

    /** Public view of a signatory — never exposes the token jti. */
    @Data @Builder
    public static class SignatoryResponse {
        private String  id;
        private String  name;
        private String  email;
        private int     signingOrder;
        private String  status;
        private LocalDateTime viewedAt;
        private LocalDateTime signedAt;

        public static SignatoryResponse from(ESignDocument.Signatory s) {
            return SignatoryResponse.builder()
                    .id(s.getId())
                    .name(s.getName())
                    .email(s.getEmail())
                    .signingOrder(s.getSigningOrder())
                    .status(s.getStatus() != null ? s.getStatus().name() : null)
                    .viewedAt(s.getViewedAt())
                    .signedAt(s.getSignedAt())
                    .build();
        }
    }

    public static DocumentResponse from(ESignDocument doc, List<ESignSignatureField> fields, boolean includePdf) {
        // Resolve each field's signer name from the document's signatories (fallback: the client name).
        java.util.Map<String, String> signatoryNames = new java.util.HashMap<>();
        if (doc.getSignatories() != null)
            doc.getSignatories().forEach(s -> signatoryNames.put(s.getId(), s.getName()));
        java.util.function.Function<ESignSignatureField, String> nameFor = f ->
                f.getSignatoryId() != null && signatoryNames.containsKey(f.getSignatoryId())
                        ? signatoryNames.get(f.getSignatoryId())
                        : doc.getClientName();

        return DocumentResponse.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .status(doc.getStatus().name())
                .clientEmail(doc.getClientEmail())
                .clientName(doc.getClientName())
                .sourceType(doc.getSourceType() != null ? doc.getSourceType().name() : null)
                .bulkBatchId(doc.getBulkBatchId())
                .ccEmails(doc.getCcEmails())
                .completionCcEmails(doc.getCompletionCcEmails())
                .signingMode(doc.getSigningMode() != null ? doc.getSigningMode().name() : null)
                .signatories(doc.getSignatories() == null ? List.of()
                        : doc.getSignatories().stream().map(SignatoryResponse::from).toList())
                .sourcePdfBase64(includePdf && doc.getSourcePdfData() != null
                        ? java.util.Base64.getEncoder().encodeToString(doc.getSourcePdfData()) : null)
                .signedPdfBase64(includePdf && doc.getSignedPdfData() != null
                        ? java.util.Base64.getEncoder().encodeToString(doc.getSignedPdfData()) : null)
                .signedPdfHash(doc.getSignedPdfHash())
                .allowClientUpload(doc.isAllowClientUpload())
                .allowedClientUploadFileTypes(doc.getAllowedClientUploadFileTypes())
                .sentAt(doc.getSentAt())
                .viewedAt(doc.getViewedAt())
                .submittedAt(doc.getSubmittedAt())
                .completedAt(doc.getCompletedAt())
                .tokenExpiresAt(doc.getTokenExpiresAt())
                .createdAt(doc.getCreatedAt())
                .fields(fields.stream().map(f -> FieldResponse.from(f, nameFor.apply(f))).toList())
                .build();
    }
}
