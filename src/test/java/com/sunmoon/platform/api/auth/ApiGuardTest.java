package com.sunmoon.platform.api.auth;

import com.sunmoon.platform.api.auth.ApiGuard.Audience;
import com.sunmoon.platform.api.auth.ApiGuard.Mode;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGuardTest {

    private static final RouteKey NOTICE_POST = new RouteKey(HttpMethod.POST, "/pos/notices");
    private static final RouteKey ORDERS = new RouteKey(HttpMethod.GET, "/orders");
    private static final RouteKey MASTERS = new RouteKey(HttpMethod.GET, "/pos/masters");
    private static final RouteKey HEALTH = new RouteKey(HttpMethod.GET, "/health");

    private final AtomicInteger calls = new AtomicInteger();
    private final RestEndpoint ok = request -> {
        calls.incrementAndGet();
        return JsonResponses.of(HttpResponseStatus.OK, Map.of("ok", true));
    };

    private int status(ApiGuard guard, RouteKey key, FullHttpRequest request) {
        RestEndpoint endpoint = guard.protect(Map.of(key, ok)).get(key);
        return endpoint.handle(request).status().code();
    }

    private static FullHttpRequest request(RouteKey key) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, key.method(), key.path());
    }

    @Test
    void enforce_admin_route_needs_the_admin_token() {
        ApiGuard guard = new ApiGuard(Mode.ENFORCE, "s3cret");

        assertEquals(401, status(guard, NOTICE_POST, request(NOTICE_POST)));

        FullHttpRequest wrong = request(NOTICE_POST);
        wrong.headers().set("X-Admin-Token", "s3cre");
        assertEquals(401, status(guard, NOTICE_POST, wrong));

        FullHttpRequest right = request(NOTICE_POST);
        right.headers().set("X-Admin-Token", "s3cret");
        assertEquals(200, status(guard, NOTICE_POST, right));
        assertEquals(1, calls.get(), "거절된 요청은 엔드포인트에 닿지 않습니다");
    }

    /** 단말 주소는 토큰 없이 열립니다 (2026-10-01 — 단말 토큰 제외). */
    @Test
    void terminal_routes_are_open_even_in_enforce() {
        ApiGuard guard = new ApiGuard(Mode.ENFORCE, "s3cret");
        assertEquals(200, status(guard, ORDERS, request(ORDERS)));
        assertEquals(200, status(guard, MASTERS, request(MASTERS)));
    }

    @Test
    void no_admin_token_configured_means_nobody_is_admin() {
        ApiGuard guard = new ApiGuard(Mode.ENFORCE, "  ");
        FullHttpRequest empty = request(NOTICE_POST);
        empty.headers().set("X-Admin-Token", "");
        assertEquals(401, status(guard, NOTICE_POST, empty));
    }

    @Test
    void warn_lets_everything_through() {
        ApiGuard guard = new ApiGuard(Mode.WARN, "s3cret");
        assertEquals(200, status(guard, NOTICE_POST, request(NOTICE_POST)));
        assertEquals(200, status(guard, ORDERS, request(ORDERS)));
        assertEquals(2, calls.get());
    }

    @Test
    void public_routes_need_nothing() {
        ApiGuard guard = new ApiGuard(Mode.ENFORCE, "s3cret");
        assertEquals(200, status(guard, HEALTH, request(HEALTH)));
    }

    @Test
    void unknown_routes_are_admin() {
        assertEquals(Audience.ADMIN, ApiGuard.audienceOf(new RouteKey(HttpMethod.POST, "/new-thing")));
    }

    @Test
    void mode_parsing_never_turns_auth_off_by_accident() {
        assertEquals(Mode.WARN, ApiGuard.modeFrom(null));
        assertEquals(Mode.WARN, ApiGuard.modeFrom(""));
        assertEquals(Mode.WARN, ApiGuard.modeFrom("of"));
        assertEquals(Mode.OFF, ApiGuard.modeFrom("off"));
        assertEquals(Mode.ENFORCE, ApiGuard.modeFrom(" Enforce "));
    }

    /**
     * 새 주소를 Bootstrap 에 만들면 이 시험이 깨집니다 — 청중을 정하라는 뜻입니다.
     * 관리로 두려면 아래 ADMIN 목록에, 단말이 부르면 ApiGuard 표에 적습니다.
     */
    @Test
    void every_registered_route_has_a_deliberate_audience() throws IOException {
        Set<RouteKey> admin = Set.of(
                key("GET /terminals"), key("POST /terminals/push"), key("POST /terminals/broadcast"),
                key("GET /sales/totals"), key("GET /etc-sales/totals"),
                key("GET /pos/business"), key("GET /pos/business/history"), key("GET /pos/live"),
                key("POST /pos/masters/version"), key("GET /pos/masters/pending"),
                key("GET /reprocess"), key("POST /reprocess/one"), key("POST /reprocess/all"),
                key("POST /pos/programs/release"), key("GET /pos/programs/status"),
                key("GET /pos/notices/all"), key("POST /pos/notices"),
                key("POST /orders/legacy"));

        Set<RouteKey> registered = registeredRoutes();
        assertTrue(registered.size() > 30, "Bootstrap 의 주소를 못 읽었습니다: " + registered.size());
        for (RouteKey route : registered) {
            boolean deliberate = ApiGuard.classified().contains(route) || admin.contains(route);
            assertTrue(deliberate, "청중이 정해지지 않은 주소: " + route.method() + " " + route.path());
            if (admin.contains(route)) {
                assertEquals(Audience.ADMIN, ApiGuard.audienceOf(route), route.path());
            }
        }
    }

    /** BO 가 부르는 주소는 관리 토큰으로 열려야 합니다 — 안 그러면 enforce 에서 BO 가 깨집니다. */
    @Test
    void every_route_bo_calls_accepts_the_admin_token() {
        for (String call : new String[] {
                "GET /pos/business", "GET /pos/business/history", "GET /pos/live",
                "GET /pos/masters", "GET /pos/masters/pending", "GET /pos/notices/all",
                "GET /pos/programs", "GET /pos/programs/status", "GET /reprocess",
                "POST /reprocess/all", "POST /reprocess/one", "POST /pos/masters/version",
                "POST /pos/notices", "POST /pos/programs/release", "GET /terminals"}) {
            Audience audience = ApiGuard.audienceOf(key(call));
            assertTrue(audience == Audience.ADMIN || audience == Audience.PUBLIC,
                    call + " 는 " + audience);
        }
    }

    private static RouteKey key(String methodAndPath) {
        String[] parts = methodAndPath.split(" ");
        return new RouteKey(HttpMethod.valueOf(parts[0]), parts[1]);
    }

    private static Set<RouteKey> registeredRoutes() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/sunmoon/platform/Bootstrap.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("new RouteKey\\(HttpMethod\\.([A-Z]+), \"([^\"]+)\"\\)").matcher(source);
        Set<RouteKey> routes = new LinkedHashSet<>();
        while (m.find()) {
            routes.add(new RouteKey(HttpMethod.valueOf(m.group(1)), m.group(2)));
        }
        return routes;
    }
}
