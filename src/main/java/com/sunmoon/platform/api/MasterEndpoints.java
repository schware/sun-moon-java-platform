package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.pos.MasterDispatch;
import com.sunmoon.platform.domain.pos.MasterDispatchService;
import com.sunmoon.platform.domain.pos.MasterPlan;
import com.sunmoon.platform.domain.pos.MasterType;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 마스터 IRT 배포 (업무 정의서 §3.6).
 *
 * <p>내려보내는 것은 <b>목록과 버전</b>이고 마스터의 내용이 아닙니다.
 * 장비는 이 답을 보고 "무엇을, 전체로 받을지 변경분으로 받을지"를 알고,
 * 실제 내용은 그 마스터의 조회로 가져갑니다. 두 단계로 나눈 이유는
 * 한 번에 다 내리면 매장 회선으로 매번 전체가 흐르기 때문입니다.
 */
public final class MasterEndpoints {

    private final MasterDispatchService masters;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MasterEndpoints(MasterDispatchService masters) {
        this.masters = masters;
    }

    /**
     * {@code GET /pos/masters?storeId=&deviceType=POS&deviceNo=pos-01&issue=true}
     *
     * <p>{@code issue} 없이 부르면 <b>읽기만</b> 합니다. 관제 화면이
     * "이 장비가 뒤처져 있나"를 볼 때마다 전송 기록이 찍히면, 한 번도
     * 보내지 않은 마스터가 방금 보낸 것처럼 보입니다.
     */
    public RestEndpoint plan() {
        return request -> {
            String storeId = param(request, "storeId");
            String deviceType = param(request, "deviceType");
            String deviceNo = param(request, "deviceNo");
            if (storeId == null || deviceType == null || deviceNo == null) {
                return badRequest("storeId, deviceType, deviceNo are required");
            }
            boolean issue = "true".equalsIgnoreCase(param(request, "issue"));

            List<MasterPlan> plans = issue
                    ? masters.issue(storeId, deviceType, deviceNo)
                    : masters.plan(storeId, deviceType, deviceNo);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (MasterPlan plan : plans) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("masterType", plan.masterType().name());
                row.put("label", plan.masterType().label());
                row.put("currentVersion", plan.currentVersion());
                row.put("appliedVersion", plan.appliedVersion());
                row.put("mode", plan.mode().name());
                row.put("lastError", plan.lastError());
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "deviceType", deviceType, "deviceNo", deviceNo,
                    "issued", issue, "masters", rows));
        };
    }

    /**
     * {@code POST /pos/masters/ack} — 장비가 적용 결과를 답합니다.
     *
     * <p>{@code applied: false} 도 받습니다. 못 받은 장비와 받았지만
     * 깨진 장비는 손봐야 할 곳이 다른데, 실패를 말할 자리가 없으면
     * 서버는 둘을 구분할 수 없습니다.
     */
    public RestEndpoint ack() {
        return request -> {
            JsonNode body;
            try {
                body = objectMapper.readTree(request.content().toString(StandardCharsets.UTF_8));
            } catch (Exception notJson) {
                return badRequest("본문이 JSON이 아닙니다");
            }

            String storeId = text(body, "storeId");
            String deviceType = text(body, "deviceType");
            String deviceNo = text(body, "deviceNo");
            Optional<MasterType> type = MasterType.of(text(body, "masterType"));
            if (storeId == null || deviceType == null || deviceNo == null || type.isEmpty()) {
                return badRequest("storeId, deviceType, deviceNo, masterType are required");
            }
            if (!body.hasNonNull("version")) {
                return badRequest("version is required");
            }
            long version = body.get("version").asLong();
            boolean applied = !body.hasNonNull("applied") || body.get("applied").asBoolean();

            masters.ack(storeId, deviceType, deviceNo, type.get(), version, applied, text(body, "message"));
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "deviceNo", deviceNo,
                    "masterType", type.get().name(),
                    "status", applied ? "APPLIED" : "FAILED"));
        };
    }

    /**
     * {@code POST /pos/masters/version} — 마스터가 바뀌었습니다.
     *
     * <p>BO(나중에는 BO-Interface)가 부르는 자리입니다. 버전을 올리는
     * 것만으로 배포가 일어나지는 않습니다 — 장비가 다음에 물어볼 때
     * 변경분 대상이 됩니다. 밀어붙이지 않는 이유는 꺼져 있는 장비가
     * 있기 때문이고, 그 장비는 켜질 때 알아서 뒤처진 것을 받습니다.
     */
    public RestEndpoint bumpVersion() {
        return request -> {
            JsonNode body;
            try {
                body = objectMapper.readTree(request.content().toString(StandardCharsets.UTF_8));
            } catch (Exception notJson) {
                return badRequest("본문이 JSON이 아닙니다");
            }
            String storeId = text(body, "storeId");
            Optional<MasterType> type = MasterType.of(text(body, "masterType"));
            if (storeId == null || type.isEmpty()) {
                return badRequest("storeId and a known masterType are required");
            }
            long version = masters.bumpVersion(storeId, type.get());
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "masterType", type.get().name(), "version", version));
        };
    }

    /** {@code GET /pos/masters/pending?storeId=} — 재전송 대상 (§3.6 "누락이나 실패 건을 재전송한다"). */
    public RestEndpoint pending() {
        return request -> {
            String storeId = param(request, "storeId");
            if (storeId == null) {
                return badRequest("storeId is required");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (MasterDispatch dispatch : masters.unfinished(storeId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deviceType", dispatch.deviceType());
                row.put("deviceNo", dispatch.deviceNo());
                row.put("masterType", dispatch.masterType().name());
                row.put("appliedVersion", dispatch.appliedVersion());
                row.put("status", dispatch.status().name());
                row.put("attempts", dispatch.attempts());
                row.put("lastError", dispatch.lastError());
                row.put("sentAt", dispatch.sentAt());
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "pending", rows));
        };
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String param(FullHttpRequest request, String name) {
        List<String> values = new QueryStringDecoder(request.uri()).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static io.netty.handler.codec.http.FullHttpResponse badRequest(String message) {
        return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", String.valueOf(message)));
    }
}
