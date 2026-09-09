package com.sunmoon.platform.domain.terminal;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry decides whether a shop counts as staffed, and that decision
 * is what stands between "a screen's wifi dropped" and "an order was
 * refused". Worth testing directly rather than only through a socket.
 */
class TerminalRegistryTest {

    private static final Duration GRACE = Duration.ofMinutes(3);
    private static final String STORE = "store-01";
    private static final String OTHER_STORE = "store-02";

    @Test
    void aConnectedTerminalIsPresent() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.register("pos-01", STORE, TerminalType.POS, new EmbeddedChannel());

        assertTrue(registry.isPresentOrRecentlySeen(STORE, TerminalType.POS, GRACE));
        assertEquals(1, registry.size());
    }

    /** Nothing of this kind has ever connected, so there is no history to wait on. */
    @Test
    void aKindNeverSeenIsAbsentImmediately() {
        TerminalRegistry registry = new TerminalRegistry();

        assertFalse(registry.isPresentOrRecentlySeen(STORE, TerminalType.KDS, GRACE));
        assertTrue(registry.lastSeen(STORE, TerminalType.KDS).isEmpty());
    }

    /** The case the grace period exists for: a screen that just dropped is still counted. */
    @Test
    void aTerminalThatJustDisconnectedIsStillCountedWithinTheGracePeriod() {
        TerminalRegistry registry = new TerminalRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        registry.register("pos-01", STORE, TerminalType.POS, channel);
        registry.unregister("pos-01", channel);

        assertEquals(0, registry.size());
        assertTrue(registry.isPresentOrRecentlySeen(STORE, TerminalType.POS, GRACE),
                "a terminal seen a moment ago should still count as present");
    }

    /** And the case it must not swallow: gone long enough is gone. */
    @Test
    void aTerminalGoneLongerThanTheGracePeriodIsAbsent() {
        TerminalRegistry registry = new TerminalRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        registry.register("pos-01", STORE, TerminalType.POS, channel);
        registry.unregister("pos-01", channel);

        assertFalse(registry.isPresentOrRecentlySeen(STORE, TerminalType.POS, Duration.ZERO),
                "with no grace at all, a disconnected terminal is absent");
    }

    /** Presence is per kind: a kitchen screen does not make a counter staffed. */
    @Test
    void oneKindOfTerminalDoesNotVouchForAnother() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.register("kds-01", STORE, TerminalType.KDS, new EmbeddedChannel());

        assertTrue(registry.isPresentOrRecentlySeen(STORE, TerminalType.KDS, GRACE));
        assertFalse(registry.isPresentOrRecentlySeen(STORE, TerminalType.POS, GRACE));
    }

    /**
     * The bug stores exist to prevent: one branch's screen must not make
     * another branch look staffed.
     */
    @Test
    void oneStoresTerminalDoesNotVouchForAnother() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.register("pos-01", STORE, TerminalType.POS, new EmbeddedChannel());

        assertTrue(registry.isPresentOrRecentlySeen(STORE, TerminalType.POS, GRACE));
        assertFalse(registry.isPresentOrRecentlySeen(OTHER_STORE, TerminalType.POS, GRACE),
                "a POS at one store must not make another store look staffed");
    }

    /**
     * A device reconnecting hands back the channel it displaced, so the
     * caller can close it — two connections claiming one device id would
     * mean an order accepted on one screen and invisible on the other.
     */
    @Test
    void reconnectingDisplacesTheOlderConnection() {
        TerminalRegistry registry = new TerminalRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();

        assertTrue(registry.register("pos-01", STORE, TerminalType.POS, first).isEmpty());
        assertEquals(first, registry.register("pos-01", STORE, TerminalType.POS, second).orElseThrow());
        assertEquals(1, registry.size());
    }

    /**
     * The old channel's close event can arrive after the reconnect. It must
     * not take the new session with it.
     */
    @Test
    void aLateCloseFromADisplacedChannelDoesNotUnregisterTheNewOne() {
        TerminalRegistry registry = new TerminalRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        registry.register("pos-01", STORE, TerminalType.POS, first);
        registry.register("pos-01", STORE, TerminalType.POS, second);

        registry.unregister("pos-01", first);

        assertEquals(1, registry.size());
        assertEquals(second, registry.channelFor("pos-01").orElseThrow());
    }
}
