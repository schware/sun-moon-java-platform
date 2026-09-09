package com.sunmoon.platform.core;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The Core Runtime: one process, one pair of Netty event-loop groups, and
 * every {@link ListenerSpec} it is handed bound onto them.
 *
 * <p>It knows nothing about what those listeners speak — HTTP, raw TCP, or
 * anything else — so the same kernel serves BO and the device-facing
 * runtime (docs/adr/0014). The worker pool that endpoints run on
 * (docs/adr/0010) is owned by the composition root and passed into the
 * pipelines, not created here.
 */
public final class CoreRuntime {

    private static final Logger log = LoggerFactory.getLogger(CoreRuntime.class);

    private final List<ListenerSpec> listeners;

    public CoreRuntime(List<ListenerSpec> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            List<Channel> channels = new ArrayList<>();
            for (ListenerSpec listener : listeners) {
                channels.add(new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(listener.initializer())
                        .bind(listener.port())
                        .sync()
                        .channel());
                log.info("Core Runtime: {} listening on port {}", listener.name(), listener.port());
            }
            for (Channel channel : channels) {
                channel.closeFuture().sync();
            }
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
