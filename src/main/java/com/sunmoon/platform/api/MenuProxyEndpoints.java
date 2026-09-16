package com.sunmoon.platform.api;

import com.sunmoon.platform.infrastructure.appserver.AppServerClient;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The terminal's <b>local master</b>: what this store sells today, and
 * what it has run out of.
 *
 * <p>Same shape as {@link OrderProxyEndpoints} and for the same reason —
 * the terminal asks one origin. The difference is where the answer comes
 * from: orders come from OMS, the menu board comes from APP-SERVER, which
 * reads BO. A terminal knows neither.
 *
 * <p>This is the "Get" half again. 결품 arrives as a WebSocket nudge
 * ({@code MENU_SOLD_OUT}), and the terminal then asks here for what is
 * true. A screen that was offline when the 결품 was declared still finds
 * out, because it re-reads on connect.
 */
public final class MenuProxyEndpoints {

    private static final Logger log = LoggerFactory.getLogger(MenuProxyEndpoints.class);

    private final AppServerClient appServer;

    public MenuProxyEndpoints(AppServerClient appServer) {
        this.appServer = appServer;
    }

    /** {@code GET /menus?storeId=store-01} — 결품과 예상시간이 붙은 오늘의 메뉴판. */
    public RestEndpoint menus() {
        return request -> {
            String storeId = param(request.uri(), "storeId");
            if (storeId == null || storeId.isBlank()) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "storeId is required"));
            }
            try {
                return passThrough(appServer.menus(storeId, param(request.uri(), "date")));
            } catch (Exception e) {
                // 502, not 500: the fault is upstream, and the terminal
                // should say which side is down rather than blame itself.
                log.warn("APP-SERVER unreachable", e);
                return JsonResponses.of(HttpResponseStatus.BAD_GATEWAY,
                        Map.of("error", "메뉴 서버에 연결할 수 없습니다"));
            }
        };
    }

    private static FullHttpResponse passThrough(HttpResponse<String> upstream) {
        byte[] body = upstream.body().getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(upstream.statusCode()),
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return response;
    }

    private static String param(String uri, String name) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
