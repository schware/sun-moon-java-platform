package com.sunmoon.platform.domain.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 원본 하나를 풀어낸 결과 — Header 하나와 그 밑의 줄들 (§3.2).
 *
 * <p>Header 의 금액과 Detail·Payment 합계가 맞는지는 {@link #balanced()}
 * 가 봅니다. 안 맞는 원본을 그대로 저장하면, 나중에 매출이 안 맞을 때
 * 어디서 틀어졌는지 알 수 없습니다 — 받은 그 자리에서 걸러야 합니다.
 */
public record DecomposedSale(
        Header header,
        List<Detail> details,
        List<Payment> payments,
        List<Etc> etc) {

    public record Header(
            String storeId,
            String posNo,
            LocalDate businessDate,
            String tranNo,
            String tranType,
            BigDecimal totalAmount,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            Instant soldAt) {
    }

    public record Detail(
            int detailSeq,
            String menuCode,
            String menuName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal detailAmount,
            BigDecimal discountAmount) {
    }

    public record Payment(
            int paymentSeq,
            String paymentType,
            BigDecimal paymentAmount,
            String approvalNo,
            String cardNoMasked) {
    }

    public record Etc(int etcSeq, String key, String value) {
    }

    public BigDecimal detailTotal() {
        return details.stream().map(Detail::detailAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal paymentTotal() {
        return payments.stream().map(Payment::paymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Header 금액이 Detail 합계·Payment 합계와 맞는가 (§3.2 정합성).
     *
     * <p>{@code compareTo} 로 봅니다 — {@code equals} 는 소수 자릿수까지
     * 같아야 해서 {@code 12000} 과 {@code 12000.00} 이 다르다고 합니다.
     * 두 숫자는 같은 금액입니다.
     *
     * <p>결제가 없는 원본(외상, 후불)은 Payment 검사를 건너뜁니다 —
     * 0건인 것과 금액이 안 맞는 것은 다릅니다.
     */
    public boolean balanced() {
        if (header.totalAmount().compareTo(detailTotal()) != 0) {
            return false;
        }
        return payments.isEmpty() || header.totalAmount().compareTo(paymentTotal()) == 0;
    }

    /** 왜 안 맞는지 — 로그와 오류 사유에 그대로 적습니다. */
    public String imbalance() {
        return "header=" + header.totalAmount()
                + " detail=" + detailTotal()
                + " payment=" + paymentTotal();
    }
}
