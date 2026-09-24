package com.sunmoon.platform.domain.reprocess;

import com.sunmoon.platform.domain.intake.RawLog;
import com.sunmoon.platform.domain.intake.RawLogStore;

import java.util.List;

/**
 * 분해에 실패한 원본을 다시 풀게 만듭니다 — <b>TR 과 ETC 둘 다</b>
 * (§3.2, §3.3, §5.8).
 *
 * <p>2026-09-18에는 {@code SaleReprocessor} 하나였습니다. ETC 가 붙으면서
 * 두 번째가 필요해졌는데, 두 클래스가 <b>글자 하나 빼고 같았습니다</b> —
 * 종류 이름과 사람이 읽는 이름만 달랐습니다. 그래서 둘로 만들지 않고
 * 이걸로 합쳤습니다.
 *
 * <p>{@link Reprocessor} 가 원래 그 판단으로 만들어진 자리입니다:
 * <i>"셋을 따로 만들지 않은 이유: 모양이 같습니다. 세 번째쯤 만들면
 * 모양이 갈라지고, 갈라진 뒤에는 화면도 셋이 됩니다."</i> 이번이 그
 * 네 번째이고, 여기서 갈랐으면 화면이 넷이 됐을 것입니다.
 *
 * <p><b>마스터·프로그램은 합치지 않습니다.</b> 저 둘은 "다시 한다"가
 * 다릅니다 — 서버가 직접 하는 것이 아니라 장비가 가져갑니다. 모양이
 * 비슷해 보여도 뜻이 달라서, 합치면 그 차이가 설정이 됩니다.
 */
public final class RawLogReprocessor implements Reprocessor {

    private final String kind;
    private final String label;
    private final RawLogStore store;

    /**
     * @param kind  주소에 쓰이는 이름 — {@code SALE} / {@code ETC}.
     * @param label 사람이 읽는 이름.
     */
    public RawLogReprocessor(String kind, String label, RawLogStore store) {
        this.kind = kind;
        this.label = label;
        this.store = store;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public List<FailedItem> failed(String storeId) {
        return store.failed(storeId).stream()
                .map(raw -> new FailedItem(
                        String.valueOf(raw.id()),
                        raw.storeId(),
                        // 내부 id 가 아니라 **그 건을 가리키는 말**로
                        // 보여줍니다 — 판매일자 / POS / 번호.
                        raw.describe(),
                        raw.error(),
                        raw.attempts(),
                        raw.receivedAt()))
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
        if (!store.markForRetry(logId)) {
            // 이미 풀렸거나 없는 건입니다. 둘 다 "다시 걸 것이 없다"라
            // 같은 답이면 됩니다 — 운영자가 목록을 오래 열어 두면 그
            // 사이에 배치가 처리했을 수 있습니다.
            return Outcome.notFound(id);
        }
        // "다시 했다"가 아니라 "다시 하게 걸었다"입니다. 배치가 30초마다
        // 도니까 누르자마자 바뀌지 않고, 그 사실을 말해 줘야 운영자가
        // 계속 누르지 않습니다.
        return Outcome.queued("다시 분해하도록 걸었습니다 — 다음 배치(30초 이내)에 처리됩니다");
    }

    /** 원본을 그대로 들고 있으니 규칙을 고친 뒤 다시 풀 수도 있습니다. */
    public long count(String storeId) {
        return store.failed(storeId).stream().map(RawLog::id).count();
    }
}
