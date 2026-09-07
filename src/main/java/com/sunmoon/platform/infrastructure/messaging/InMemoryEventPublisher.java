package com.sunmoon.platform.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Default publisher — no Kafka adapter is wired yet, so every environment
// (including production) just logs the event. See README "Status".
@Component
public class InMemoryEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);

    @Override
    public void publish(OrderCreatedEvent event) {
        log.info("order-created-event: {}", event);
    }
}
