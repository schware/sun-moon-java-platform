package com.sunmoon.platform.infrastructure.appserver;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The Device Server's view of APP-SERVER — today, the menu board.
 *
 * <p>Why a terminal reads its menu master through here rather than from
 * APP-SERVER directly: the same reason it reads orders through here.
 * APP-SERVER is LAN-only, terminals are opened from the public hub, and
 * one origin per screen is the whole point of this server. A terminal
 * that had to know two addresses would be a terminal someone has to
 * reconfigure twice.
 *
 * <p>Thin on purpose. 결품 is BO's to declare and APP-SERVER's to answer;
 * this class neither caches it nor has an opinion about it.
 */
public final class AppServerClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String baseUrl;

    public AppServerClient(String baseUrl) {
        // Trailing slashes make every later concatenation a guess.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * {@code GET /menus?storeId=…&date=…} — the store's menu board with
     * 결품 and 예상시간 already resolved.
     *
     * <p>{@code date} is passed through only when the caller gave one.
     * Omitting it lets APP-SERVER answer for its own Asia/Seoul today,
     * which is what a terminal wants — a screen must not have to compute
     * the shop's calendar to ask a question.
     */
    public HttpResponse<String> menus(String storeId, String date) throws Exception {
        StringBuilder uri = new StringBuilder(baseUrl)
                .append("/menus?storeId=")
                .append(URLEncoder.encode(storeId, StandardCharsets.UTF_8));
        if (date != null && !date.isBlank()) {
            uri.append("&date=").append(URLEncoder.encode(date, StandardCharsets.UTF_8));
        }
        return http.send(
                HttpRequest.newBuilder(URI.create(uri.toString())).GET()
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
