package com.sunmoon.platform.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCacheClientTest {

    @Test
    void putThenGetReturnsTheValue() {
        CacheClient cache = new InMemoryCacheClient();

        cache.put("k1", "v1", Duration.ofMinutes(1));

        assertEquals("v1", cache.get("k1").orElseThrow());
    }

    @Test
    void getMissingKeyIsEmpty() {
        CacheClient cache = new InMemoryCacheClient();

        assertTrue(cache.get("missing").isEmpty());
    }

    @Test
    void expiredEntryIsTreatedAsMissing() throws InterruptedException {
        CacheClient cache = new InMemoryCacheClient();

        cache.put("k1", "v1", Duration.ofMillis(10));
        Thread.sleep(50);

        assertTrue(cache.get("k1").isEmpty());
    }

    @Test
    void withLockSerializesConcurrentAccessToTheSameKey() throws InterruptedException {
        CacheClient cache = new InMemoryCacheClient();
        AtomicInteger counter = new AtomicInteger(0);
        int threads = 20;
        Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> cache.withLock("shared-lock", () -> {
                int current = counter.get();
                Thread.onSpinWait();
                counter.set(current + 1);
            }));
        }
        for (Thread t : workers) {
            t.start();
        }
        for (Thread t : workers) {
            t.join();
        }

        assertEquals(threads, counter.get());
    }
}
