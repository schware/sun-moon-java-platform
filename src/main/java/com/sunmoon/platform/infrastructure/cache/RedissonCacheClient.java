package com.sunmoon.platform.infrastructure.cache;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Real adapter over Redis via Redisson. Not live-verified in this
 * environment — no Redis instance available (see docs/adr/0003). Swap this
 * in for {@link InMemoryCacheClient} once a real Redis instance is reachable.
 */
public final class RedissonCacheClient implements CacheClient {

    private final RedissonClient redisson;

    public RedissonCacheClient(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redisson.<String>getBucket(key).set(value, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisson.<String>getBucket(key).get());
    }

    @Override
    public void withLock(String lockKey, Runnable criticalSection) {
        RLock lock = redisson.getLock(lockKey);
        lock.lock();
        try {
            criticalSection.run();
        } finally {
            lock.unlock();
        }
    }
}
