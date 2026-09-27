package com.sunmoon.platform.domain.pairing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 페어링 코드와 기기 자격을 담는 곳. 토큰 자체는 들어오지 않습니다 — 해시만. */
public interface PairingStore {

    /** 지금 살아 있는(안 쓰였고 안 끝난) 코드 중에 이것이 있는가. */
    boolean liveCodeExists(String code, Instant now);

    /**
     * 코드를 적습니다. <b>같은 기기에 살아 있던 옛 코드는 끝냅니다</b> —
     * 코드를 두 번 발급했으면 마지막 것만 통합니다.
     */
    void issue(String code, DeviceIdentity device, String issuedBy, Instant issuedAt, Instant expiresAt);

    /**
     * 코드를 쓰고, 그 기기의 옛 자격을 끝내고, 새 자격을 적습니다 — 한 번에.
     *
     * @return 코드가 가리키던 기기. 코드가 없거나 끝났거나 이미 쓰였으면 비어 있음.
     */
    Optional<DeviceIdentity> pair(String code, String tokenHash, Instant now);

    Optional<DeviceIdentity> findByTokenHash(String tokenHash);

    /** 마지막으로 보인 시각. 자주 부르지 않습니다(캐시가 비었을 때만). */
    void touch(String tokenHash, Instant now);

    /** @return 끝낸 자격 수 (0 또는 1) */
    int revoke(String storeId, String deviceId, String type, Instant now);

    List<PairedDevice> devices(String storeId, Instant now);

    /** BO 장비 화면이 보는 한 줄. {@code pendingUntil} 은 발급됐지만 아직 안 쓰인 코드의 만료 시각. */
    record PairedDevice(DeviceIdentity device, Instant pairedAt, Instant lastSeen, Instant pendingUntil) {
    }
}
