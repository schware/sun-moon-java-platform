package com.sunmoon.platform.domain.kiosk;

import com.sunmoon.platform.infrastructure.kiosk.JdbcKioskPinStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** KIOSK 관리자 PIN (store-app.md §5.3). */
class KioskPinsTest {

    private Instant now = Instant.parse("2026-09-29T01:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final KioskPins.Store store = JdbcKioskPinStore.inMemory();
    private final KioskPins pins = new KioskPins(store, clock);

    @Test
    void theRightPinOpensAndTheHashIsNotThePin() {
        pins.set("s1", "kiosk-01", "4821", "admin");
        assertEquals(KioskPins.Result.OK, pins.check("s1", "kiosk-01", "4821"));
        assertEquals(KioskPins.Result.WRONG, pins.check("s1", "kiosk-01", "4822"));
        assertNotEquals("4821", store.find("s1", "kiosk-01").orElseThrow().hash());
    }

    @Test
    void anotherKioskHasItsOwnPin() {
        pins.set("s1", "kiosk-01", "4821", "admin");
        assertEquals(KioskPins.Result.NOT_SET, pins.check("s1", "kiosk-02", "4821"));
    }

    @Test
    void fiveWrongTriesLockForTenMinutesEvenTheRightPin() {
        pins.set("s1", "kiosk-01", "4821", "admin");
        for (int i = 0; i < KioskPins.MAX_FAILURES; i++) {
            assertEquals(KioskPins.Result.WRONG, pins.check("s1", "kiosk-01", "0000"));
        }
        assertEquals(KioskPins.Result.LOCKED, pins.check("s1", "kiosk-01", "4821"));
        now = now.plus(Duration.ofMinutes(11));
        assertEquals(KioskPins.Result.OK, pins.check("s1", "kiosk-01", "4821"));
    }

    @Test
    void onlyFourToEightDigits() {
        assertThrows(IllegalArgumentException.class, () -> pins.set("s1", "k", "123", "a"));
        assertThrows(IllegalArgumentException.class, () -> pins.set("s1", "k", "123456789", "a"));
        assertThrows(IllegalArgumentException.class, () -> pins.set("s1", "k", "12a4", "a"));
    }
}
