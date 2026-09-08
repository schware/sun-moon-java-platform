package com.sunmoon.platform.infrastructure.auth;

import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;

import java.util.Map;
import java.util.Optional;

/**
 * Port — server-side session state, chosen over JWT specifically so a
 * compromised/offboarded operator account can be revoked immediately by
 * deleting its record here (see docs/adr/0004). Real adapter (Redis, via
 * Redisson): not yet built — this is the natural next real adapter to add,
 * same pattern as {@code CacheClient} (docs/adr/0003). Fake, wired in by
 * default: {@link InMemorySessionStore}.
 */
public interface SessionStore {
    Session create(long operatorId, String username, String displayName, boolean superAdmin,
                    Map<Screen, OperatorScreenPermission> permissions);

    Optional<Session> get(String sessionId);

    void delete(String sessionId);
}
