package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.device.Device;
import com.sunmoon.platform.domain.device.DeviceRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fake adapter — wired in by default (see docs/adr/0005). */
public final class InMemoryDeviceRepository implements DeviceRepository {

    private final Map<String, Device> byDeviceId = new ConcurrentHashMap<>();

    @Override
    public List<Device> findAll() {
        return byDeviceId.values().stream()
                .sorted(Comparator.comparing(Device::deviceId))
                .toList();
    }

    @Override
    public List<Device> findByType(String deviceType) {
        return byDeviceId.values().stream()
                .filter(d -> d.deviceType().equals(deviceType))
                .sorted(Comparator.comparing(Device::deviceId))
                .toList();
    }

    @Override
    public Optional<Device> findOne(String deviceId) {
        return Optional.ofNullable(byDeviceId.get(deviceId));
    }

    @Override
    public void create(Device device) {
        if (byDeviceId.putIfAbsent(device.deviceId(), device) != null) {
            throw new IllegalStateException("device already exists: " + device.deviceId());
        }
    }

    @Override
    public void save(Device device) {
        if (!byDeviceId.containsKey(device.deviceId())) {
            throw new IllegalStateException("device does not exist: " + device.deviceId());
        }
        byDeviceId.put(device.deviceId(), device);
    }

    @Override
    public void delete(String deviceId) {
        byDeviceId.remove(deviceId);
    }
}
