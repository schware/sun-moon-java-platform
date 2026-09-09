package com.sunmoon.platform.domain.terminal;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

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
     * @return the channel that was displaced, if this device was already
     *         connected — the caller closes it, so the registry never
     *         blocks an event-loop thread on I/O.
     */
    public Optional<Channel> register(String deviceId, TerminalType type, Channel channel) {
        TerminalSession session = new TerminalSession(deviceId, type, channel.id().asShortText(), Instant.now());
        sessionsByDevice.put(deviceId, session);
        groupFor(type).add(channel);
        Channel displaced = channelsByDevice.put(deviceId, channel);
        return Optional.ofNullable(displaced == channel ? null : displaced);
    }

    /** Idempotent: a channel can close for several reasons and each one calls this. */
    public void unregister(String deviceId, Channel channel) {
        channelsByDevice.remove(deviceId, channel);
        // Only forget the session if this channel is still the current one;
        // a reconnect that already replaced it must not be undone by the
        // old channel's close event arriving late.
        sessionsByDevice.computeIfPresent(deviceId,
                (id, session) -> session.channelId().equals(channel.id().asShortText()) ? null : session);
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

    private ChannelGroup groupFor(TerminalType type) {
        return channelsByType.computeIfAbsent(type,
                t -> new DefaultChannelGroup(t.name(), GlobalEventExecutor.INSTANCE));
    }
}
