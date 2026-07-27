package com.braify.feature.ai.service.provider;

import com.braify.feature.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** xAI Grok via its OpenAI-compatible Chat Completions API. */
@Component
@RequiredArgsConstructor
public class GrokProvider extends OpenAiCompatibleProvider {

    private final AiProperties props;

    @Override protected AiProperties.Provider cfg() { return props.getGrok(); }
    @Override public String id() { return "grok"; }
}
