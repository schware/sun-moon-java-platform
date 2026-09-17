package com.sunmoon.platform.domain.sale;

import java.time.Instant;
import java.time.LocalDate;

/**
 * POS가 올린 거래 원본 한 건 (업무 정의서 §3.1).
 *
 * <p>{@code payload} 는 <b>해석하지 않은 바이트</b>입니다. 지금은 JSON
 * 이지만 POS의 전문 형식은 바뀔 수 있고, 그때 이 표를 다시 만들지
 * 않으려면 받는 쪽이 내용을 몰라야 합니다. 해석은 분해가 합니다.
 *
 * <p>키는 매출의 키 그대로입니다 — 판매일자 + 점포 + POS번호 + 거래번호
 * (umbrella {@code docs/adr/0009}). 그 넷이 같은 원본이 두 번 들어오면
 * 데이터베이스가 막습니다.
 */
public record SaleLog(
        Long id,
        String storeId,
        String posNo,
        LocalDate businessDate,
        String tranNo,
        String contentType,
        byte[] payload,
        Status status,
        String error,
        int attempts,
        Instant receivedAt,
        Instant decomposedAt) {

    public enum Status {
        /** 받아서 적었습니다. 아직 분해 전. */
        RECEIVED,
        /** 분해까지 끝났습니다. */
        DECOMPOSED,
        /**
         * 분해하다 실패했습니다.
         *
         * <p>지우지 않습니다 — 원본은 그대로 남고, 규칙을 고친 뒤 다시
         * 돌리면 됩니다. 실패한 것을 버리면 그 거래는 영영 매출이
         * 안 됩니다.
         */
        FAILED
    }
}
