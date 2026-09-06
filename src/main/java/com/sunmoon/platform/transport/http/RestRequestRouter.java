package com.sunmoon.platform.transport.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.util.Map;

/** Dispatches REST requests by exact path to a registered {@link RestEndpoint}. No regex/path-variable routing yet — add it when a second endpoint actually needs it, not before. */
public final class RestRequestRouter extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final Map<String, RestEndpoint> routes;

    public RestRequestRouter(Map<String, RestEndpoint> routes) {
        this.routes = routes;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        RestEndpoint endpoint = routes.get(request.uri());
        FullHttpResponse response = endpoint != null
                ? endpoint.handle(request)
                : notFound();

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        if (!keepAlive) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response);
        }
    }

    private static FullHttpResponse notFound() {
        return new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.EMPTY_BUFFER);
    }
}
