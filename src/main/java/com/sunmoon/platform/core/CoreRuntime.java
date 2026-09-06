package com.sunmoon.platform.core;

import com.sunmoon.platform.transport.http.HttpServerInitializer;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.socket.SocketServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The Core Runtime: one process, one pair of Netty event-loop groups,
 * multiple listening ports — REST + WebSocket share the HTTP port
 * ({@link HttpServerInitializer}), raw Socket gets its own
 * ({@link SocketServerInitializer}). Batch (Quartz-triggered) runs on the
 * same JVM but off the event loop entirely — see {@code batch} package.
 */
public final class CoreRuntime {

    private static final Logger log = LoggerFactory.getLogger(CoreRuntime.class);

    private final RuntimeConfig config;
    private final Map<String, RestEndpoint> httpRoutes;

    public CoreRuntime(RuntimeConfig config, Map<String, RestEndpoint> httpRoutes) {
        this.config = config;
        this.httpRoutes = httpRoutes;
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            Channel httpChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HttpServerInitializer(httpRoutes))
                    .bind(config.httpPort())
                    .sync()
                    .channel();
            log.info("Core Runtime: REST + WebSocket listening on port {}", config.httpPort());

            Channel socketChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new SocketServerInitializer())
                    .bind(config.socketPort())
                    .sync()
                    .channel();
            log.info("Core Runtime: Socket listening on port {}", config.socketPort());

            httpChannel.closeFuture().sync();
            socketChannel.closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
