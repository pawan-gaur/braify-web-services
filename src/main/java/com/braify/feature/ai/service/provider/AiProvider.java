package com.braify.feature.ai.service.provider;

/**
 * A single LLM backend. Implementations wrap one vendor's HTTP API behind a uniform
 * "system + user prompt in, plain text out" contract so the assist service stays
 * provider-agnostic.
 */
public interface AiProvider {

    /** Stable id matched against {@code braify.ai.provider} (e.g. "anthropic"). */
    String id();

    /** True when this provider has an API key configured and can be called. */
    boolean isConfigured();

    /** The model id this provider will call (for display/telemetry). */
    String model();

    /**
     * Run a single completion.
     *
     * @return the model's plain-text response.
     * @throws AiProviderException on transport / API errors.
     */
    String complete(String systemPrompt, String userPrompt, int maxTokens);

    /** Thrown when a provider call fails; carries a user-safe message. */
    class AiProviderException extends RuntimeException {
        public AiProviderException(String message, Throwable cause) { super(message, cause); }
        public AiProviderException(String message) { super(message); }
    }
}
