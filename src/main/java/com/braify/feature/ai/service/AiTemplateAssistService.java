package com.braify.feature.ai.service;

import com.braify.feature.ai.config.AiProperties;
import com.braify.feature.ai.dto.TemplateAssistRequest;
import com.braify.feature.ai.dto.TemplateAssistResponse;
import com.braify.feature.ai.service.provider.AiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Orchestrates one AI template edit: build the scoped prompts, call the active provider,
 * then enforce scope on the OUTPUT (refusal detection + HTML sanitisation) before returning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTemplateAssistService {

    private final AiProviderRegistry registry;
    private final TemplatePromptBuilder prompts;
    private final AiProperties props;

    // Second-line output guards — strip anything the model shouldn't emit even if it slips past the prompt.
    private static final Pattern SCRIPT   = Pattern.compile("(?is)<script\\b.*?</script>");
    private static final Pattern IFRAME   = Pattern.compile("(?is)<iframe\\b.*?</iframe>");
    private static final Pattern ON_ATTR  = Pattern.compile("(?is)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URL   = Pattern.compile("(?is)(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2");
    private static final Pattern FENCE    = Pattern.compile("(?is)^```[a-z]*\\s*|\\s*```$");

    public TemplateAssistResponse assist(TemplateAssistRequest req) {
        if (!registry.isAvailable()) {
            return TemplateAssistResponse.unavailable(
                    "AI assist is not configured. Set braify.ai.enabled=true and an API key for the active provider.");
        }
        AiProvider provider = registry.active().orElseThrow();
        String mode = "INSERT".equalsIgnoreCase(req.getMode()) ? "INSERT" : "REWRITE";
        String context = "EMAIL".equalsIgnoreCase(req.getContext()) ? "EMAIL" : "PDF";

        String system = prompts.systemPrompt(context, mode);
        String user   = prompts.userPrompt(req.getCurrentHtml(), req.getInstruction());

        String raw;
        try {
            raw = provider.complete(system, user, props.getMaxOutputTokens());
        } catch (AiProvider.AiProviderException e) {
            log.warn("AI assist call failed: {}", e.getMessage());
            return TemplateAssistResponse.error("The AI provider could not complete the request. " + e.getMessage(),
                    provider.id(), provider.model());
        }

        String out = raw == null ? "" : raw.trim();

        // Scope guard 1: explicit refusal from the model.
        if (out.startsWith(TemplatePromptBuilder.REFUSAL_MARKER)) {
            String msg = out.substring(TemplatePromptBuilder.REFUSAL_MARKER.length()).trim();
            return TemplateAssistResponse.refused(
                    msg.isBlank() ? "I can only help with your template." : msg, provider.id(), provider.model());
        }

        String html = sanitize(out);

        // Scope guard 2: a non-HTML answer (no tags at all) is treated as off-topic.
        if (!html.contains("<")) {
            return TemplateAssistResponse.refused(
                    "I can only help you build and edit this template.", provider.id(), provider.model());
        }
        return TemplateAssistResponse.ok(html, mode, provider.id(), provider.model());
    }

    /** Remove markdown fences and any disallowed active content. */
    private String sanitize(String s) {
        String html = FENCE.matcher(s).replaceAll("").trim();
        html = SCRIPT.matcher(html).replaceAll("");
        html = IFRAME.matcher(html).replaceAll("");
        html = ON_ATTR.matcher(html).replaceAll("");
        html = JS_URL.matcher(html).replaceAll("$1=\"#\"");
        return html.trim();
    }
}
