package com.sunmoon.platform.domain.reprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 실패한 것들을 한자리에서 보고 되돌립니다 (§5.8, §10).
 *
 * <p>종류마다 저장소가 다르고 "다시 한다"의 뜻도 다르지만, 운영자가
 * 묻는 것은 하나입니다: <b>지금 손봐야 할 것이 있나.</b> 그 질문에
 * 답하는 자리가 세 군데면 아무도 세 군데를 다 열어보지 않습니다.
 */
public final class ReprocessService {

    private static final Logger log = LoggerFactory.getLogger(ReprocessService.class);

    private final Map<String, Reprocessor> byKind = new LinkedHashMap<>();

    public ReprocessService(List<Reprocessor> reprocessors) {
        for (Reprocessor reprocessor : reprocessors) {
            byKind.put(reprocessor.kind(), reprocessor);
        }
    }

    public List<Reprocessor> kinds() {
        return List.copyOf(byKind.values());
    }

    public Optional<Reprocessor> of(String kind) {
        return Optional.ofNullable(byKind.get(kind == null ? null : kind.toUpperCase()));
    }

    /** 종류별 실패 목록. 없는 종류도 <b>빈 목록으로 나옵니다</b> — 화면이 "전부 정상"을 그릴 수 있어야 합니다. */
    public Map<String, List<FailedItem>> allFailed(String storeId) {
        Map<String, List<FailedItem>> found = new LinkedHashMap<>();
        for (Reprocessor reprocessor : byKind.values()) {
            found.put(reprocessor.kind(), reprocessor.failed(storeId));
        }
        return found;
    }

    public Reprocessor.Outcome retry(String kind, String storeId, String id) {
        return of(kind)
                .map(reprocessor -> {
                    Reprocessor.Outcome outcome = reprocessor.retry(storeId, id);
                    log.info("재처리 {} {}/{} — {}", reprocessor.label(), storeId, id, outcome.message());
                    return outcome;
                })
                .orElseGet(() -> new Reprocessor.Outcome(false, false, "그런 종류가 없습니다: " + kind));
    }

    /**
     * 그 종류의 실패를 <b>전부</b> 다시 겁니다.
     *
     * <p>한 건씩 누르게 두면 스무 건이 쌓인 날 아무도 안 누릅니다.
     * 대신 한 건이 실패해도 나머지는 계속합니다 — 목록의 첫 줄이
     * 이상하다고 나머지 열아홉 건이 막히면 안 됩니다.
     */
    public Retried retryAll(String kind, String storeId) {
        Optional<Reprocessor> reprocessor = of(kind);
        if (reprocessor.isEmpty()) {
            return new Retried(0, 0);
        }
        int ok = 0;
        int failed = 0;
        for (FailedItem item : reprocessor.get().failed(storeId)) {
            try {
                // 한 번만 부릅니다. 두 번 부르면 두 번째는 첫 번째가
                // 이미 되돌려 놓은 것을 못 찾아 실패로 셉니다.
                if (reprocessor.get().retry(storeId, item.id()).accepted()) {
                    ok++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                log.warn("재처리 실패: {} {}", kind, item.id(), e);
                failed++;
            }
        }
        log.info("재처리 일괄 {} {} — 성공 {}건, 실패 {}건", kind, storeId, ok, failed);
        return new Retried(ok, failed);
    }

    public record Retried(int queued, int failed) {
    }
}
