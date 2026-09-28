package com.sunmoon.platform.api;

import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalSession;
import com.sunmoon.platform.domain.terminal.TerminalType;
import com.sunmoon.platform.transport.ws.TerminalHandshakeHandler;
import io.netty.channel.Channel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주방의 명령은 KDS-MAIN 이 냅니다 (ADR-0015, umbrella docs/design/store-app.md §4.2).
 *
 * <p>지금까지는 화면이 버튼을 숨긴 것뿐이었습니다. 페어링(1단계)으로 이 서버가
 * 기기의 역할을 알게 되었으므로 여기서 지킵니다.
 *
 * <table>
 *   <tr><td>수령</td><td>MAIN · (비상 시) SUB</td></tr>
 *   <tr><td>다시 부르기</td><td>MAIN</td></tr>
 *   <tr><td>강제 전부 완료</td><td>MAIN</td></tr>
 * </table>
 *
 * <h2>신원이 없는 요청</h2>
 *
 * <p>토큰 없이 온 요청(페어링 전 단말)은 역할을 모르므로 <b>예전처럼 통과</b>합니다.
 * {@code API_AUTH=enforce} 가 되면 그런 요청은 ApiGuard 가 먼저 막습니다 — 여기가
 * 두 번 막을 필요가 없습니다. 반대로 <b>토큰이 있는 기기의 역할 위반은 모드와
 * 상관없이 막습니다</b>: 페어링된 기기는 새로 생긴 것이라 막아도 멈출 매장이 없습니다.
 *
 * <h2>비상 모드</h2>
 *
 * <p>그 매장의 MAIN 이 {@code TERMINAL_GRACE}(3분) 넘게 안 붙어 있으면 SUB 의 수령을
 * 통과시키고, 그 수령에 {@code emergency=true} 를 적어 보냅니다. MAIN 이 돌아오면
 * 다시 막힙니다. 매장을 멈추지 않되 지나간 것을 남기는, 강제 수령과 같은 규칙입니다.
 *
 * <p>"언제부터 없었나" 는 {@link #observe()} 가 몇 초마다 연결을 보고 적습니다.
 * 물어볼 때만 보면, MAIN 이 한 시간 붙어 있다가 끊긴 직후에 "한 시간 전부터
 * 없었다" 가 됩니다.
 */
public final class KdsAuthority {

    /** REPRINT: 라벨 다시 찍기 (§7). 라벨 프린터가 KDS-MAIN 옆에 있어서 MAIN 의 일입니다. */
    public enum Action { PICKUP, RECALL, FORCE_PRODUCED, REPRINT }

    public record Decision(boolean allowed, boolean emergency, String reason) {
        static Decision allow() {
            return new Decision(true, false, null);
        }

        static Decision emergencyPickup() {
            return new Decision(true, true, null);
        }

        static Decision deny(String reason) {
            return new Decision(false, false, reason);
        }
    }

    public record Mode(boolean emergency, Instant since) {
    }

    private final TerminalRegistry registry;
    private final Duration grace;
    private final Clock clock;
    private final Instant bootedAt;
    private final Map<String, Instant> mainLastSeen = new ConcurrentHashMap<>();

    public KdsAuthority(TerminalRegistry registry, Duration grace, Clock clock) {
        this.registry = registry;
        this.grace = grace;
        this.clock = clock;
        // 재기동 직후에는 MAIN 도 아직 다시 붙기 전입니다. 기동 시각부터 셉니다 —
        // 모르는 것을 "오래전부터 없었다" 로 읽으면 재기동마다 비상 모드가 열립니다.
        this.bootedAt = clock.instant();
    }

    /** 역할을 따지지 않는 권한 — 시험과, 등록기가 없는 기동용. */
    public static KdsAuthority permissive() {
        return new KdsAuthority(null, Duration.ofMinutes(3), Clock.systemUTC());
    }

    public Decision decide(Action action, Optional<DeviceIdentity> caller) {
        if (caller.isEmpty() || registry == null) {
            return Decision.allow();
        }
        DeviceIdentity device = caller.get();
        if (!"KDS".equals(device.type())) {
            return Decision.deny(device.type() + " 에서는 할 수 없습니다 — 주방(KDS-MAIN)의 일입니다");
        }
        if (device.isKdsMain()) {
            return Decision.allow();
        }
        if (action == Action.PICKUP && mode(device.storeId()).emergency()) {
            return Decision.emergencyPickup();
        }
        return Decision.deny(action == Action.PICKUP
                ? "수령은 KDS-MAIN 에서 합니다 (MAIN 이 3분 넘게 끊기면 여기서도 됩니다)"
                : "KDS-MAIN 만 할 수 있습니다");
    }

    /** 그 매장이 지금 비상 모드인가 ({@code GET /kds/mode}). */
    public Mode mode(String storeId) {
        Instant now = clock.instant();
        if (mainConnected(storeId)) {
            mainLastSeen.put(storeId, now);
            return new Mode(false, null);
        }
        Instant since = mainLastSeen.getOrDefault(storeId, bootedAt);
        boolean emergency = Duration.between(since, now).compareTo(grace) > 0;
        return new Mode(emergency, emergency ? since : null);
    }

    /** 붙어 있는 MAIN 을 보고 마지막으로 본 시각을 적습니다. 몇 초마다 부릅니다. */
    public void observe() {
        if (registry == null) {
            return;
        }
        Instant now = clock.instant();
        for (TerminalSession session : registry.connected()) {
            if (session.type() == TerminalType.KDS && mainConnected(session.storeId())) {
                mainLastSeen.put(session.storeId(), now);
            }
        }
    }

    private boolean mainConnected(String storeId) {
        if (registry == null || storeId == null) {
            return false;
        }
        for (Channel channel : registry.channelsOf(storeId, TerminalType.KDS)) {
            if (channel.isActive() && "MAIN".equals(channel.attr(TerminalHandshakeHandler.KDS_ROLE).get())) {
                return true;
            }
        }
        return false;
    }
}
