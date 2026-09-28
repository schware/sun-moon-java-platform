package com.sunmoon.platform.api;

import com.sunmoon.platform.api.KdsAuthority.Action;
import com.sunmoon.platform.domain.pairing.DeviceIdentity;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 주방의 명령은 KDS-MAIN 이 (store-app.md §4.2). */
class KdsAuthorityTest {

    private Instant now = Instant.parse("2026-09-28T03:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final TerminalRegistry registry = new TerminalRegistry();
    private final KdsAuthority kds = new KdsAuthority(registry, Duration.ofMinutes(3), clock);

    private static final Optional<DeviceIdentity> MAIN =
            Optional.of(new DeviceIdentity("store-01", "kds-main", "KDS", "MAIN"));
    private static final Optional<DeviceIdentity> SUB =
            Optional.of(new DeviceIdentity("store-01", "kds-01", "KDS", "SUB"));
    private static final Optional<DeviceIdentity> POS =
            Optional.of(new DeviceIdentity("store-01", "pos-01", "POS", null));

    private EmbeddedChannel connectMain() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(TerminalHandshakeHandler.DEVICE_ID).set("kds-main");
        channel.attr(TerminalHandshakeHandler.KDS_ROLE).set("MAIN");
        registry.register("kds-main", "store-01", TerminalType.KDS, channel);
        return channel;
    }

    @Test
    void mainMayDoEverything() {
        for (Action action : Action.values()) {
            assertTrue(kds.decide(action, MAIN).allowed(), action.name());
        }
    }

    @Test
    void subMayNotRecallOrForce() {
        connectMain();
        assertFalse(kds.decide(Action.RECALL, SUB).allowed());
        assertFalse(kds.decide(Action.FORCE_PRODUCED, SUB).allowed());
        assertFalse(kds.decide(Action.PICKUP, SUB).allowed(), "MAIN 이 붙어 있으면 SUB 는 수령 못 함");
    }

    @Test
    void aPosIsNotTheKitchen() {
        assertFalse(kds.decide(Action.PICKUP, POS).allowed());
    }

    @Test
    void unpairedCallersPassAsBefore() {
        assertTrue(kds.decide(Action.RECALL, Optional.empty()).allowed());
    }

    @Test
    void subPicksUpAsEmergencyOnlyAfterTheGrace() {
        EmbeddedChannel main = connectMain();
        kds.observe();
        main.close();
        registry.unregister("kds-main", "store-01", TerminalType.KDS, main);

        now = now.plus(Duration.ofMinutes(2));
        assertFalse(kds.decide(Action.PICKUP, SUB).allowed(), "3분 전에는 아직 막힘");
        assertFalse(kds.mode("store-01").emergency());

        now = now.plus(Duration.ofMinutes(2));
        KdsAuthority.Decision decision = kds.decide(Action.PICKUP, SUB);
        assertTrue(decision.allowed());
        assertTrue(decision.emergency(), "비상 수령으로 적힘");
        assertNotNull(kds.mode("store-01").since());
        assertFalse(kds.decide(Action.RECALL, SUB).allowed(), "비상이어도 다시 부르기는 MAIN 만");
    }

    @Test
    void theGraceCountsFromWhenMainWasLastSeenNotFromWhenItFirstCame() {
        EmbeddedChannel main = connectMain();
        kds.observe();
        now = now.plus(Duration.ofHours(1));
        kds.observe();   // 한 시간 내내 붙어 있었음
        main.close();
        registry.unregister("kds-main", "store-01", TerminalType.KDS, main);

        now = now.plus(Duration.ofMinutes(1));
        assertFalse(kds.mode("store-01").emergency(), "끊긴 지 1분 — 한 시간 전부터 없던 것이 아님");
    }

    @Test
    void mainComingBackEndsTheEmergency() {
        now = now.plus(Duration.ofMinutes(5));
        assertTrue(kds.mode("store-01").emergency(), "기동 뒤 3분이 지나도록 MAIN 이 없음");

        connectMain();
        assertFalse(kds.mode("store-01").emergency());
        assertEquals(false, kds.decide(Action.PICKUP, SUB).allowed());
    }

    @Test
    void rightAfterARestartThereIsNoEmergency() {
        now = now.plus(Duration.ofMinutes(1));
        assertFalse(kds.mode("store-01").emergency(), "재기동 직후 MAIN 이 다시 붙을 시간");
    }
}
