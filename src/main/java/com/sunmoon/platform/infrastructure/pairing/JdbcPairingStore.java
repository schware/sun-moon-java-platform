package com.sunmoon.platform.infrastructure.pairing;

import com.sunmoon.platform.domain.pairing.DeviceIdentity;
import com.sunmoon.platform.domain.pairing.PairingStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** pos_server 의 device_pairing_code / device_credential (V6). */
public final class JdbcPairingStore implements PairingStore {

    private final DataSource dataSource;

    public JdbcPairingStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean liveCodeExists(String code, Instant now) {
        String sql = "SELECT 1 FROM device_pairing_code WHERE code = ? AND used_at IS NULL AND expires_at > ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("페어링 코드를 확인하지 못했습니다", e);
        }
    }

    @Override
    public void issue(String code, DeviceIdentity device, String issuedBy, Instant issuedAt, Instant expiresAt) {
        String retire = "UPDATE device_pairing_code SET expires_at = ? "
                + "WHERE store_id = ? AND device_id = ? AND device_type = ? AND used_at IS NULL AND expires_at > ?";
        String insert = "INSERT INTO device_pairing_code "
                + "(code, store_id, device_id, device_type, kds_role, issued_by, issued_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement r = c.prepareStatement(retire); PreparedStatement i = c.prepareStatement(insert)) {
                r.setTimestamp(1, Timestamp.from(issuedAt));
                r.setString(2, device.storeId());
                r.setString(3, device.deviceId());
                r.setString(4, device.type());
                r.setTimestamp(5, Timestamp.from(issuedAt));
                r.executeUpdate();

                i.setString(1, code);
                i.setString(2, device.storeId());
                i.setString(3, device.deviceId());
                i.setString(4, device.type());
                i.setString(5, device.kdsRole());
                i.setString(6, issuedBy);
                i.setTimestamp(7, Timestamp.from(issuedAt));
                i.setTimestamp(8, Timestamp.from(expiresAt));
                i.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("페어링 코드를 적지 못했습니다", e);
        }
    }

    @Override
    public Optional<DeviceIdentity> pair(String code, String tokenHash, Instant now) {
        // 쓰는 것과 확인하는 것이 한 문장 — 같은 코드를 둘이 동시에 넣어도 하나만 이깁니다.
        String consume = "UPDATE device_pairing_code SET used_at = ? "
                + "WHERE code = ? AND used_at IS NULL AND expires_at > ? "
                + "RETURNING store_id, device_id, device_type, kds_role";
        String retire = "UPDATE device_credential SET revoked_at = ?, revoked_why = 'REPAIRED' "
                + "WHERE store_id = ? AND device_id = ? AND device_type = ? AND revoked_at IS NULL";
        String insert = "INSERT INTO device_credential "
                + "(store_id, device_id, device_type, kds_role, token_hash, paired_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<DeviceIdentity> hits = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(consume)) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setString(2, code);
                    ps.setTimestamp(3, Timestamp.from(now));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            hits.add(identity(rs));
                        }
                    }
                }
                // 살아 있는 같은 코드가 둘이면(발급 경합) 어느 기기인지 모릅니다 — 둘 다 태우고 거절.
                if (hits.size() != 1) {
                    c.commit();
                    return Optional.empty();
                }
                DeviceIdentity device = hits.get(0);
                try (PreparedStatement r = c.prepareStatement(retire); PreparedStatement i = c.prepareStatement(insert)) {
                    r.setTimestamp(1, Timestamp.from(now));
                    r.setString(2, device.storeId());
                    r.setString(3, device.deviceId());
                    r.setString(4, device.type());
                    r.executeUpdate();

                    i.setString(1, device.storeId());
                    i.setString(2, device.deviceId());
                    i.setString(3, device.type());
                    i.setString(4, device.kdsRole());
                    i.setString(5, tokenHash);
                    i.setTimestamp(6, Timestamp.from(now));
                    i.executeUpdate();
                }
                c.commit();
                return Optional.of(device);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("페어링을 적지 못했습니다", e);
        }
    }

    @Override
    public Optional<DeviceIdentity> findByTokenHash(String tokenHash) {
        String sql = "SELECT store_id, device_id, device_type, kds_role FROM device_credential "
                + "WHERE token_hash = ? AND revoked_at IS NULL";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(identity(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("기기 토큰을 확인하지 못했습니다", e);
        }
    }

    @Override
    public void touch(String tokenHash, Instant now) {
        String sql = "UPDATE device_credential SET last_seen = ? WHERE token_hash = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setString(2, tokenHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("기기의 마지막 접속을 적지 못했습니다", e);
        }
    }

    @Override
    public int revoke(String storeId, String deviceId, String type, Instant now) {
        String sql = "UPDATE device_credential SET revoked_at = ?, revoked_why = 'REVOKED' "
                + "WHERE store_id = ? AND device_id = ? AND device_type = ? AND revoked_at IS NULL";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setString(2, storeId);
            ps.setString(3, deviceId);
            ps.setString(4, type);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("기기 자격을 끝내지 못했습니다", e);
        }
    }

    @Override
    public List<PairedDevice> devices(String storeId, Instant now) {
        String live = "SELECT store_id, device_id, device_type, kds_role, paired_at, last_seen "
                + "FROM device_credential WHERE revoked_at IS NULL AND (CAST(? AS TEXT) IS NULL OR store_id = ?) "
                + "ORDER BY store_id, device_type, device_id";
        String pending = "SELECT store_id, device_id, device_type, kds_role, expires_at "
                + "FROM device_pairing_code WHERE used_at IS NULL AND expires_at > ? AND (CAST(? AS TEXT) IS NULL OR store_id = ?) "
                + "ORDER BY store_id, device_type, device_id";
        List<PairedDevice> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(live)) {
                ps.setString(1, storeId);
                ps.setString(2, storeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PairedDevice(identity(rs), instant(rs, "paired_at"),
                                instant(rs, "last_seen"), null));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(pending)) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setString(2, storeId);
                ps.setString(3, storeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PairedDevice(identity(rs), null, null, instant(rs, "expires_at")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("기기 목록을 읽지 못했습니다", e);
        }
        return rows;
    }

    private static DeviceIdentity identity(ResultSet rs) throws SQLException {
        return new DeviceIdentity(rs.getString("store_id"), rs.getString("device_id"),
                rs.getString("device_type"), rs.getString("kds_role"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
