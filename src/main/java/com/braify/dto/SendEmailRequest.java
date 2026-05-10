package com.braify.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request body for POST /api/email-templates/{id}/send
 */
@Data
public class SendEmailRequest {

    /** Recipient email address (required). */
    private String to;

    /**
     * Optional subject override.
     * If omitted, the template's own subject field is used.
     */
    private String subject;

    /**
     * Placeholder values to substitute into the template HTML.
     * Keys must match the {{placeholderName}} tokens declared in the template.
     */
    private Map<String, String> placeholders;
}
