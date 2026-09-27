package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.intake.RawLog;
import com.sunmoon.platform.domain.intake.RawLogStore;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * POS가 ETC 매출을 올리는 자리 (업무 정의서 §3.3).
 *
 * <p>{@link SaleEndpoints} 와 <b>같은 모양</b>입니다 — 문서가 "TR 매출과
 * 동일한 방식으로" 라고 적어 뒀습니다. 받는 것이 전부이고, 풀지 않고,
 * 바이트로 담습니다. 다른 것은 키의 마지막 자리 이름({@code etcNo})
 * 하나입니다.
 *
 * <h2>전문 모양</h2>
 *
 * <pre>
 *   { "header": { "storeId", "posNo", "businessDate", "etcNo" },
 *     ...나머지는 무엇이든 }
 * </pre>
 *
 * <p>{@code header} 의 넷만 요구합니다. 그 넷이 원본의 키이고, 없으면
 * 중복도 못 가리고 되짚을 수도 없습니다. <b>나머지는 요구하지 않습니다</b> —
 * §3.3 이 "ETC 상세 테이블 구조는 추후 확정한다" 고 적어 뒀고, 정해지지
 * 않은 모양을 여기서 막으면 단말이 올려야 할 것을 못 올립니다.
 */
public final class EtcSaleEndpoints {

    private static final Logger log = LoggerFactory.getLogger(EtcSaleEndpoints.class);

    private final RawLogStore etc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EtcSaleEndpoints(RawLogStore etc) {
        this.etc = etc;
    }

    /**
     * {@code POST /etc-sales} — 원본 하나 또는 여럿.
     *
     * <p>배열도 받습니다. POS 의 로컬 원장이 밀린 것을 한 번에 올리는 것이
     * 정상 동작입니다(umbrella {@code docs/adr/0010}).
     *
     * <p><b>이미 받은 것은 실패가 아닙니다.</b> {@code duplicates} 로 셀
     * 뿐입니다 — 여기서 오류를 내면 단말이 영원히 다시 보냅니다.
     *
     * <p>하나라도 키가 없으면 <b>전부 거절합니다</b>. 반만 받고 반은 안
     * 받으면, 단말은 무엇이 들어갔는지 모른 채 전부를 다시 보내게 되고
     * 그 사이에 "들어간 반" 이 중복으로 셉니다. 전부 아니면 전무가 단말
     * 쪽 재시도를 단순하게 만듭니다.
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

            // 먼저 전부 검사합니다 — 하나라도 키가 없으면 아무것도 안 적습니다.
            for (JsonNode item : items) {
                JsonNode header = item.path("header");
                for (String key : List.of("storeId", "posNo", "businessDate", "etcNo")) {
                    if (header.path(key).asText("").isBlank()) {
                        return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of(
                                "error", "header 에 storeId, posNo, businessDate, etcNo 가 모두 있어야 합니다"));
                    }
                }
                try {
                    LocalDate.parse(header.path("businessDate").asText());
                } catch (DateTimeParseException badDate) {
                    return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of(
                            "error", "businessDate 는 yyyy-MM-dd 여야 합니다: " + header.path("businessDate").asText()));
                }
            }

            int received = 0;
            int duplicates = 0;
            for (JsonNode item : items) {
                JsonNode header = item.path("header");
                boolean isNew = etc.receive(new RawLog(
                        null,
                        header.path("storeId").asText(),
                        header.path("posNo").asText(),
                        LocalDate.parse(header.path("businessDate").asText()),
                        header.path("etcNo").asText(),
                        "application/json",
                        objectMapper.valueToTree(item).toString().getBytes(StandardCharsets.UTF_8),
                        RawLog.Status.RECEIVED, null, 0, null, null)).isPresent();
                if (isNew) {
                    received++;
                } else {
                    duplicates++;
                }
            }

            log.info("ETC 매출 수신: 신규 {}건, 이미 받은 것 {}건", received, duplicates);
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "received", received,
                    "duplicates", duplicates));
        };
    }

    /**
     * {@code GET /etc-sales/totals?storeId=&businessDate=} — 받은 것과 푼 것.
     *
     * <p>TR 과 달리 <b>금액이 없습니다.</b> ETC 에 금액이 있는지부터가
     * 아직 안 정해졌고(§3.3), 없는 합계를 0으로 내면 "0원" 과 "모른다" 가
     * 같은 그림이 됩니다.
     */
    public RestEndpoint totals() {
        return request -> {
            QueryStringDecoder query = new QueryStringDecoder(request.uri());
            String storeId = first(query, "storeId");
            String day = first(query, "businessDate");
            if (storeId == null || day == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "storeId and businessDate are required"));
            }
            LocalDate businessDate;
            try {
                businessDate = LocalDate.parse(day);
            } catch (DateTimeParseException bad) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "businessDate 는 yyyy-MM-dd 여야 합니다"));
            }
            RawLogStore.Counts counts = etc.counts(storeId, businessDate);
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId,
                    "businessDate", businessDate.toString(),
                    "logs", counts.logs(),
                    "decomposed", counts.decomposed(),
                    "failed", counts.failed()));
        };
    }

    private static String first(QueryStringDecoder query, String name) {
        List<String> values = query.parameters().get(name);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
    }
}
