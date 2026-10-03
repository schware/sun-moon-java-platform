package com.sunmoon.platform.domain.reprocess;

import com.sunmoon.common.reprocess.FailedItem;
import com.sunmoon.common.reprocess.Reprocessor;

import com.sunmoon.platform.domain.print.PrintJob;
import com.sunmoon.platform.domain.print.PrintJobStore;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 못 찍은 라벨 — 재처리 화면의 다섯 번째 종류 {@code PRINT} (store-app.md §7.2).
 *
 * <p>원본 분해(RawLogReprocessor)와 합치지 않았습니다. 거기서 "다시" 는 배치가 다시 푸는
 * 것이고, 여기서 "다시" 는 <b>KDS-MAIN 에 다시 밀어 넣는 것</b>입니다 — 뜻이 다릅니다.
 */
public final class PrintJobReprocessor implements Reprocessor {

    private final PrintJobStore jobs;
    private final Function<String, List<PrintJob>> failed;
    private final BiConsumer<String, Long> pushAgain;

    /**
     * @param failed    매장(비면 전 매장)의 FAILED 작업
     * @param pushAgain (매장, 작업) — 다시 줄 세운 뒤 MAIN 에 알림
     */
    public PrintJobReprocessor(PrintJobStore jobs, Function<String, List<PrintJob>> failed,
                               BiConsumer<String, Long> pushAgain) {
        this.jobs = jobs;
        this.failed = failed;
        this.pushAgain = pushAgain;
    }

    @Override
    public String kind() {
        return "PRINT";
    }

    @Override
    public String label() {
        return "라벨 출력";
    }

    @Override
    public List<FailedItem> failed(String storeId) {
        return failed.apply(storeId).stream()
                .map(j -> new FailedItem(
                        String.valueOf(j.id()),
                        j.storeId(),
                        "주문 " + j.orderId() + " · " + j.content().get("menuName")
                                + " " + j.unitNo() + "/" + j.unitTotal()
                                + (j.copyNo() > 0 ? " · 재출력 " + j.copyNo() : "")
                                + " · 프린터 " + j.printerId(),
                        j.error(),
                        j.attempts(),
                        j.requestedAt()))
                .toList();
    }

    @Override
    public Outcome retry(String storeId, String id) {
        long jobId;
        try {
            jobId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return Outcome.notFound(id);
        }
        var job = jobs.find(jobId);
        if (job.isEmpty() || !jobs.requeue(storeId, jobId)) {
            return Outcome.notFound(id);
        }
        pushAgain.accept(job.get().storeId(), jobId);
        return Outcome.queued("다시 줄 세웠습니다 — KDS-MAIN 이 붙어 있으면 바로, 아니면 다시 붙을 때 찍습니다");
    }
}
