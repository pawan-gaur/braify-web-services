package com.braify.feature.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link PlatformEvent}s. Never throws: a webhook / notification problem must not
 * fail the business action (a signature, an upload) that produced the event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publish(PlatformEvent event) {
        if (event == null || event.orgId() == null) return;
        try {
            publisher.publishEvent(event);
        } catch (Exception e) {
            log.warn("Event {} ({}) listener failed: {}", event.type(), event.id(), e.getMessage());
        }
    }
}
