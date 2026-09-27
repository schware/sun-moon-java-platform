package com.sunmoon.platform.api.auth;

import java.util.Optional;

/**
 * 기기 토큰 → 기기. 토큰은 1단계 페어링(store-app.md §3)에서 발급됩니다.
 *
 * <p>그전까지는 {@link #NONE} — 어떤 토큰도 모릅니다. 그래서 0단계의
 * {@code warn} 로그에는 단말 주소마다 NO_DEVICE_TOKEN 이 찍히는 것이 정상이고,
 * 0단계에서 {@code enforce} 로 올리면 안 됩니다(단말이 전부 막힙니다).
 */
public interface TerminalTokens {

    TerminalTokens NONE = token -> Optional.empty();

    Optional<DeviceIdentity> resolve(String token);
}
