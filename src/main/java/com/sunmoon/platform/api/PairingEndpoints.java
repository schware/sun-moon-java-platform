package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.pairing.PairingService;
import com.sunmoon.platform.domain.pairing.PairingService.PairingRefused;
import com.sunmoon.platform.domain.pairing.PairingStore.PairedDevice;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기기 페어링 주소 넷 (umbrella docs/design/store-app.md §3.4).
 *
 * <table>
 *   <tr><td>{@code POST /pos/pairing-codes}</td><td>관리</td><td>BO 가 코드를 받아 감</td></tr>
 *   <tr><td>{@code POST /pos/pair}</td><td>공개</td><td>코드가 곧 증명</td></tr>
 *   <tr><td>{@code POST /pos/devices/revoke}</td><td>관리</td><td>기기 자격을 끝냄</td></tr>
 *   <tr><td>{@code GET /pos/devices}</td><td>관리</td><td>페어링 상태</td></tr>
 * </table>
 */
public final class PairingEndpoints {

    private static final Logger log = LoggerFactory.getLogger(PairingEndpoints.class);

    private final PairingService pairing;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PairingEndpoints(PairingService pairing) {
        this.pairing = pairing;
    }

    public RestEndpoint issueCode() {
        return request -> {
            JsonNode body = json(request);
            if (body == null) {
                return bad("본문이 JSON 이 아닙니다");
            }
            DeviceIdentity device = new DeviceIdentity(text(body, "storeId"), text(body, "deviceId"),
                    text(body, "deviceType"), text(body, "kdsRole"));
            try {
                PairingService.Issued issued = pairing.issue(device, text(body, "issuedBy"));
                log.info("페어링 코드 발급: {} {} {} (역할 {}, {})", device.storeId(), device.type(),
                        device.deviceId(), device.kdsRole(), text(body, "issuedBy"));
                return JsonResponses.of(HttpResponseStatus.OK,
                        Map.of("code", issued.code(), "expiresAt", issued.expiresAt().toString()));
            } catch (IllegalArgumentException invalid) {
                return bad(invalid.getMessage());
            }
        };
    }

    public RestEndpoint pair() {
        return request -> {
            JsonNode body = json(request);
            if (body == null) {
                return bad("본문이 JSON 이 아닙니다");
            }
            try {
                PairingService.Paired paired = pairing.pair(text(body, "code"));
                DeviceIdentity d = paired.device();
                log.info("페어링됨: {} {} {} (역할 {})", d.storeId(), d.type(), d.deviceId(), d.kdsRole());
                Map<String, Object> out = identity(d);
                out.put("token", paired.token());
                return JsonResponses.of(HttpResponseStatus.OK, out);
            } catch (PairingRefused refused) {
                return switch (refused.refusal()) {
                    case THROTTLED -> JsonResponses.of(HttpResponseStatus.TOO_MANY_REQUESTS,
                            Map.of("error", "잘못된 코드가 너무 많이 들어왔습니다. 1분 뒤 다시 하십시오",
                                    "reason", "THROTTLED"));
                    case UNKNOWN_CODE -> JsonResponses.of(HttpResponseStatus.NOT_FOUND,
                            Map.of("error", "코드가 맞지 않거나 이미 쓰였거나 10분이 지났습니다. BO 에서 새로 발급하십시오",
                                    "reason", "UNKNOWN_CODE"));
                };
            }
        };
    }

    public RestEndpoint revoke() {
        return request -> {
            JsonNode body = json(request);
            if (body == null) {
                return bad("본문이 JSON 이 아닙니다");
            }
            String storeId = text(body, "storeId");
            String deviceId = text(body, "deviceId");
            String type = text(body, "deviceType");
            if (storeId == null || deviceId == null || type == null) {
                return bad("storeId, deviceId, deviceType 가 필요합니다");
            }
            boolean revoked = pairing.revoke(storeId, deviceId, type.toUpperCase(java.util.Locale.ROOT));
            log.info("기기 자격 끝냄: {} {} {} → {}", storeId, type, deviceId, revoked);
            return JsonResponses.of(HttpResponseStatus.OK, Map.of("revoked", revoked));
        };
    }

    public RestEndpoint list() {
        return request -> {
            List<String> store = new QueryStringDecoder(request.uri()).parameters().get("storeId");
            String storeId = store == null || store.isEmpty() || store.get(0).isBlank() ? null : store.get(0);
            List<Map<String, Object>> rows = pairing.devices(storeId).stream().map(PairingEndpoints::row).toList();
            return JsonResponses.of(HttpResponseStatus.OK, rows);
        };
    }

    private static Map<String, Object> row(PairedDevice p) {
        Map<String, Object> out = identity(p.device());
        out.put("state", p.pendingUntil() != null ? "CODE_ISSUED" : "PAIRED");
        out.put("pairedAt", p.pairedAt() == null ? null : p.pairedAt().toString());
        out.put("lastSeen", p.lastSeen() == null ? null : p.lastSeen().toString());
        out.put("codeExpiresAt", p.pendingUntil() == null ? null : p.pendingUntil().toString());
        return out;
    }

    private static Map<String, Object> identity(DeviceIdentity d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", d.storeId());
        out.put("deviceId", d.deviceId());
        out.put("deviceType", d.type());
        out.put("kdsRole", d.kdsRole());
        return out;
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
