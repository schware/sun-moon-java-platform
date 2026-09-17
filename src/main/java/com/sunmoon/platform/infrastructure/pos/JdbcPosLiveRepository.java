package com.sunmoon.platform.infrastructure.pos;

import com.sunmoon.platform.domain.pos.AxisState;
import com.sunmoon.platform.domain.pos.PosHeartbeat;
import com.sunmoon.platform.domain.pos.PosLiveRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * POS가 보고한 것만 담는 저장소 (§3.5).
 *
 * <p>이력이 없습니다 — 덮어씁니다. Heartbeat 는 "지금 어떤가"에만
 * 쓰이고, 한 시간 전의 Heartbeat 가 답할 질문은 없습니다. 남겨 두면
 * 단말 하나가 하루에 수천 줄을 쌓습니다.
 *
 * <p>정말 이력이 필요한 것은 축이 <b>바뀐 순간</b>인데, 그것은 나중에
 * 상태 변화를 감지해서 따로 적을 자리입니다 — 모든 보고를 남기는 것과
 * 다른 일입니다.
 */
public final class JdbcPosLiveRepository implements PosLiveRepository {

    private final DataSource dataSource;

    public JdbcPosLiveRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void report(PosHeartbeat heartbeat) {
        String sql = """
                INSERT INTO pos_live_report
                  (store_id, pos_no, application_status, device_status, data_status,
                   program_version, db_version, devices, pos_time, clock_drift_seconds, reported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)
                ON CONFLICT (store_id, pos_no) DO UPDATE
                   SET application_status = EXCLUDED.application_status,
                       device_status = EXCLUDED.device_status,
                       data_status = EXCLUDED.data_status,
                       program_version = EXCLUDED.program_version,
                       db_version = EXCLUDED.db_version,
                       devices = EXCLUDED.devices,
                       pos_time = EXCLUDED.pos_time,
                       clock_drift_seconds = EXCLUDED.clock_drift_seconds,
                       reported_at = EXCLUDED.reported_at
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, heartbeat.storeId());
            ps.setString(2, heartbeat.posNo());
            ps.setString(3, heartbeat.application().name());
            ps.setString(4, heartbeat.device().name());
            ps.setString(5, heartbeat.data().name());
            ps.setString(6, heartbeat.programVersion());
            ps.setString(7, heartbeat.dbVersion());
            if (heartbeat.devices() == null) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, heartbeat.devices());
            }
            if (heartbeat.posTime() == null) {
                ps.setNull(9, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(9, Timestamp.from(heartbeat.posTime()));
            }
            if (heartbeat.driftSeconds() == null) {
                ps.setNull(10, Types.INTEGER);
            } else {
                ps.setInt(10, heartbeat.driftSeconds());
            }
            ps.setTimestamp(11, Timestamp.from(heartbeat.reportedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("POS 상태 보고를 저장하지 못했습니다", e);
        }
    }

    @Override
    public Optional<PosHeartbeat> lastReport(String storeId, String posNo) {
        String sql = SELECT + " WHERE store_id = ? AND pos_no = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, posNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(heartbeat(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("POS 상태 보고를 읽지 못했습니다", e);
        }
    }

    @Override
    public List<PosHeartbeat> reportsOf(String storeId) {
        String sql = SELECT + " WHERE store_id = ? ORDER BY pos_no";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PosHeartbeat> reports = new ArrayList<>();
                while (rs.next()) {
                    reports.add(heartbeat(rs));
                }
                return reports;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("POS 상태 보고 목록을 읽지 못했습니다", e);
        }
    }

    private static final String SELECT = """
            SELECT store_id, pos_no, application_status, device_status, data_status,
                   program_version, db_version, devices::text AS devices,
                   pos_time, clock_drift_seconds, reported_at
              FROM pos_live_report
            """;

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** {@code wasNull()} 은 바로 다음 줄에서 읽어야 합니다 — 안 그러면 0과 없음이 뒤섞입니다. */
    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static PosHeartbeat heartbeat(ResultSet rs) throws SQLException {
        return new PosHeartbeat(
                rs.getString("store_id"), rs.getString("pos_no"),
                AxisState.reported(rs.getString("application_status")),
                AxisState.reported(rs.getString("device_status")),
                AxisState.reported(rs.getString("data_status")),
                rs.getString("program_version"), rs.getString("db_version"),
                rs.getString("devices"),
                instant(rs, "pos_time"), integer(rs, "clock_drift_seconds"),
                rs.getTimestamp("reported_at").toInstant());
    }
}
