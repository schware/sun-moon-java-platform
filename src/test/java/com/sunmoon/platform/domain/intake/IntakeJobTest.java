package com.sunmoon.platform.domain.intake;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공통 배치의 규칙 — TR 과 ETC 가 같이 기대는 것들.
 *
 * <p>제일 중요한 것은 <b>catch 둘을 가르는 것</b>입니다. 원본이 잘못된
 * 것은 FAILED 로 멈추고, 저장이 안 된 것은 원본을 그대로 둡니다. 이게
 * 뒤집히면 둘 중 하나가 납니다:
 *
 * <ul>
 *   <li>잘못된 원본이 FAILED 가 안 됨 → 30초마다 다시 풀림. 2026-09-18에
 *       실서버에서 한 건을 이틀 동안 388번 풀었습니다.
 *   <li>멀쩡한 원본이 FAILED 가 됨 → 데이터베이스가 잠깐 안 된 것
 *       때문에 사람이 재처리를 눌러야 합니다.
 * </ul>
 */
class IntakeJobTest {

    private static RawLog raw(long id) {
        return new RawLog(id, "store-01", "pos-01", LocalDate.of(2026, 9, 27), "00000" + id,
                "application/json", "{}".getBytes(), RawLog.Status.RECEIVED, null, 0, null, null);
    }

    @Test
    void 원본이_잘못되면_FAILED_로_멈춘다() {
        FakeStore store = new FakeStore(raw(1));
        IntakeJob job = new IntakeJob("시험", store, r -> {
            throw new IntakeException("details 가 비어 있습니다");
        }, 10);

        IntakeJob.Result result = job.run();

        assertEquals(0, result.decomposed());
        assertEquals(1, result.failed());
        assertEquals("details 가 비어 있습니다", store.failedWith.get(1L),
                "사유가 그대로 남아야 운영자가 보고 판단합니다");
    }

    @Test
    void 저장이_안_되면_원본을_낙인찍지_않는다() {
        FakeStore store = new FakeStore(raw(1));
        IntakeJob job = new IntakeJob("시험", store, r -> {
            throw new IllegalStateException("connection refused");
        }, 10);

        IntakeJob.Result result = job.run();

        assertEquals(1, result.failed(), "시도는 실패로 셉니다");
        assertFalse(store.failedWith.containsKey(1L),
                "원본 탓이 아니므로 FAILED 로 만들면 안 됩니다 — 다음 회차에 다시 시도합니다");
    }

    @Test
    void 한_건이_실패해도_나머지는_계속_간다() {
        FakeStore store = new FakeStore(raw(1), raw(2), raw(3));
        List<Long> handled = new ArrayList<>();
        IntakeJob job = new IntakeJob("시험", store, r -> {
            handled.add(r.id());
            if (r.id() == 2L) {
                throw new IntakeException("2번만 잘못됐습니다");
            }
        }, 10);

        IntakeJob.Result result = job.run();

        assertEquals(List.of(1L, 2L, 3L), handled,
                "잘못된 원본 하나가 그날 매출 전체를 막으면 안 됩니다");
        assertEquals(2, result.decomposed());
        assertEquals(1, result.failed());
        assertEquals(Map.of(2L, "2번만 잘못됐습니다"), store.failedWith);
    }

    @Test
    void 성공하면_배치는_아무것도_표시하지_않는다() {
        // DECOMPOSED 표시는 handler 의 트랜잭션 안에서 이미 됐습니다.
        // 배치가 한 번 더 건드리면 결과 행과 상태가 다른 트랜잭션에
        // 갈라집니다.
        FakeStore store = new FakeStore(raw(1));
        IntakeJob job = new IntakeJob("시험", store, r -> { }, 10);

        IntakeJob.Result result = job.run();

        assertEquals(1, result.decomposed());
        assertTrue(store.failedWith.isEmpty());
    }

    /** 대기 목록만 주고, 실패 표시를 기록하는 저장소. */
    private static final class FakeStore implements RawLogStore {
        private final List<RawLog> pending;
        final Map<Long, String> failedWith = new ConcurrentHashMap<>();

        FakeStore(RawLog... pending) {
            this.pending = List.of(pending);
        }

        @Override
        public Optional<Long> receive(RawLog log) {
            return Optional.empty();
        }

        @Override
        public List<RawLog> pending(int limit) {
            return pending;
        }

        @Override
        public void markFailed(long logId, String error) {
            failedWith.put(logId, error);
        }

        @Override
        public boolean markForRetry(long logId) {
            return false;
        }

        @Override
        public List<RawLog> failed(String storeId) {
            return List.of();
        }

        @Override
        public Counts counts(String storeId, LocalDate businessDate) {
            return new Counts(0, 0, 0);
        }
    }
}
