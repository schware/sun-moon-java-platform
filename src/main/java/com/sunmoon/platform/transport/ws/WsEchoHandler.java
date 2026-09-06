package com.sunmoon.platform.transport.ws;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * WebSocket transport, first cut: echoes text frames back, prefixed so a
 * client can tell it round-tripped through the server. Same "prove the
 * transport, then replace with real dispatch" approach as
 * {@link com.sunmoon.platform.transport.socket.TcpEchoHandler}.
 */
public final class WsEchoHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        ctx.writeAndFlush(new TextWebSocketFrame("echo: " + frame.text()));
    }
}
