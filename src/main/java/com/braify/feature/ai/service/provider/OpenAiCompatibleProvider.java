package com.braify.feature.ai.service.provider;

import com.braify.feature.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Shared logic for OpenAI-style /chat/completions APIs (OpenAI, xAI Grok). */
abstract class OpenAiCompatibleProvider extends AbstractHttpAiProvider {

    protected abstract AiProperties.Provider cfg();

    @Override public boolean isConfigured() { return cfg().hasKey(); }
    @Override public String model() { return cfg().getModel(); }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        JsonNode res = postJson(cfg().getBaseUrl() + "/chat/completions",
                h -> h.setBearerAuth(cfg().getApiKey()),
                Map.of(
                        "model", cfg().getModel(),
                        "max_tokens", maxTokens,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))
                ));
        String text = res.path("choices").path(0).path("message").path("content").asText(null);
        if (text == null || text.isBlank()) throw new AiProviderException(id() + " returned no message content");
        return text;
    }
}
