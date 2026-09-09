package com.sunmoon.platform.transport.ws;

import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalType;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Identifies a terminal <em>before</em> the WebSocket upgrade completes.
 *
 * <p>A terminal connects to
 * {@code /ws?deviceId=pos-01&storeId=store-01&type=POS}. This handler
 * reads those, checks them, and either stamps them on the channel for
 * {@link TerminalFrameHandler} to pick up, or answers the HTTP handshake
 * with 400 and closes.
 *
 * <p>{@code storeId} is required rather than optional. A terminal that
 * did not say which shop it is in cannot be routed to, and defaulting it
 * would mean guessing which counter an order belongs to.
 *
 * <p>Rejecting at handshake rather than after upgrade is deliberate: a
 * client that got it wrong gets a status code it can read, instead of an
 * open socket that silently never receives anything.
 */
public final class TerminalHandshakeHandler extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<String> DEVICE_ID = AttributeKey.valueOf("deviceId");
    public static final AttributeKey<String> STORE_ID = AttributeKey.valueOf("storeId");
    public static final AttributeKey<TerminalType> TERMINAL_TYPE = AttributeKey.valueOf("terminalType");

    private static final String WEBSOCKET_PATH = "/ws";

    private final DeviceDirectory directory;

    public TerminalHandshakeHandler(DeviceDirectory directory) {
        this.directory = directory;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(message);
            return;
        }

        QueryStringDecoder query = new QueryStringDecoder(request.uri());
        if (!WEBSOCKET_PATH.equals(query.path())) {
            ctx.fireChannelRead(message);
            return;
        }

        String deviceId = first(query, "deviceId");
        String storeId = first(query, "storeId");
        String rawType = first(query, "type");

        if (!directory.isRegistered(deviceId)) {
            reject(ctx, request, "deviceId is missing or not a valid device id");
            return;
        }
        if (storeId == null || storeId.isBlank()) {
            reject(ctx, request, "storeId is required");
            return;
        }

        TerminalType type = parseType(rawType);
        if (type == null) {
            reject(ctx, request, "type must be one of POS, KDS, DID");
            return;
        }

        ctx.channel().attr(DEVICE_ID).set(deviceId);
        ctx.channel().attr(STORE_ID).set(storeId);
        ctx.channel().attr(TERMINAL_TYPE).set(type);
        ctx.fireChannelRead(message);
    }

    private static String first(QueryStringDecoder query, String name) {
        List<String> values = query.parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static TerminalType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TerminalType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static void reject(ChannelHandlerContext ctx, FullHttpRequest request, String reason) {
        ReferenceCountUtil.release(request);
        byte[] body = ("{\"error\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(future -> ctx.close());
    }
}
