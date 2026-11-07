package com.braify.feature.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A template drafted by AI from an uploaded document. Nothing is saved: the client reviews the
 * draft and creates the template through the normal template API.
 * status: OK | REFUSED | UNAVAILABLE | ERROR.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTemplateDraft {

    private String status;
    /** Human-readable message for non-OK statuses. */
    private String message;

    private String name;
    private String description;
    /** EMAIL drafts only. */
    private String subject;
    private String htmlContent;
    private String cssContent;
    private List<Placeholder> placeholders;

    /** DOCX | PDF | HTML | TEXT */
    private String sourceType;
    private String sourceFileName;
    private String provider;
    private String model;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Placeholder {
        private String key;
        private String label;
        /** A realistic value taken from the source document, for previews. */
        private String example;
    }

    public static DocumentTemplateDraft unavailable(String message) {
        return DocumentTemplateDraft.builder().status("UNAVAILABLE").message(message).build();
    }
    public static DocumentTemplateDraft error(String message, String provider, String model) {
        return DocumentTemplateDraft.builder().status("ERROR").message(message).provider(provider).model(model).build();
    }
    public static DocumentTemplateDraft refused(String message, String provider, String model) {
        return DocumentTemplateDraft.builder().status("REFUSED").message(message).provider(provider).model(model).build();
    }
}
