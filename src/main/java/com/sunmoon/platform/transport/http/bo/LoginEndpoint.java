package com.sunmoon.platform.transport.http.bo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.operator.Operator;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;
import com.sunmoon.platform.infrastructure.auth.PasswordHasher;
import com.sunmoon.platform.infrastructure.auth.Session;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** POST /bo/auth/login — the session-cookie issuance point (docs/adr/0004). */
public final class LoginEndpoint implements RestEndpoint {

    public record LoginRequest(String username, String password) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperatorRepository operatorRepository;
    private final SessionStore sessionStore;
    private final boolean secureCookie;

    public LoginEndpoint(OperatorRepository operatorRepository, SessionStore sessionStore, boolean secureCookie) {
        this.operatorRepository = operatorRepository;
        this.sessionStore = sessionStore;
        this.secureCookie = secureCookie;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            return JsonResponses.of(HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "use POST"));
        }

        LoginRequest loginRequest;
        try {
            loginRequest = JSON.readValue(request.content().toString(StandardCharsets.UTF_8), LoginRequest.class);
        } catch (Exception e) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "invalid JSON body"));
        }

        Optional<Operator> found = operatorRepository.findByUsername(loginRequest.username());
        boolean credentialsOk = found.isPresent() && found.get().active()
                && PasswordHasher.matches(loginRequest.password(), found.get().passwordHash());
        if (!credentialsOk) {
            return JsonResponses.of(HttpResponseStatus.UNAUTHORIZED, Map.of("error", "invalid credentials"));
        }

        Operator operator = found.get();
        Map<Screen, OperatorScreenPermission> permissions = operatorRepository.findPermissions(operator.id()).stream()
                .collect(Collectors.toMap(OperatorScreenPermission::screen, p -> p));

        Session session = sessionStore.create(
                operator.id(), operator.username(), operator.displayName(), operator.superAdmin(), permissions);

        FullHttpResponse response = JsonResponses.of(HttpResponseStatus.OK, Map.of(
                "username", operator.username(),
                "displayName", operator.displayName(),
                "superAdmin", operator.superAdmin()));

        DefaultCookie cookie = new DefaultCookie(BoAuth.SESSION_COOKIE_NAME, session.sessionId());
        cookie.setHttpOnly(true);
        cookie.setPath("/bo");
        cookie.setSameSite(CookieHeaderNames.SameSite.Lax);
        cookie.setSecure(secureCookie); // COOKIE_SECURE — on wherever this is served over TLS
        response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
        return response;
    }
}
