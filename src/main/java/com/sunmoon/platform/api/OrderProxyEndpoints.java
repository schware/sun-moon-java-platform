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
     *
     * <p>{@code tranNo} rides along on the same call — 거래번호는 POS가
     * 채번해서 수락과 함께 들고 옵니다 (umbrella docs/adr/0009). 이
     * 서버는 만들지도 검사하지도 않고 넘기기만 합니다: 같은 번호가 두
     * 번 오는 것은 Order 쪽 데이터베이스의 유일 제약이 막습니다.
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
                return passThrough(orders.changeStatus(
                        orderId, status, deviceId, param(request.uri(), "tranNo")));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /**
     * {@code POST /orders/pickup?id=1&pin=0042&deviceId=kds-01}
     *
     * <p>수령 확인. Order의 답을 상태 코드까지 그대로 넘깁니다 —
     * 403(번호 불일치)과 409(아직/이미)는 직원이 손님에게 할 말이
     * 다르고, 여기서 200으로 뭉개면 그 구분이 사라집니다.
     */
    public RestEndpoint handOver() {
        return request -> {
            String rawId = param(request.uri(), "id");
            if (rawId == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "id is required"));
            }
            long orderId;
            try {
                orderId = Long.parseLong(rawId);
            } catch (NumberFormatException notANumber) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "id must be a number"));
            }
            try {
                // 강제 진행은 이 서버가 판단하지 않고 넘기기만 합니다 —
                // 번호를 볼지 말지는 Order 의 규칙이고, 여기서 한 번 더
                // 거르면 같은 규칙이 두 곳에 생깁니다.
                boolean override = "true".equalsIgnoreCase(param(request.uri(), "override"));
                return passThrough(orders.handOver(
                        orderId, param(request.uri(), "pin"),
                        param(request.uri(), "deviceId"), override));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /**
     * {@code POST /orders/received?id=1&deviceId=pos-01}
     *
     * <p>POS가 주문을 받아 저장했다고 알리는 자리. 상태 전이가 아니라
     * 전달 사실이라 {@code /orders/status} 와 따로 있습니다 — 한 곳에
     * 섞으면 "상태를 바꾸지 않는 상태 변경"이 됩니다.
     */
    public RestEndpoint markReceived() {
        return request -> {
            String rawId = param(request.uri(), "id");
            String deviceId = param(request.uri(), "deviceId");
            if (rawId == null || deviceId == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "id and deviceId are required"));
            }
            long orderId;
            try {
                orderId = Long.parseLong(rawId);
            } catch (NumberFormatException notANumber) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "id must be a number"));
            }
            try {
                return passThrough(orders.markReceived(orderId, deviceId));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /** {@code GET /business-days/status?storeId=store-01} — whether that store has 개점'd. */
    public RestEndpoint businessDayStatus() {
        return request -> {
            String storeId = param(request.uri(), "storeId");
            if (storeId == null || storeId.isBlank()) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "storeId is required"));
            }
            try {
                return passThrough(orders.businessDayStatus(storeId));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /** {@code POST /business-days/open} — body {@code {storeId, deviceId}}, forwarded to Order as-is. */
    public RestEndpoint openBusinessDay() {
        return request -> {
            try {
                return passThrough(orders.postBusinessDay("open", request.content().toString(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /** {@code POST /business-days/close} — same body shape as open. */
    public RestEndpoint closeBusinessDay() {
        return request -> {
            try {
                return passThrough(
                        orders.postBusinessDay("close", request.content().toString(StandardCharsets.UTF_8)));
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
