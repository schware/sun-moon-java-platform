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

    /**
     * 아직 분해하지 않은 원본. 오래 기다린 것부터.
     *
     * <p><b>실패한 것은 안 들어갑니다</b> (2026-09-18 고침). 처음에는
     * {@code status <> 'DECOMPOSED'} 로 골랐는데, 그러면 영원히 못 푸는
     * 거래를 30초마다 다시 풉니다 — 실서버에서 금액이 안 맞는 거래
     * 하나를 이틀 동안 388번 다시 풀고 있었습니다.
     *
     * <p>세 가지가 나빴습니다: 쓸데없는 일을 하고, 30초마다 로그에
     * 에러를 찍어 진짜 문제를 묻고, 무엇보다 <b>{@code attempts} 가
     * 뜻을 잃습니다</b> — "몇 번 시도했나"가 "얼마나 오래 깨져 있었나"가
     * 되어, 사람이 봐야 할 건을 고르는 신호로 못 씁니다.
     *
     * <p>이제 실패는 <b>멈추고 사람을 기다립니다</b>. 되돌리는 길은
     * 재처리({@link #markForRetry})뿐이고, 그래서 그 버튼에 뜻이
     * 생깁니다. 규칙을 고쳐서 다시 푸는 경우도 같은 길입니다.
     */
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

    /**
     * 분해에 실패해 있는 원본들.
     *
     * <p>{@link #pending} 과 다릅니다 — 저건 "아직 안 한 것"이고 이건
     * "해 봤는데 안 된 것"입니다. 앞은 배치가 알아서 집어 가지만 뒤는
     * 사람이 봐야 합니다.
     */
    List<SaleLog> failed(String storeId);

    /**
     * 다시 분해하게 되돌립니다 (§5.8 재처리).
     *
     * <p>사유를 <b>지우지 않습니다</b>. 다시 돌려서 또 실패하면 새 사유가
     * 덮어쓰고, 성공하면 분해가 지웁니다 — 그 사이에 화면을 여는 사람은
     * "무엇 때문에 다시 돌린 건인지"를 볼 수 있어야 합니다.
     *
     * <p>시도 횟수도 그대로 둡니다. 세 번째 재처리라는 사실이 "이건
     * 사람이 봐야 한다"는 신호입니다.
     */
    boolean markForRetry(long logId);

    /** 그 날 그 매장의 분해된 매출 건수·금액 — 정합성 확인용 (§3.2). */
    Totals totals(String storeId, java.time.LocalDate businessDate);

    record Totals(long logs, long decomposed, long failed, java.math.BigDecimal amount) {
    }
}
