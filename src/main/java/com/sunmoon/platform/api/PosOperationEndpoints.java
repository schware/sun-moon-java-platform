package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.pos.AxisState;
import com.sunmoon.platform.domain.pos.BusinessEvent;
import com.sunmoon.platform.domain.pos.BusinessState;
import com.sunmoon.platform.domain.pos.LiveAxis;
import com.sunmoon.platform.domain.pos.PosBusinessIrt;
import com.sunmoon.platform.domain.pos.PosBusinessRepository;
import com.sunmoon.platform.domain.pos.PosBusinessService;
import com.sunmoon.platform.domain.pos.PosHeartbeat;
import com.sunmoon.platform.domain.pos.PosLiveService;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POS 운영 — 개점·마감(§3.4)과 Live Check(§3.5).
 *
 * <p>한 클래스에 둔 이유: 두 업무가 같은 것을 서로 읽습니다. Live
 * Check 의 영업 축은 개점·마감 상태 그 자체이고, 개점한 채로 조용한
 * 단말을 찾는 것이 Live Check 의 본래 목적입니다.
 */
public final class PosOperationEndpoints {

    /** 판매일자는 서울 기준입니다 — 자정을 넘긴 영업은 여전히 그 전날의 장사입니다. */
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    private final PosBusinessService business;
    private final PosLiveService live;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PosOperationEndpoints(PosBusinessService business, PosLiveService live) {
        this.business = business;
        this.live = live;
    }

    /**
     * {@code POST /pos/business} — 개점·마감 IRT 한 건.
     *
     * <p>답에 {@code result} 가 들어갑니다. POS가 200 만 보고 "반영됐다"고
     * 믿으면 안 되기 때문입니다 — 늦게 도착해 무시된 전문도 200 입니다
     * (받기는 받았으니까). 무엇이 일어났는지는 {@code result} 가 말합니다.
     */
    public RestEndpoint receiveBusinessIrt() {
        return request -> {
            JsonNode body;
            try {
                body = objectMapper.readTree(request.content().toString(StandardCharsets.UTF_8));
            } catch (Exception notJson) {
                return badRequest("본문이 JSON이 아닙니다");
            }

            PosBusinessIrt irt;
            try {
                irt = new PosBusinessIrt(
                        text(body, "storeId"), text(body, "posNo"),
                        body.hasNonNull("businessDate")
                                ? LocalDate.parse(body.get("businessDate").asText())
                                : LocalDate.now(STORE_ZONE),
                        BusinessEvent.reported(text(body, "event")),
                        body.hasNonNull("eventAt")
                                ? Instant.parse(body.get("eventAt").asText())
                                : Instant.now(),
                        text(body, "irtId"));
            } catch (RuntimeException badShape) {
                return badRequest(badShape.getMessage());
            }

            PosBusinessService.Outcome outcome = business.receive(irt);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("storeId", irt.storeId());
            answer.put("posNo", irt.posNo());
            answer.put("businessDate", irt.businessDate().toString());
            answer.put("status", outcome.state().status().name());
            answer.put("event", outcome.recordedEvent().name());
            answer.put("result", outcome.result().name());
            answer.put("seq", outcome.seq());
            answer.put("openCount", outcome.state().openCount());
            answer.put("message", outcome.message());
            return JsonResponses.of(HttpResponseStatus.OK, answer);
        };
    }

    /** {@code GET /pos/business?storeId=&businessDate=} — 그 매장 단말들의 현재 상태. */
    public RestEndpoint businessStates() {
        return request -> {
            String storeId = param(request, "storeId");
            if (storeId == null) {
                return badRequest("storeId is required");
            }
            LocalDate day = dayOf(request);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BusinessState state : business.statesOf(storeId, day)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("posNo", state.posNo());
                row.put("status", state.status().name());
                row.put("lastEvent", state.lastEvent().name());
                row.put("openCount", state.openCount());
                row.put("eventAt", state.eventAt());
                row.put("receivedAt", state.receivedAt());
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "businessDate", day.toString(), "terminals", rows));
        };
    }

    /**
     * {@code GET /pos/business/history?storeId=&posNo=&businessDate=}
     *
     * <p>무시된 전문까지 전부 나옵니다. "이 POS는 왜 마감으로 안
     * 바뀌었나"에 답할 수 있는 유일한 자리입니다.
     */
    public RestEndpoint businessHistory() {
        return request -> {
            String storeId = param(request, "storeId");
            String posNo = param(request, "posNo");
            if (storeId == null || posNo == null) {
                return badRequest("storeId and posNo are required");
            }
            LocalDate day = dayOf(request);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PosBusinessRepository.BusinessHistoryEntry entry
                    : business.historyOf(storeId, posNo, day)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", entry.seq());
                row.put("event", entry.event().name());
                row.put("result", entry.result().name());
                row.put("message", entry.message());
                row.put("eventAt", entry.eventAt());
                row.put("receivedAt", entry.receivedAt());
                row.put("irtId", entry.irtId());
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "posNo", posNo,
                    "businessDate", day.toString(), "history", rows));
        };
    }

    /** {@code POST /pos/heartbeat} — POS의 주기적 자기 보고 (§3.5). */
    public RestEndpoint heartbeat() {
        return request -> {
            JsonNode body;
            try {
                body = objectMapper.readTree(request.content().toString(StandardCharsets.UTF_8));
            } catch (Exception notJson) {
                return badRequest("본문이 JSON이 아닙니다");
            }
            try {
                PosHeartbeat beat = new PosHeartbeat(
                        text(body, "storeId"), text(body, "posNo"),
                        AxisState.reported(text(body, "application")),
                        AxisState.reported(text(body, "device")),
                        AxisState.reported(text(body, "data")),
                        text(body, "programVersion"), text(body, "dbVersion"),
                        body.hasNonNull("devices") ? body.get("devices").toString() : null,
                        body.hasNonNull("reportedAt")
                                ? Instant.parse(body.get("reportedAt").asText()) : Instant.now());
                live.report(beat);
                return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                        "storeId", beat.storeId(), "posNo", beat.posNo(),
                        "reportedAt", beat.reportedAt().toString()));
            } catch (RuntimeException badShape) {
                return badRequest(badShape.getMessage());
            }
        };
    }

    /**
     * {@code GET /pos/live?storeId=&businessDate=} — 다섯 축을 따로.
     *
     * <p>{@code overall} 하나만 보고 넘어가지 말라고 축을 전부 함께
     * 내보냅니다. 요약은 관제 화면의 색을 고를 때 쓰고, 사람이 보는
     * 것은 어느 축이 무너졌는가입니다.
     */
    public RestEndpoint liveCheck() {
        return request -> {
            String storeId = param(request, "storeId");
            if (storeId == null) {
                return badRequest("storeId is required");
            }
            LocalDate day = dayOf(request);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PosLiveService.PosLiveView view : live.viewOf(storeId, day)) {
                Map<String, Object> axes = new LinkedHashMap<>();
                for (LiveAxis axis : LiveAxis.values()) {
                    axes.put(axis.name(), view.axes().get(axis).name());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("posNo", view.posNo());
                row.put("axes", axes);
                row.put("overall", view.overall().name());
                // Grafana 가 읽는 한 글자. 정상/중단 둘로만 가릅니다.
                row.put("normal", view.normal());
                row.put("programVersion", view.programVersion());
                row.put("dbVersion", view.dbVersion());
                row.put("lastReportedAt", view.lastReportedAt());
                row.put("mismatches", view.mismatches());
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "businessDate", day.toString(), "terminals", rows));
        };
    }

    private LocalDate dayOf(FullHttpRequest request) {
        String raw = param(request, "businessDate");
        return raw == null ? LocalDate.now(STORE_ZONE) : LocalDate.parse(raw);
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
