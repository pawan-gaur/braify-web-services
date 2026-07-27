package com.braify.feature.ai.service.provider;

import com.braify.feature.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Anthropic Claude via the Messages API. */
@Component
@RequiredArgsConstructor
public class AnthropicProvider extends AbstractHttpAiProvider {

    private final AiProperties props;

    private AiProperties.Provider cfg() { return props.getAnthropic(); }

    @Override public String id() { return "anthropic"; }
    @Override public boolean isConfigured() { return cfg().hasKey(); }
    @Override public String model() { return cfg().getModel(); }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        JsonNode res = postJson(cfg().getBaseUrl() + "/messages", h -> {
            h.set("x-api-key", cfg().getApiKey());
            h.set("anthropic-version", "2023-06-01");
        }, Map.of(
                "model", cfg().getModel(),
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        ));
        // content is an array of blocks; concatenate any text blocks.
        StringBuilder sb = new StringBuilder();
        JsonNode content = res.path("content");
        if (content.isArray()) {
            content.forEach(block -> {
                if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
            });
        }
        if (sb.length() == 0) throw new AiProviderException("Anthropic returned no text content");
        return sb.toString();
    }
}
