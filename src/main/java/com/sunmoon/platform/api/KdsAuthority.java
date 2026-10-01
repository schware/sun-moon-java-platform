package com.sunmoon.platform.api;

import com.sunmoon.platform.domain.terminal.DeviceDirectory;
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
 * <p>누가 MAIN 인지는 <b>BO 장비의 "KDS 역할"</b>이 정합니다(2026-10-01 — 페어링을 걷어내고
 * 역할의 근거를 BO 로). 요청의 {@code deviceId} 를 그 매장의 BO 명단에서 찾습니다.
 *
 * <table>
 *   <tr><td>수령</td><td>MAIN · (비상 시) SUB</td></tr>
 *   <tr><td>다시 부르기</td><td>MAIN</td></tr>
 *   <tr><td>강제 전부 완료</td><td>MAIN</td></tr>
 *   <tr><td>라벨 다시</td><td>MAIN</td></tr>
 * </table>
 *
 * <h2>모르는 것은 막지 않습니다</h2>
 *
 * <p>BO 명단을 못 읽었거나(BO 를 안 보는 기동 포함), 요청에 {@code deviceId} 가 없거나,
 * 명단에 없는 KDS 면 통과시킵니다. 모르는 채로 막으면 매장이 조용히 멈춥니다.
 *
 * <h2>비상 모드</h2>
 *
 * <p>BO 에 MAIN 이 정해진 매장에서, 그 MAIN 이 {@code TERMINAL_GRACE}(3분) 넘게 안 붙어 있으면
 * SUB 의 수령을 통과시키고 그 수령에 {@code emergency=true} 를 적어 보냅니다. MAIN 이 돌아오면
 * 다시 막힙니다. BO 에 MAIN 이 없는 매장은 비상 모드가 없습니다 — 없으면 모든 SUB 에 수령 칸이 뜹니다.
 *
 * <p>"언제부터 없었나" 는 {@link #observe()} 가 몇 초마다 연결을 보고 적습니다.
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
    private final DeviceDirectory directory;
    private final Duration grace;
    private final Clock clock;
    private final Instant bootedAt;
    private final Map<String, Instant> mainLastSeen = new ConcurrentHashMap<>();

    public KdsAuthority(TerminalRegistry registry, DeviceDirectory directory, Duration grace, Clock clock) {
        this.registry = registry;
        this.directory = directory;
        this.grace = grace;
        this.clock = clock;
        // 재기동 직후에는 MAIN 도 아직 다시 붙기 전입니다. 기동 시각부터 셉니다 —
        // 모르는 것을 "오래전부터 없었다" 로 읽으면 재기동마다 비상 모드가 열립니다.
        this.bootedAt = clock.instant();
    }

    /** 역할을 따지지 않는 권한 — 시험과, 등록기가 없는 기동용. */
    public static KdsAuthority permissive() {
        return new KdsAuthority(null, null, Duration.ofMinutes(3), Clock.systemUTC());
    }

    public Decision decide(Action action, String storeId, String deviceId) {
        if (registry == null || directory == null || storeId == null || deviceId == null) {
            return Decision.allow();
        }
        Optional<String> role = directory.kdsRole(storeId, deviceId);
        if (role.isEmpty() || "MAIN".equals(role.get())) {
            return Decision.allow();
        }
        if (action == Action.PICKUP && mode(storeId).emergency()) {
            return Decision.emergencyPickup();
        }
        return Decision.deny(action == Action.PICKUP
                ? "수령은 KDS-MAIN 에서 합니다 (MAIN 이 3분 넘게 끊기면 여기서도 됩니다)"
                : "KDS-MAIN 만 할 수 있습니다 (BO 장비의 KDS 역할)");
    }

    /** 그 매장이 지금 비상 모드인가 ({@code GET /kds/mode}). */
    public Mode mode(String storeId) {
        if (directory == null || storeId == null || !directory.hasKdsMain(storeId)) {
            return new Mode(false, null);
        }
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
