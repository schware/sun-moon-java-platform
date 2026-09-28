package com.sunmoon.platform.api.auth;

import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.pairing.TerminalTokens;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 누가 이 주소를 불러도 되는가 (umbrella docs/design/store-app.md §2.2).
 *
 * <p>이 서버(8087)는 단말이 인터넷에서 붙어야 해서 공개입니다. 그런데
 * 2026-09-28 까지 <b>HTTP 인증이 전혀 없었습니다</b> — 인터넷의 누구든 긴급
 * 공지를 띄우고, 매장을 마감시키고, 프로그램 배포를 등록할 수 있었습니다.
 * 마지막 것은 Electron 자동 업데이트와 만나면 원격 코드 실행이 됩니다.
 *
 * <h2>청중 셋</h2>
 *
 * <ul>
 *   <li>{@link Audience#PUBLIC} — 확인 없음. health, 기준 시각, 지표.
 *   <li>{@link Audience#TERMINAL} — 기기 토큰 ({@code Authorization: Device <token>}).
 *   <li>{@link Audience#ADMIN} — 관리 토큰 ({@code X-Admin-Token}). BO 가 보냅니다.
 *   <li>{@link Audience#TERMINAL_OR_ADMIN} — 단말도 BO 도 읽는 주소
 *       (배포 계획은 장비가 가져가고 BO 가 들여다봅니다).
 * </ul>
 *
 * <h2>표 하나, 모르는 주소는 관리</h2>
 *
 * <p>분류는 {@link #AUDIENCE} 한 곳에 있습니다. <b>표에 없는 주소는 관리로
 * 봅니다</b> — 새 주소를 만들고 여기 적는 것을 잊으면, 열리는 쪽이 아니라
 * 닫히는 쪽으로 틀립니다. 기동할 때 분류 안 된 주소를 로그에 남깁니다.
 *
 * <h2>켜는 방식</h2>
 *
 * <p>env {@code API_AUTH=off|warn|enforce}, 기본 {@code warn}. {@code warn} 은
 * 확인에 실패해도 통과시키고 로그에 남깁니다. <b>모르는 채로 막으면 전 매장이
 * 조용히 멈춥니다</b> — 단말 인증({@code DEVICE_DIRECTORY})을 켤 때와 같은
 * 이유입니다. 로그가 조용해지면 {@code enforce}.
 */
public final class ApiGuard {

    private static final Logger log = LoggerFactory.getLogger(ApiGuard.class);

    public enum Mode { OFF, WARN, ENFORCE }

    public enum Audience { PUBLIC, TERMINAL, ADMIN, TERMINAL_OR_ADMIN }

    /**
     * 주소마다 청중. 여기 없는 주소는 {@link Audience#ADMIN}.
     *
     * <p>BO 가 부르는 주소(HttpPosOpsRepository, HttpTerminalDirectory)는 전부
     * ADMIN 이거나 TERMINAL_OR_ADMIN 이어야 합니다 — 안 그러면 enforce 에서
     * BO 화면이 깨집니다.
     */
    static final Map<RouteKey, Audience> AUDIENCE = new LinkedHashMap<>();

    static {
        put(HttpMethod.GET, "/health", Audience.PUBLIC);
        put(HttpMethod.GET, "/pos/time", Audience.PUBLIC);
        // Prometheus 가 localhost 에서 30초마다 긁습니다. 요청에 출발지 주소가
        // 없어서(엔드포인트가 받는 것은 요청뿐) localhost 만 풀 수가 없고,
        // 관리로 두면 긁기가 막힙니다. 지표는 기계의 사실이라 공개로 둡니다 —
        // store-app.md §2.2 와 다른 점으로 적어 둡니다.
        put(HttpMethod.GET, "/metrics", Audience.PUBLIC);
        // 페어링은 코드 자체가 증명입니다 (§3.4). 무차별 대입은 PairingService 가 막습니다.
        put(HttpMethod.POST, "/pos/pair", Audience.PUBLIC);

        for (String path : new String[] {
                "/orders/status", "/orders/pickup", "/orders/recall", "/orders/received",
                "/sales", "/etc-sales", "/pos/business", "/pos/heartbeat",
                "/pos/masters/ack", "/pos/programs/ack", "/pos/notices/ack",
                "/business-days/open", "/business-days/close",
                "/orders/start", "/orders/force-produced",
                "/pos/print-jobs/ack", "/pos/print-jobs/reprint"}) {
            put(HttpMethod.POST, path, Audience.TERMINAL);
        }
        for (String path : new String[] {
                "/orders", "/pos/notices", "/menus", "/business-days/status", "/kds/mode",
                "/pos/printers", "/pos/print-jobs"}) {
            put(HttpMethod.GET, path, Audience.TERMINAL);
        }
        // 장비가 가져가고 BO 도 들여다봅니다.
        put(HttpMethod.GET, "/pos/masters", Audience.TERMINAL_OR_ADMIN);
        put(HttpMethod.GET, "/pos/programs", Audience.TERMINAL_OR_ADMIN);

        // 관리 — 표에 없어도 관리지만, 적어 두면 "잊은 것" 과 "정한 것" 이 구분됩니다.
        for (String path : new String[] {
                "/terminals", "/sales/totals", "/etc-sales/totals", "/pos/business",
                "/pos/business/history", "/pos/live", "/pos/masters/pending", "/reprocess",
                "/pos/programs/status", "/pos/notices/all", "/pos/devices"}) {
            put(HttpMethod.GET, path, Audience.ADMIN);
        }
        for (String path : new String[] {
                "/terminals/push", "/terminals/broadcast", "/pos/masters/version",
                "/reprocess/one", "/reprocess/all", "/pos/programs/release", "/pos/notices",
                "/orders/legacy", "/pos/pairing-codes", "/pos/devices/revoke"}) {
            put(HttpMethod.POST, path, Audience.ADMIN);
        }
    }

    /**
     * 지금 처리 중인 요청을 보낸 기기. 단말 토큰이 확인된 요청에서만 있습니다.
     *
     * <p>엔드포인트는 요청만 받으므로 신원을 여기로 넘깁니다. 엔드포인트 호출은
     * 동기(응답을 돌려줌)라 같은 스레드에서 끝나고, 끝나면 지웁니다.
     */
    private static final ThreadLocal<DeviceIdentity> CURRENT = new ThreadLocal<>();

    public static Optional<DeviceIdentity> currentDevice() {
        return Optional.ofNullable(CURRENT.get());
    }

    private static void put(HttpMethod method, String path, Audience audience) {
        AUDIENCE.put(new RouteKey(method, path), audience);
    }

    public static Audience audienceOf(RouteKey key) {
        return AUDIENCE.getOrDefault(key, Audience.ADMIN);
    }

    private final Mode mode;
    private final byte[] adminToken;
    private final TerminalTokens terminals;

    /** 같은 이유의 실패를 1분에 한 번만 로그로 — 단말이 15초마다 부릅니다. */
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> suppressed = new ConcurrentHashMap<>();

    public ApiGuard(Mode mode, String adminToken, TerminalTokens terminals) {
        this.mode = mode;
        this.adminToken = adminToken == null || adminToken.isBlank()
                ? null : adminToken.getBytes(StandardCharsets.UTF_8);
        this.terminals = terminals == null ? TerminalTokens.NONE : terminals;
        if (mode != Mode.OFF && this.adminToken == null) {
            log.warn("ADMIN_TOKEN 이 없습니다 — 관리 주소를 증명할 방법이 없습니다. "
                    + "enforce 에서는 BO 를 포함해 모든 관리 요청이 거절됩니다");
        }
        log.info("API 인증: {} (관리 토큰 {})", mode, this.adminToken == null ? "없음" : "있음");
    }

    public Mode mode() {
        return mode;
    }

    public TerminalTokens terminals() {
        return terminals;
    }

    public static Mode modeFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.WARN;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // 오타로 인증이 꺼지면 안 됩니다. 모르는 값은 warn — 막지도 풀지도 않고 말합니다.
            log.error("API_AUTH 값을 모릅니다: {} — warn 으로 갑니다", raw);
            return Mode.WARN;
        }
    }

    /** 경로 표 전체를 감쌉니다. 분류 안 된 주소는 로그에 남깁니다. */
    public Map<RouteKey, RestEndpoint> protect(Map<RouteKey, RestEndpoint> routes) {
        Map<RouteKey, RestEndpoint> guarded = new LinkedHashMap<>();
        for (Map.Entry<RouteKey, RestEndpoint> route : routes.entrySet()) {
            Audience audience = audienceOf(route.getKey());
            if (!AUDIENCE.containsKey(route.getKey())) {
                log.warn("분류 안 된 주소 — 관리로 봅니다: {} {}",
                        route.getKey().method(), route.getKey().path());
            }
            guarded.put(route.getKey(), wrap(route.getKey(), audience, route.getValue()));
        }
        return guarded;
    }

    private RestEndpoint wrap(RouteKey key, Audience audience, RestEndpoint endpoint) {
        if (mode == Mode.OFF || audience == Audience.PUBLIC) {
            return endpoint;
        }
        return request -> {
            Verdict verdict = check(audience, request);
            if (verdict.ok()) {
                return handleAs(verdict.identity().orElse(null), endpoint, request);
            }
            report(key, verdict.reason());
            if (mode == Mode.WARN) {
                return endpoint.handle(request);
            }
            return JsonResponses.of(HttpResponseStatus.UNAUTHORIZED,
                    Map.of("error", "인증이 필요합니다", "reason", verdict.reason()));
        };
    }

    private static io.netty.handler.codec.http.FullHttpResponse handleAs(
            DeviceIdentity device, RestEndpoint endpoint, FullHttpRequest request) {
        if (device == null) {
            return endpoint.handle(request);
        }
        CURRENT.set(device);
        try {
            return endpoint.handle(request);
        } finally {
            CURRENT.remove();
        }
    }

    record Verdict(boolean ok, String reason, Optional<DeviceIdentity> identity) {
        static Verdict pass(Optional<DeviceIdentity> identity) {
            return new Verdict(true, null, identity);
        }

        static Verdict fail(String reason) {
            return new Verdict(false, reason, Optional.empty());
        }
    }

    Verdict check(Audience audience, FullHttpRequest request) {
        boolean admin = isAdmin(request);
        Optional<DeviceIdentity> device = device(request);
        return switch (audience) {
            case PUBLIC -> Verdict.pass(Optional.empty());
            case ADMIN -> admin ? Verdict.pass(Optional.empty()) : Verdict.fail("NO_ADMIN_TOKEN");
            case TERMINAL -> device.isPresent() ? Verdict.pass(device) : Verdict.fail("NO_DEVICE_TOKEN");
            case TERMINAL_OR_ADMIN -> admin || device.isPresent()
                    ? Verdict.pass(device) : Verdict.fail("NO_TOKEN");
        };
    }

    private boolean isAdmin(FullHttpRequest request) {
        if (adminToken == null) {
            return false;
        }
        String given = request.headers().get("X-Admin-Token");
        // 길이까지 새지 않게 상수 시간 비교.
        return given != null
                && MessageDigest.isEqual(adminToken, given.getBytes(StandardCharsets.UTF_8));
    }

    private Optional<DeviceIdentity> device(FullHttpRequest request) {
        String header = request.headers().get("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Device ", 0, 7)) {
            return Optional.empty();
        }
        return terminals.resolve(header.substring(7).trim());
    }

    private void report(RouteKey key, String reason) {
        String what = key.method() + " " + key.path() + " " + reason;
        long now = System.currentTimeMillis();
        Long last = lastLogged.get(what);
        AtomicLong quiet = suppressed.computeIfAbsent(what, k -> new AtomicLong());
        if (last != null && now - last < 60_000) {
            quiet.incrementAndGet();
            return;
        }
        lastLogged.put(what, now);
        long skipped = quiet.getAndSet(0);
        String prefix = mode == Mode.WARN ? "[warn — 통과시킴] " : "[거절] ";
        log.warn("{}인증 없는 요청: {}{}", prefix, what,
                skipped > 0 ? " (지난 1분 동안 " + skipped + "번 더)" : "");
    }

    /** 표에 적힌 주소 — 시험용. */
    static Set<RouteKey> classified() {
        return AUDIENCE.keySet();
    }
}
