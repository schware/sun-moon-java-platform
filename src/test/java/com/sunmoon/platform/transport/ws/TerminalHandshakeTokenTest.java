package com.sunmoon.platform.transport.ws;

import com.sunmoon.platform.api.auth.ApiGuard.Mode;
import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.pairing.TerminalTokens;
import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 토큰이 있으면 토큰이 신원 (store-app.md §3.5). */
class TerminalHandshakeTokenTest {

    private static final DeviceDirectory ANYONE = (storeId, deviceId, type) -> true;
    private static final Map<String, DeviceIdentity> PAIRED = Map.of(
            "main-token", new DeviceIdentity("store-01", "kds-01", "KDS", "MAIN"),
            "main2-token", new DeviceIdentity("store-01", "kds-02", "KDS", "MAIN"),
            "did-token", new DeviceIdentity("store-01", "did-01", "DID", null));
    private static final TerminalTokens TOKENS = token -> Optional.ofNullable(PAIRED.get(token));

    private final TerminalRegistry registry = new TerminalRegistry();

    private EmbeddedChannel connect(Mode mode, String uri) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new TerminalHandshakeHandler(ANYONE, TOKENS, mode, registry));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
        return channel;
    }

    private static int rejected(EmbeddedChannel channel) {
        FullHttpResponse response = channel.readOutbound();
        return response == null ? 0 : response.status().code();
    }

    @Test
    void the_token_decides_who_you_are_not_the_query() {
        EmbeddedChannel ch = connect(Mode.WARN, "/ws?token=main-token&storeId=store-99&deviceId=pos-07&type=POS");
        assertEquals(0, rejected(ch));
        assertEquals("store-01", ch.attr(TerminalHandshakeHandler.STORE_ID).get());
        assertEquals("kds-01", ch.attr(TerminalHandshakeHandler.DEVICE_ID).get());
        assertEquals(TerminalType.KDS, ch.attr(TerminalHandshakeHandler.TERMINAL_TYPE).get());
        assertEquals("MAIN", ch.attr(TerminalHandshakeHandler.KDS_ROLE).get());
    }

    @Test
    void an_unknown_token_is_refused_even_in_warn() {
        assertEquals(401, rejected(connect(Mode.WARN, "/ws?token=revoked&storeId=store-01&deviceId=kds-01&type=KDS")));
    }

    @Test
    void no_token_passes_in_warn_but_not_in_enforce() {
        EmbeddedChannel warn = connect(Mode.WARN, "/ws?storeId=store-01&deviceId=pos-01&type=POS");
        assertEquals(0, rejected(warn));
        assertNull(warn.attr(TerminalHandshakeHandler.KDS_ROLE).get());

        assertEquals(401, rejected(connect(Mode.ENFORCE, "/ws?storeId=store-01&deviceId=pos-01&type=POS")));
    }

    @Test
    void a_second_kds_main_in_the_same_store_is_refused() {
        EmbeddedChannel first = connect(Mode.WARN, "/ws?token=main-token");
        assertEquals(0, rejected(first));
        registry.register("kds-01", "store-01", TerminalType.KDS, first);

        assertEquals(409, rejected(connect(Mode.WARN, "/ws?token=main2-token")));
        // 같은 기기가 다시 붙는 것은 괜찮습니다 (끊겼다 다시 붙기).
        assertEquals(0, rejected(connect(Mode.WARN, "/ws?token=main-token")));
    }

    @Test
    void a_did_may_watch_several_stores_if_its_own_is_among_them() {
        EmbeddedChannel ch = connect(Mode.WARN, "/ws?token=did-token&storeId=store-01,store-02");
        assertEquals(0, rejected(ch));
        assertEquals(java.util.List.of("store-01", "store-02"), ch.attr(TerminalHandshakeHandler.STORE_IDS).get());

        EmbeddedChannel other = connect(Mode.WARN, "/ws?token=did-token&storeId=store-05");
        assertEquals(java.util.List.of("store-01"), other.attr(TerminalHandshakeHandler.STORE_IDS).get());
    }
}
