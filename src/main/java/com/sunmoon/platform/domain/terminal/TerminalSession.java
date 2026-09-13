package com.sunmoon.platform.domain.terminal;

import java.time.Instant;
import java.util.List;

/**
 * One connected terminal. {@code channelId} is Netty's own id for the
 * connection, kept for display — identity is the channel itself, because
 * a short id is a truncation and can collide.
 *
 * <p>{@code storeId} is the single store for POS/KDS/most DID terminals,
 * and null for a DID watching several — {@code storeIds} always carries
 * the full list (a singleton for everything but a multi-store DID), so
 * {@code /terminals} can show what a screen is actually watching either
 * way.
 */
public record TerminalSession(
        String deviceId, String storeId, List<String> storeIds, TerminalType type, String channelId,
        Instant connectedAt) {

    /** Not meaningful for a multi-store DID, which belongs to several groups at once. */
    public TerminalGroup group() {
        return new TerminalGroup(storeId, type);
    }
}
