package com.braify.feature.placeholder.dto;

import com.braify.feature.placeholder.model.GlobalPlaceholder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for creating / updating a global placeholder.
 */
@Data
public class GlobalPlaceholderRequest {

    /**
     * Placeholder key — this is what templates reference as {@code {{key}}}.
     * Restricted to the same character set the {@code {{...}}} resolver understands
     * (letters, digits, underscore, dot) so a saved key is always usable in a template.
     */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_.]+$",
             message = "Key may only contain letters, digits, underscore and dot")
    @Size(max = 100)
    private String key;

    /** Value substituted for the placeholder (may be empty). */
    @Size(max = 20000)
    private String value;

    @Size(max = 120)
    private String label;

    /** TEXT (default) or IMAGE. */
    private GlobalPlaceholder.Type type;
}
