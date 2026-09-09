package com.sunmoon.platform.core;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

/**
 * One listening port inside the runtime: a name for the log, the port, and
 * the pipeline to install on every accepted connection.
 *
 * <p>Deliberately says nothing about HTTP. The kernel binds ports; what
 * speaks on them is the composing application's business — which is what
 * lets BO (HTTP only) and the runtime (HTTP + raw Socket) share this
 * without either knowing about the other (docs/adr/0014).
 */
public record ListenerSpec(String name, int port, ChannelInitializer<SocketChannel> initializer) {
}
