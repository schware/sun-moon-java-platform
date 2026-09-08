package com.sunmoon.platform.infrastructure.auth;

import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;

import java.util.Map;

/** Permissions are loaded once at login and cached here, rather than hitting {@code OperatorRepository} on every request. */
public record Session(
        String sessionId,
        long operatorId,
        String username,
        String displayName,
        boolean superAdmin,
        Map<Screen, OperatorScreenPermission> permissionsByScreen
) {
}
