package com.sunmoon.platform.domain.terminal;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which terminals are connected right now, and how to reach them.
 *
 * <p>Everything here is touched from many event-loop threads at once, so
 * the maps are concurrent and the channel groups are Netty's own — a
 * {@link ChannelGroup} drops closed channels by itself, which is what
 * keeps a crashed terminal from lingering as a phantom subscriber.
 *
 * <p>A device connecting twice replaces its earlier session and the older
 * channel is closed. Two connections claiming one device id is not a case
 * to support: it would mean an order accepted on one screen and invisible
 * on the other.
 */
public final class TerminalRegistry {

    private final Map<String, TerminalSession> sessionsByDevice = new ConcurrentHashMap<>();
    private final Map<String, Channel> channelsByDevice = new ConcurrentHashMap<>();
    private final Map<TerminalType, ChannelGroup> channelsByType = new ConcurrentHashMap<>();

    /**
     * When a terminal of each kind was last known to be there — updated on
     * connect and again on disconnect.
     *
     * <p>This exists so a dropped connection and an absent shop can be told
     * apart. A screen that reconnects after a network blip should not have
     * cost the store its orders in the meantime; a counter with nothing
     * plugged in should stop taking them.
     */
    private final Map<TerminalType, Instant> lastSeenByType = new ConcurrentHashMap<>();

    /**
     * @return the channel that was displaced, if this device was already
     *         connected — the caller closes it, so the registry never
     *         blocks an event-loop thread on I/O.
     */
    public Optional<Channel> register(String deviceId, TerminalType type, Channel channel) {
        TerminalSession session = new TerminalSession(deviceId, type, channel.id().asShortText(), Instant.now());
        sessionsByDevice.put(deviceId, session);
        groupFor(type).add(channel);
        lastSeenByType.put(type, Instant.now());
        Channel displaced = channelsByDevice.put(deviceId, channel);
        return Optional.ofNullable(displaced == channel ? null : displaced);
    }

    /**
     * Idempotent: a channel can close for several reasons and each one
     * calls this.
     *
     * <p>Identity is the {@link Channel} itself, not its id. A short id is
     * a truncation and can collide — and if it does, an old connection's
     * late close would evict the reconnect that replaced it, leaving a
     * live terminal that the registry believes is gone.
     */
    public void unregister(String deviceId, Channel channel) {
        Channel current = channelsByDevice.get(deviceId);
        if (current != null && current != channel) {
            // Already replaced by a reconnect; this is the old channel
            // catching up, and it has nothing left to remove.
            groupsRemove(channel);
            return;
        }

        TerminalSession leaving = sessionsByDevice.remove(deviceId);
        channelsByDevice.remove(deviceId, channel);
        // Explicitly, rather than waiting for the ChannelGroup to notice the
        // close: presence is read the moment an order arrives, and "still
        // listed because the socket has not finished closing" is exactly the
        // wrong answer at that moment.
        groupsRemove(channel);

        if (leaving != null) {
            // Stamped on the way out, so the grace period is measured from
            // when the terminal actually went rather than from whenever
            // someone next asks.
            lastSeenByType.put(leaving.type(), Instant.now());
        }
    }

    private void groupsRemove(Channel channel) {
        channelsByType.values().forEach(group -> group.remove(channel));
    }

    public List<TerminalSession> connected() {
        return sessionsByDevice.values().stream()
                .sorted((a, b) -> a.deviceId().compareTo(b.deviceId()))
                .toList();
    }

    public Optional<Channel> channelFor(String deviceId) {
        return Optional.ofNullable(channelsByDevice.get(deviceId));
    }

    /** Every connected terminal of one kind — how "tell all the KDS screens" is expressed. */
    public ChannelGroup channelsOf(TerminalType type) {
        return groupFor(type);
    }

    public int size() {
        return sessionsByDevice.size();
    }

    /**
     * Whether a terminal of this kind is here, or was here recently enough
     * to be worth waiting for.
     *
     * <p>"Recently enough" is the difference between a shop that is closed
     * and a screen whose wifi dropped. Within the grace period an order is
     * left PLACED rather than refused: the terminal fetches the list when
     * it reconnects, so a blip costs a delay instead of a customer.
     *
     * <p>A kind never seen at all has no history to wait on, and is absent
     * immediately.
     */
    public boolean isPresentOrRecentlySeen(TerminalType type, Duration grace) {
        if (!groupFor(type).isEmpty()) {
            return true;
        }
        Instant lastSeen = lastSeenByType.get(type);
        return lastSeen != null && lastSeen.isAfter(Instant.now().minus(grace));
    }

    /** When a terminal of this kind was last connected, if one ever was. */
    public Optional<Instant> lastSeen(TerminalType type) {
        return Optional.ofNullable(lastSeenByType.get(type));
    }

    private ChannelGroup groupFor(TerminalType type) {
        return channelsByType.computeIfAbsent(type,
                t -> new DefaultChannelGroup(t.name(), GlobalEventExecutor.INSTANCE));
    }
}
