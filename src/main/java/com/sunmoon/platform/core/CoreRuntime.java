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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Core Runtime: one process, one pair of Netty event-loop groups,
 * several listening ports — one per {@link HttpListenerSpec} (BO and the
 * Order API each get their own, see docs/adr/0009), plus the raw Socket
 * port ({@link SocketServerInitializer}).
 *
 * <p>It also owns the bounded worker pool that REST endpoints run on, so
 * blocking work (JDBC, above all) never occupies an event-loop thread —
 * see docs/adr/0010. Batch runs off the event loop too, on Quartz's own
 * pool (see {@code batch} package).
 */
public final class CoreRuntime {

    private static final Logger log = LoggerFactory.getLogger(CoreRuntime.class);

    private final List<HttpListenerSpec> httpListeners;
    private final int socketPort;
    private final int workerThreads;

    public CoreRuntime(List<HttpListenerSpec> httpListeners, int socketPort, int workerThreads) {
        this.httpListeners = List.copyOf(httpListeners);
        this.socketPort = socketPort;
        this.workerThreads = workerThreads;
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        ExecutorService blockingWorkExecutor = Executors.newFixedThreadPool(
                workerThreads, Thread.ofPlatform().name("platform-worker-", 0).daemon(true).factory());
        log.info("Core Runtime: {} worker threads for blocking endpoint work", workerThreads);

        try {
            List<Channel> channels = new ArrayList<>();

            for (HttpListenerSpec listener : httpListeners) {
                channels.add(new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new HttpServerInitializer(
                                listener.routes(), listener.webSocketEnabled(), blockingWorkExecutor))
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
            blockingWorkExecutor.shutdown();
            try {
                if (!blockingWorkExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    blockingWorkExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                blockingWorkExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
