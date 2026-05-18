package com.braify.feature.apikey.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "api_key_usage_logs")
public class ApiKeyUsageLog {

    @Id
    private String id;

    private String orgId;
    private String apiKeyId;

    /** Short prefix of the key, e.g. "brfy_a1b2c3d", for display */
    private String keyPrefix;

    /** Feature accessed: PDF_TEMPLATES, EMAIL_TEMPLATES, E_SIGN */
    private String feature;

    /** Full endpoint path, e.g. "/api/external/pdf/generate" */
    private String endpoint;

    /** HTTP method: GET, POST, etc. */
    private String method;

    private int statusCode;
    private boolean success;

    @CreatedDate
    private LocalDateTime calledAt;
}
