package com.braify.feature.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** A single AI edit request from a template builder. */
@Data
public class TemplateAssistRequest {

    /** PDF | EMAIL — selects the safety profile and wording. */
    private String context = "PDF";

    /** REWRITE (replace whole template) | INSERT (append a new fragment). */
    private String mode = "REWRITE";

    /** Natural-language change the user wants. */
    @NotBlank
    private String instruction;

    /** The current template HTML the AI should edit (may be empty for a fresh template). */
    private String currentHtml = "";
}
