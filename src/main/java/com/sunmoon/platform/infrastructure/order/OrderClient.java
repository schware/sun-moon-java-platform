package com.sunmoon.platform.infrastructure.order;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(
                request.timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
