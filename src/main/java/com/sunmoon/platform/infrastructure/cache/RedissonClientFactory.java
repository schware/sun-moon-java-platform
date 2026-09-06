package com.sunmoon.platform.infrastructure.cache;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

public final class RedissonClientFactory {

    public static RedissonClient create(String redisUrl) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        return Redisson.create(config);
    }

    public static String redisUrlFromEnv() {
        return System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
    }

    private RedissonClientFactory() {
    }
}
