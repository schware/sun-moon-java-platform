package com.sunmoon.platform.domain.terminal;

import java.time.Instant;

/**
 * One connected terminal. {@code channelId} is Netty's own id for the
 * connection, kept so a session can be told apart from a reconnect of the
 * same device.
 */
public record TerminalSession(String deviceId, TerminalType type, String channelId, Instant connectedAt) {
}
