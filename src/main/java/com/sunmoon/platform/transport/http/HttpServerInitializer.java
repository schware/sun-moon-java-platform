package com.sunmoon.platform.transport.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * REST, optionally sharing the port with WebSocket — the standard Netty
 * pattern (WebSocketServerProtocolHandler intercepts the "/ws" handshake;
 * every other request passes through to {@link RestRequestRouter}).
 *
 * <p>The WebSocket frame handler arrives as a {@link Supplier} rather than
 * an instance, because a new one is needed per connection — Netty rejects
 * a non-{@code @Sharable} handler added to a second pipeline. Pass
 * {@code null} for REST-only listeners such as BO.
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_AGGREGATED_CONTENT_BYTES = 1024 * 1024;
    private static final String WEBSOCKET_PATH = "/ws";

    private final Map<RouteKey, RestEndpoint> routes;
    private final Supplier<ChannelHandler> webSocketHandlerFactory;
    private final Executor blockingWorkExecutor;

    public HttpServerInitializer(Map<RouteKey, RestEndpoint> routes,
                                 Supplier<ChannelHandler> webSocketHandlerFactory,
                                 Executor blockingWorkExecutor) {
        this.routes = routes;
        this.webSocketHandlerFactory = webSocketHandlerFactory;
        this.blockingWorkExecutor = blockingWorkExecutor;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATED_CONTENT_BYTES));
        if (webSocketHandlerFactory != null) {
            pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
            pipeline.addLast(webSocketHandlerFactory.get());
        }
        pipeline.addLast(new RestRequestRouter(routes, blockingWorkExecutor));
    }
}
