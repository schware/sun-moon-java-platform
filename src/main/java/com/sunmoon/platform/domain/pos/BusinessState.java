package com.sunmoon.platform.domain.pos;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 한 단말의 그날 현재 상태 (§3.4 MERGE 대상).
 *
 * @param openCount 그날 몇 번 열었나. 2 이상이면 재개점이 있었습니다.
 */
public record BusinessState(
        String storeId, String posNo, LocalDate businessDate,
        BusinessStatus status, BusinessEvent lastEvent, int openCount,
        Instant eventAt, Instant receivedAt) {

    public boolean open() {
        return status == BusinessStatus.OPEN;
    }
}
