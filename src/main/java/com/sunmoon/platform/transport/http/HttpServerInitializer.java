package com.sunmoon.platform.transport.http;

import com.sunmoon.platform.transport.ws.WsEchoHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * When WebSocket is enabled, REST and WebSocket share this listener's port
 * and pipeline — the standard Netty pattern (WebSocketServerProtocolHandler
 * intercepts the "/ws" handshake and upgrades that connection to WS frames;
 * every other HTTP request passes through unchanged to
 * {@link RestRequestRouter}), and the same "one runtime, multiple
 * transports" shape {@code sun-moon-c-server}'s "http" mode proved in C.
 * The BO listener leaves it off — BO is REST only.
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_AGGREGATED_CONTENT_BYTES = 1024 * 1024;
    private static final String WEBSOCKET_PATH = "/ws";

    private final Map<RouteKey, RestEndpoint> routes;
    private final boolean webSocketEnabled;
    private final Executor blockingWorkExecutor;

    public HttpServerInitializer(Map<RouteKey, RestEndpoint> routes, boolean webSocketEnabled,
                                 Executor blockingWorkExecutor) {
        this.routes = routes;
        this.webSocketEnabled = webSocketEnabled;
        this.blockingWorkExecutor = blockingWorkExecutor;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATED_CONTENT_BYTES));
        if (webSocketEnabled) {
            pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
            pipeline.addLast(new WsEchoHandler());
        }
        pipeline.addLast(new RestRequestRouter(routes, blockingWorkExecutor));
    }
}
