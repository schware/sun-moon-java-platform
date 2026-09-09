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
 * <p>Terminals are grouped by {@link TerminalGroup} — a kind of screen in
 * one store — never by kind alone. With kind alone, "is a POS connected"
 * would be answered by any branch that happened to have one, and an order
 * would be pushed to a counter in a different shop.
 *
 * <p>Everything here is touched from many event-loop threads at once, so
 * the maps are concurrent and the channel groups are Netty's own.
 *
 * <p>Terminals are identified by {@link TerminalId} — a device id
 * <em>within a store</em>, because every branch has a {@code pos-01}. One
 * terminal connecting twice replaces its earlier session and the older
 * channel is closed; two connections claiming one terminal would mean an
 * order accepted on one screen and invisible on the other.
 */
public final class TerminalRegistry {

    private final Map<TerminalId, TerminalSession> sessionsByTerminal = new ConcurrentHashMap<>();
    private final Map<TerminalId, Channel> channelsByTerminal = new ConcurrentHashMap<>();
    private final Map<TerminalGroup, ChannelGroup> channelsByGroup = new ConcurrentHashMap<>();

    /**
     * When each group was last known to be there — updated on connect and
     * again on disconnect.
     *
     * <p>This exists so a dropped connection and an unstaffed counter can
     * be told apart. A screen that reconnects after a network blip should
     * not have cost its store the orders in between; a counter with
     * nothing plugged in should stop taking them. Keyed per store, so one
     * branch's screen never vouches for another's.
     */
    private final Map<TerminalGroup, Instant> lastSeenByGroup = new ConcurrentHashMap<>();

    /**
     * @return the channel that was displaced, if this device was already
     *         connected — the caller closes it, so the registry never
     *         blocks an event-loop thread on I/O.
     */
    public Optional<Channel> register(String deviceId, String storeId, TerminalType type, Channel channel) {
        TerminalId id = new TerminalId(storeId, deviceId);
        TerminalGroup group = new TerminalGroup(storeId, type);

        sessionsByTerminal.put(id,
                new TerminalSession(deviceId, storeId, type, channel.id().asShortText(), Instant.now()));
        groupFor(group).add(channel);
        lastSeenByGroup.put(group, Instant.now());

        Channel displaced = channelsByTerminal.put(id, channel);
        return Optional.ofNullable(displaced == channel ? null : displaced);
    }

    /**
     * Idempotent: a channel can close for several reasons and each one
     * calls this.
     *
     * <p>Identity is the {@link Channel} itself, not its id. A short id is
     * a truncation and can collide — and if it does, an old connection's
     * late close would evict the reconnect that replaced it, leaving a
     * live terminal the registry believes is gone.
     */
    public void unregister(String deviceId, String storeId, Channel channel) {
        TerminalId id = new TerminalId(storeId, deviceId);
        Channel current = channelsByTerminal.get(id);
        if (current != null && current != channel) {
            // Already replaced by a reconnect; this is the old channel
            // catching up, and it has nothing left to remove.
            groupsRemove(channel);
            return;
        }

        TerminalSession leaving = sessionsByTerminal.remove(id);
        channelsByTerminal.remove(id, channel);
        // Explicitly, rather than waiting for the ChannelGroup to notice
        // the close: presence is read the moment an order arrives, and
        // "still listed because the socket has not finished closing" is
        // exactly the wrong answer at that moment.
        groupsRemove(channel);

        if (leaving != null) {
            // Stamped on the way out, so the grace period is measured from
            // when the terminal actually went rather than from whenever
            // someone next asks.
            lastSeenByGroup.put(leaving.group(), Instant.now());
        }
    }

    public List<TerminalSession> connected() {
        return sessionsByTerminal.values().stream()
                .sorted((a, b) -> a.deviceId().compareTo(b.deviceId()))
                .toList();
    }

    /** A device id alone does not name a terminal — every store has a {@code pos-01}. */
    public Optional<Channel> channelFor(String storeId, String deviceId) {
        return Optional.ofNullable(channelsByTerminal.get(new TerminalId(storeId, deviceId)));
    }

    /** Every connected terminal of one kind in one store — how "tell that shop's kitchen" is expressed. */
    public ChannelGroup channelsOf(String storeId, TerminalType type) {
        return groupFor(new TerminalGroup(storeId, type));
    }

    public int size() {
        return sessionsByTerminal.size();
    }

    /**
     * Whether that store has a terminal of this kind here, or had one
     * recently enough to be worth waiting for.
     *
     * <p>"Recently enough" is the difference between a shop that is closed
     * and a screen whose wifi dropped. Within the grace period an order is
     * left PLACED rather than refused: the terminal fetches the list when
     * it reconnects, so a blip costs a delay instead of a customer.
     *
     * <p>A store that has never connected this kind has no history to wait
     * on, and is absent immediately.
     */
    public boolean isPresentOrRecentlySeen(String storeId, TerminalType type, Duration grace) {
        TerminalGroup group = new TerminalGroup(storeId, type);
        if (!groupFor(group).isEmpty()) {
            return true;
        }
        Instant lastSeen = lastSeenByGroup.get(group);
        return lastSeen != null && lastSeen.isAfter(Instant.now().minus(grace));
    }

    /** When that store last had a terminal of this kind connected, if it ever did. */
    public Optional<Instant> lastSeen(String storeId, TerminalType type) {
        return Optional.ofNullable(lastSeenByGroup.get(new TerminalGroup(storeId, type)));
    }

    private void groupsRemove(Channel channel) {
        channelsByGroup.values().forEach(group -> group.remove(channel));
    }

    private ChannelGroup groupFor(TerminalGroup group) {
        return channelsByGroup.computeIfAbsent(group,
                g -> new DefaultChannelGroup(g.label(), GlobalEventExecutor.INSTANCE));
    }
}
