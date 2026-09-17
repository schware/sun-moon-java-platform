package com.sunmoon.platform.domain.reprocess;

import com.sunmoon.platform.domain.sale.SaleLog;
import com.sunmoon.platform.domain.sale.SaleRepository;

import java.util.List;

/**
 * 분해에 실패한 매출을 다시 풀게 만듭니다 (§3.2, §5.8).
 *
 * <p>셋 중 <b>유일하게 이 서버가 직접 다시 하는</b> 것입니다. 원본이
 * 그대로 있으니 상태만 되돌리면 배치가 다음 회차에 집어 갑니다 —
 * 원본을 바이트로 담아 둔 이유가 여기서 값을 합니다(umbrella adr/0013).
 *
 * <p>규칙이 바뀌어서 다시 푸는 경우도 같은 길입니다. 분해기를 고치고
 * 여기를 누르면 어제 실패한 거래가 오늘 규칙으로 다시 풀립니다.
 *
 * <p>2026-09-18 이전에는 배치가 <b>실패한 것도 계속 집어 갔습니다</b> —
 * 영원히 못 푸는 거래를 30초마다 다시 풀고 있었고, 그래서 이 버튼은
 * 아무 뜻이 없었습니다. 배치가 {@code RECEIVED} 만 보게 고친 뒤에야
 * "실패는 멈추고 사람을 기다린다"가 되었고, 되돌리는 길이 여기 하나가
 * 되었습니다.
 */
public final class SaleReprocessor implements Reprocessor {

    private final SaleRepository sales;

    public SaleReprocessor(SaleRepository sales) {
        this.sales = sales;
    }

    @Override
    public String kind() {
        return "SALE";
    }

    @Override
    public String label() {
        return "매출 분해";
    }

    @Override
    public List<FailedItem> failed(String storeId) {
        return sales.failed(storeId).stream()
                .map(log -> new FailedItem(
                        String.valueOf(log.id()),
                        log.storeId(),
                        // 내부 id 가 아니라 **거래를 가리키는 말**로 보여줍니다.
                        // 매출 키 그대로입니다 (판매일자/POS/거래번호).
                        log.businessDate() + " " + log.posNo() + " / " + log.tranNo(),
                        log.error(),
                        log.attempts(),
                        log.receivedAt()))
                .toList();
    }

    @Override
    public Outcome retry(String storeId, String id) {
        long logId;
        try {
            logId = Long.parseLong(id);
        } catch (NumberFormatException notANumber) {
            return Outcome.notFound(id);
        }
        if (!sales.markForRetry(logId)) {
            // 이미 풀렸거나 없는 건입니다. 둘 다 "다시 걸 것이 없다"라
            // 같은 답이면 됩니다 — 운영자가 목록을 오래 열어 두면
            // 그 사이에 배치가 처리했을 수 있습니다.
            return Outcome.notFound(id);
        }
        // "다시 했다"가 아니라 "다시 하게 걸었다"입니다. 배치가 30초마다
        // 도니까 누르자마자 바뀌지 않고, 그 사실을 말해 줘야 운영자가
        // 계속 누르지 않습니다.
        return Outcome.queued("다시 분해하도록 걸었습니다 — 다음 배치(30초 이내)에 처리됩니다");
    }

    /** 이 종류만 <b>얼마나 쌓였는지</b>가 금액으로도 의미가 있어서 함께 셉니다. */
    public long count(String storeId) {
        return sales.failed(storeId).stream().map(SaleLog::id).count();
    }
}
