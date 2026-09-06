package com.sunmoon.platform.infrastructure.messaging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fake adapter — records published events in memory instead of sending them to Kafka (see docs/adr/0003). Useful for assertions in tests. */
public final class InMemoryEventPublisher implements EventPublisher {

    public record PublishedEvent(String topic, String key, String payload) {
    }

    private final List<PublishedEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String topic, String key, String payload) {
        events.add(new PublishedEvent(topic, key, payload));
    }

    public List<PublishedEvent> events() {
        return List.copyOf(events);
    }
}
