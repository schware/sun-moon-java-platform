package com.sunmoon.platform.infrastructure.pos;

import com.sunmoon.platform.domain.pos.MasterDispatch;
import com.sunmoon.platform.domain.pos.MasterRepository;
import com.sunmoon.platform.domain.pos.MasterType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 마스터 버전과 장비별 적용 결과의 JDBC 저장소 (§3.6).
 *
 * <p>마스터의 <b>내용은 여기 없습니다</b>. 있는 것은 "지금 몇 번
 * 버전인가"와 "이 장비는 몇 번까지 받았나" 둘뿐입니다.
 */
public final class JdbcMasterRepository implements MasterRepository {

    private final DataSource dataSource;

    public JdbcMasterRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Map<MasterType, Long> versionsOf(String storeId) {
        String sql = "SELECT master_type, version FROM pos_master_version WHERE store_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<MasterType, Long> versions = new EnumMap<>(MasterType.class);
                while (rs.next()) {
                    // 모르는 이름은 건너뜁니다 — 표에 남은 옛 마스터 하나가
                    // 배포 전체를 세우면 안 됩니다.
                    Optional<MasterType> type = MasterType.of(rs.getString("master_type"));
                    if (type.isPresent()) {
                        versions.put(type.get(), rs.getLong("version"));
                    }
                }
                return versions;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 버전을 읽지 못했습니다", e);
        }
    }

    /**
     * 버전 올리기는 <b>한 문장</b>입니다.
     *
     * <p>읽고 더해서 쓰면, BO에서 두 사람이 같은 마스터를 동시에 고칠 때
     * 둘 다 같은 번호를 만들고 장비는 한 번만 받습니다.
     */
    @Override
    public long bumpVersion(String storeId, MasterType masterType) {
        String sql = """
                INSERT INTO pos_master_version (store_id, master_type, version, updated_at)
                VALUES (?, ?, 1, now())
                ON CONFLICT (store_id, master_type) DO UPDATE
                   SET version = pos_master_version.version + 1, updated_at = now()
                RETURNING version
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, masterType.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 버전을 올리지 못했습니다", e);
        }
    }

    @Override
    public List<MasterDispatch> dispatchesOf(String storeId, String deviceType, String deviceNo) {
        String sql = SELECT + " WHERE store_id = ? AND device_type = ? AND device_no = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            return list(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 적용 결과를 읽지 못했습니다", e);
        }
    }

    /**
     * 보냈다는 사실만 적습니다 — {@code applied_version} 은 건드리지
     * 않습니다. 이 메서드에 그 컬럼이 없는 것이 §3.6 의 규칙 그 자체
     * 입니다: 보낸 것과 적용된 것은 다른 일입니다.
     */
    @Override
    public void markSent(String storeId, String deviceType, String deviceNo, MasterType masterType) {
        String sql = """
                INSERT INTO pos_master_dispatch
                  (store_id, device_type, device_no, master_type, status, attempts, sent_at)
                VALUES (?, ?, ?, ?, 'SENT', 1, now())
                ON CONFLICT (store_id, device_type, device_no, master_type) DO UPDATE
                   SET status = 'SENT',
                       attempts = pos_master_dispatch.attempts + 1,
                       sent_at = now()
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, masterType.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 전송 기록을 남기지 못했습니다", e);
        }
    }

    @Override
    public void ack(String storeId, String deviceType, String deviceNo, MasterType masterType,
                    long version, boolean applied, String message) {
        // 실패했을 때 applied_version 을 그대로 두는 것이 중요합니다.
        // 옛 버전으로 돌아간 것이 아니라 새 버전을 못 받은 것이라,
        // 장비가 실제로 들고 있는 것은 여전히 그 전 버전입니다.
        String sql = """
                INSERT INTO pos_master_dispatch
                  (store_id, device_type, device_no, master_type,
                   applied_version, status, attempts, last_error, sent_at, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, now(), ?)
                ON CONFLICT (store_id, device_type, device_no, master_type) DO UPDATE
                   SET applied_version = CASE WHEN EXCLUDED.status = 'APPLIED'
                                              THEN EXCLUDED.applied_version
                                              ELSE pos_master_dispatch.applied_version END,
                       status = EXCLUDED.status,
                       last_error = EXCLUDED.last_error,
                       applied_at = CASE WHEN EXCLUDED.status = 'APPLIED'
                                         THEN now() ELSE pos_master_dispatch.applied_at END
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, masterType.name());
            if (applied) {
                ps.setLong(5, version);
            } else {
                ps.setNull(5, Types.BIGINT);
            }
            ps.setString(6, applied ? "APPLIED" : "FAILED");
            ps.setString(7, applied ? null : message);
            if (applied) {
                ps.setTimestamp(8, new java.sql.Timestamp(System.currentTimeMillis()));
            } else {
                ps.setNull(8, Types.TIMESTAMP);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 적용 결과를 남기지 못했습니다", e);
        }
    }

    @Override
    public List<MasterDispatch> unfinished(String storeId) {
        String sql = SELECT + " WHERE store_id = ? AND status <> 'APPLIED' ORDER BY device_type, device_no, master_type";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            return list(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("재전송 대상을 읽지 못했습니다", e);
        }
    }

    @Override
    public Optional<MasterDispatch> find(String storeId, String deviceType, String deviceNo,
                                         MasterType masterType) {
        String sql = SELECT + " WHERE store_id = ? AND device_type = ? AND device_no = ? AND master_type = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, masterType.name());
            List<MasterDispatch> found = list(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 적용 결과를 읽지 못했습니다", e);
        }
    }

    @Override
    public boolean clearFailure(String storeId, String deviceType, String deviceNo,
                                MasterType masterType) {
        // status 를 SENT 로 되돌리지 않습니다 — 보낸 적이 없을 수도
        // 있고, 그러면 "보냈는데 답이 없음"이라는 거짓말이 됩니다.
        // 사유만 지우고 상태는 그대로 둡니다.
        String sql = """
                UPDATE pos_master_dispatch
                   SET last_error = NULL
                 WHERE store_id = ? AND device_type = ? AND device_no = ?
                   AND master_type = ? AND status = 'FAILED'
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, masterType.name());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("마스터 재처리를 걸지 못했습니다", e);
        }
    }

    private static final String SELECT = """
            SELECT store_id, device_type, device_no, master_type, applied_version,
                   status, attempts, last_error, sent_at, applied_at
              FROM pos_master_dispatch
            """;

    private static List<MasterDispatch> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<MasterDispatch> rows = new ArrayList<>();
            while (rs.next()) {
                Optional<MasterType> type = MasterType.of(rs.getString("master_type"));
                if (type.isEmpty()) {
                    continue;
                }
                long raw = rs.getLong("applied_version");
                // wasNull() 은 **바로 다음 줄**에서 읽어야 합니다. 생성자
                // 인자 안에 두면 그 사이의 getString 들이 먼저 평가돼,
                // 한 번도 적용 안 한 장비가 0번 버전을 들고 있는 것으로
                // 보입니다.
                Long appliedVersion = rs.wasNull() ? null : raw;
                rows.add(new MasterDispatch(
                        rs.getString("store_id"), rs.getString("device_type"), rs.getString("device_no"),
                        type.get(),
                        appliedVersion,
                        MasterDispatch.Status.valueOf(rs.getString("status")),
                        rs.getInt("attempts"), rs.getString("last_error"),
                        instant(rs, "sent_at"), instant(rs, "applied_at")));
            }
            return rows;
        }
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
