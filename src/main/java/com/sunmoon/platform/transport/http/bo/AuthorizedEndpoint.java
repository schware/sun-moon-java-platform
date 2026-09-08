package com.sunmoon.platform.transport.http.bo;

import com.sunmoon.platform.domain.operator.Action;
import com.sunmoon.platform.domain.operator.Screen;
import com.sunmoon.platform.infrastructure.auth.Session;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;
import java.util.Optional;

/**
 * Decorator implementing the 3-tier BO permission model (docs/adr/0004):
 * super admin bypasses every check; otherwise the caller's session must
 * carry a permission for {@code screen} that {@link OperatorScreenPermission#allows}
 * {@code action}. Wrap any {@code /bo/*} {@link RestEndpoint} in this — the
 * endpoint itself never needs to know about auth.
 */
public final class AuthorizedEndpoint implements RestEndpoint {

    private final Screen screen;
    private final Action action;
    private final SessionStore sessionStore;
    private final RestEndpoint delegate;

    public AuthorizedEndpoint(Screen screen, Action action, SessionStore sessionStore, RestEndpoint delegate) {
        this.screen = screen;
        this.action = action;
        this.sessionStore = sessionStore;
        this.delegate = delegate;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        Optional<Session> session = BoAuth.resolveSession(request, sessionStore);
        if (session.isEmpty()) {
            return JsonResponses.of(HttpResponseStatus.UNAUTHORIZED, Map.of("error", "login required"));
        }

        Session s = session.get();
        boolean allowed = s.superAdmin()
                || Optional.ofNullable(s.permissionsByScreen().get(screen)).map(p -> p.allows(action)).orElse(false);
        if (!allowed) {
            return JsonResponses.of(HttpResponseStatus.FORBIDDEN, Map.of("error", "permission denied"));
        }

        return delegate.handle(request);
    }
}
