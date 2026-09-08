package com.sunmoon.platform.transport.http;

import com.sunmoon.platform.transport.ws.WsEchoHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Map;

/**
 * REST and WebSocket share one port and one pipeline — the standard Netty
 * pattern (WebSocketServerProtocolHandler intercepts the "/ws" handshake and
 * upgrades that connection to WS frames; every other HTTP request passes
 * through unchanged to {@link RestRequestRouter}), and the same "one runtime,
 * multiple transports" shape {@code sun-moon-c-server}'s "http" mode already
 * proved in C.
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_AGGREGATED_CONTENT_BYTES = 1024 * 1024;
    private static final String WEBSOCKET_PATH = "/ws";

    private final Map<RouteKey, RestEndpoint> routes;

    public HttpServerInitializer(Map<RouteKey, RestEndpoint> routes) {
        this.routes = routes;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATED_CONTENT_BYTES));
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
        pipeline.addLast(new WsEchoHandler());
        pipeline.addLast(new RestRequestRouter(routes));
    }
}
