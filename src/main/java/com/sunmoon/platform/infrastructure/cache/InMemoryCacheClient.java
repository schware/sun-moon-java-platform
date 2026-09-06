package com.sunmoon.platform.infrastructure.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Fake adapter — in-process map + per-key {@link ReentrantLock} standing in for Redis/Redisson (see docs/adr/0003). Not for multi-process use; that's the whole point of the real adapter. */
public final class InMemoryCacheClient implements CacheClient {

    private record Entry(String value, Instant expiresAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void withLock(String lockKey, Runnable criticalSection) {
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            criticalSection.run();
        } finally {
            lock.unlock();
        }
    }
}
