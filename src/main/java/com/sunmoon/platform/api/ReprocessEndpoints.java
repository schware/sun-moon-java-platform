package com.sunmoon.platform.api;

import com.sunmoon.platform.domain.reprocess.FailedItem;
import com.sunmoon.platform.domain.reprocess.Reprocessor;
import com.sunmoon.platform.domain.reprocess.ReprocessService;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실패한 것을 보고 되돌리는 자리 (업무 정의서 §5.8, §10).
 *
 * <p>세 종류가 한 주소를 지납니다. 따로 뒀다면 화면도 셋이었을 것이고,
 * 셋이면 아무도 셋을 다 열어보지 않습니다.
 */
public final class ReprocessEndpoints {

    private final ReprocessService reprocess;

    public ReprocessEndpoints(ReprocessService reprocess) {
        this.reprocess = reprocess;
    }

    /**
     * {@code GET /reprocess?storeId=} — 지금 손봐야 할 것 전부.
     *
     * <p>{@code storeId} 를 안 주면 전 매장입니다. 본사에서 보는 화면이
     * 매장을 하나씩 골라야 하면, 어느 매장이 문제인지 알아야 고를 수
     * 있다는 순환이 됩니다.
     */
    public RestEndpoint list() {
        return request -> {
            String storeId = param(request, "storeId");
            Map<String, List<FailedItem>> failed = reprocess.allFailed(storeId);

            List<Map<String, Object>> kinds = new ArrayList<>();
            int total = 0;
            for (Reprocessor reprocessor : reprocess.kinds()) {
                List<FailedItem> items = failed.getOrDefault(reprocessor.kind(), List.of());
                total += items.size();

                List<Map<String, Object>> rows = new ArrayList<>();
                for (FailedItem item : items) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.id());
                    row.put("storeId", item.storeId());
                    row.put("summary", item.summary());
                    // 사유를 그대로 내보냅니다. 요약하면 다시 돌려도
                    // 되는지 판단할 근거가 사라집니다.
                    row.put("error", item.error());
                    row.put("attempts", item.attempts());
                    row.put("failedAt", item.failedAt());
                    rows.add(row);
                }

                Map<String, Object> kind = new LinkedHashMap<>();
                kind.put("kind", reprocessor.kind());
                kind.put("label", reprocessor.label());
                kind.put("count", items.size());
                kind.put("items", rows);
                kinds.add(kind);
            }

            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId == null ? "" : storeId,
                    "total", total,
                    "kinds", kinds));
        };
    }

    /**
     * {@code POST /reprocess/one?kind=SALE&storeId=&id=} — 한 건.
     *
     * <p>답에 {@code done} 이 들어갑니다. 매출은 배치가 30초 뒤에 집어
     * 가고 마스터·프로그램은 장비가 다음에 물어볼 때라, <b>지금 끝난
     * 것과 걸어 둔 것</b>을 가려 말해야 운영자가 계속 누르지 않습니다.
     */
    public RestEndpoint retryOne() {
        return request -> {
            String kind = param(request, "kind");
            String id = param(request, "id");
            if (kind == null || id == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "kind and id are required"));
            }
            Reprocessor.Outcome outcome = reprocess.retry(kind, param(request, "storeId"), id);

            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("kind", kind);
            answer.put("id", id);
            answer.put("accepted", outcome.accepted());
            answer.put("done", outcome.done());
            answer.put("message", outcome.message());
            // 못 찾은 것은 404가 아니라 200입니다 — 목록을 열어 둔
            // 사이에 배치가 처리했을 수 있고, 그건 오류가 아닙니다.
            return JsonResponses.of(HttpResponseStatus.OK, answer);
        };
    }

    /**
     * {@code POST /reprocess/all?kind=SALE&storeId=} — 그 종류 전부.
     *
     * <p>한 건씩 누르게 두면 스무 건이 쌓인 날 아무도 안 누릅니다.
     */
    public RestEndpoint retryAll() {
        return request -> {
            String kind = param(request, "kind");
            if (kind == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "kind is required"));
            }
            ReprocessService.Retried retried = reprocess.retryAll(kind, param(request, "storeId"));
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "kind", kind,
                    "queued", retried.queued(),
                    "failed", retried.failed()));
        };
    }

    private static String param(FullHttpRequest request, String name) {
        List<String> values = new QueryStringDecoder(request.uri()).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
