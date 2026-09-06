package com.sunmoon.platform.transport.socket;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public final class SocketServerInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline().addLast(new TcpEchoHandler());
    }
}
