package com.sunmoon.platform.api;

import com.sunmoon.platform.infrastructure.order.OrderClient;
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
 * What a terminal needs from Order, offered on the Device Server's own
 * origin.
 *
 * <p>This is the "Get" half of the flow. The WebSocket event is only a
 * nudge — a terminal that was offline when an order was placed still has
 * to see it, and an event that arrived out of order must not become the
 * terminal's truth. So the terminal is told *something changed*, and then
 * asks here for what is actually true.
 *
 * <p>Order's answer is passed through unchanged, status code included.
 * Re-wrapping it would mean this server having an opinion about orders,
 * and it does not have one.
 */
public final class OrderProxyEndpoints {

    private static final Logger log = LoggerFactory.getLogger(OrderProxyEndpoints.class);

    private final OrderClient orders;

    public OrderProxyEndpoints(OrderClient orders) {
        this.orders = orders;
    }

    /** {@code GET /orders?status=PLACED} — the list a POS terminal works from. */
    public RestEndpoint list() {
        return request -> {
            String status = param(request.uri(), "status");
            try {
                return passThrough(orders.listOrders(status));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /**
     * {@code POST /orders/status?id=1&status=ACCEPTED&deviceId=pos-01}
     *
     * <p>A terminal accepting an order is the one place a device id
     * matters, so it is a parameter here rather than something the
     * terminal has to remember to put in a body.
     */
    public RestEndpoint changeStatus() {
        return request -> {
            String rawId = param(request.uri(), "id");
            String status = param(request.uri(), "status");
            String deviceId = param(request.uri(), "deviceId");

            if (rawId == null || status == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "id and status are required"));
            }
            long orderId;
            try {
                orderId = Long.parseLong(rawId);
            } catch (NumberFormatException notANumber) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "id must be a number"));
            }

            try {
                // A 409 from Order means the move was not allowed — that is
                // an answer, not a failure, and it reaches the terminal intact.
                return passThrough(orders.changeStatus(orderId, status, deviceId));
            } catch (Exception e) {
                return unreachable(e);
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

    /** Order being down is a 502, not a 500: the fault is upstream and the terminal should say so. */
    private static FullHttpResponse unreachable(Exception e) {
        log.warn("Order service unreachable", e);
        return JsonResponses.of(HttpResponseStatus.BAD_GATEWAY,
                Map.of("error", "주문 서비스에 연결할 수 없습니다"));
    }

    private static String param(String uri, String name) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
