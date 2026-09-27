package com.sunmoon.platform.domain.pairing;

import com.sunmoon.platform.domain.pairing.PairingService.PairingRefused;
import com.sunmoon.platform.domain.pairing.PairingService.Refusal;
import com.sunmoon.platform.infrastructure.pairing.InMemoryPairingStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingServiceTest {

    private Instant now = Instant.parse("2026-09-28T01:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final PairingService pairing = new PairingService(new InMemoryPairingStore(), clock);

    private static final DeviceIdentity KDS_MAIN = new DeviceIdentity("store-01", "kds-01", "kds", "main");

    @Test
    void a_code_becomes_a_token_that_carries_what_bo_decided() {
        String code = pairing.issue(KDS_MAIN, "admin").code();
        assertTrue(code.matches("[0-9]{6}"));

        PairingService.Paired paired = pairing.pair(code);
        assertEquals("store-01", paired.device().storeId());
        assertEquals("KDS", paired.device().type());
        assertEquals("MAIN", paired.device().kdsRole());
        assertEquals(KDS_MAIN, pairing.resolve(paired.token()).orElseThrow());
    }

    @Test
    void a_code_works_once() {
        String code = pairing.issue(KDS_MAIN, "admin").code();
        pairing.pair(code);
        PairingRefused second = assertThrows(PairingRefused.class, () -> pairing.pair(code));
        assertEquals(Refusal.UNKNOWN_CODE, second.refusal());
    }

    @Test
    void a_code_dies_after_ten_minutes() {
        String code = pairing.issue(KDS_MAIN, "admin").code();
        now = now.plus(Duration.ofMinutes(10)).plusSeconds(1);
        assertThrows(PairingRefused.class, () -> pairing.pair(code));
    }

    @Test
    void issuing_again_retires_the_earlier_code() {
        String first = pairing.issue(KDS_MAIN, "admin").code();
        String second = pairing.issue(KDS_MAIN, "admin").code();
        if (!first.equals(second)) {
            assertThrows(PairingRefused.class, () -> pairing.pair(first));
        }
        pairing.pair(second);
    }

    @Test
    void pairing_again_ends_the_old_token() {
        String oldToken = pairing.pair(pairing.issue(KDS_MAIN, "admin").code()).token();
        String newToken = pairing.pair(pairing.issue(KDS_MAIN, "admin").code()).token();
        assertNotEquals(oldToken, newToken);
        assertTrue(pairing.resolve(oldToken).isEmpty());
        assertTrue(pairing.resolve(newToken).isPresent());
    }

    @Test
    void revoke_ends_the_token_at_once_despite_the_cache() {
        String token = pairing.pair(pairing.issue(KDS_MAIN, "admin").code()).token();
        assertTrue(pairing.resolve(token).isPresent());   // 캐시에 들어감
        assertTrue(pairing.revoke("store-01", "kds-01", "KDS"));
        assertTrue(pairing.resolve(token).isEmpty());
        assertFalse(pairing.revoke("store-01", "kds-01", "KDS"));
    }

    @Test
    void too_many_wrong_codes_close_pairing_for_a_minute() {
        String code = pairing.issue(KDS_MAIN, "admin").code();
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < PairingService.MAX_FAILURES_PER_MINUTE; i++) {
            assertThrows(PairingRefused.class, () -> pairing.pair(wrong));
        }
        PairingRefused blocked = assertThrows(PairingRefused.class, () -> pairing.pair(code));
        assertEquals(Refusal.THROTTLED, blocked.refusal(), "맞는 코드도 그 1분 동안은 막힘");

        now = now.plusSeconds(61);
        pairing.pair(code);
    }

    @Test
    void only_kds_has_a_role_and_blank_means_sub() {
        assertEquals(null, new DeviceIdentity("s", "p", "POS", "MAIN").kdsRole());
        assertEquals("SUB", new DeviceIdentity("s", "k", "KDS", null).kdsRole());
        assertEquals("SUB", new DeviceIdentity("s", "k", "KDS", "whatever").kdsRole());
    }

    @Test
    void unknown_types_are_not_issued() {
        assertThrows(IllegalArgumentException.class,
                () -> pairing.issue(new DeviceIdentity("s", "x", "PRINTER", null), "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> pairing.issue(new DeviceIdentity("s", " ", "POS", null), "admin"));
    }

    @Test
    void the_device_list_shows_paired_and_pending() {
        pairing.pair(pairing.issue(KDS_MAIN, "admin").code());
        pairing.issue(new DeviceIdentity("store-01", "pos-01", "POS", null), "admin");
        pairing.issue(new DeviceIdentity("store-02", "pos-01", "POS", null), "admin");

        var rows = pairing.devices("store-01");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.pairedAt() != null && r.device().deviceId().equals("kds-01")));
        assertTrue(rows.stream().anyMatch(r -> r.pendingUntil() != null && r.device().deviceId().equals("pos-01")));
    }
}
