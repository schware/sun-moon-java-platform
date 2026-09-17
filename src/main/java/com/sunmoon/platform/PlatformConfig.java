package com.sunmoon.platform;

import java.time.Duration;

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
    private final String orderServiceUrl;
    private final String appServerUrl;
    private final String redisUrl;
    private final Duration terminalGrace;
    private final Duration heartbeatGrace;
    private final Duration clockTolerance;

    private PlatformConfig(int apiPort, int socketPort, int workerThreads,
                           String orderServiceUrl, String appServerUrl, String redisUrl,
                           Duration terminalGrace, Duration heartbeatGrace, Duration clockTolerance) {
        this.apiPort = apiPort;
        this.socketPort = socketPort;
        this.workerThreads = workerThreads;
        this.orderServiceUrl = orderServiceUrl;
        this.appServerUrl = appServerUrl;
        this.redisUrl = redisUrl;
        this.terminalGrace = terminalGrace;
        this.heartbeatGrace = heartbeatGrace;
        this.clockTolerance = clockTolerance;
    }

    public static PlatformConfig fromEnv() {
        var env = System.getenv();
        return new PlatformConfig(
                Integer.parseInt(env.getOrDefault("API_PORT", env.getOrDefault("PORT", "8087"))),
                Integer.parseInt(env.getOrDefault("SOCKET_PORT", "9011")),
                Integer.parseInt(env.getOrDefault("WORKER_THREADS",
                        String.valueOf(Runtime.getRuntime().availableProcessors() * 4))),
                env.getOrDefault("ORDER_SERVICE_URL", "http://localhost:8083/order"),
                env.getOrDefault("APP_SERVER_URL", "http://localhost:8086/app-server"),
                env.get("REDIS_URL"),
                Duration.parse(env.getOrDefault("TERMINAL_GRACE", "PT3M")),
                Duration.parse(env.getOrDefault("POS_HEARTBEAT_GRACE", "PT2M")),
                Duration.parse(env.getOrDefault("POS_CLOCK_TOLERANCE", "PT30S")));
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

    /** Where the Spring Order service listens, context-path included. */
    public String orderServiceUrl() {
        return orderServiceUrl;
    }

    /**
     * Where APP-SERVER listens, context-path included. Terminals read
     * their menu master (결품 included) through this server rather than
     * going there directly — APP-SERVER is LAN-only and terminals are
     * opened from the public hub.
     */
    public String appServerUrl() {
        return appServerUrl;
    }

    /**
     * {@code redis://host:port}. Absent means no subscription — terminals
     * still connect and can still fetch orders, they just are not nudged.
     * A machine with only a JDK stays runnable.
     */
    public String redisUrl() {
        return redisUrl;
    }

    /**
     * How long after a terminal disconnects its shop still counts as
     * staffed. Inside this window a placed order waits instead of being
     * refused, because a screen that just dropped is usually reconnecting.
     *
     * <p>Worth keeping below Order's acceptance timeout: past that the
     * order expires anyway, and a grace period longer than it would only
     * change which of the two endings the customer is told about.
     */
    public Duration terminalGrace() {
        return terminalGrace;
    }

    /**
     * Heartbeat 가 이보다 오래되면 그 POS의 보고를 <b>모른다</b>로
     * 칩니다 (§3.5).
     *
     * <p>{@link #terminalGrace()} 와 따로인 이유: 저쪽은 "소켓이 잠깐
     * 끊겼어도 사람은 매장에 있다"는 판단이고, 이쪽은 "마지막으로
     * 정상이라고 말한 뒤에 죽었을 수 있다"는 판단입니다. 하나로 묶으면
     * 둘 중 하나는 반드시 틀린 값으로 돕니다.
     *
     * <p>POS의 보고 주기보다 넉넉해야 합니다 — 주기와 같으면 한 번만
     * 늦어도 멀쩡한 단말이 UNKNOWN 이 됩니다.
     */
    public Duration heartbeatGrace() {
        return heartbeatGrace;
    }

    /**
     * POS 시계가 이보다 더 틀어지면 손봐야 합니다 (§3.5 System Date 동기화).
     *
     * <p>30초가 기본인 이유: 영수증 시각이 몇 초 다른 것은 아무도
     * 모르지만, 자정 언저리에 1분이 틀어지면 <b>매출이 다른 날로
     * 갑니다</b>. 판매일자는 하루의 경계라 작은 오차가 큰 사고가 되는
     * 유일한 자리입니다 (umbrella docs/adr/0006).
     */
    public Duration clockTolerance() {
        return clockTolerance;
    }
}
