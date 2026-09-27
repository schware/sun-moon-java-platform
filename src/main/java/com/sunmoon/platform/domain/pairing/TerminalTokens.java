package com.sunmoon.platform.domain.pairing;

import java.util.Optional;

/**
 * 기기 토큰 → 기기. 토큰은 페어링(store-app.md §3)에서 발급됩니다.
 *
 * <p>{@link #NONE} 은 어떤 토큰도 모릅니다 — 데이터베이스가 없는 개발 기동용.
 */
public interface TerminalTokens {

    TerminalTokens NONE = token -> Optional.empty();

    Optional<DeviceIdentity> resolve(String token);
}
