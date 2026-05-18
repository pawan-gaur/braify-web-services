package com.braify.feature.esign.dto;

import lombok.Data;

@Data
public class CreateDocumentRequest {
    private String title;
    private String sourceType;   // "TEMPLATE" | "UPLOAD"
    private String templateId;   // required when sourceType == TEMPLATE
    private String pdfBase64;    // required when sourceType == UPLOAD
    private String clientEmail;
    private String clientName;
    private int    tokenValidDays = 7;
}
