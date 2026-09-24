package com.sunmoon.platform.domain.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 쌓인 원본을 푸는 배치 — TR 과 ETC 가 같은 것을 씁니다 (§3.2, §3.3).
 *
 * <p>받는 것과 따로 도는 이유가 셋입니다: 받는 쪽은 단말이 손님을
 * 세워 두고 기다리므로 빨라야 하고, 분해가 실패해도 원본은 남아야
 * 하고, 분해 규칙이 바뀌면 원본으로 다시 풀 수 있어야 합니다.
 *
 * <p><b>한 건이 실패해도 나머지는 계속 갑니다.</b> 잘못된 원본 하나가
 * 그날 매출 전체를 막으면, 고치는 동안 매장이 통째로 멈춥니다.
 *
 * <p>이 클래스에서 제일 중요한 것은 <b>catch 가 둘</b>이라는 것입니다.
 * 아래에 이유가 적혀 있습니다.
 */
public final class IntakeJob {

    private static final Logger log = LoggerFactory.getLogger(IntakeJob.class);

    private final String label;
    private final RawLogStore store;
    private final IntakeHandler handler;
    private final int batchSize;

    /** @param label 로그에 찍히는 이름 — "TR 매출" / "ETC 매출". */
    public IntakeJob(String label, RawLogStore store, IntakeHandler handler, int batchSize) {
        this.label = label;
        this.store = store;
        this.handler = handler;
        this.batchSize = batchSize;
    }

    public Result run() {
        int done = 0;
        int failed = 0;

        for (RawLog raw : store.pending(batchSize)) {
            try {
                // 성공하면 여기서 할 일이 없습니다 — 결과 저장과
                // DECOMPOSED 표시가 handler 의 한 트랜잭션 안에 있습니다.
                handler.handle(raw);
                done++;
            } catch (IntakeException badPayload) {
                // **원본이 잘못됐습니다.** 낙인찍고 사람을 기다립니다.
                // 다시 돌려도 같은 결과라, 배치가 계속 집어 가면 안 됩니다.
                store.markFailed(raw.id(), badPayload.getMessage());
                failed++;
                log.warn("{} 원본 {} 을 풀지 못했습니다: {}", label, raw.id(), badPayload.getMessage());
            } catch (RuntimeException somethingElse) {
                // **원본 탓이 아닙니다** — 데이터베이스가 잠깐 안 되는
                // 것일 수 있습니다. FAILED 로 낙인찍으면 멀쩡한 원본이
                // 사람 손을 기다리게 되므로, 그대로 두고 다음 회차에
                // 다시 시도합니다.
                failed++;
                log.error("{} 원본 {} 을 저장하지 못했습니다 — 다음 회차에 다시 시도합니다",
                        label, raw.id(), somethingElse);
            }
        }

        if (done > 0 || failed > 0) {
            log.info("{} 분해: 성공 {}건, 실패 {}건", label, done, failed);
        }
        return new Result(done, failed);
    }

    public record Result(int decomposed, int failed) {
    }
}
