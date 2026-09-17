package com.sunmoon.platform.infrastructure.pos;

import com.sunmoon.platform.domain.pos.Notice;
import com.sunmoon.platform.domain.pos.NoticeRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 공지·긴급 메시지의 JDBC 저장소 (§3.5). */
public final class JdbcNoticeRepository implements NoticeRepository {

    private final DataSource dataSource;

    public JdbcNoticeRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public long create(Notice notice) {
        String sql = """
                INSERT INTO pos_notice
                  (store_id, device_type, title, body, urgent, starts_at, ends_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            setNullable(ps, 1, notice.storeId());
            setNullable(ps, 2, notice.deviceType());
            ps.setString(3, notice.title());
            ps.setString(4, notice.body());
            ps.setBoolean(5, notice.urgent());
            ps.setTimestamp(6, Timestamp.from(notice.startsAt()));
            if (notice.endsAt() == null) {
                ps.setNull(7, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(7, Timestamp.from(notice.endsAt()));
            }
            setNullable(ps, 8, notice.createdBy());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("공지를 저장하지 못했습니다", e);
        }
    }

    /**
     * 그 장비에 지금 걸려 있는 것.
     *
     * <p>{@code store_id IS NULL} 을 함께 고르는 것이 "전 매장"의
     * 구현입니다. 등록할 때 매장 수만큼 줄을 복사하는 방식이 아닌
     * 이유: 매장이 하나 늘 때마다 지난 공지가 그 매장에는 없게 됩니다.
     */
    @Override
    public List<Notice> activeFor(String storeId, String deviceType, Instant when) {
        String sql = SELECT + """
                 WHERE (store_id IS NULL OR store_id = ?)
                   AND (device_type IS NULL OR upper(device_type) = upper(?))
                   AND starts_at <= ?
                   AND (ends_at IS NULL OR ends_at > ?)
                 ORDER BY urgent DESC, starts_at DESC
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType == null ? "" : deviceType);
            ps.setTimestamp(3, Timestamp.from(when));
            ps.setTimestamp(4, Timestamp.from(when));
            return notices(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("공지를 읽지 못했습니다", e);
        }
    }

    @Override
    public List<Notice> all(String storeId, int limit) {
        String sql = SELECT + """
                 WHERE (? IS NULL OR store_id IS NULL OR store_id = ?)
                 ORDER BY starts_at DESC
                 LIMIT ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            setNullable(ps, 1, storeId);
            ps.setString(2, storeId);
            ps.setInt(3, limit);
            return notices(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("공지 목록을 읽지 못했습니다", e);
        }
    }

    @Override
    public Optional<Notice> find(long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setLong(1, id);
            List<Notice> found = notices(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException e) {
            throw new IllegalStateException("공지를 읽지 못했습니다", e);
        }
    }

    @Override
    public void acknowledge(long noticeId, String storeId, String deviceType, String deviceNo) {
        // 두 번 확인해도 한 줄입니다. 처음 본 시각이 남는 쪽이 맞습니다 —
        // 마지막으로 본 시각은 아무 질문에도 답하지 않습니다.
        String sql = """
                INSERT INTO pos_notice_receipt (notice_id, store_id, device_type, device_no)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (notice_id, store_id, device_type, device_no) DO NOTHING
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, noticeId);
            ps.setString(2, storeId);
            ps.setString(3, deviceType);
            ps.setString(4, deviceNo);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("공지 확인을 저장하지 못했습니다", e);
        }
    }

    @Override
    public List<Receipt> receiptsOf(long noticeId) {
        String sql = """
                SELECT store_id, device_type, device_no, acknowledged_at
                  FROM pos_notice_receipt
                 WHERE notice_id = ?
                 ORDER BY acknowledged_at
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, noticeId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Receipt> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new Receipt(rs.getString("store_id"), rs.getString("device_type"),
                            rs.getString("device_no"), rs.getTimestamp("acknowledged_at").toInstant()));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("공지 확인 목록을 읽지 못했습니다", e);
        }
    }

    private static final String SELECT = """
            SELECT id, store_id, device_type, title, body, urgent,
                   starts_at, ends_at, created_by, created_at
              FROM pos_notice
            """;

    private static List<Notice> notices(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Notice> rows = new ArrayList<>();
            while (rs.next()) {
                Timestamp endsAt = rs.getTimestamp("ends_at");
                rows.add(new Notice(
                        rs.getLong("id"), rs.getString("store_id"), rs.getString("device_type"),
                        rs.getString("title"), rs.getString("body"), rs.getBoolean("urgent"),
                        rs.getTimestamp("starts_at").toInstant(),
                        endsAt == null ? null : endsAt.toInstant(),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant()));
            }
            return rows;
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
