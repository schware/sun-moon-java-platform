package com.sunmoon.platform.core;

/**
 * Runtime-wide configuration. Ports separate exposure boundaries within the
 * one runtime (docs/adr/0009): BO on its own port (the one published when
 * deployed), the Order API on another, raw Socket on a third.
 */
public final class RuntimeConfig {

    private final int boPort;
    private final int apiPort;
    private final int socketPort;
    private final int workerThreads;
    private final boolean secureCookies;

    private RuntimeConfig(int boPort, int apiPort, int socketPort, int workerThreads, boolean secureCookies) {
        this.boPort = boPort;
        this.apiPort = apiPort;
        this.socketPort = socketPort;
        this.workerThreads = workerThreads;
        this.secureCookies = secureCookies;
    }

    public static RuntimeConfig fromEnv() {
        var env = System.getenv();
        // PaaS hosts (Render among them) inject PORT and expect the published
        // service to bind exactly it. BO is the published surface
        // (docs/adr/0007), so PORT lands there when BO_PORT isn't set.
        String publishedPort = env.getOrDefault("PORT", "8080");
        return new RuntimeConfig(
                Integer.parseInt(env.getOrDefault("BO_PORT", publishedPort)),
                Integer.parseInt(env.getOrDefault("API_PORT", "8083")),
                Integer.parseInt(env.getOrDefault("SOCKET_PORT", "9090")),
                Integer.parseInt(env.getOrDefault("WORKER_THREADS",
                        String.valueOf(Runtime.getRuntime().availableProcessors() * 4))),
                Boolean.parseBoolean(env.getOrDefault("COOKIE_SECURE", "false"))
        );
    }

    public int boPort() {
        return boPort;
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

    /**
     * Adds {@code Secure} to the BO session cookie. Off by default because
     * local development is plain HTTP and a {@code Secure} cookie would
     * simply never be sent; must be on wherever this is served over TLS
     * (docs/adr/0004, docs/adr/0007).
     */
    public boolean secureCookies() {
        return secureCookies;
    }
}
