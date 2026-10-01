package com.sunmoon.platform.infrastructure.kiosk;

import com.sunmoon.platform.domain.kiosk.KioskPins;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** pos_server {@code kiosk_admin_pin} (V8). 데이터베이스가 없으면 {@link #inMemory()}. */
public final class JdbcKioskPinStore implements KioskPins.Store {

    private final DataSource dataSource;

    public JdbcKioskPinStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static KioskPins.Store inMemory() {
        Map<String, KioskPins.Stored> pins = new ConcurrentHashMap<>();
        return new KioskPins.Store() {
            @Override
            public Optional<KioskPins.Stored> find(String storeId, String deviceId) {
                return Optional.ofNullable(pins.get(storeId + "|" + deviceId));
            }

            @Override
            public void save(String storeId, String deviceId, KioskPins.Stored pin, String setBy) {
                pins.put(storeId + "|" + deviceId, pin);
            }
        };
    }

    @Override
    public Optional<KioskPins.Stored> find(String storeId, String deviceId) {
        String sql = "SELECT pin_hash, salt, iterations FROM kiosk_admin_pin WHERE store_id = ? AND device_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new KioskPins.Stored(rs.getString(1), rs.getString(2), rs.getInt(3)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("KIOSK PIN 을 읽지 못했습니다", e);
        }
    }

    @Override
    public void save(String storeId, String deviceId, KioskPins.Stored pin, String setBy) {
        String sql = "INSERT INTO kiosk_admin_pin (store_id, device_id, pin_hash, salt, iterations, set_by, set_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, now()) ON CONFLICT (store_id, device_id) DO UPDATE SET "
                + "pin_hash = excluded.pin_hash, salt = excluded.salt, iterations = excluded.iterations, "
                + "set_by = excluded.set_by, set_at = now()";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceId);
            ps.setString(3, pin.hash());
            ps.setString(4, pin.salt());
            ps.setInt(5, pin.iterations());
            ps.setString(6, setBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("KIOSK PIN 을 적지 못했습니다", e);
        }
    }
}
