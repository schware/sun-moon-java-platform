package com.sunmoon.platform.domain.terminal;

/**
 * What identifies one terminal: a device id **within a store, within a
 * kind of screen**.
 *
 * <p>Device ids are only unique inside a shop — every branch has a
 * {@code pos-01}. Keying on the device id alone made two branches' first
 * counters the same terminal, so each connection displaced the other, each
 * displacement looked like a dropped socket, and the two screens knocked
 * each other offline in a loop. {@code storeId} fixed that.
 *
 * <p>{@code type} exists for the same reason, one level down: a store's
 * POS and its KDS are different screens, but nothing stopped both from
 * being opened as device id {@code "01"} — found 2026-09-13, when doing
 * exactly that displaced one with the other, each looking like a dropped
 * connection to the other's screen. A device id is only unique within one
 * kind of terminal at one store, not across kinds.
 */
public record TerminalId(String storeId, String deviceId, TerminalType type) {

    public TerminalId {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("storeId is required");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
    }

    @Override
    public String toString() {
        return storeId + "/" + type + "/" + deviceId;
    }
}
