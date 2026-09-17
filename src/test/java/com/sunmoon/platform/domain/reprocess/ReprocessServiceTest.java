package com.sunmoon.platform.domain.reprocess;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실패를 되돌리는 공통 자리 (업무 정의서 §5.8, §10).
 *
 * <p>지금까지 실패는 세 군데에 <b>적히기만</b> 했습니다. 실서버의 거래
 * 하나가 이틀째 FAILED 로 남아 있었고, 그걸 되돌릴 방법이 없었습니다.
 */
class ReprocessServiceTest {

    private static FailedItem item(String id) {
        return new FailedItem(id, "store-01", id + " 요약", "사유", 1, Instant.now());
    }

    /** 무엇을 되돌릴 수 있는지 한 번에 답합니다 — 세 군데를 다 열어보는 사람은 없습니다. */
    @Test
    void oneCallAnswersForEveryKind() {
        ReprocessService service = new ReprocessService(List.of(
                new Fake("SALE", "매출 분해", List.of(item("1"), item("2"))),
                new Fake("MASTER", "마스터 배포", List.of(item("a")))));

        var failed = service.allFailed("store-01");

        assertEquals(2, failed.get("SALE").size());
        assertEquals(1, failed.get("MASTER").size());
    }

    /** 실패가 없는 종류도 <b>빈 목록으로</b> 나옵니다 — 화면이 "전부 정상"을 그릴 수 있어야 합니다. */
    @Test
    void aKindWithNothingWrongStillAppears() {
        ReprocessService service = new ReprocessService(List.of(
                new Fake("PROGRAM", "프로그램 설치", List.of())));

        assertTrue(service.allFailed("store-01").containsKey("PROGRAM"));
        assertTrue(service.allFailed("store-01").get("PROGRAM").isEmpty());
    }

    /**
     * <b>"다시 했다"와 "다시 하게 걸었다"를 가릅니다.</b>
     *
     * <p>매출은 배치가 30초 뒤에 집어 가고, 마스터·프로그램은 장비가
     * 다음에 물어볼 때입니다. 둘을 같은 말로 답하면 운영자는 눌렀는데
     * 목록이 그대로인 것을 보고 계속 누릅니다.
     */
    @Test
    void queuedIsNotTheSameAsDone() {
        assertFalse(Reprocessor.Outcome.queued("걸었습니다").done());
        assertTrue(Reprocessor.Outcome.queued("걸었습니다").accepted());

        assertTrue(Reprocessor.Outcome.done("했습니다").done());
        assertTrue(Reprocessor.Outcome.done("했습니다").accepted());
    }

    /**
     * 없는 건은 <b>오류가 아닙니다</b>.
     *
     * <p>운영자가 목록을 열어 둔 사이에 배치가 처리했을 수 있습니다.
     * 그때 404를 내면 "고쳐졌는데 실패했다고 뜨는" 화면이 됩니다.
     */
    @Test
    void somethingAlreadyHandledIsNotAnError() {
        Reprocessor.Outcome outcome = Reprocessor.Outcome.notFound("9");

        assertFalse(outcome.accepted());
        assertFalse(outcome.done());
    }

    /**
     * 일괄 재처리는 한 건씩 <b>한 번만</b> 부릅니다.
     *
     * <p>두 번 부르면 두 번째는 첫 번째가 이미 되돌려 놓은 것을 못 찾아
     * 실패로 세고, 화면에는 "성공 0건 실패 20건"이 뜹니다.
     */
    @Test
    void retryingAllCallsEachItemExactlyOnce() {
        Fake sales = new Fake("SALE", "매출 분해", List.of(item("1"), item("2"), item("3")));
        ReprocessService service = new ReprocessService(List.of(sales));

        ReprocessService.Retried retried = service.retryAll("SALE", "store-01");

        assertEquals(3, retried.queued());
        assertEquals(0, retried.failed());
        assertEquals(3, sales.tried.size());
    }

    /** 한 건이 안 돼도 나머지는 계속합니다 — 첫 줄이 이상하다고 열아홉 건이 막히면 안 됩니다. */
    @Test
    void oneBadItemDoesNotStopTheRest() {
        Fake sales = new Fake("SALE", "매출 분해", List.of(item("1"), item("boom"), item("3")));
        ReprocessService service = new ReprocessService(List.of(sales));

        ReprocessService.Retried retried = service.retryAll("SALE", "store-01");

        assertEquals(2, retried.queued());
        assertEquals(1, retried.failed());
    }

    @Test
    void anUnknownKindIsAnswerNotAnException() {
        ReprocessService service = new ReprocessService(List.of());

        assertFalse(service.retry("NOPE", "store-01", "1").accepted());
        assertEquals(0, service.retryAll("NOPE", "store-01").queued());
    }

    /** 종류 이름은 대소문자를 안 가립니다 — 주소창에 손으로 치는 값입니다. */
    @Test
    void theKindIsNotCaseSensitive() {
        ReprocessService service = new ReprocessService(List.of(
                new Fake("SALE", "매출 분해", List.of(item("1")))));

        assertTrue(service.of("sale").isPresent());
    }

    private static final class Fake implements Reprocessor {
        private final String kind;
        private final String label;
        private final List<FailedItem> items;
        private final Set<String> tried = new LinkedHashSet<>();

        Fake(String kind, String label, List<FailedItem> items) {
            this.kind = kind;
            this.label = label;
            this.items = new ArrayList<>(items);
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
            return items;
        }

        @Override
        public Outcome retry(String storeId, String id) {
            if (!tried.add(id)) {
                // 두 번 불린 것을 시험이 잡을 수 있게 합니다.
                throw new IllegalStateException("두 번 불렸습니다: " + id);
            }
            if ("boom".equals(id)) {
                throw new IllegalStateException("일부러 터뜨립니다");
            }
            return Outcome.queued("걸었습니다");
        }
    }
}
