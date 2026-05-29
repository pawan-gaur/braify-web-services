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
    private String sourcePdfBase64;       // null for list view; populated on detail
    private String signedPdfBase64;       // null until COMPLETED
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
        private int     page;
        private double  x, y, width, height;
        private String  fieldType;
        private String  label;
        private boolean required;
        private boolean signed;
        private String  signingMethod;

        public static FieldResponse from(ESignSignatureField f) {
            return FieldResponse.builder()
                    .id(f.getId())
                    .page(f.getPage())
                    .x(f.getX()).y(f.getY())
                    .width(f.getWidth()).height(f.getHeight())
                    .fieldType(f.getFieldType().name())
                    .label(f.getLabel())
                    .required(f.isRequired())
                    .signed(f.getValue() != null && !f.getValue().isBlank())
                    .signingMethod(f.getSigningMethod() != null ? f.getSigningMethod().name() : null)
                    .build();
        }
    }

    public static DocumentResponse from(ESignDocument doc, List<ESignSignatureField> fields, boolean includePdf) {
        return DocumentResponse.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .status(doc.getStatus().name())
                .clientEmail(doc.getClientEmail())
                .clientName(doc.getClientName())
                .sourceType(doc.getSourceType() != null ? doc.getSourceType().name() : null)
                .bulkBatchId(doc.getBulkBatchId())
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
                .fields(fields.stream().map(FieldResponse::from).toList())
                .build();
    }
}
