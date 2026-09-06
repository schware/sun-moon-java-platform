package com.sunmoon.platform.infrastructure.messaging;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Real adapter over Kafka. Not live-verified in this environment — no
 * broker available (see docs/adr/0003). Swap this in for
 * {@link InMemoryEventPublisher} once a real Kafka cluster is reachable.
 */
public final class KafkaEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final Producer<String, String> producer;

    public KafkaEventPublisher(Producer<String, String> producer) {
        this.producer = producer;
    }

    public static KafkaEventPublisher create(String bootstrapServers) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        return new KafkaEventPublisher(new KafkaProducer<>(props));
    }

    @Override
    public void publish(String topic, String key, String payload) {
        producer.send(new ProducerRecord<>(topic, key, payload), (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to publish to topic {}", topic, exception);
            }
        });
    }

    @Override
    public void close() {
        producer.close();
    }
}
