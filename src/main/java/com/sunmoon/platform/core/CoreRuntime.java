package com.sunmoon.platform.core;

import com.sunmoon.platform.transport.http.HttpListenerSpec;
import com.sunmoon.platform.transport.http.HttpServerInitializer;
import com.sunmoon.platform.transport.socket.SocketServerInitializer;
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
 * The Core Runtime: one process, one pair of Netty event-loop groups,
 * several listening ports — one per {@link HttpListenerSpec} (BO and the
 * Order API each get their own, see docs/adr/0009), plus the raw Socket
 * port ({@link SocketServerInitializer}). Batch (Quartz-triggered) runs on
 * the same JVM but off the event loop entirely — see {@code batch} package.
 */
public final class CoreRuntime {

    private static final Logger log = LoggerFactory.getLogger(CoreRuntime.class);

    private final List<HttpListenerSpec> httpListeners;
    private final int socketPort;

    public CoreRuntime(List<HttpListenerSpec> httpListeners, int socketPort) {
        this.httpListeners = List.copyOf(httpListeners);
        this.socketPort = socketPort;
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            List<Channel> channels = new ArrayList<>();

            for (HttpListenerSpec listener : httpListeners) {
                channels.add(new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new HttpServerInitializer(listener.routes(), listener.webSocketEnabled()))
                        .bind(listener.port())
                        .sync()
                        .channel());
                log.info("Core Runtime: {} listening on port {}{}",
                        listener.name(), listener.port(), listener.webSocketEnabled() ? " (REST + WebSocket)" : " (REST)");
            }

            channels.add(new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new SocketServerInitializer())
                    .bind(socketPort)
                    .sync()
                    .channel());
            log.info("Core Runtime: Socket listening on port {}", socketPort);

            for (Channel channel : channels) {
                channel.closeFuture().sync();
            }
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
