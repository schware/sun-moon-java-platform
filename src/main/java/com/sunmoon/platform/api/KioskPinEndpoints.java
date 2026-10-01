package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.kiosk.KioskPins;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * KIOSK 관리자 PIN (store-app.md §5.3) — BO 가 정하고, 셸이 물어 확인합니다.
 *
 * <table>
 *   <tr><td>{@code POST /pos/devices/kiosk-pin}</td><td>관리</td><td>{@code {storeId, deviceId, pin, setBy}}</td></tr>
 *   <tr><td>{@code POST /pos/kiosk/unlock}</td><td>단말</td><td>{@code {storeId, deviceId, pin}}</td></tr>
 * </table>
 */
public final class KioskPinEndpoints {

    private static final Logger log = LoggerFactory.getLogger(KioskPinEndpoints.class);

    private final KioskPins kioskPins;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KioskPinEndpoints(KioskPins kioskPins) {
        this.kioskPins = kioskPins;
    }

    /** 해시만 저장합니다. */
    public RestEndpoint setKioskPin() {
        return request -> {
            JsonNode body = json(request);
            if (body == null) {
                return bad("본문이 JSON 이 아닙니다");
            }
            String storeId = text(body, "storeId");
            String deviceId = text(body, "deviceId");
            if (storeId == null || deviceId == null) {
                return bad("storeId, deviceId 가 필요합니다");
            }
            try {
                kioskPins.set(storeId, deviceId, text(body, "pin"), text(body, "setBy"));
            } catch (IllegalArgumentException invalid) {
                return bad(invalid.getMessage());
            }
            log.info("KIOSK 관리자 PIN 설정: {} {} ({})", storeId, deviceId, text(body, "setBy"));
            return JsonResponses.of(HttpResponseStatus.OK, Map.of("set", true));
        };
    }

    /** 기기마다 10분에 5번 틀리면 그 10분 동안 423. */
    public RestEndpoint unlockKiosk() {
        return request -> {
            JsonNode body = json(request);
            String storeId = body == null ? null : text(body, "storeId");
            String deviceId = body == null ? null : text(body, "deviceId");
            if (storeId == null || deviceId == null) {
                return bad("storeId, deviceId 가 필요합니다");
            }
            var result = kioskPins.check(storeId, deviceId, text(body, "pin"));
            log.info("KIOSK 관리자 잠금 해제 시도: {} {} → {}", storeId, deviceId, result);
            return switch (result) {
                case OK -> JsonResponses.of(HttpResponseStatus.OK, Map.of("ok", true));
                case WRONG -> JsonResponses.of(HttpResponseStatus.FORBIDDEN,
                        Map.of("ok", false, "error", "PIN 이 맞지 않습니다"));
                case NOT_SET -> JsonResponses.of(HttpResponseStatus.CONFLICT,
                        Map.of("ok", false, "error", "이 KIOSK 에는 관리자 PIN 이 없습니다 — BO 장비 화면에서 정하십시오"));
                case LOCKED -> JsonResponses.of(HttpResponseStatus.valueOf(423),
                        Map.of("ok", false, "error", "잘못된 PIN 이 여러 번 들어왔습니다. 10분 뒤 다시 하십시오"));
            };
        };
    }

    private JsonNode json(FullHttpRequest request) {
        try {
            JsonNode node = objectMapper.readTree(request.content().toString(StandardCharsets.UTF_8));
            return node != null && node.isObject() ? node : null;
        } catch (Exception notJson) {
            return null;
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode v = body.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static io.netty.handler.codec.http.FullHttpResponse bad(String message) {
        return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", message));
    }
}
