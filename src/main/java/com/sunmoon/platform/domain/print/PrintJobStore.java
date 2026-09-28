package com.sunmoon.platform.domain.print;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 라벨 작업을 담는 곳 (pos_server V7). */
public interface PrintJobStore {

    /**
     * 처음 출력 작업을 만듭니다. <b>이미 있는 단위는 건너뜁니다</b>(주문 이벤트가 두 번
     * 와도 라벨은 한 번) — 새로 만든 것만 돌려줍니다.
     */
    List<PrintJob> createFirst(String storeId, String printerId, long orderId, List<PrintJob.Unit> units,
                               String requestedBy);

    Optional<PrintJob> find(long id);

    /** 그 매장의 작업. {@code status}·{@code orderId} 는 null 이면 거르지 않습니다. */
    List<PrintJob> list(String storeId, PrintJob.Status status, Long orderId);

    /** 찍혔다 / 못 찍었다. 이미 PRINTED 인 것은 그대로 둡니다. */
    Optional<PrintJob> ack(long id, boolean ok, String error, Instant at);

    /** 재출력: content 를 복사한 새 작업, copy_no 는 그 단위의 마지막 + 1. */
    Optional<PrintJob> reprint(long id, String requestedBy);

    /** FAILED 를 다시 QUEUED 로 — 재처리 화면의 "다시" (§7.2). */
    boolean requeue(String storeId, long id);
}
