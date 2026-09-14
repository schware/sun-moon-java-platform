package com.sunmoon.platform.domain.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /terminals} is how BO's 단말 현황 screen learns who is
 * connected, so a session that cannot be serialized is not a cosmetic
 * problem — it is that screen going blank.
 *
 * <p>The specific worry is {@link TerminalSession#group()}: a public
 * no-arg method that throws for a multi-store DID, because that kind of
 * terminal has no single store to make a group from. If Jackson ever
 * treated it as a property, one food-court board connecting would 500 the
 * endpoint for every other terminal too.
 */
class TerminalSessionJsonTest {

    /** The same mapper {@code JsonResponses} uses, configured the same way. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Instant WHEN = Instant.parse("2026-09-14T02:00:00Z");

    @Test
    void serializesAnOrdinaryTerminal() throws Exception {
        String json = JSON.writeValueAsString(new TerminalSession(
                "pos-01", "store-01", List.of("store-01"), TerminalType.POS, "abc123", WHEN));

        assertTrue(json.contains("\"deviceId\":\"pos-01\""), json);
        assertTrue(json.contains("\"storeId\":\"store-01\""), json);
        assertTrue(json.contains("\"type\":\"POS\""), json);
        assertTrue(json.contains("\"connectedAt\":\"2026-09-14T02:00:00Z\""), json);
    }

    @Test
    void serializesAMultiStoreDidWhoseGroupWouldThrow() {
        TerminalSession foodCourt = new TerminalSession(
                "did-hall", null, List.of("store-01", "store-02"), TerminalType.DID, "def456", WHEN);

        String json = assertDoesNotThrow(() -> JSON.writeValueAsString(foodCourt));

        assertTrue(json.contains("\"storeIds\":[\"store-01\",\"store-02\"]"), json);
        assertTrue(json.contains("\"storeId\":null"), json);
    }
}
