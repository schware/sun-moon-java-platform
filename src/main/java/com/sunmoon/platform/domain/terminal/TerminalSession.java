package com.sunmoon.platform.domain.terminal;

import java.time.Instant;

/**
 * One connected terminal. {@code channelId} is Netty's own id for the
 * connection, kept for display — identity is the channel itself, because
 * a short id is a truncation and can collide.
 */
public record TerminalSession(
        String deviceId, String storeId, TerminalType type, String channelId, Instant connectedAt) {

    public TerminalGroup group() {
        return new TerminalGroup(storeId, type);
    }
}
