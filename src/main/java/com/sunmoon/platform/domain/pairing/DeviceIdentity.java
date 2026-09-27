package com.sunmoon.platform.domain.pairing;

import java.util.Locale;
import java.util.Set;

/**
 * 토큰이 가리키는 기기 — 매장, 번호, 종류(POS/KDS/DID), 그리고 KDS 면 역할.
 *
 * <p>페어링할 때 BO 가 정한 값이 그대로 여기 옵니다. 단말이 고르지 않습니다
 * (store-app.md §3.1).
 */
public record DeviceIdentity(String storeId, String deviceId, String type, String kdsRole) {

    public static final Set<String> TYPES = Set.of("POS", "KDS", "DID");

    public DeviceIdentity {
        type = type == null ? null : type.trim().toUpperCase(Locale.ROOT);
        kdsRole = normaliseRole(type, kdsRole);
    }

    /** KDS 가 아니면 역할 없음. KDS 인데 비어 있으면 SUB — 중심은 명시해야만 생깁니다. */
    private static String normaliseRole(String type, String raw) {
        if (!"KDS".equals(type)) {
            return null;
        }
        return raw != null && "MAIN".equalsIgnoreCase(raw.trim()) ? "MAIN" : "SUB";
    }

    public boolean isKdsMain() {
        return "MAIN".equals(kdsRole);
    }
}
