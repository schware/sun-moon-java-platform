package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.sale.SaleLog;
import com.sunmoon.platform.domain.sale.SaleRepository;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * POS가 매출을 올리는 자리 (업무 정의서 §3.1).
 *
 * <p>POS-Server 가 처음으로 갖는 <b>자기 업무</b>입니다. 지금까지 이
 * 서버는 단말의 소켓을 들고 Order·APP-SERVER 로 넘기기만 했습니다.
 *
 * <p>받는 것이 전부입니다 — 여기서는 <b>풀지 않습니다</b>. 이유가
 * 셋입니다: POS는 매장에서 손님을 세워 두고 기다리므로 빨라야 하고,
 * 분해가 실패해도 원본은 남아야 하고, 분해 규칙이 바뀌면 원본으로
 * 다시 풀 수 있어야 합니다. 그래서 payload 를 해석하지 않고 바이트로
 * 담습니다.
 */
public final class SaleEndpoints {

    private static final Logger log = LoggerFactory.getLogger(SaleEndpoints.class);

    private final SaleRepository sales;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SaleEndpoints(SaleRepository sales) {
        this.sales = sales;
    }

    /**
     * {@code POST /sales} — 거래 원본 하나 또는 여럿.
     *
     * <p>배열도 받습니다. POS의 로컬 DB가 밀린 거래를 한 번에 올리는
     * 것이 정상 동작이고(umbrella {@code docs/adr/0010}), 그때 건마다
     * 왕복하면 느립니다.
     *
     * <p>이미 받은 거래는 <b>실패가 아닙니다</b>. 배치 재시도에서 흔한
     * 일이고, POS는 "이미 받았다"를 보고 안심하고 자기 쪽에서 지웁니다 —
     * 거기서 오류를 내면 POS가 영원히 다시 보냅니다.
     */
    public RestEndpoint receive() {
        return request -> {
            String body = request.content().toString(StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "body is required"));
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (Exception notJson) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "본문이 JSON이 아닙니다"));
            }

            List<JsonNode> items = root.isArray()
                    ? java.util.stream.StreamSupport.stream(root.spliterator(), false).toList()
                    : List.of(root);

            int received = 0;
            int duplicates = 0;
            for (JsonNode item : items) {
                JsonNode header = item.path("header");
                String storeId = header.path("storeId").asText(null);
                String posNo = header.path("posNo").asText(null);
                String businessDate = header.path("businessDate").asText(null);
                String tranNo = header.path("tranNo").asText(null);

                if (storeId == null || posNo == null || businessDate == null || tranNo == null) {
                    // 키가 없으면 중복도 못 가리고 되짚을 수도 없습니다.
                    // 받아 두는 것이 안 받는 것보다 나쁩니다.
                    return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of(
                            "error", "header 에 storeId, posNo, businessDate, tranNo 가 모두 있어야 합니다"));
                }

                boolean isNew = sales.receive(new SaleLog(
                        null, storeId, posNo, LocalDate.parse(businessDate), tranNo,
                        "application/json",
                        objectMapper.valueToTree(item).toString().getBytes(StandardCharsets.UTF_8),
                        SaleLog.Status.RECEIVED, null, 0, null, null)).isPresent();

                if (isNew) {
                    received++;
                } else {
                    duplicates++;
                }
            }

            log.info("매출 수신: 신규 {}건, 이미 받은 것 {}건", received, duplicates);
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "received", received,
                    "duplicates", duplicates));
        };
    }

    /**
     * {@code GET /sales/totals?storeId=&businessDate=} — 원본 건수와
     * 분해 결과를 나란히 (§3.2 정합성).
     *
     * <p>BO의 관제 화면이 "올라온 건수와 분해된 건수가 같은가"를 볼
     * 자리입니다. 실패 건수가 0이 아니면 재처리할 것이 있다는 뜻입니다.
     */
    public RestEndpoint totals() {
        return request -> {
            String businessDate = param(request.uri(), "businessDate");
            if (businessDate == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "businessDate is required"));
            }
            SaleRepository.Totals totals =
                    sales.totals(param(request.uri(), "storeId"), LocalDate.parse(businessDate));
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "businessDate", businessDate,
                    "logs", totals.logs(),
                    "decomposed", totals.decomposed(),
                    "failed", totals.failed(),
                    "amount", totals.amount()));
        };
    }

    private static String param(String uri, String name) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
