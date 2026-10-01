package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.print.PrintJob;
import com.sunmoon.platform.domain.print.PrintJobStore;
import com.sunmoon.platform.infrastructure.print.BoPrinters;
import com.sunmoon.platform.infrastructure.print.LabelPush;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 프린터와 라벨 작업 (store-app.md §6.3, §7). 매장은 다른 단말 주소와 같이 요청의
 * {@code storeId} 로 받습니다 — 작업은 그 매장 것만 보이고 만질 수 있습니다.
 *
 * <table>
 *   <tr><td>{@code GET /pos/printers?storeId=}</td><td>그 매장의 프린터(주소 포함)</td></tr>
 *   <tr><td>{@code GET /pos/print-jobs?storeId=&id= | &status= | &orderId=}</td><td>작업</td></tr>
 *   <tr><td>{@code POST /pos/print-jobs/ack?storeId=&id=} {@code {ok, error}}</td><td>찍혔다/못 찍었다</td></tr>
 *   <tr><td>{@code POST /pos/print-jobs/reprint?storeId=&id=&deviceId=}</td><td>다시 찍기 (KDS-MAIN)</td></tr>
 * </table>
 */
public final class PrintEndpoints {

    private final PrintJobStore jobs;
    private final BoPrinters printers;
    private final LabelPush push;
    private final KdsAuthority kds;
    private final ObjectMapper json = new ObjectMapper();

    public PrintEndpoints(PrintJobStore jobs, BoPrinters printers, LabelPush push, KdsAuthority kds) {
        this.jobs = jobs;
        this.printers = printers;
        this.push = push;
        this.kds = kds;
    }

    private static FullHttpResponse noStore() {
        return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "storeId 가 필요합니다"));
    }

    public RestEndpoint printers() {
        return request -> {
            String store = param(request.uri(), "storeId");
            if (store == null) {
                return noStore();
            }
            return JsonResponses.of(HttpResponseStatus.OK, printers.of(store));
        };
    }

    public RestEndpoint list() {
        return request -> {
            String store = param(request.uri(), "storeId");
            if (store == null) {
                return noStore();
            }
            Long id = number(request.uri(), "id");
            if (id != null) {
                return jobs.find(id).filter(j -> j.storeId().equals(store))
                        .<FullHttpResponse>map(j -> JsonResponses.of(HttpResponseStatus.OK, j))
                        .orElseGet(() -> JsonResponses.of(HttpResponseStatus.NOT_FOUND,
                                Map.of("error", "그런 작업이 없습니다: " + id)));
            }
            PrintJob.Status status = null;
            String raw = param(request.uri(), "status");
            if (raw != null) {
                try {
                    status = PrintJob.Status.valueOf(raw.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                            Map.of("error", "status 는 QUEUED, PRINTED, FAILED 중 하나입니다"));
                }
            }
            return JsonResponses.of(HttpResponseStatus.OK, jobs.list(store, status, number(request.uri(), "orderId")));
        };
    }

    public RestEndpoint ack() {
        return request -> {
            String store = param(request.uri(), "storeId");
            if (store == null) {
                return noStore();
            }
            Long id = number(request.uri(), "id");
            Optional<PrintJob> job = id == null ? Optional.empty() : jobs.find(id);
            if (job.isEmpty() || !job.get().storeId().equals(store)) {
                return JsonResponses.of(HttpResponseStatus.NOT_FOUND, Map.of("error", "그런 작업이 없습니다"));
            }
            JsonNode body = body(request);
            boolean ok = body != null && body.path("ok").asBoolean(false);
            String error = body == null ? "본문이 없습니다" : body.path("error").asText(null);
            return JsonResponses.of(HttpResponseStatus.OK, jobs.ack(id, ok, error, Instant.now()).orElseThrow());
        };
    }

    public RestEndpoint reprint() {
        return request -> {
            String store = param(request.uri(), "storeId");
            String deviceId = param(request.uri(), "deviceId");
            if (store == null || deviceId == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "storeId 와 deviceId 가 필요합니다"));
            }
            Long id = number(request.uri(), "id");
            Optional<PrintJob> original = id == null ? Optional.empty() : jobs.find(id);
            if (original.isEmpty() || !original.get().storeId().equals(store)) {
                return JsonResponses.of(HttpResponseStatus.NOT_FOUND, Map.of("error", "그런 작업이 없습니다"));
            }
            KdsAuthority.Decision decision = kds.decide(KdsAuthority.Action.REPRINT, store, deviceId);
            if (!decision.allowed()) {
                return JsonResponses.of(HttpResponseStatus.FORBIDDEN, Map.of("error", decision.reason()));
            }
            PrintJob again = jobs.reprint(id, deviceId).orElseThrow();
            push.push(again.storeId(), List.of(again.id()));
            return JsonResponses.of(HttpResponseStatus.OK, again);
        };
    }

    private JsonNode body(FullHttpRequest request) {
        try {
            return json.readTree(request.content().toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static Long number(String uri, String name) {
        String raw = param(uri, name);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String param(String uri, String name) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
    }
}
