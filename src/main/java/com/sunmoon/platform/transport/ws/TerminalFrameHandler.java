package com.sunmoon.platform.transport.ws;

import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A terminal's connection, once the upgrade has succeeded.
 *
 * <p>Registration happens on the handshake-complete event rather than on
 * {@code channelActive}: the channel is active while it is still an HTTP
 * connection, and a terminal that fails the upgrade must never end up in
 * the registry.
 */
public final class TerminalFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    /**
     * Close status for "another connection took this device id".
     *
     * <p>In the private 4000-4999 range, and it exists because closing
     * silently was worse than useless: two windows using one device id
     * each displaced the other, each displacement looked like a dropped
     * connection, each reconnected — and the two screens knocked each
     * other offline forever while burning a core. A client that is told
     * *why* can stop and say so.
     */
    public static final int CLOSE_REPLACED = 4001;

    private static final Logger log = LoggerFactory.getLogger(TerminalFrameHandler.class);

    private final TerminalRegistry registry;

    private String deviceId;
    private String storeId;
    private TerminalType type;

    public TerminalFrameHandler(TerminalRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            deviceId = ctx.channel().attr(TerminalHandshakeHandler.DEVICE_ID).get();
            storeId = ctx.channel().attr(TerminalHandshakeHandler.STORE_ID).get();
            type = ctx.channel().attr(TerminalHandshakeHandler.TERMINAL_TYPE).get();

            registry.register(deviceId, storeId, type, ctx.channel())
                    // A device reconnecting displaces its old channel. Closing
                    // it here, off the registry, keeps that map free of I/O.
                    .ifPresent(displaced -> {
                        log.info("terminal {} claimed by a new connection; closing the previous one", deviceId);
                        displaced.writeAndFlush(new CloseWebSocketFrame(
                                        CLOSE_REPLACED, "device id claimed by another connection"))
                                .addListener(future -> displaced.close());
                    });

            log.info("terminal connected: {} ({} at {}) — {} now connected",
                    deviceId, type, storeId, registry.size());
            ctx.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"WELCOME\",\"deviceId\":\"" + deviceId
                            + "\",\"storeId\":\"" + storeId
                            + "\",\"terminal\":\"" + type + "\"}"));
            return;
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        // Terminals have nothing to say upstream yet — they accept orders
        // and report production over REST, where the state machine lives.
        // A ping keeps intermediaries from closing an idle connection.
        if ("ping".equalsIgnoreCase(frame.text().trim())) {
            ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"PONG\"}"));
            return;
        }
        log.debug("ignoring frame from {}: {}", deviceId, frame.text());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (deviceId != null) {
            registry.unregister(deviceId, ctx.channel());
            log.info("terminal disconnected: {} — {} still connected", deviceId, registry.size());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // One terminal's failure must not take down the event loop it
        // shares with every other terminal.
        log.warn("terminal {} failed; closing its connection", deviceId, cause);
        Channel channel = ctx.channel();
        if (channel.isActive()) {
            channel.close();
        }
    }
}
