package com.sunmoon.platform.transport.ws;

import com.sunmoon.platform.api.auth.ApiGuard;
import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.pairing.TerminalTokens;
import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>For a DID only (2026-09-13), {@code storeId} may be a comma-separated
 * list — {@code storeId=store-01,store-02,store-03} — a food-court style
 * board watching several branches at once. POS and KDS are physically at
 * one counter, so exactly one store is enforced for them; a list there
 * would be a client mistake worth rejecting rather than silently
 * accepting the first one.
 *
 * <p>Rejecting at handshake rather than after upgrade is deliberate: a
 * client that got it wrong gets a status code it can read, instead of an
 * open socket that silently never receives anything.
 */
public final class TerminalHandshakeHandler extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<String> DEVICE_ID = AttributeKey.valueOf("deviceId");
    public static final AttributeKey<String> STORE_ID = AttributeKey.valueOf("storeId");
    public static final AttributeKey<List<String>> STORE_IDS = AttributeKey.valueOf("storeIds");
    public static final AttributeKey<TerminalType> TERMINAL_TYPE = AttributeKey.valueOf("terminalType");
    /** KDS 의 역할 (MAIN | SUB). 토큰으로 붙은 KDS 에만 있습니다 — 옛 방식은 역할을 모릅니다. */
    public static final AttributeKey<String> KDS_ROLE = AttributeKey.valueOf("kdsRole");

    private static final Logger log = LoggerFactory.getLogger(TerminalHandshakeHandler.class);
    private static final String WEBSOCKET_PATH = "/ws";
    /** 토큰 없이 붙은 단말을 1분에 한 번만 적습니다 — 전 매장이 그렇게 붙어 있는 동안은 시끄럽습니다. */
    private static final AtomicLong lastUntokenedLog = new AtomicLong();

    private final DeviceDirectory directory;
    private final TerminalTokens tokens;
    private final ApiGuard.Mode mode;
    private final TerminalRegistry registry;

    /** 옛 방식 그대로 — 토큰을 보지 않습니다. 시험과 데이터베이스 없는 기동용. */
    public TerminalHandshakeHandler(DeviceDirectory directory) {
        this(directory, TerminalTokens.NONE, ApiGuard.Mode.OFF, null);
    }

    /**
     * 토큰이 있으면 <b>토큰이 신원</b>입니다 (store-app.md §3.5) — 쿼리의
     * 매장·번호·종류는 무시하고 페어링 때 적힌 것을 씁니다.
     *
     * <p>토큰이 없으면: {@code enforce} 는 거절, 그 밖에는 예전처럼 쿼리를 믿고
     * 적어 둡니다. 페어링이 전 매장에 퍼지기 전에 막으면 단말이 전부 멈춥니다.
     */
    public TerminalHandshakeHandler(DeviceDirectory directory, TerminalTokens tokens,
                                    ApiGuard.Mode mode, TerminalRegistry registry) {
        this.directory = directory;
        this.tokens = tokens;
        this.mode = mode;
        this.registry = registry;
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
        String kdsRole = null;

        String token = first(query, "token");
        if (token != null && !token.isBlank()) {
            Optional<DeviceIdentity> paired = tokens.resolve(token);
            if (paired.isEmpty()) {
                // 토큰을 냈는데 모르는 토큰 — 끝난 자격이거나 다른 서버의 것.
                // warn 에서도 쿼리로 되돌아가지 않습니다: 그러면 revoke 가 뜻이 없습니다.
                reject(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                        "이 기기의 페어링이 끝났습니다. BO 에서 코드를 새로 받아 다시 페어링하십시오");
                return;
            }
            DeviceIdentity device = paired.get();
            if (deviceId != null && !deviceId.equals(device.deviceId())) {
                log.warn("토큰의 기기({})와 쿼리의 기기({})가 다릅니다 — 토큰을 씁니다", device.deviceId(), deviceId);
            }
            deviceId = device.deviceId();
            rawType = device.type();
            kdsRole = device.kdsRole();
            // DID 는 여러 매장을 볼 수 있습니다 — 쿼리의 목록을 쓰되 자기 매장이 들어 있어야 합니다.
            if ("DID".equals(device.type()) && storeId != null
                    && splitStoreIds(storeId).contains(device.storeId())) {
                // 그대로
            } else {
                storeId = device.storeId();
            }
        } else if (mode == ApiGuard.Mode.ENFORCE) {
            reject(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                    "페어링되지 않은 기기입니다. BO 에서 페어링 코드를 받아 입력하십시오");
            return;
        } else if (mode == ApiGuard.Mode.WARN) {
            long now = System.currentTimeMillis();
            long last = lastUntokenedLog.get();
            if (now - last > 60_000 && lastUntokenedLog.compareAndSet(last, now)) {
                log.warn("[warn — 통과시킴] 토큰 없이 붙은 단말: {} {} {}", storeId, rawType, deviceId);
            }
        }

        if (deviceId == null || deviceId.isBlank()) {
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

        List<String> storeIds = splitStoreIds(storeId);
        if (storeIds.isEmpty()) {
            reject(ctx, request, "storeId is required");
            return;
        }

        // 등록 확인은 **매장과 종류를 안 뒤에** 합니다 (2026-09-24).
        // 단말의 신원은 (매장, 번호, 종류)이고, 번호만으로 물으면
        // 어느 매장의 어느 종류인지 모른 채 답하게 됩니다.
        //
        // DID는 여러 매장을 볼 수 있어서 첫 매장으로 묻습니다 — 장비는
        // 한 대이고 어딘가에 등록돼 있으면 되는데, 그 "어딘가"를
        // 대표하는 값이 필요합니다.
        if (!directory.isRegistered(storeIds.get(0), deviceId, type.name())) {
            reject(ctx, request, "deviceId is missing or not a valid device id");
            return;
        }
        if (type != TerminalType.DID && storeIds.size() > 1) {
            reject(ctx, request, "storeId must be exactly one value for " + type);
            return;
        }
        // KDS-MAIN 은 매장에 하나 (§3.5). 같은 기기가 다시 붙는 것은 괜찮습니다 —
        // 등록이 옛 연결을 대신합니다.
        if ("MAIN".equals(kdsRole) && otherMainConnected(storeIds.get(0), deviceId)) {
            reject(ctx, request, HttpResponseStatus.CONFLICT, "이 매장의 KDS-MAIN 이 이미 연결돼 있습니다");
            return;
        }

        ctx.channel().attr(DEVICE_ID).set(deviceId);
        ctx.channel().attr(STORE_ID).set(storeId);
        ctx.channel().attr(STORE_IDS).set(storeIds);
        ctx.channel().attr(TERMINAL_TYPE).set(type);
        ctx.channel().attr(KDS_ROLE).set(kdsRole);
        ctx.fireChannelRead(message);
    }

    private boolean otherMainConnected(String storeId, String deviceId) {
        if (registry == null) {
            return false;
        }
        for (io.netty.channel.Channel channel : registry.channelsOf(storeId, TerminalType.KDS)) {
            if ("MAIN".equals(channel.attr(KDS_ROLE).get())
                    && !deviceId.equals(channel.attr(DEVICE_ID).get())) {
                return true;
            }
        }
        return false;
    }

    /** {@code "store-01, store-02"} -> {@code ["store-01", "store-02"]}; blanks and surrounding whitespace dropped. */
    private static List<String> splitStoreIds(String raw) {
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids;
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
        reject(ctx, request, HttpResponseStatus.BAD_REQUEST, reason);
    }

    private static void reject(ChannelHandlerContext ctx, FullHttpRequest request,
                               HttpResponseStatus status, String reason) {
        ReferenceCountUtil.release(request);
        byte[] body = ("{\"error\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(future -> ctx.close());
    }
}
