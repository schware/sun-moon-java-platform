package com.sunmoon.platform.transport.http.bo;

import com.sunmoon.platform.infrastructure.auth.Session;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.util.Optional;
import java.util.Set;

public final class BoAuth {

    public static final String SESSION_COOKIE_NAME = "BO_SESSION";

    public static Optional<Session> resolveSession(FullHttpRequest request, SessionStore sessionStore) {
        String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader == null) {
            return Optional.empty();
        }
        Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieHeader);
        return cookies.stream()
                .filter(c -> SESSION_COOKIE_NAME.equals(c.name()))
                .findFirst()
                .flatMap(c -> sessionStore.get(c.value()));
    }

    private BoAuth() {
    }
}
