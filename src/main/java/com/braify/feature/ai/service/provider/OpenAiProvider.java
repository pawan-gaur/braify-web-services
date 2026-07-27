package com.braify.feature.ai.service.provider;

import com.braify.feature.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** OpenAI GPT via the Chat Completions API. */
@Component
@RequiredArgsConstructor
public class OpenAiProvider extends OpenAiCompatibleProvider {

    private final AiProperties props;

    @Override protected AiProperties.Provider cfg() { return props.getOpenai(); }
    @Override public String id() { return "openai"; }
}
