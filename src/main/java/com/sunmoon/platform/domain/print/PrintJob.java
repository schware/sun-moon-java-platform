package com.sunmoon.platform.domain.print;

import java.time.Instant;
import java.util.Map;

/**
 * 찍어야 할 것 하나 (store-app.md §7.1).
 *
 * @param content 만드는 순간 고정된 내용. 재출력은 이것을 복사합니다.
 */
public record PrintJob(
        long id,
        String storeId,
        String printerId,
        String kind,
        long orderId,
        Integer lineSeq,
        Integer unitNo,
        Integer unitTotal,
        int copyNo,
        Long originalId,
        Map<String, Object> content,
        String requestedBy,
        Instant requestedAt,
        Status status,
        String error,
        int attempts,
        Instant printedAt) {

    public enum Status { QUEUED, PRINTED, FAILED }

    /** 한 주문의 줄 하나, 수량 중 몇 번째 — 만들 때의 한 단위. */
    public record Unit(int lineSeq, int unitNo, int unitTotal, Map<String, Object> content) {
    }
}
