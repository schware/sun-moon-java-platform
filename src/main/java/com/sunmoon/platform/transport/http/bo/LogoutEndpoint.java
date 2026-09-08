package com.sunmoon.platform.transport.http.bo;

import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

/** POST /bo/auth/logout — deletes the server-side session (the whole point of picking session over JWT, docs/adr/0004). */
public final class LogoutEndpoint implements RestEndpoint {

    private final SessionStore sessionStore;

    public LogoutEndpoint(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        BoAuth.resolveSession(request, sessionStore).ifPresent(s -> sessionStore.delete(s.sessionId()));

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        DefaultCookie expired = new DefaultCookie(BoAuth.SESSION_COOKIE_NAME, "");
        expired.setPath("/bo");
        expired.setMaxAge(0);
        response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(expired));
        return response;
    }
}
