package com.sunmoon.platform.api;

import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalSession;
import com.sunmoon.platform.domain.terminal.TerminalType;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The REST surface of the Device Server: who is connected, and how to send
 * something to them.
 *
 * <p>These are how the rest of the platform reaches a terminal today.
 * Once Order publishes to Redis and this server subscribes, the push
 * endpoints become an operator/debug tool rather than the main path —
 * they are kept either way, because "can this server actually reach that
 * screen" is a question worth being able to ask directly.
 *
 * <p>They are unauthenticated. That is only tolerable because this
 * listener is LAN-only; it is a named gap, not an oversight.
 */
public final class TerminalEndpoints {

    private static final Logger log = LoggerFactory.getLogger(TerminalEndpoints.class);

    private final TerminalRegistry registry;

    public TerminalEndpoints(TerminalRegistry registry) {
        this.registry = registry;
    }

    /** {@code GET /terminals} — the connected terminals, newest state, not a database read. */
    public RestEndpoint list() {
        return request -> {
            List<TerminalSession> sessions = registry.connected();
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "count", sessions.size(),
                    "terminals", sessions));
        };
    }

    /** {@code POST /terminals/push?deviceId=pos-01} — body is sent verbatim to that terminal. */
    public RestEndpoint pushToDevice() {
        return request -> {
            String deviceId = param(request.uri(), "deviceId");
            if (deviceId == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "deviceId is required"));
            }
            String payload = request.content().toString(StandardCharsets.UTF_8);

            return registry.channelFor(deviceId)
                    .map(channel -> {
                        channel.writeAndFlush(new TextWebSocketFrame(payload));
                        log.debug("pushed {} bytes to {}", payload.length(), deviceId);
                        return JsonResponses.of(HttpResponseStatus.OK, Map.of("delivered", true, "deviceId", deviceId));
                    })
                    // Not an error: a terminal being offline is an ordinary
                    // state, and the caller needs to tell it apart from a
                    // delivery, so it is 404 with an explicit flag.
                    .orElseGet(() -> JsonResponses.of(HttpResponseStatus.NOT_FOUND,
                            Map.of("delivered", false, "error", "terminal not connected: " + deviceId)));
        };
    }

    /** {@code POST /terminals/broadcast?type=KDS} — how "tell every kitchen screen" is expressed. */
    public RestEndpoint broadcastToType() {
        return request -> {
            String rawType = param(request.uri(), "type");
            TerminalType type = parseType(rawType);
            if (type == null) {
                return JsonResponses.of(HttpResponseStatus.BAD_REQUEST,
                        Map.of("error", "type must be one of POS, KDS, DID"));
            }
            String payload = request.content().toString(StandardCharsets.UTF_8);
            int reached = registry.channelsOf(type).size();
            registry.channelsOf(type).writeAndFlush(new TextWebSocketFrame(payload));

            return JsonResponses.of(HttpResponseStatus.OK, Map.of("type", type.name(), "delivered", reached));
        };
    }

    private static String param(String uri, String name) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
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
}
