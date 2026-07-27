package com.braify.feature.ai.controller;

import com.braify.feature.ai.dto.TemplateAssistRequest;
import com.braify.feature.ai.dto.TemplateAssistResponse;
import com.braify.feature.ai.service.AiProviderRegistry;
import com.braify.feature.ai.service.AiTemplateAssistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI assist for the PDF and email template builders. Scope is enforced server-side: the
 * assistant only edits the supplied template and refuses anything else.
 */
@Tag(name = "AI Assist", description = "Scoped AI editing for PDF & email templates.")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAssistController {

    private final AiTemplateAssistService assistService;
    private final AiProviderRegistry registry;

    /** Lets the UI show/hide the assist bar and name the active provider. */
    @Operation(summary = "Whether AI assist is configured and available")
    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean available = registry.isAvailable();
        Map<String, Object> out = new HashMap<>();   // allows null values (Map.of does not)
        out.put("available", available);
        out.put("provider", registry.active().map(p -> p.id()).orElse(null));
        out.put("model", available ? registry.active().map(p -> p.model()).orElse(null) : null);
        return out;
    }

    @Operation(summary = "Apply a natural-language change to a PDF or email template")
    @PostMapping("/template-assist")
    public TemplateAssistResponse assist(@Valid @RequestBody TemplateAssistRequest request) {
        return assistService.assist(request);
    }
}
