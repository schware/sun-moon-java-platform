package com.sunmoon.platform.transport.http.bo;

import com.sunmoon.platform.infrastructure.auth.Session;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;
import java.util.Optional;

/** GET /bo/auth/me — lets the Frontend build its menu/buttons from the caller's own permission set. */
public final class MeEndpoint implements RestEndpoint {

    private final SessionStore sessionStore;

    public MeEndpoint(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        Optional<Session> session = BoAuth.resolveSession(request, sessionStore);
        if (session.isEmpty()) {
            return JsonResponses.of(HttpResponseStatus.UNAUTHORIZED, Map.of("error", "login required"));
        }

        Session s = session.get();
        return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                "username", s.username(),
                "displayName", s.displayName(),
                "superAdmin", s.superAdmin(),
                "screens", s.permissionsByScreen().keySet()));
    }
}
