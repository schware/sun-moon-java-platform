package com.sunmoon.platform.domain.pos;

import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POS Live Check 5종 (업무 정의서 §3.5).
 *
 * <p>테스트가 지키는 한 문장: <b>"WebSocket 연결 상태만으로 정상 영업
 * 여부를 판단하지 않는다."</b> 그래서 첫 테스트가 "소켓은 붙어 있는데
 * 나머지는 모른다"입니다 — 연결만 보는 관제라면 초록색일 단말입니다.
 */
class PosLiveServiceTest {

    private static final String STORE = "store-01";
    private static final String POS = "pos-01";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final Instant NOW = Instant.parse("2026-09-17T03:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final Duration TOLERANCE = Duration.ofSeconds(30);

    private TerminalRegistry terminals;
    private FakeLiveRepository reports;
    private FakeBusiness business;

    @BeforeEach
    void setUp() {
        terminals = new TerminalRegistry();
        reports = new FakeLiveRepository();
        business = new FakeBusiness();
    }

    private PosLiveService service() {
        return service(PosLiveService.SaleFailureLookup.NONE);
    }

    private PosLiveService service(PosLiveService.SaleFailureLookup failures) {
        return new PosLiveService(terminals, reports, business, failures, GRACE, TOLERANCE,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void connect() {
        terminals.register(POS, STORE, TerminalType.POS, new EmbeddedChannel());
    }

    private void heartbeat(AxisState application, AxisState device, AxisState data, Instant at) {
        reports.report(new PosHeartbeat(STORE, POS, application, device, data,
                "1.4.2", "37", null, null, null, at));
    }

    /**
     * 소켓은 붙어 있고 아무 말도 없는 단말.
     *
     * <p>이 파일에서 제일 중요한 테스트입니다. 연결만 보는 관제 화면
     * 에서는 이 단말이 정상입니다. 실제로는 POS 프로그램이 죽었는지
     * 프린터가 나갔는지 <b>아무것도 모릅니다</b>.
     */
    @Test
    void aConnectedTerminalThatSaysNothingIsNotNormal() {
        connect();

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(AxisState.OK, view.axes().get(LiveAxis.CONNECTION));
        assertEquals(AxisState.UNKNOWN, view.axes().get(LiveAxis.APPLICATION));
        assertFalse(view.normal());
        assertTrue(view.mismatches().stream().anyMatch(m -> m.contains("상태 보고가 없습니다")),
                view.mismatches().toString());
    }

    /** 다 갖춰지면 정상입니다 — 연결, 보고, 그리고 개점까지. */
    @Test
    void allFiveAxesHealthyIsNormal() {
        connect();
        heartbeat(AxisState.OK, AxisState.OK, AxisState.OK, NOW.minusSeconds(30));
        business.open(STORE, POS, DAY);

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(AxisState.OK, view.overall());
        assertTrue(view.normal());
        assertTrue(view.mismatches().isEmpty(), view.mismatches().toString());
        assertEquals("1.4.2", view.programVersion());
    }

    /**
     * 끊긴 보고는 <b>마지막 값으로 남지 않습니다</b>.
     *
     * <p>죽기 직전에 "정상"이라고 말한 POS를 영원히 정상으로 두면,
     * 관제 화면은 그 단말이 죽은 것을 끝내 알려 주지 않습니다.
     */
    @Test
    void aStaleHeartbeatBecomesUnknownRatherThanStayingOk() {
        connect();
        heartbeat(AxisState.OK, AxisState.OK, AxisState.OK, NOW.minus(Duration.ofMinutes(10)));

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(AxisState.UNKNOWN, view.axes().get(LiveAxis.APPLICATION));
        assertEquals(AxisState.UNKNOWN, view.axes().get(LiveAxis.DEVICE));
        assertEquals(AxisState.UNKNOWN, view.axes().get(LiveAxis.DATA));
    }

    /**
     * 영업 축은 §3.4 의 개점·마감을 그대로 읽습니다 — 따로 보고받지
     * 않습니다. 두 곳에서 관리하면 서로 다른 말을 하는 날이 옵니다.
     */
    @Test
    void theBusinessAxisComesFromTheOpenCloseState() {
        connect();
        heartbeat(AxisState.OK, AxisState.OK, AxisState.OK, NOW.minusSeconds(10));

        assertEquals(AxisState.UNKNOWN, service().viewOf(STORE, POS, DAY).axes().get(LiveAxis.BUSINESS));

        business.open(STORE, POS, DAY);
        assertEquals(AxisState.OK, service().viewOf(STORE, POS, DAY).axes().get(LiveAxis.BUSINESS));

        business.close(STORE, POS, DAY);
        assertEquals(AxisState.DOWN, service().viewOf(STORE, POS, DAY).axes().get(LiveAxis.BUSINESS));
    }

    /** 영업 중인데 연결이 없는 단말 — 관제가 제일 먼저 봐야 할 줄입니다. */
    @Test
    void anOpenTerminalWithNoConnectionIsFlagged() {
        business.open(STORE, POS, DAY);
        heartbeat(AxisState.OK, AxisState.OK, AxisState.OK, NOW.minusSeconds(10));

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(AxisState.DOWN, view.axes().get(LiveAxis.CONNECTION));
        assertTrue(view.mismatches().stream().anyMatch(m -> m.contains("영업 중인데 연결이 끊겼습니다")),
                view.mismatches().toString());
    }

    /**
     * 서버가 본 것이 POS의 말을 이깁니다.
     *
     * <p>POS는 자기가 보낸 매출이 서버에서 깨졌는지 알 길이 없습니다.
     * 그 경우 POS의 "정상"은 거짓말이 아니라 모르고 하는 말이라,
     * 데이터 축은 서버가 본 실패 건수로 덮습니다.
     */
    @Test
    void failedSalesOverrideWhatThePosReported() {
        connect();
        heartbeat(AxisState.OK, AxisState.OK, AxisState.OK, NOW.minusSeconds(10));
        business.open(STORE, POS, DAY);

        PosLiveService.PosLiveView view =
                service((storeId, day) -> 3L).viewOf(STORE, POS, DAY);

        assertEquals(AxisState.DOWN, view.axes().get(LiveAxis.DATA));
        assertFalse(view.normal());
        assertTrue(view.mismatches().stream().anyMatch(m -> m.contains("3건")),
                view.mismatches().toString());
    }

    /**
     * 개점하고 죽은 단말은 소켓도 Heartbeat 도 없습니다 — 그런데
     * <b>바로 그것이 봐야 할 단말</b>이라, 목록에서 빠지면 안 됩니다.
     */
    @Test
    void aTerminalThatOpenedAndDiedStillShowsUp() {
        business.open(STORE, POS, DAY);

        List<PosLiveService.PosLiveView> views = service().viewOf(STORE, DAY);

        assertEquals(1, views.size());
        assertEquals(POS, views.get(0).posNo());
        assertEquals(AxisState.DOWN, views.get(0).axes().get(LiveAxis.CONNECTION));
    }

    /** 한 번도 안 켠 단말이 매일 아침 경보를 내면 안 됩니다 — 모르는 것은 고장이 아닙니다. */
    @Test
    void neverSeenIsUnknownNotDown() {
        heartbeat(AxisState.UNKNOWN, AxisState.UNKNOWN, AxisState.UNKNOWN, NOW.minusSeconds(10));

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(AxisState.UNKNOWN, view.axes().get(LiveAxis.APPLICATION));
        // 연결이 없으므로 종합은 DOWN 입니다. 중요한 것은 프로그램 축이
        // DOWN 으로 둔갑하지 않았다는 점입니다.
        assertEquals(AxisState.DOWN, view.overall());
    }

    /**
     * 시계가 틀어진 단말은 다섯 축이 <b>전부 정상</b>일 수 있습니다.
     *
     * <p>그게 여섯 번째 축을 만들지 않고 어긋남으로 낸 이유입니다 —
     * 축은 문서가 다섯이라고 했고, 시계는 축의 상태가 아니라 축들이
     * 말해 주지 않는 사실입니다. 그런데 그대로 두면 그 단말의 매출이
     * 다른 날로 갑니다 (umbrella docs/adr/0006).
     */
    @Test
    void aTerminalWithAWrongClockLooksHealthyOnEveryAxis() {
        connect();
        business.open(STORE, POS, DAY);
        // POS가 90초 느린 시각을 들고 보고합니다.
        service().report(new PosHeartbeat(STORE, POS, AxisState.OK, AxisState.OK, AxisState.OK,
                "1.4.2", "37", null, NOW.minusSeconds(90), null, NOW.minusSeconds(10)));

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(AxisState.OK, view.overall());
        assertEquals(90, view.clockDriftSeconds());
        assertTrue(view.mismatches().stream().anyMatch(m -> m.contains("느립니다")),
                view.mismatches().toString());
    }

    /** 허용 범위 안이면 아무 말도 하지 않습니다 — 몇 초는 늘 틀어져 있습니다. */
    @Test
    void aSmallDriftIsNotWorthSaying() {
        connect();
        business.open(STORE, POS, DAY);
        service().report(new PosHeartbeat(STORE, POS, AxisState.OK, AxisState.OK, AxisState.OK,
                "1.4.2", "37", null, NOW.minusSeconds(5), null, NOW.minusSeconds(5)));

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertTrue(view.mismatches().isEmpty(), view.mismatches().toString());
        assertTrue(view.normal());
    }

    /** 빠른 시계와 느린 시계는 다른 고장입니다 — 부호를 지우지 않습니다. */
    @Test
    void aClockRunningFastIsSaidDifferently() {
        connect();
        business.open(STORE, POS, DAY);
        service().report(new PosHeartbeat(STORE, POS, AxisState.OK, AxisState.OK, AxisState.OK,
                "1.4.2", "37", null, NOW.plusSeconds(120), null, NOW.minusSeconds(5)));

        PosLiveService.PosLiveView view = service().viewOf(STORE, POS, DAY);

        assertEquals(-120, view.clockDriftSeconds());
        assertTrue(view.mismatches().stream().anyMatch(m -> m.contains("빠릅니다")),
                view.mismatches().toString());
    }

    private static final class FakeLiveRepository implements PosLiveRepository {
        private final Map<String, PosHeartbeat> latest = new HashMap<>();

        @Override
        public void report(PosHeartbeat heartbeat) {
            latest.put(heartbeat.storeId() + "/" + heartbeat.posNo(), heartbeat);
        }

        @Override
        public Optional<PosHeartbeat> lastReport(String storeId, String posNo) {
            return Optional.ofNullable(latest.get(storeId + "/" + posNo));
        }

        @Override
        public List<PosHeartbeat> reportsOf(String storeId) {
            return latest.values().stream().filter(h -> h.storeId().equals(storeId)).toList();
        }
    }

    private static final class FakeBusiness implements PosBusinessRepository {
        private final Map<String, BusinessState> states = new HashMap<>();

        void open(String storeId, String posNo, LocalDate day) {
            put(storeId, posNo, day, BusinessStatus.OPEN, BusinessEvent.OPEN);
        }

        void close(String storeId, String posNo, LocalDate day) {
            put(storeId, posNo, day, BusinessStatus.CLOSED, BusinessEvent.CLOSE);
        }

        private void put(String storeId, String posNo, LocalDate day,
                         BusinessStatus status, BusinessEvent event) {
            states.put(storeId + "/" + posNo + "/" + day, new BusinessState(
                    storeId, posNo, day, status, event, 1, NOW, NOW));
        }

        @Override
        public Optional<BusinessState> find(String storeId, String posNo, LocalDate businessDate) {
            return Optional.ofNullable(states.get(storeId + "/" + posNo + "/" + businessDate));
        }

        @Override
        public int apply(BusinessState state, BusinessEvent event, IrtResult result, String message,
                         Instant eventAt, String irtId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int record(PosBusinessIrt irt, IrtResult result, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BusinessState> statesOf(String storeId, LocalDate businessDate) {
            List<BusinessState> found = new ArrayList<>();
            for (BusinessState state : states.values()) {
                if (state.storeId().equals(storeId) && state.businessDate().equals(businessDate)) {
                    found.add(state);
                }
            }
            return found;
        }

        @Override
        public List<BusinessHistoryEntry> historyOf(String storeId, String posNo, LocalDate businessDate) {
            return List.of();
        }
    }
}
