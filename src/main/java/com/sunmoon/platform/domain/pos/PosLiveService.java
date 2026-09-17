package com.sunmoon.platform.domain.pos;

import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * POS Live Check — 다섯 축을 <b>따로</b> 답합니다 (업무 정의서 §3.5).
 *
 * <p>이 클래스의 값어치는 합치는 데 있지 않고 <b>가르는 데</b>
 * 있습니다. 소켓이 붙어 있다는 사실은 그 단말에 대해 정확히 한 가지만
 * 말해 줍니다 — 소켓이 붙어 있다는 것. 프로그램이 도는지, 마감을
 * 했는지, 프린터가 살아 있는지, 매출이 올라갔는지는 아무것도 말해
 * 주지 않습니다. 문서가 "연결 상태만으로 정상 영업 여부를 판단하지
 * 않는다"고 못 박은 자리입니다.
 *
 * <p>축마다 <b>출처가 다릅니다</b>:
 * <ul>
 *   <li>연결 — 이 서버의 소켓 레지스트리. 남에게 묻지 않습니다.
 *   <li>영업 — §3.4 의 개점·마감 상태. 따로 보고받으면 두 곳이 서로
 *       다른 말을 하는 날이 옵니다.
 *   <li>프로그램·장비 — POS의 Heartbeat.
 *   <li>데이터 — POS의 Heartbeat <b>와</b> 서버가 본 분해 실패.
 *       POS는 자기가 보낸 매출이 서버에서 깨졌는지 모릅니다.
 * </ul>
 *
 * <p>보고가 끊기면 마지막 값을 쓰지 않고 {@link AxisState#UNKNOWN}
 * 입니다. 죽기 직전에 "정상"이라고 말한 POS를 영원히 정상으로 두지
 * 않기 위해서입니다.
 */
public final class PosLiveService {

    /**
     * 그 매장 그 날에 아직 분해되지 않은 매출이 몇 건인가.
     *
     * <p>{@code SaleRepository} 를 직접 받지 않는 이유: Live Check 가
     * 매출 저장소에 묶이면 Postgres 없는 환경에서 관제 화면 전체가
     * 못 뜹니다. 여기서 필요한 것은 숫자 하나입니다.
     */
    @FunctionalInterface
    public interface SaleFailureLookup {
        long failedSales(String storeId, LocalDate businessDate);

        SaleFailureLookup NONE = (storeId, businessDate) -> 0L;
    }

    /**
     * @param overall 다섯 축 가운데 <b>가장 나쁜 것</b>. 밤에는 영업
     *                축이 DOWN 이라 단말도 중단으로 보이는데, 그게
     *                맞습니다 — 묻는 것이 "정상 영업 중인가"입니다.
     * @param mismatches 축끼리 아귀가 안 맞는 대목. 문서의 "상태 불일치
     *                시 정합성 보정 절차"가 시작되는 자리입니다.
     */
    public record PosLiveView(
            String storeId, String posNo,
            Map<LiveAxis, AxisState> axes, AxisState overall,
            String programVersion, String dbVersion,
            Instant lastReportedAt, List<String> mismatches) {

        public boolean normal() {
            return overall.normal();
        }
    }

    private final TerminalRegistry terminals;
    private final PosLiveRepository reports;
    private final PosBusinessRepository business;
    private final SaleFailureLookup failures;
    private final Duration heartbeatGrace;
    private final Clock clock;

    public PosLiveService(TerminalRegistry terminals, PosLiveRepository reports,
                          PosBusinessRepository business, SaleFailureLookup failures,
                          Duration heartbeatGrace, Clock clock) {
        this.terminals = terminals;
        this.reports = reports;
        this.business = business;
        this.failures = failures == null ? SaleFailureLookup.NONE : failures;
        this.heartbeatGrace = heartbeatGrace;
        this.clock = clock;
    }

    public void report(PosHeartbeat heartbeat) {
        reports.report(heartbeat);
    }

    /**
     * 그 매장 단말들의 다섯 축.
     *
     * <p>대상 단말을 세 곳에서 모읍니다 — 지금 붙어 있는 소켓,
     * Heartbeat 를 보낸 적 있는 단말, 그리고 오늘 개점한 단말.
     * 하나만 보면 놓칩니다: 개점하고 죽은 POS는 소켓도 Heartbeat 도
     * 없지만 <b>바로 그것이 봐야 할 단말</b>입니다.
     */
    public List<PosLiveView> viewOf(String storeId, LocalDate businessDate) {
        Set<String> posNos = new LinkedHashSet<>();
        terminals.connected().stream()
                .filter(session -> session.type() == TerminalType.POS)
                .filter(session -> storeId.equals(session.storeId()))
                .forEach(session -> posNos.add(session.deviceId()));
        reports.reportsOf(storeId).forEach(report -> posNos.add(report.posNo()));
        business.statesOf(storeId, businessDate).forEach(state -> posNos.add(state.posNo()));

        long failed = failures.failedSales(storeId, businessDate);

        List<PosLiveView> views = new ArrayList<>();
        for (String posNo : posNos) {
            views.add(viewOf(storeId, posNo, businessDate, failed));
        }
        views.sort(Comparator.comparing(PosLiveView::posNo));
        return views;
    }

    public PosLiveView viewOf(String storeId, String posNo, LocalDate businessDate) {
        return viewOf(storeId, posNo, businessDate, failures.failedSales(storeId, businessDate));
    }

    private PosLiveView viewOf(String storeId, String posNo, LocalDate businessDate, long failedSales) {
        Instant now = clock.instant();

        AxisState connection = terminals.channelFor(storeId, posNo, TerminalType.POS).isPresent()
                ? AxisState.OK : AxisState.DOWN;

        Optional<PosHeartbeat> report = reports.lastReport(storeId, posNo);
        boolean heard = report.isPresent()
                && !report.get().reportedAt().isBefore(now.minus(heartbeatGrace));

        AxisState application = heard ? report.get().application() : AxisState.UNKNOWN;
        AxisState device = heard ? report.get().device() : AxisState.UNKNOWN;
        AxisState data = heard ? report.get().data() : AxisState.UNKNOWN;

        // 서버가 본 것이 POS의 말을 이깁니다. POS는 자기가 보낸 매출이
        // 서버에서 깨졌는지 알 길이 없어서, 그 경우 POS의 "정상"은
        // 거짓이 아니라 모르고 하는 말입니다.
        if (failedSales > 0) {
            data = AxisState.DOWN;
        }

        AxisState businessAxis = business.find(storeId, posNo, businessDate)
                .map(state -> state.open() ? AxisState.OK : AxisState.DOWN)
                .orElse(AxisState.UNKNOWN);

        Map<LiveAxis, AxisState> axes = new EnumMap<>(LiveAxis.class);
        axes.put(LiveAxis.CONNECTION, connection);
        axes.put(LiveAxis.APPLICATION, application);
        axes.put(LiveAxis.BUSINESS, businessAxis);
        axes.put(LiveAxis.DEVICE, device);
        axes.put(LiveAxis.DATA, data);

        return new PosLiveView(storeId, posNo, axes, worst(axes.values()),
                report.map(PosHeartbeat::programVersion).orElse(null),
                report.map(PosHeartbeat::dbVersion).orElse(null),
                report.map(PosHeartbeat::reportedAt).orElse(null),
                mismatches(axes, failedSales));
    }

    /**
     * 축끼리 어긋난 대목.
     *
     * <p>첫 항목이 이 화면이 존재하는 이유입니다 — 소켓은 붙어 있는데
     * 아무 말이 없는 단말. 연결만 보는 관제라면 이 단말은 초록색입니다.
     */
    private static List<String> mismatches(Map<LiveAxis, AxisState> axes, long failedSales) {
        List<String> found = new ArrayList<>();
        AxisState connection = axes.get(LiveAxis.CONNECTION);
        AxisState application = axes.get(LiveAxis.APPLICATION);
        AxisState businessAxis = axes.get(LiveAxis.BUSINESS);

        if (connection == AxisState.OK && application == AxisState.UNKNOWN) {
            found.add("연결은 살아 있는데 상태 보고가 없습니다 — 연결만으로 정상이라고 볼 수 없습니다");
        }
        if (connection == AxisState.DOWN && application != AxisState.UNKNOWN) {
            found.add("상태 보고는 오는데 연결이 없습니다 — WebSocket 쪽을 보십시오");
        }
        if (businessAxis == AxisState.OK && connection == AxisState.DOWN) {
            found.add("영업 중인데 연결이 끊겼습니다");
        }
        if (businessAxis == AxisState.OK && application == AxisState.DOWN) {
            found.add("영업 중인데 POS 프로그램이 멈췄습니다");
        }
        if (failedSales > 0) {
            found.add("분해되지 않은 매출이 " + failedSales + "건 있습니다");
        }
        return found;
    }

    private static AxisState worst(Iterable<AxisState> states) {
        AxisState worst = AxisState.OK;
        for (AxisState state : states) {
            if (rank(state) > rank(worst)) {
                worst = state;
            }
        }
        return worst;
    }

    // UNKNOWN 이 DOWN 보다 낮은 이유: 모르는 것을 고장으로 부르면
    // 아직 한 번도 안 켠 단말이 매일 아침 경보를 냅니다.
    private static int rank(AxisState state) {
        return switch (state) {
            case OK -> 0;
            case WARN -> 1;
            case UNKNOWN -> 2;
            case DOWN -> 3;
        };
    }
}
