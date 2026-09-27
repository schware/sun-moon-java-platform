package com.sunmoon.platform.infrastructure.pairing;

import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.pairing.PairingStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 데이터베이스 없이 띄울 때와 시험용. 재기동하면 전부 사라집니다. */
public final class InMemoryPairingStore implements PairingStore {

    private static final class Code {
        final String code;
        final DeviceIdentity device;
        Instant expiresAt;
        Instant usedAt;

        Code(String code, DeviceIdentity device, Instant expiresAt) {
            this.code = code;
            this.device = device;
            this.expiresAt = expiresAt;
        }

        boolean live(Instant now) {
            return usedAt == null && expiresAt.isAfter(now);
        }
    }

    private static final class Credential {
        final DeviceIdentity device;
        final String tokenHash;
        final Instant pairedAt;
        Instant lastSeen;
        Instant revokedAt;

        Credential(DeviceIdentity device, String tokenHash, Instant pairedAt) {
            this.device = device;
            this.tokenHash = tokenHash;
            this.pairedAt = pairedAt;
        }
    }

    private final List<Code> codes = new ArrayList<>();
    private final List<Credential> credentials = new ArrayList<>();

    @Override
    public synchronized boolean liveCodeExists(String code, Instant now) {
        return codes.stream().anyMatch(c -> c.code.equals(code) && c.live(now));
    }

    @Override
    public synchronized void issue(String code, DeviceIdentity device, String issuedBy,
                                   Instant issuedAt, Instant expiresAt) {
        codes.stream().filter(c -> sameDevice(c.device, device) && c.live(issuedAt))
                .forEach(c -> c.expiresAt = issuedAt);
        codes.add(new Code(code, device, expiresAt));
    }

    @Override
    public synchronized Optional<DeviceIdentity> pair(String code, String tokenHash, Instant now) {
        Optional<Code> hit = codes.stream().filter(c -> c.code.equals(code) && c.live(now)).findFirst();
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        hit.get().usedAt = now;
        DeviceIdentity device = hit.get().device;
        credentials.stream().filter(c -> c.revokedAt == null && sameDevice(c.device, device))
                .forEach(c -> c.revokedAt = now);
        credentials.add(new Credential(device, tokenHash, now));
        return Optional.of(device);
    }

    @Override
    public synchronized Optional<DeviceIdentity> findByTokenHash(String tokenHash) {
        return credentials.stream().filter(c -> c.revokedAt == null && c.tokenHash.equals(tokenHash))
                .map(c -> c.device).findFirst();
    }

    @Override
    public synchronized void touch(String tokenHash, Instant now) {
        credentials.stream().filter(c -> c.tokenHash.equals(tokenHash)).forEach(c -> c.lastSeen = now);
    }

    @Override
    public synchronized int revoke(String storeId, String deviceId, String type, Instant now) {
        int n = 0;
        for (Credential c : credentials) {
            if (c.revokedAt == null && c.device.storeId().equals(storeId)
                    && c.device.deviceId().equals(deviceId) && c.device.type().equals(type)) {
                c.revokedAt = now;
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized List<PairedDevice> devices(String storeId, Instant now) {
        List<PairedDevice> rows = new ArrayList<>();
        for (Credential c : credentials) {
            if (c.revokedAt == null && (storeId == null || c.device.storeId().equals(storeId))) {
                rows.add(new PairedDevice(c.device, c.pairedAt, c.lastSeen, null));
            }
        }
        for (Code c : codes) {
            if (c.live(now) && (storeId == null || c.device.storeId().equals(storeId))) {
                rows.add(new PairedDevice(c.device, null, null, c.expiresAt));
            }
        }
        return rows;
    }

    private static boolean sameDevice(DeviceIdentity a, DeviceIdentity b) {
        return Objects.equals(a.storeId(), b.storeId()) && Objects.equals(a.deviceId(), b.deviceId())
                && Objects.equals(a.type(), b.type());
    }
}
