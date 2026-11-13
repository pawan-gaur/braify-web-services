package com.braify.feature.ai.controller;

import com.braify.feature.ai.dto.DocumentTemplateDraft;
import com.braify.feature.ai.dto.TemplateAssistRequest;
import com.braify.feature.ai.dto.TemplateAssistResponse;
import com.braify.feature.ai.service.AiDocumentTemplateService;
import com.braify.feature.ai.service.AiProviderRegistry;
import com.braify.feature.ai.service.AiTemplateAssistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
    private final AiDocumentTemplateService documentTemplateService;
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

    @Operation(summary = "Draft a PDF or email template from an uploaded document",
               description = "Upload a .docx, .pdf, .html or .txt file. The AI rebuilds it as a template with " +
                             "{{placeholders}} for the values that change per use. Nothing is saved — create the " +
                             "template from the returned draft with POST /api/templates or /api/email-templates.")
    @PostMapping(value = "/template-from-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentTemplateDraft templateFromDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kind", defaultValue = "PDF") String kind,
            @RequestParam(value = "instructions", required = false) String instructions) throws IOException {
        if (instructions != null && instructions.length() > 2000) {
            throw new IllegalArgumentException("Notes for the AI can be at most 2000 characters.");
        }
        return documentTemplateService.draft(file.getOriginalFilename(), file.getBytes(), kind, instructions);
    }
}
