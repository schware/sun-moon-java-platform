package com.sunmoon.platform.domain.terminal;

/**
 * What identifies one terminal: a device id **within a store**.
 *
 * <p>Device ids are only unique inside a shop — every branch has a
 * {@code pos-01}. Keying on the device id alone made two branches' first
 * counters the same terminal, so each connection displaced the other, each
 * displacement looked like a dropped socket, and the two screens knocked
 * each other offline in a loop.
 */
public record TerminalId(String storeId, String deviceId) {

    public TerminalId {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("storeId is required");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
    }

    @Override
    public String toString() {
        return storeId + "/" + deviceId;
    }
}
