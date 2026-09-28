package com.sunmoon.platform.infrastructure.order;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The Device Server's view of the Order service.
 *
 * <p>Terminals talk only to this server — they fetch orders through here
 * and accept them through here. That is not indirection for its own sake:
 * it keeps the terminals on a single origin (no CORS), and it means a
 * terminal never needs to know where Order lives or that it moved.
 *
 * <p>Deliberately thin. This server does not interpret an order, cache
 * one, or decide whether a transition is legal — the state machine lives
 * in Order, and a second copy of it here would be a second answer.
 */
public final class OrderClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String baseUrl;

    public OrderClient(String baseUrl) {
        // Trailing slashes make every later concatenation a guess.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** {@code GET /orders?status=PLACED} — what a terminal asks for after being nudged. */
    public HttpResponse<String> listOrders(String status) throws Exception {
        String uri = baseUrl + "/orders" + (status == null || status.isBlank() ? "" : "?status=" + status);
        return send(HttpRequest.newBuilder(URI.create(uri)).GET());
    }

    /** {@code PUT /orders/{id}/status} — the move itself is still Order's to allow or refuse. */
    public HttpResponse<String> changeStatus(long orderId, String status, String deviceId) throws Exception {
        return changeStatus(orderId, status, deviceId, null);
    }

    /**
     * 수락과 함께 거래번호가 실립니다. <b>POS가 채번한 값</b>이고, 이
     * 서버도 Order도 그것을 만들지 않습니다 (umbrella docs/adr/0009) —
     * 네트워크가 끊긴 매장의 POS도 계속 팔아야 하기 때문입니다.
     */
    public HttpResponse<String> changeStatus(long orderId, String status, String deviceId, String tranNo)
            throws Exception {
        String body = "{\"status\":\"" + status + "\""
                + (deviceId == null ? "" : ",\"deviceId\":" + json(deviceId))
                + (tranNo == null ? "" : ",\"tranNo\":" + json(tranNo))
                + "}";
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + orderId + "/status"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    /**
     * {@code POST /orders/{id}/pickup} — 손님이 번호를 내밀고 가져가는 순간.
     *
     * <p>일반 상태 전이와 따로 부르는 이유는 Order 쪽에서 따로 있는
     * 이유와 같습니다: 번호 검사와 전이가 한 덩어리여야 빠뜨릴 수가
     * 없습니다. 403(번호 불일치)과 409(아직/이미)가 서로 다른 답이고,
     * 둘 다 그대로 단말에 전달됩니다.
     */
    public HttpResponse<String> handOver(long orderId, String pin, String deviceId, boolean override)
            throws Exception {
        return handOver(orderId, pin, deviceId, override, false);
    }

    /** @param emergency KDS-MAIN 이 끊긴 동안 SUB 가 내주는가 — 이 서버가 판단해서 적어 보냅니다. */
    public HttpResponse<String> handOver(long orderId, String pin, String deviceId, boolean override,
                                         boolean emergency) throws Exception {
        String body = "{\"pin\":" + json(pin) + ",\"deviceId\":" + json(deviceId)
                + ",\"override\":" + override + ",\"emergency\":" + emergency + "}";
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + orderId + "/pickup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** {@code POST /orders/{id}/start} — 그 주방의 줄만 조리 시작 (store-app.md §4.1). */
    public HttpResponse<String> start(long orderId, String deviceId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + orderId + "/start?deviceId="
                        + java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** {@code POST /orders/{id}/force-produced} — KDS-MAIN 의 강제 전부 완료. */
    public HttpResponse<String> forceProduced(long orderId, String deviceId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + orderId + "/force-produced?deviceId="
                        + java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /**
     * {@code POST /orders/{id}/recall} — 번호를 다시 부릅니다.
     *
     * <p>본문이 없습니다. 부르는 데 필요한 것은 어느 주문인가뿐이고,
     * 누가 눌렀는지는 KDS 한 대뿐인 매장에서 답이 정해져 있습니다 —
     * 여러 대가 되는 날 붙이면 됩니다.
     */
    public HttpResponse<String> recall(long orderId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + orderId + "/recall"))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** null 은 JSON null 로, 값은 따옴표로. 이 두 필드에 따옴표가 들어올 일은 없습니다. */
    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "") + "\"";
    }

    /**
     * {@code POST /orders/{id}/received} — POS가 받아서 저장했다는 사실.
     *
     * <p><b>상태를 바꾸는 호출이 아닙니다.</b> 주문은 여전히 매장의
     * 답을 기다립니다. 이 기록이 있어야 "POS가 아예 못 받았다"와
     * "받고도 매장이 답을 안 했다"가 갈립니다 (업무 정의서 §5.3, §9.2).
     */
    public HttpResponse<String> markReceived(long orderId, String deviceId) throws Exception {
        String encoded = URLEncoder.encode(deviceId, StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/orders/" + orderId + "/received?deviceId=" + encoded))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** {@code GET /business-days/{storeId}} — whether that store has 개점'd, and whether it needs 마감. */
    public HttpResponse<String> businessDayStatus(String storeId) throws Exception {
        String encoded = URLEncoder.encode(storeId, StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/business-days/" + encoded)).GET());
    }

    /**
     * {@code POST /business-days/open} or {@code /business-days/close} —
     * the body is {@code {storeId, deviceId}} either way, so it is
     * forwarded exactly as the terminal sent it rather than parsed and
     * rebuilt here.
     */
    public HttpResponse<String> postBusinessDay(String action, String jsonBody) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/business-days/" + action))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(
                request.timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
