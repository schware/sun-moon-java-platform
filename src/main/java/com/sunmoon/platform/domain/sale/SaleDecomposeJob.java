package com.sunmoon.platform.domain.sale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 쌓인 원본을 풀어 매출 표에 넣습니다 (업무 정의서 §3.2).
 *
 * <p>받는 것과 따로 도는 이유가 셋입니다: 받는 쪽은 POS가 손님을 세워
 * 두고 기다리므로 빨라야 하고, 분해가 실패해도 원본은 남아야 하고,
 * 분해 규칙이 바뀌면 원본으로 다시 풀 수 있어야 합니다.
 *
 * <p>한 건이 실패해도 <b>나머지는 계속 갑니다</b>. 잘못된 원본 하나가
 * 그날 매출 전체를 막으면, 고치는 동안 매장이 통째로 멈춥니다.
 */
public final class SaleDecomposeJob {

    private static final Logger log = LoggerFactory.getLogger(SaleDecomposeJob.class);

    private final SaleRepository sales;
    private final JsonSaleDecomposer decomposer;
    private final int batchSize;

    public SaleDecomposeJob(SaleRepository sales, JsonSaleDecomposer decomposer, int batchSize) {
        this.sales = sales;
        this.decomposer = decomposer;
        this.batchSize = batchSize;
    }

    /** 한 번 돌고 무엇을 했는지 돌려줍니다. */
    public Result run() {
        int done = 0;
        int failed = 0;

        for (SaleLog pending : sales.pending(batchSize)) {
            try {
                DecomposedSale sale = decomposer.decompose(pending.payload());

                // 금액이 안 맞으면 저장하지 않습니다. 그대로 넣으면
                // 나중에 매출이 안 맞을 때 어디서 틀어졌는지 알 수
                // 없습니다 — 받은 그 자리에서 걸러야 합니다.
                if (!sale.balanced()) {
                    throw new SaleDecomposeException("금액이 맞지 않습니다: " + sale.imbalance());
                }

                sales.store(pending.id(), sale);
                done++;
            } catch (SaleDecomposeException e) {
                sales.markFailed(pending.id(), e.getMessage());
                failed++;
                log.warn("매출 원본 {} 을 풀지 못했습니다: {}", pending.id(), e.getMessage());
            } catch (RuntimeException e) {
                // 저장 자체가 실패한 경우 — 데이터베이스 문제일 수
                // 있습니다. 원본을 FAILED 로 낙인찍지 않고 다음 회차에
                // 다시 시도합니다: 원본이 잘못된 것과 저장이 안 되는
                // 것은 다른 사고입니다.
                failed++;
                log.error("매출 원본 {} 을 저장하지 못했습니다 — 다음 회차에 다시 시도합니다",
                        pending.id(), e);
            }
        }

        if (done > 0 || failed > 0) {
            log.info("매출 분해: 성공 {}건, 실패 {}건", done, failed);
        }
        return new Result(done, failed);
    }

    public record Result(int decomposed, int failed) {
    }
}
