package com.braify.feature.ai.service.provider;

import com.braify.feature.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Google Gemini via the generateContent API. */
@Component
@RequiredArgsConstructor
public class GeminiProvider extends AbstractHttpAiProvider {

    private final AiProperties props;

    private AiProperties.Provider cfg() { return props.getGemini(); }

    @Override public String id() { return "gemini"; }
    @Override public boolean isConfigured() { return cfg().hasKey(); }
    @Override public String model() { return cfg().getModel(); }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        String url = cfg().getBaseUrl() + "/models/" + cfg().getModel() + ":generateContent?key=" + cfg().getApiKey();
        JsonNode res = postJson(url, h -> {}, Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", Map.of("maxOutputTokens", maxTokens)
        ));
        StringBuilder sb = new StringBuilder();
        JsonNode parts = res.path("candidates").path(0).path("content").path("parts");
        if (parts.isArray()) parts.forEach(p -> sb.append(p.path("text").asText("")));
        if (sb.length() == 0) throw new AiProviderException("Gemini returned no content");
        return sb.toString();
    }
}
