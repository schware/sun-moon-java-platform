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

    private RuntimeConfig(int boPort, int apiPort, int socketPort) {
        this.boPort = boPort;
        this.apiPort = apiPort;
        this.socketPort = socketPort;
    }

    public static RuntimeConfig fromEnv() {
        var env = System.getenv();
        return new RuntimeConfig(
                Integer.parseInt(env.getOrDefault("BO_PORT", "8080")),
                Integer.parseInt(env.getOrDefault("API_PORT", "8083")),
                Integer.parseInt(env.getOrDefault("SOCKET_PORT", "9090"))
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
}
