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
        String body = deviceId == null
                ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\",\"deviceId\":\"" + deviceId + "\"}";
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
    public HttpResponse<String> handOver(long orderId, String pin, String deviceId) throws Exception {
        String body = "{\"pin\":" + json(pin) + ",\"deviceId\":" + json(deviceId) + "}";
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + orderId + "/pickup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** null 은 JSON null 로, 값은 따옴표로. 이 두 필드에 따옴표가 들어올 일은 없습니다. */
    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "") + "\"";
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
