package com.sunmoon.platform.domain.device;

import java.util.List;
import java.util.Optional;

/**
 * Port. Real adapter: {@code infrastructure.persistence.MyBatisDeviceRepository}
 * (PostgreSQL — not live-verified, docs/adr/0005). Fake, wired in by
 * default: {@code infrastructure.persistence.InMemoryDeviceRepository}.
 */
public interface DeviceRepository {
    List<Device> findAll();

    List<Device> findByType(String deviceType);

    Optional<Device> findOne(String deviceId);

    /** Fails with {@link IllegalStateException} if {@code deviceId} already exists — 신규 vs 저장, same split as Common Code. */
    void create(Device device);

    /** Fails if {@code deviceId} doesn't exist yet. */
    void save(Device device);

    void delete(String deviceId);
}
