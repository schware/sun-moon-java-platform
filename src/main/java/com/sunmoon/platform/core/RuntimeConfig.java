package com.sunmoon.platform.core;

/**
 * Runtime-wide configuration. Grows one field at a time as each
 * transport/persistence piece is wired up, rather than pre-declaring the
 * whole eventual shape.
 */
public final class RuntimeConfig {

    private final int httpPort;
    private final int socketPort;

    private RuntimeConfig(int httpPort, int socketPort) {
        this.httpPort = httpPort;
        this.socketPort = socketPort;
    }

    public static RuntimeConfig fromEnv() {
        int httpPort = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));
        int socketPort = Integer.parseInt(System.getenv().getOrDefault("SOCKET_PORT", "9090"));
        return new RuntimeConfig(httpPort, socketPort);
    }

    public static RuntimeConfig forTest(int httpPort, int socketPort) {
        return new RuntimeConfig(httpPort, socketPort);
    }

    public int httpPort() {
        return httpPort;
    }

    public int socketPort() {
        return socketPort;
    }
}
