package com.sunmoon.platform.api;

import com.sunmoon.platform.api.auth.ApiGuard;
import com.sunmoon.platform.domain.pairing.DeviceIdentity;
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
    private final KdsAuthority kds;
    /** KIOSK 주문의 값을 매길 메뉴판 (§9). 없으면 KIOSK 주문을 받지 않습니다. */
    private final com.sunmoon.platform.infrastructure.appserver.AppServerClient menuBoard;

    public OrderProxyEndpoints(OrderClient orders) {
        this(orders, KdsAuthority.permissive());
    }

    public OrderProxyEndpoints(OrderClient orders, KdsAuthority kds) {
        this(orders, kds, null);
    }

    public OrderProxyEndpoints(OrderClient orders, KdsAuthority kds,
                               com.sunmoon.platform.infrastructure.appserver.AppServerClient menuBoard) {
        this.orders = orders;
        this.kds = kds;
        this.menuBoard = menuBoard;
    }

    /**
     * 누가 눌렀나. 페어링된 기기면 <b>토큰의 기기</b>이고 쿼리의 {@code deviceId} 는
     * 무시합니다(store-app.md §3.5) — 쿼리는 아무나 아무 값이나 적을 수 있습니다.
     */
    private static String caller(String uri) {
        return ApiGuard.currentDevice().map(DeviceIdentity::deviceId).orElse(param(uri, "deviceId"));
    }

    /**
     * 페어링된 기기가 <b>다른 매장의 주문</b>을 만지려 하면 403 을 돌려줍니다.
     *
     * <p>주문 id 는 쿼리에 있어서 아무 값이나 적을 수 있습니다. 매장을 아는 요청(토큰)은
     * 그 주문이 자기 매장 것인지 한 번 더 읽고 확인합니다. 신원이 없거나 DID(여러 매장)면
     * 확인하지 않고, 주문을 못 읽으면(404 등) 원래 호출이 그 답을 하게 둡니다.
     */
    private FullHttpResponse otherStore(long orderId) throws Exception {
        java.util.Optional<DeviceIdentity> device = ApiGuard.currentDevice();
        if (device.isEmpty() || "DID".equals(device.get().type())) {
            return null;
        }
        HttpResponse<String> found = orders.order(orderId);
        if (found.statusCode() != 200) {
            return null;
        }
        String store = JSON.readTree(found.body()).path("storeId").asText(null);
        return device.get().storeId().equals(store) ? null : forbidden("다른 매장의 주문입니다");
    }

    private static FullHttpResponse forbidden(String reason) {
        return JsonResponses.of(HttpResponseStatus.FORBIDDEN, Map.of("error", reason));
    }

    /**
     * {@code GET /orders?status=PLACED} — the list a POS terminal works from.
     *
     * <p><b>페어링된 POS·KDS 에는 자기 매장 주문만</b> 줍니다 (2026-09-28). 그전에는
     * 전 매장 주문이 단말로 가고 화면이 자기 매장만 걸러 봤습니다 — 이 서버가
     * 공개라, 거르는 쪽이 화면이면 거르지 않는 누군가도 전부를 받습니다.
     * DID 는 여러 매장을 볼 수 있어 거르지 않습니다. 페어링 전 단말은 예전 그대로.
     */
    public RestEndpoint list() {
        return request -> {
            String status = param(request.uri(), "status");
            try {
                HttpResponse<String> upstream = orders.listOrders(status);
                java.util.Optional<DeviceIdentity> device = ApiGuard.currentDevice();
                if (device.isEmpty() || "DID".equals(device.get().type()) || upstream.statusCode() != 200) {
                    return passThrough(upstream);
                }
                return ownStoreOnly(upstream.body(), device.get().storeId());
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static FullHttpResponse ownStoreOnly(String body, String storeId) throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode all = JSON.readTree(body);
        com.fasterxml.jackson.databind.node.ArrayNode mine = JSON.createArrayNode();
        if (all != null && all.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode order : all) {
                if (storeId.equals(order.path("storeId").asText(null))) {
                    mine.add(order);
                }
            }
        }
        byte[] bytes = JSON.writeValueAsBytes(mine);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return response;
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
            String deviceId = caller(request.uri());

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
                if (otherStore(orderId) instanceof FullHttpResponse refused) {
                    return refused;
                }
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
                // 역할은 이 서버가 봅니다 — 역할을 아는 것은 연결을 쥔 쪽뿐입니다 (§4.2).
                KdsAuthority.Decision decision = kds.decide(KdsAuthority.Action.PICKUP, ApiGuard.currentDevice());
                if (!decision.allowed()) {
                    return forbidden(decision.reason());
                }
                if (otherStore(orderId) instanceof FullHttpResponse refused) {
                    return refused;
                }
                return passThrough(orders.handOver(
                        orderId, param(request.uri(), "pin"),
                        caller(request.uri()), override, decision.emergency()));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /**
     * {@code POST /orders/recall?id=1} — 번호를 다시 부릅니다.
     *
     * <p>DID가 흐르는 화면이라 필요한 자리입니다: 조리가 끝난 번호가
     * 잠깐 떴다가 지나가고, 그 뒤에 온 손님에게는 다시 띄워 줘야 합니다.
     * 누르는 곳은 KDS이고 뜨는 곳은 DID라, 둘 사이의 사실은 Order 가
     * 들고 있습니다.
     */
    public RestEndpoint recall() {
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
            KdsAuthority.Decision decision = kds.decide(KdsAuthority.Action.RECALL, ApiGuard.currentDevice());
            if (!decision.allowed()) {
                return forbidden(decision.reason());
            }
            try {
                if (otherStore(orderId) instanceof FullHttpResponse refused) {
                    return refused;
                }
                return passThrough(orders.recall(orderId));
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
            String deviceId = caller(request.uri());
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
                if (otherStore(orderId) instanceof FullHttpResponse refused) {
                    return refused;
                }
                return passThrough(orders.markReceived(orderId, deviceId));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /** {@code GET /business-days/status?storeId=store-01} — whether that store has 개점'd. */
    /**
     * {@code POST /orders/start?id=&deviceId=} — 그 주방의 줄만 조리 시작 (§4.1).
     * SUB 의 일이라 역할을 따지지 않습니다. 어느 줄인지는 누른 기기가 정합니다.
     */
    public RestEndpoint start() {
        return request -> {
            Long orderId = orderId(request.uri());
            String deviceId = caller(request.uri());
            if (orderId == null || deviceId == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "id(숫자)와 deviceId 가 필요합니다"));
            }
            try {
                if (otherStore(orderId) instanceof FullHttpResponse refused) {
                    return refused;
                }
                return passThrough(orders.start(orderId, deviceId));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /** {@code POST /orders/force-produced?id=} — KDS-MAIN 의 강제 전부 완료 (§4.1). */
    public RestEndpoint forceProduced() {
        return request -> {
            Long orderId = orderId(request.uri());
            String deviceId = caller(request.uri());
            if (orderId == null || deviceId == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "id(숫자)와 deviceId 가 필요합니다"));
            }
            KdsAuthority.Decision decision =
                    kds.decide(KdsAuthority.Action.FORCE_PRODUCED, ApiGuard.currentDevice());
            if (!decision.allowed()) {
                return forbidden(decision.reason());
            }
            try {
                if (otherStore(orderId) instanceof FullHttpResponse refused) {
                    return refused;
                }
                return passThrough(orders.forceProduced(orderId, deviceId));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    /**
     * {@code GET /kds/mode?storeId=} — 이 매장이 비상 모드인가 (§4.2).
     * SUB 화면은 비상일 때만 수령 칸을 띄웁니다. 페어링된 기기면 자기 매장.
     */
    public RestEndpoint kdsMode() {
        return request -> {
            String storeId = ApiGuard.currentDevice().map(DeviceIdentity::storeId)
                    .orElse(param(request.uri(), "storeId"));
            if (storeId == null || storeId.isBlank()) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "storeId is required"));
            }
            KdsAuthority.Mode mode = kds.mode(storeId);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("storeId", storeId);
            body.put("emergency", mode.emergency());
            body.put("since", mode.since() == null ? null : mode.since().toString());
            return JsonResponses.of(HttpResponseStatus.OK, body);
        };
    }

    /**
     * {@code POST /orders/kiosk} — 캐셔 없는 POS 의 주문 (store-app.md §9).
     *
     * <p>본문: {@code {lines:[{menuCode, menuName, quantity, unitPrice, kdsDeviceId}], tranNo,
     * payment:{method, approvalNo, amount}, memberNo}}. <b>매장·단말은 본문이 아니라 토큰의
     * 신원</b>에서 씁니다 — KIOSK 로 페어링된 기기만 부를 수 있습니다(모드와 상관없이).
     * Order 에는 수락된 채로 생깁니다({@code POST /orders/accepted}).
     */
    public RestEndpoint kiosk() {
        return request -> {
            java.util.Optional<DeviceIdentity> device = ApiGuard.currentDevice();
            if (device.isEmpty()) {
                return JsonResponses.of(HttpResponseStatus.UNAUTHORIZED,
                        Map.of("error", "페어링된 KIOSK 만 주문을 넣습니다"));
            }
            if (!"KIOSK".equals(device.get().type())) {
                return forbidden(device.get().type() + " 에서는 KIOSK 주문을 넣을 수 없습니다");
            }
            com.fasterxml.jackson.databind.JsonNode body;
            try {
                body = JSON.readTree(request.content().toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                body = null;
            }
            if (body == null || !body.path("lines").isArray() || body.path("lines").isEmpty()
                    || body.path("tranNo").asText("").isBlank()) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "lines 와 tranNo 가 필요합니다"));
            }
            // 값은 메뉴판에서 — 화면이 보낸 단가·이름·주방은 쓰지 않습니다.
            if (menuBoard == null) {
                return JsonResponses.of(HttpResponseStatus.SERVICE_UNAVAILABLE,
                        Map.of("error", "메뉴판에 연결되지 않아 KIOSK 주문을 받을 수 없습니다"));
            }
            KioskPricing.Result priced;
            try {
                HttpResponse<String> board = menuBoard.menus(device.get().storeId(), null);
                if (board.statusCode() != 200) {
                    return JsonResponses.of(HttpResponseStatus.BAD_GATEWAY,
                            Map.of("error", "메뉴판을 읽지 못했습니다 (" + board.statusCode() + ")"));
                }
                priced = KioskPricing.price(body.get("lines"), JSON.readTree(board.body()));
            } catch (Exception e) {
                return unreachable(e);
            }
            if (priced.refusal() != null) {
                return JsonResponses.of(HttpResponseStatus.CONFLICT, Map.of("error", priced.refusal()));
            }
            long total = KioskPricing.total(priced.lines());
            if (body.hasNonNull("payment")
                    && Math.round(body.path("payment").path("amount").asDouble(-1)) != total) {
                return JsonResponses.of(HttpResponseStatus.CONFLICT, Map.of("error",
                        "결제 금액이 메뉴판 값과 다릅니다 (메뉴판 " + total + "원) — 메뉴판이 바뀌었을 수 있습니다"));
            }
            com.fasterxml.jackson.databind.node.ObjectNode order = JSON.createObjectNode();
            order.put("storeId", device.get().storeId());
            String member = body.path("memberNo").asText("");
            order.put("customerId", member.isBlank() ? "kiosk" : member);
            order.set("lines", priced.lines());
            if (body.hasNonNull("payment")) {
                order.set("payment", body.get("payment"));
            }
            com.fasterxml.jackson.databind.node.ObjectNode wrapper = JSON.createObjectNode();
            wrapper.set("order", order);
            wrapper.put("deviceId", device.get().deviceId());
            wrapper.put("tranNo", body.path("tranNo").asText());
            try {
                return passThrough(orders.createAccepted(JSON.writeValueAsString(wrapper)));
            } catch (Exception e) {
                return unreachable(e);
            }
        };
    }

    private static Long orderId(String uri) {
        String raw = param(uri, "id");
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

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
