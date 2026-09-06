package com.sunmoon.platform.infrastructure.messaging;

/**
 * Port for the Event Bus. Real adapter: {@link KafkaEventPublisher} — not
 * live-verified here, no Kafka broker in this environment (see
 * docs/adr/0003). Fake adapter used by default: {@link InMemoryEventPublisher}.
 */
public interface EventPublisher {
    void publish(String topic, String key, String payload);
}
