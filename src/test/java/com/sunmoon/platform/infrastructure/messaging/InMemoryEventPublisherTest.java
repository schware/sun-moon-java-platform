package com.sunmoon.platform.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryEventPublisherTest {

    @Test
    void recordsPublishedEventsInOrder() {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();

        publisher.publish("orders", "order-1", "{\"status\":\"created\"}");
        publisher.publish("orders", "order-2", "{\"status\":\"created\"}");

        assertEquals(2, publisher.events().size());
        assertEquals("order-1", publisher.events().get(0).key());
        assertEquals("orders", publisher.events().get(1).topic());
    }
}
