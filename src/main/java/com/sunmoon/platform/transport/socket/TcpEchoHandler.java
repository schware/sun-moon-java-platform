package com.sunmoon.platform.transport.socket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Raw Socket transport, first cut: echoes bytes back. Stands in for a real
 * business protocol the way {@code sun-moon-c-server}'s tcp_echo_service.c
 * did before its own protocol handlers were built — replace with real
 * framing/dispatch once there's an actual Socket-based use case to serve.
 */
public final class TcpEchoHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        ctx.writeAndFlush(msg.retainedDuplicate());
    }
}
