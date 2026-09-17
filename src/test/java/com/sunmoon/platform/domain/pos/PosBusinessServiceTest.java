package com.sunmoon.platform.domain.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 개점·마감 IRT 의 규칙 (업무 정의서 §3.4).
 *
 * <p>여기서 지키려는 것 셋: <b>재개점을 서버가 알아보는가</b>,
 * <b>늦게 온 전문이 상태를 되돌리지 않는가</b>, 그리고 <b>무시한
 * 전문도 이력에 남는가</b>. 셋 다 실제 매장에서 일어나는 일입니다 —
 * POS는 점심에 재부팅하고, 끊겼던 단말은 밀린 전문을 몰아 올립니다.
 */
class PosBusinessServiceTest {

    private static final String STORE = "store-01";
    private static final String POS = "pos-01";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final Instant MORNING = Instant.parse("2026-09-17T00:00:00Z");

    private FakeBusinessRepository repository;
    private PosBusinessService service;

    @BeforeEach
    void setUp() {
        repository = new FakeBusinessRepository();
        service = new PosBusinessService(repository);
    }

    private static PosBusinessIrt irt(BusinessEvent event, Instant at, String irtId) {
        return new PosBusinessIrt(STORE, POS, DAY, event, at, irtId);
    }

    @Test
    void theFirstOpenStartsTheDay() {
        PosBusinessService.Outcome outcome = service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));

        assertEquals(IrtResult.APPLIED, outcome.result());
        assertEquals(BusinessStatus.OPEN, outcome.state().status());
        assertEquals(BusinessEvent.OPEN, outcome.recordedEvent());
        assertEquals(1, outcome.state().openCount());
    }

    /**
     * 닫혔다 다시 열면 <b>재개점</b>입니다 — 그 이름은 서버가 붙입니다.
     *
     * <p>POS가 스스로 판단하게 두면 안 되는 이유: 재부팅했거나 로컬
     * DB를 새로 깐 POS는 오늘 이미 열었다는 것을 모르고, 그냥 개점이라고
     * 말합니다. 그러면 이력에서 재개점이 사라지고, 그날 몇 번 닫혔는지
     * 아무도 알 수 없습니다.
     */
    @Test
    void openingAgainAfterCloseIsRecordedAsReopen() {
        service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));
        service.receive(irt(BusinessEvent.CLOSE, MORNING.plusSeconds(3600), "irt-2"));

        PosBusinessService.Outcome reopened =
                service.receive(irt(BusinessEvent.OPEN, MORNING.plusSeconds(7200), "irt-3"));

        assertEquals(BusinessEvent.REOPEN, reopened.recordedEvent());
        assertEquals(BusinessStatus.OPEN, reopened.state().status());
        // 두 번째 개점입니다. 이 숫자가 "오늘 몇 번 닫혔나"의 답입니다.
        assertEquals(2, reopened.state().openCount());
    }

    /**
     * 같은 말이 두 번 와도 오류가 아닙니다.
     *
     * <p>응답을 못 받은 POS는 다시 보냅니다. 거기서 오류를 내면 POS는
     * 영원히 재시도합니다 — 개점 한 번에 전문이 수백 개 쌓입니다.
     */
    @Test
    void openingTwiceIsNotAnError() {
        service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));

        PosBusinessService.Outcome again =
                service.receive(irt(BusinessEvent.OPEN, MORNING.plusSeconds(10), "irt-2"));

        assertEquals(IrtResult.DUPLICATE, again.result());
        assertEquals(BusinessStatus.OPEN, again.state().status());
        // 두 번 열렸다고 세지 않습니다.
        assertEquals(1, again.state().openCount());
    }

    /**
     * <b>늦게 도착한 전문은 상태를 바꾸지 않습니다.</b>
     *
     * <p>이 테스트가 이 파일에서 제일 중요합니다. 끊겼던 POS가 밀린
     * 전문을 몰아 올리면 아침 9시의 개점이 저녁 6시의 마감 뒤에 도착할
     * 수 있습니다. 시각을 안 보면 마감한 단말이 다시 열립니다 — 그리고
     * 아무도 그 단말을 다시 닫지 않습니다.
     */
    @Test
    void aLateArrivingOldEventDoesNotReopenAClosedTerminal() {
        service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));
        service.receive(irt(BusinessEvent.CLOSE, MORNING.plusSeconds(36000), "irt-2"));

        // 아침에 일어난 개점이 이제야 도착했습니다.
        PosBusinessService.Outcome late =
                service.receive(irt(BusinessEvent.OPEN, MORNING.plusSeconds(60), "irt-3"));

        assertEquals(IrtResult.IGNORED_STALE, late.result());
        assertEquals(BusinessStatus.CLOSED, late.state().status());
        assertFalse(late.state().open());
    }

    /** 무시했어도 이력에는 남습니다 — 무시했다는 사실 자체가 나중에 답해야 할 질문입니다. */
    @Test
    void anIgnoredEventIsStillInTheHistory() {
        service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));
        service.receive(irt(BusinessEvent.CLOSE, MORNING.plusSeconds(36000), "irt-2"));
        service.receive(irt(BusinessEvent.OPEN, MORNING.plusSeconds(60), "irt-3"));

        List<PosBusinessRepository.BusinessHistoryEntry> history =
                service.historyOf(STORE, POS, DAY);

        assertEquals(3, history.size());
        assertEquals(IrtResult.IGNORED_STALE, history.get(2).result());
        assertNotNull(history.get(2).message());
        // 처리 순번은 1부터 이어집니다.
        assertEquals(List.of(1, 2, 3), history.stream().map(
                PosBusinessRepository.BusinessHistoryEntry::seq).toList());
    }

    /**
     * 개점 없이 마감이 먼저 와도 <b>받습니다</b>.
     *
     * <p>개점 전문이 유실됐거나 서버가 내려가 있었다는 뜻인데, 여기서
     * 막으면 그 단말은 그날 내내 "열려 있음"으로 남습니다 — 실제로는
     * 닫혀 있는데도. 열려 있다고 잘못 아는 쪽이 더 나쁩니다.
     */
    @Test
    void aCloseWithoutAnOpenIsAcceptedAndFlagged() {
        PosBusinessService.Outcome outcome =
                service.receive(irt(BusinessEvent.CLOSE, MORNING, "irt-1"));

        assertEquals(IrtResult.APPLIED, outcome.result());
        assertEquals(BusinessStatus.CLOSED, outcome.state().status());
        assertTrue(outcome.message().contains("개점 기록 없이"), outcome.message());
    }

    /** POS는 OPEN 과 CLOSE 만 보냅니다. REOPEN 은 서버만 씁니다. */
    @Test
    void posCannotDeclareAReopenItself() {
        try {
            BusinessEvent.reported("REOPEN");
            org.junit.jupiter.api.Assertions.fail("REOPEN 은 POS가 보낼 수 있는 값이 아닙니다");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("OPEN or CLOSE"), expected.getMessage());
        }
    }

    /**
     * 매장 안의 다른 POS 는 별개입니다.
     *
     * <p>키가 (매장, POS, 판매일자)인 이유가 이것입니다 — 한 단말의
     * 마감이 옆 단말을 닫으면 그 매장은 영업 중에 매출이 끊깁니다.
     */
    @Test
    void oneTerminalClosingDoesNotCloseTheOthers() {
        service.receive(new PosBusinessIrt(STORE, "pos-01", DAY, BusinessEvent.OPEN, MORNING, "a"));
        service.receive(new PosBusinessIrt(STORE, "pos-02", DAY, BusinessEvent.OPEN, MORNING, "b"));
        service.receive(new PosBusinessIrt(STORE, "pos-01", DAY, BusinessEvent.CLOSE,
                MORNING.plusSeconds(60), "c"));

        List<BusinessState> states = service.statesOf(STORE, DAY);
        assertEquals(2, states.size());
        assertEquals(BusinessStatus.CLOSED, states.get(0).status());
        assertEquals(BusinessStatus.OPEN, states.get(1).status());
    }

    /**
     * 같은 IRT 식별값이 두 번 오면 이력은 하나입니다.
     *
     * <p>실서버에서는 유일 인덱스가 막습니다. 여기서는 그 계약을
     * 가짜 저장소로 흉내 내어, 서비스가 그 계약에 기대고 있다는 것을
     * 적어 둡니다.
     */
    @Test
    void theSameIrtTwiceIsOneHistoryRow() {
        service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));
        service.receive(irt(BusinessEvent.OPEN, MORNING, "irt-1"));

        assertEquals(1, service.historyOf(STORE, POS, DAY).size());
    }

    /** 유일 인덱스까지 포함한 저장소 계약을 흉내 냅니다. */
    private static final class FakeBusinessRepository implements PosBusinessRepository {

        private final Map<String, BusinessState> states = new HashMap<>();
        private final Map<String, List<BusinessHistoryEntry>> history = new HashMap<>();
        private final Map<String, Integer> seqByIrt = new HashMap<>();

        private static String key(String storeId, String posNo, LocalDate day) {
            return storeId + "/" + posNo + "/" + day;
        }

        @Override
        public Optional<BusinessState> find(String storeId, String posNo, LocalDate businessDate) {
            return Optional.ofNullable(states.get(key(storeId, posNo, businessDate)));
        }

        @Override
        public int apply(BusinessState state, BusinessEvent event, IrtResult result, String message,
                         Instant eventAt, String irtId) {
            states.put(key(state.storeId(), state.posNo(), state.businessDate()), state);
            return append(state.storeId(), state.posNo(), state.businessDate(),
                    event, result, message, eventAt, irtId);
        }

        @Override
        public int record(PosBusinessIrt irt, IrtResult result, String message) {
            return append(irt.storeId(), irt.posNo(), irt.businessDate(),
                    irt.event(), result, message, irt.eventAt(), irt.irtId());
        }

        private int append(String storeId, String posNo, LocalDate day, BusinessEvent event,
                           IrtResult result, String message, Instant eventAt, String irtId) {
            if (irtId != null && seqByIrt.containsKey(irtId)) {
                return seqByIrt.get(irtId);
            }
            List<BusinessHistoryEntry> rows =
                    history.computeIfAbsent(key(storeId, posNo, day), k -> new ArrayList<>());
            int seq = rows.size() + 1;
            rows.add(new BusinessHistoryEntry(seq, event, result, message, eventAt, Instant.now(), irtId));
            if (irtId != null) {
                seqByIrt.put(irtId, seq);
            }
            return seq;
        }

        @Override
        public List<BusinessState> statesOf(String storeId, LocalDate businessDate) {
            return states.values().stream()
                    .filter(state -> state.storeId().equals(storeId))
                    .filter(state -> state.businessDate().equals(businessDate))
                    .sorted(java.util.Comparator.comparing(BusinessState::posNo))
                    .toList();
        }

        @Override
        public List<BusinessHistoryEntry> historyOf(String storeId, String posNo, LocalDate businessDate) {
            return history.getOrDefault(key(storeId, posNo, businessDate), List.of());
        }
    }
}
