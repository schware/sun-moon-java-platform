package com.sunmoon.platform.infrastructure.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.print.LabelUnits;
import com.sunmoon.platform.domain.print.PrintJob;
import com.sunmoon.platform.domain.print.PrintJobStore;
import com.sunmoon.platform.infrastructure.order.OrderClient;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

/**
 * 주문이 다 만들어지면 라벨 작업을 만듭니다 (store-app.md §7.2).
 *
 * <p>▶ 기본값: <b>주문 전체가 PRODUCED 가 될 때</b>. 줄마다 찍으려면 이 조건만 바꾸면 됩니다.
 * 다시 부르기(recall)도 PRODUCED 이벤트를 내지만 이전 상태가 PRODUCED 라 건너뜁니다 —
 * 그리고 혹시 두 번 와도 표의 유일 제약이 한 장으로 막습니다.
 *
 * <p>라벨 프린터가 없는 매장은 아무것도 하지 않습니다. 라벨은 매장이 고르는 장비입니다.
 */
public final class LabelJobCreator {

    private static final Logger log = LoggerFactory.getLogger(LabelJobCreator.class);

    private final RedissonClient redisson;
    private final OrderClient orders;
    private final PrintJobStore jobs;
    private final BoPrinters printers;
    private final LabelPush push;
    private final ObjectMapper json = new ObjectMapper();

    public LabelJobCreator(RedissonClient redisson, OrderClient orders, PrintJobStore jobs,
                           BoPrinters printers, LabelPush push) {
        this.redisson = redisson;
        this.orders = orders;
        this.jobs = jobs;
        this.printers = printers;
        this.push = push;
    }

    public void start() {
        RTopic topic = redisson.getTopic("order-events", StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, message) -> onEvent(message));
        log.info("라벨 작업: 주문 완료 이벤트를 듣습니다");
    }

    void onEvent(String message) {
        try {
            JsonNode event = json.readTree(message);
            if (!"PRODUCED".equals(event.path("status").asText())
                    || "PRODUCED".equals(event.path("previousStatus").asText())) {
                return;
            }
            long orderId = event.path("orderId").asLong();
            String storeId = event.path("storeId").asText(null);
            if (storeId == null || storeId.isBlank()) {
                return;
            }
            create(storeId, orderId);
        } catch (Exception e) {
            // 라벨을 못 만든 것이 주문 흐름을 막으면 안 됩니다. 크게 적고 넘어갑니다.
            log.warn("라벨 작업을 만들지 못했습니다: {}", message, e);
        }
    }

    /** @return 새로 만든 작업 수 */
    public int create(String storeId, long orderId) throws Exception {
        Optional<BoPrinters.Printer> printer = printers.labelPrinter(storeId);
        if (printer.isEmpty()) {
            log.debug("{} 에 라벨 프린터가 없어 주문 {} 라벨을 만들지 않습니다", storeId, orderId);
            return 0;
        }
        HttpResponse<String> found = orders.order(orderId);
        if (found.statusCode() != 200) {
            log.warn("주문 {} 를 읽지 못해 라벨을 만들지 않았습니다 ({})", orderId, found.statusCode());
            return 0;
        }
        List<PrintJob.Unit> units = LabelUnits.of(json.readTree(found.body()));
        List<PrintJob> created = jobs.createFirst(storeId, printer.get().deviceId(), orderId, units, "order-events");
        push.push(storeId, created.stream().map(PrintJob::id).toList());
        return created.size();
    }
}
