package com.sunmoon.platform.transport.ws;

import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RestRequestRouter;
import com.sunmoon.platform.transport.http.RouteKey;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The device-facing listener: REST on one side, terminal WebSockets on the
 * other, sharing a port.
 *
 * <p>The kernel's own {@code HttpServerInitializer} cannot build this
 * pipeline, and that is correct rather than a shortcoming.
 * {@link TerminalHandshakeHandler} has to run <em>before</em>
 * {@link WebSocketServerProtocolHandler} so a terminal can be turned away
 * with an HTTP status instead of an upgraded-then-useless socket — and the
 * kernel only accepts a frame handler, because letting it accept
 * pre-handshake handlers would mean the kernel knowing what a terminal is
 * (docs/adr/0014). So this repository composes its own pipeline out of
 * kernel parts.
 */
public final class DeviceServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_AGGREGATED_CONTENT_BYTES = 1024 * 1024;
    private static final String WEBSOCKET_PATH = "/ws";

    private final Map<RouteKey, RestEndpoint> routes;
    private final Executor blockingWorkExecutor;
    private final DeviceDirectory directory;
    private final TerminalRegistry registry;

    public DeviceServerInitializer(Map<RouteKey, RestEndpoint> routes,
                                   Executor blockingWorkExecutor,
                                   DeviceDirectory directory,
                                   TerminalRegistry registry) {
        this.routes = routes;
        this.blockingWorkExecutor = blockingWorkExecutor;
        this.directory = directory;
        this.registry = registry;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATED_CONTENT_BYTES));
        // Order matters: identify, then upgrade, then handle frames.
        pipeline.addLast(new TerminalHandshakeHandler(directory));
        // checkStartsWith, because a terminal identifies itself in the query
        // string and Netty's default compares the whole URI to the path —
        // "/ws?deviceId=pos-01" would simply not be recognised as /ws.
        pipeline.addLast(new WebSocketServerProtocolHandler(
                WebSocketServerProtocolConfig.newBuilder()
                        .websocketPath(WEBSOCKET_PATH)
                        .checkStartsWith(true)
                        .build()));
        pipeline.addLast(new TerminalFrameHandler(registry));
        pipeline.addLast(new RestRequestRouter(routes, blockingWorkExecutor));
    }
}
