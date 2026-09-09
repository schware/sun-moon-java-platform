package com.sunmoon.platform;

/**
 * This runtime's configuration. Two listeners, so two ports: the REST/
 * WebSocket API and the raw Socket transport. BO's port is not here — BO
 * is a separate service with its own config (docs/adr/0014).
 *
 * <p>The defaults are this runtime's slots in the family-wide scheme
 * (docs/adr/0013): 8081-8089 for REST APIs, 90x1 for sockets by language.
 */
public final class PlatformConfig {

    private final int apiPort;
    private final int socketPort;
    private final int workerThreads;

    private PlatformConfig(int apiPort, int socketPort, int workerThreads) {
        this.apiPort = apiPort;
        this.socketPort = socketPort;
        this.workerThreads = workerThreads;
    }

    public static PlatformConfig fromEnv() {
        var env = System.getenv();
        return new PlatformConfig(
                Integer.parseInt(env.getOrDefault("API_PORT", env.getOrDefault("PORT", "8083"))),
                Integer.parseInt(env.getOrDefault("SOCKET_PORT", "9011")),
                Integer.parseInt(env.getOrDefault("WORKER_THREADS",
                        String.valueOf(Runtime.getRuntime().availableProcessors() * 4))));
    }

    public int apiPort() {
        return apiPort;
    }

    public int socketPort() {
        return socketPort;
    }

    /**
     * Size of the pool REST endpoints run on (docs/adr/0010). Blocking DB
     * work is the reason it exists, so the useful ceiling is related to
     * {@code POSTGRES_POOL_SIZE} — threads beyond that just queue on
     * HikariCP instead of on the executor.
     */
    public int workerThreads() {
        return workerThreads;
    }
}
