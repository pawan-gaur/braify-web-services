package com.braify.feature.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Pluggable AI configuration. One active provider is selected via {@code braify.ai.provider};
 * each provider carries its own API key / model / base URL so an operator can switch providers
 * by config alone. The feature is considered available only when the active provider has a key.
 *
 * <pre>
 * braify.ai.enabled=true
 * braify.ai.provider=anthropic          # anthropic | openai | gemini | grok
 * braify.ai.anthropic.api-key=${ANTHROPIC_API_KEY:}
 * braify.ai.anthropic.model=claude-opus-4-8
 * braify.ai.openai.api-key=${OPENAI_API_KEY:}
 * braify.ai.openai.model=gpt-4o
 * braify.ai.gemini.api-key=${GEMINI_API_KEY:}
 * braify.ai.gemini.model=gemini-1.5-pro
 * braify.ai.grok.api-key=${GROK_API_KEY:}
 * braify.ai.grok.model=grok-2-latest
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "braify.ai")
public class AiProperties {

    /** Master switch; when false the endpoint reports "not configured" regardless of keys. */
    private boolean enabled = false;

    /** Active provider id: anthropic | openai | gemini | grok. */
    private String provider = "anthropic";

    /** Upper bound on generated tokens for a single assist call. */
    private int maxOutputTokens = 8000;

    private Provider anthropic = new Provider("https://api.anthropic.com/v1", "claude-opus-4-8");
    private Provider openai    = new Provider("https://api.openai.com/v1",     "gpt-4o");
    private Provider gemini    = new Provider("https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-pro");
    private Provider grok      = new Provider("https://api.x.ai/v1",           "grok-2-latest");

    @Data
    public static class Provider {
        private String apiKey = "";
        private String model;
        private String baseUrl;

        public Provider() {}
        public Provider(String baseUrl, String model) { this.baseUrl = baseUrl; this.model = model; }

        public boolean hasKey() { return apiKey != null && !apiKey.isBlank(); }
    }
}
