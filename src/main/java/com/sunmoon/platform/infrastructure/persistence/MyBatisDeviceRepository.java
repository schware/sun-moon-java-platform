package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.device.Device;
import com.sunmoon.platform.domain.device.DeviceRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Optional;

/** Real adapter over PostgreSQL via MyBatis. Not live-verified — see docs/adr/0005. */
public final class MyBatisDeviceRepository implements DeviceRepository {

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisDeviceRepository(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Override
    public List<Device> findAll() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(DeviceMapper.class).findAll().stream()
                    .map(MyBatisDeviceRepository::toDomain)
                    .toList();
        }
    }

    @Override
    public List<Device> findByType(String deviceType) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(DeviceMapper.class).findByType(deviceType).stream()
                    .map(MyBatisDeviceRepository::toDomain)
                    .toList();
        }
    }

    @Override
    public Optional<Device> findOne(String deviceId) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return Optional.ofNullable(session.getMapper(DeviceMapper.class).findOne(deviceId))
                    .map(MyBatisDeviceRepository::toDomain);
        }
    }

    @Override
    public void create(Device device) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(DeviceMapper.class).insert(toRow(device));
        }
    }

    @Override
    public void save(Device device) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            if (session.getMapper(DeviceMapper.class).update(toRow(device)) == 0) {
                throw new IllegalStateException("device does not exist: " + device.deviceId());
            }
        }
    }

    @Override
    public void delete(String deviceId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(DeviceMapper.class).delete(deviceId);
        }
    }

    private static Device toDomain(DeviceRow row) {
        return new Device(row.getDeviceId(), row.getName(), row.getDeviceType(), row.getLocation(), row.isActive());
    }

    private static DeviceRow toRow(Device device) {
        DeviceRow row = new DeviceRow();
        row.setDeviceId(device.deviceId());
        row.setName(device.name());
        row.setDeviceType(device.deviceType());
        row.setLocation(device.location());
        row.setActive(device.active());
        return row;
    }
}
