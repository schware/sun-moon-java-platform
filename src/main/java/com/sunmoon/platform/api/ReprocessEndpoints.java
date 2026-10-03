package com.sunmoon.platform.api;

import com.sunmoon.common.reprocess.ReprocessService;
import com.sunmoon.common.reprocess.ReprocessViews;
import com.sunmoon.common.reprocess.Reprocessor;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.Map;

/**
 * 실패한 것을 보고 되돌리는 자리 (업무 정의서 §5.8, §10).
 *
 * <p>여러 종류가 한 주소를 지납니다. 따로 뒀다면 화면도 여럿이었을 것이고, 여럿이면 아무도 다 열어보지
 * 않습니다. 틀과 응답 모양은 공통 라이브러리(sun-moon-java-common 의 reprocess)에 있고 — Order·APP-SERVER
 * 도 같은 모양으로 답해서 BO 가 한 화면에 모읍니다 — 여기는 Netty 로 내보내기만 합니다.
 */
public final class ReprocessEndpoints {

    private final ReprocessService reprocess;

    public ReprocessEndpoints(ReprocessService reprocess) {
        this.reprocess = reprocess;
    }

    /**
     * {@code GET /reprocess?storeId=} — 지금 손봐야 할 것 전부.
     *
     * <p>{@code storeId} 를 안 주면 전 매장입니다. 본사에서 보는 화면이 매장을 하나씩 골라야 하면,
     * 어느 매장이 문제인지 알아야 고를 수 있다는 순환이 됩니다.
     */
    public RestEndpoint list() {
        return request -> JsonResponses.of(HttpResponseStatus.OK,
                ReprocessViews.list(reprocess, param(request, "storeId")));
    }

    /** {@code POST /reprocess/one?kind=&id=&storeId=} — 못 찾은 것도 200 (목록을 연 사이에 처리됐을 수 있음). */
    public RestEndpoint retryOne() {
        return request -> {
            String kind = param(request, "kind");
            String id = param(request, "id");
            if (kind == null || id == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "kind and id are required"));
            }
            Reprocessor.Outcome outcome = reprocess.retry(kind, param(request, "storeId"), id);
            return JsonResponses.of(HttpResponseStatus.OK, ReprocessViews.one(kind, id, outcome));
        };
    }

    /** {@code POST /reprocess/all?kind=&storeId=} */
    public RestEndpoint retryAll() {
        return request -> {
            String kind = param(request, "kind");
            if (kind == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "kind is required"));
            }
            return JsonResponses.of(HttpResponseStatus.OK,
                    ReprocessViews.all(kind, reprocess.retryAll(kind, param(request, "storeId"))));
        };
    }

    private static String param(FullHttpRequest request, String name) {
        List<String> values = new QueryStringDecoder(request.uri()).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
