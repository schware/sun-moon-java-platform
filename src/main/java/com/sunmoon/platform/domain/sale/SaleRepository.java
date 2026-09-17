package com.sunmoon.platform.domain.sale;

import java.util.List;
import java.util.Optional;

/**
 * 매출 원본과 분해 결과의 저장소.
 *
 * <p>받는 것과 분해하는 것이 다른 메서드인 이유는 <b>다른 시각에
 * 일어나기 때문</b>입니다 — POS는 손님을 세워 두고 기다리고, 분해는
 * 그 뒤에 배치가 합니다.
 */
public interface SaleRepository {

    /**
     * 원본을 적습니다. 같은 거래가 이미 있으면 {@code empty} 입니다 —
     * 유일 제약이 막은 것이고, 그게 중복 수신 방지입니다 (§7.3).
     *
     * <p>배치가 밀린 거래를 다시 올리거나 응답을 못 받은 POS가
     * 재시도하는 것은 흔한 일입니다. 그때 두 번째가 실패가 아니라
     * "이미 받았다"로 읽혀야 POS가 안심하고 지울 수 있습니다.
     */
    Optional<Long> receive(SaleLog log);

    /** 아직 분해되지 않은 원본. 오래 기다린 것부터. */
    List<SaleLog> pending(int limit);

    /**
     * 분해 결과를 저장하고 원본을 DECOMPOSED 로 옮깁니다.
     *
     * <p>한 트랜잭션입니다. Header 만 들어가고 Detail 이 빠진 매출은
     * 원본보다 나쁩니다 — 금액은 있는데 무엇을 팔았는지 모르는 행이
     * 남으면, 그걸 지울지 살릴지 아무도 판단할 수 없습니다.
     *
     * <p>다시 돌리는 경우를 위해 전에 만든 행을 먼저 지웁니다.
     */
    void store(long logId, DecomposedSale sale);

    /** 분해에 실패했습니다. 원본은 그대로 두고 사유만 적습니다. */
    void markFailed(long logId, String error);

    /** 그 날 그 매장의 분해된 매출 건수·금액 — 정합성 확인용 (§3.2). */
    Totals totals(String storeId, java.time.LocalDate businessDate);

    record Totals(long logs, long decomposed, long failed, java.math.BigDecimal amount) {
    }
}
