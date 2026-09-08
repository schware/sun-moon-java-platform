package com.sunmoon.platform.infrastructure.auth;

import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Override
    public Session create(long operatorId, String username, String displayName, boolean superAdmin,
                           Map<Screen, OperatorScreenPermission> permissions) {
        String sessionId = generateSessionId();
        Session session = new Session(sessionId, operatorId, username, displayName, superAdmin, Map.copyOf(permissions));
        sessions.put(sessionId, session);
        return session;
    }

    @Override
    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    private String generateSessionId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
