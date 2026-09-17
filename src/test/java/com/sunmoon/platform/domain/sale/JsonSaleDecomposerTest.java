package com.sunmoon.platform.domain.sale;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원본을 푸는 규칙 (업무 정의서 §3.2).
 *
 * <p>제일 중요한 것은 <b>금액이 안 맞는 원본을 통과시키지 않는 것</b>
 * 입니다. 그대로 저장하면 나중에 매출이 안 맞을 때 어디서 틀어졌는지
 * 알 수 없습니다 — 받은 그 자리에서 걸러야 합니다.
 */
class JsonSaleDecomposerTest {

    private final JsonSaleDecomposer decomposer = new JsonSaleDecomposer();

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final String GOOD = """
            {
              "header": {
                "storeId": "store-01", "posNo": "pos-01",
                "businessDate": "2026-09-17", "tranNo": "000001",
                "totalAmount": 12000, "soldAt": "2026-09-17T02:00:00Z"
              },
              "details": [
                {"menuCode": "menu-07", "menuName": "7메뉴", "quantity": 1,
                 "unitPrice": 12000, "detailAmount": 12000}
              ],
              "payments": [
                {"paymentType": "CARD", "paymentAmount": 12000, "approvalNo": "A1"}
              ],
              "etc": {"qrCode": "E4SAXPY6J4"}
            }
            """;

    @Test
    void decomposesHeaderDetailPaymentAndEtc() {
        DecomposedSale sale = decomposer.decompose(json(GOOD));

        assertEquals("store-01", sale.header().storeId());
        assertEquals("000001", sale.header().tranNo());
        // POS가 안 보낸 값은 0입니다 — 합계에 영향이 없는 것만 그렇습니다.
        assertEquals(0, sale.header().discountAmount().compareTo(java.math.BigDecimal.ZERO));
        assertEquals(1, sale.details().size());
        assertEquals(1, sale.payments().size());
        assertEquals(1, sale.etc().size());
        assertEquals("qrCode", sale.etc().get(0).key());
        assertTrue(sale.balanced());
    }

    @Test
    void numbersTheLinesWhenPosDidNot() {
        // POS 전문에 순번이 없을 수 있습니다. 순번이 없으면 같은 주문의
        // 두 줄을 구분할 수 없어서, 들어온 차례대로 매깁니다.
        DecomposedSale sale = decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17",
                            "tranNo":"1","totalAmount":3000},
                 "details": [{"menuName":"A","unitPrice":1000,"detailAmount":1000},
                             {"menuName":"B","unitPrice":2000,"detailAmount":2000}]}
                """));

        assertEquals(1, sale.details().get(0).detailSeq());
        assertEquals(2, sale.details().get(1).detailSeq());
    }

    /**
     * Header 금액과 Detail 합계가 다르면 <b>통과시키지 않습니다</b>.
     *
     * <p>이게 없으면 안 맞는 매출이 조용히 쌓이고, 월말에 총액이 안
     * 맞을 때 어느 거래가 범인인지 찾을 수 없습니다.
     */
    @Test
    void anImbalancedSaleIsNotBalanced() {
        DecomposedSale sale = decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17",
                            "tranNo":"1","totalAmount":12000},
                 "details": [{"menuName":"A","unitPrice":1000,"detailAmount":1000}]}
                """));

        assertFalse(sale.balanced());
        assertTrue(sale.imbalance().contains("header=12000"), sale.imbalance());
        assertTrue(sale.imbalance().contains("detail=1000"), sale.imbalance());
    }

    /** 결제가 없는 거래(외상·후불)는 Payment 검사를 건너뜁니다 — 0건과 불일치는 다릅니다. */
    @Test
    void aSaleWithNoPaymentIsStillBalanced() {
        DecomposedSale sale = decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17",
                            "tranNo":"1","totalAmount":1000},
                 "details": [{"menuName":"A","unitPrice":1000,"detailAmount":1000}]}
                """));

        assertTrue(sale.payments().isEmpty());
        assertTrue(sale.balanced());
    }

    /**
     * {@code 12000} 과 {@code 12000.00} 은 같은 금액입니다.
     *
     * <p>{@code equals} 로 비교하면 소수 자릿수가 달라 틀렸다고 합니다 —
     * POS가 소수점을 붙여 보내는 날 모든 거래가 실패합니다.
     */
    @Test
    void scaleDoesNotMakeTwoEqualAmountsDifferent() {
        DecomposedSale sale = decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17",
                            "tranNo":"1","totalAmount":12000.00},
                 "details": [{"menuName":"A","unitPrice":12000,"detailAmount":12000}]}
                """));

        assertTrue(sale.balanced());
    }

    @Test
    void aSaleWithNoLinesIsRefused() {
        // 금액은 있는데 무엇을 팔았는지 모르는 매출은 매출이 아닙니다.
        SaleDecomposeException thrown = assertThrows(SaleDecomposeException.class,
                () -> decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17",
                            "tranNo":"1","totalAmount":1000},
                 "details": []}
                """)));
        assertTrue(thrown.getMessage().contains("details"), thrown.getMessage());
    }

    @Test
    void aMissingKeyFieldIsRefused() {
        // 거래번호가 없으면 중복도 못 가리고 되짚을 수도 없습니다.
        SaleDecomposeException thrown = assertThrows(SaleDecomposeException.class,
                () -> decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17",
                            "totalAmount":1000},
                 "details": [{"menuName":"A","unitPrice":1000,"detailAmount":1000}]}
                """)));
        assertTrue(thrown.getMessage().contains("tranNo"), thrown.getMessage());
    }

    @Test
    void aMissingAmountIsRefusedRatherThanTreatedAsZero() {
        // 합계에 들어가는 값이 비면 0으로 채우면 안 됩니다 — 0원짜리
        // 매출이 조용히 쌓입니다.
        SaleDecomposeException thrown = assertThrows(SaleDecomposeException.class,
                () -> decomposer.decompose(json("""
                {"header": {"storeId":"s","posNo":"p","businessDate":"2026-09-17","tranNo":"1"},
                 "details": [{"menuName":"A","unitPrice":1000,"detailAmount":1000}]}
                """)));
        assertTrue(thrown.getMessage().contains("totalAmount"), thrown.getMessage());
    }

    @Test
    void garbageIsRefusedWithAReadableReason() {
        SaleDecomposeException thrown = assertThrows(SaleDecomposeException.class,
                () -> decomposer.decompose(json("not json at all")));
        assertTrue(thrown.getMessage().contains("풀지 못했습니다"), thrown.getMessage());
    }
}
