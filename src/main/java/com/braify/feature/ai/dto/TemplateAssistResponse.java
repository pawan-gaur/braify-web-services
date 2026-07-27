package com.braify.feature.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Result of an AI edit. status: OK | REFUSED | UNAVAILABLE | ERROR. */
@Data
@AllArgsConstructor
public class TemplateAssistResponse {

    private String status;
    /** For OK: the HTML (full template in REWRITE, a fragment in INSERT). */
    private String html;
    /** REWRITE | INSERT — echoes the mode so the client knows how to apply the html. */
    private String mode;
    /** Human-readable message for non-OK statuses. */
    private String message;
    private String provider;
    private String model;

    public static TemplateAssistResponse ok(String html, String mode, String provider, String model) {
        return new TemplateAssistResponse("OK", html, mode, null, provider, model);
    }
    public static TemplateAssistResponse refused(String message, String provider, String model) {
        return new TemplateAssistResponse("REFUSED", null, null, message, provider, model);
    }
    public static TemplateAssistResponse unavailable(String message) {
        return new TemplateAssistResponse("UNAVAILABLE", null, null, message, null, null);
    }
    public static TemplateAssistResponse error(String message, String provider, String model) {
        return new TemplateAssistResponse("ERROR", null, null, message, provider, model);
    }
}
