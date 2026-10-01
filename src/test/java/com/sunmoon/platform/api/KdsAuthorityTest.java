package com.sunmoon.platform.api;

import com.sunmoon.platform.api.KdsAuthority.Action;
import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import com.sunmoon.platform.transport.ws.TerminalHandshakeHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 주방의 명령은 KDS-MAIN 이 — 누가 MAIN 인지는 BO 장비의 KDS 역할 (store-app.md §4.2). */
class KdsAuthorityTest {

    private Instant now = Instant.parse("2026-10-01T03:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    /** BO: store-01 의 kds-01 은 MAIN, kds-02 는 SUB. store-02 에는 MAIN 이 없음. */
    private final DeviceDirectory bo = new DeviceDirectory() {
        private final Map<String, String> roles = Map.of(
                "store-01|kds-01", "MAIN", "store-01|kds-02", "SUB", "store-02|kds-02", "SUB");

        @Override public boolean isRegistered(String storeId, String deviceId, String type) { return true; }
        @Override public Optional<String> kdsRole(String storeId, String deviceId) {
            return Optional.ofNullable(roles.get(storeId + "|" + deviceId));
        }
        @Override public boolean hasKdsMain(String storeId) { return "store-01".equals(storeId); }
    };

    private final TerminalRegistry registry = new TerminalRegistry();
    private final KdsAuthority kds = new KdsAuthority(registry, bo, Duration.ofMinutes(3), clock);

    private EmbeddedChannel connectMain() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(TerminalHandshakeHandler.DEVICE_ID).set("kds-01");
        channel.attr(TerminalHandshakeHandler.KDS_ROLE).set("MAIN");
        registry.register("kds-01", "store-01", TerminalType.KDS, channel);
        return channel;
    }

    @Test
    void mainMayDoEverything() {
        for (Action action : Action.values()) {
            assertTrue(kds.decide(action, "store-01", "kds-01").allowed(), action.name());
        }
    }

    @Test
    void subMayNotRecallForceReprintOrPickUpWhileMainIsHere() {
        connectMain();
        assertFalse(kds.decide(Action.RECALL, "store-01", "kds-02").allowed());
        assertFalse(kds.decide(Action.FORCE_PRODUCED, "store-01", "kds-02").allowed());
        assertFalse(kds.decide(Action.REPRINT, "store-01", "kds-02").allowed());
        assertFalse(kds.decide(Action.PICKUP, "store-01", "kds-02").allowed());
    }

    @Test
    void whatIsNotKnownIsNotBlocked() {
        assertTrue(kds.decide(Action.RECALL, "store-01", "kds-99").allowed(), "BO 명단에 없는 KDS");
        assertTrue(kds.decide(Action.RECALL, "store-01", null).allowed(), "deviceId 없음");
        assertTrue(kds.decide(Action.RECALL, null, "kds-02").allowed(), "주문의 매장을 못 읽음");
    }

    @Test
    void subPicksUpAsEmergencyOnlyAfterTheGrace() {
        EmbeddedChannel main = connectMain();
        kds.observe();
        main.close();
        registry.unregister("kds-01", "store-01", TerminalType.KDS, main);

        now = now.plus(Duration.ofMinutes(2));
        assertFalse(kds.decide(Action.PICKUP, "store-01", "kds-02").allowed(), "3분 전에는 아직");

        now = now.plus(Duration.ofMinutes(2));
        KdsAuthority.Decision decision = kds.decide(Action.PICKUP, "store-01", "kds-02");
        assertTrue(decision.allowed());
        assertTrue(decision.emergency(), "비상 수령으로 남습니다");
        assertFalse(kds.decide(Action.RECALL, "store-01", "kds-02").allowed(), "비상이어도 다시 부르기는 MAIN 만");

        connectMain();
        assertFalse(kds.decide(Action.PICKUP, "store-01", "kds-02").allowed(), "MAIN 이 돌아오면 다시 막힘");
    }

    @Test
    void aStoreWithoutAMainInBoHasNoEmergency() {
        now = now.plus(Duration.ofMinutes(10));
        assertFalse(kds.mode("store-02").emergency());
        assertFalse(kds.decide(Action.PICKUP, "store-02", "kds-02").allowed());
    }
}
