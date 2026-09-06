package com.sunmoon.platform.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Port for Cache/Session/Lock. Real adapter: {@link RedissonCacheClient}
 * (Redis via Redisson) — not live-verified here, no Redis instance in this
 * environment (see docs/adr/0003). Fake adapter used by default:
 * {@link InMemoryCacheClient}.
 */
public interface CacheClient {
    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    /** Acquires a distributed lock named {@code lockKey}, runs {@code criticalSection}, then releases it — even on failure. */
    void withLock(String lockKey, Runnable criticalSection);
}
