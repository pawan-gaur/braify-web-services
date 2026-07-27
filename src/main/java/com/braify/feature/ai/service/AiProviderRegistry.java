package com.braify.feature.ai.service;

import com.braify.feature.ai.config.AiProperties;
import com.braify.feature.ai.service.provider.AiProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Resolves the configured active {@link AiProvider}. */
@Slf4j
@Service
public class AiProviderRegistry {

    private final AiProperties props;
    private final List<AiProvider> providers;

    public AiProviderRegistry(AiProperties props, List<AiProvider> providers) {
        this.props = props;
        this.providers = providers;
    }

    /** The provider selected by {@code braify.ai.provider}, if present. */
    public Optional<AiProvider> active() {
        String id = props.getProvider() == null ? "" : props.getProvider().trim().toLowerCase();
        return providers.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** True when the feature is enabled and the active provider has a key. */
    public boolean isAvailable() {
        return props.isEnabled() && active().map(AiProvider::isConfigured).orElse(false);
    }
}
