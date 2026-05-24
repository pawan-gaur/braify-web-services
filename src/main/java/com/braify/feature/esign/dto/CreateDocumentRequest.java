package com.braify.feature.esign.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateDocumentRequest {
    @NotBlank
    private String title;
    @NotBlank
    private String sourceType;   // "TEMPLATE" | "UPLOAD"
    private String templateId;   // required when sourceType == TEMPLATE
    private String pdfBase64;    // required when sourceType == UPLOAD
    @NotBlank @Email
    private String clientEmail;
    @NotBlank
    private String clientName;
    private int    tokenValidDays = 7;
    /** Optional — links this document to a bulk batch created via {@code POST /api/esign/batches/init}. */
    private String bulkBatchId;
}
