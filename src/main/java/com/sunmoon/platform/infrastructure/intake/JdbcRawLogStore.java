package com.sunmoon.platform.infrastructure.intake;

import com.sunmoon.platform.domain.intake.RawLog;
import com.sunmoon.platform.domain.intake.RawLogStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 원본 수명주기의 <b>유일한</b> 구현 — 표 이름과 키 컬럼만 바꿔
 * TR 과 ETC 두 벌로 씁니다.
 *
 * <p>이 runtime 에는 Spring 이 없습니다(docs/adr/0002). 트랜잭션도
 * 어노테이션이 아니라 손으로 열고, 그래서 경계가 코드에 보입니다.
 *
 * <h2>식별자를 문자열로 조립하는 것에 대해</h2>
 *
 * <p>표 이름과 컬럼 이름이 SQL 에 문자열로 들어갑니다. 파라미터로는
 * 식별자를 넘길 수 없어서 다른 방법이 없고, 그래서 <b>규칙을 하나
 * 둡니다</b>:
 *
 * <p><b>값은 생성자에서만 오고, 허용된 목록에 있어야 합니다.</b>
 * 요청에서 온 값은 절대 여기 닿지 않습니다. 기준정보 공통 CRUD 가
 * 같은 규칙을 씁니다(umbrella docs/adr/0014) — 거기서는 정의에서
 * 오고 여기서는 조립 시점에 옵니다. 둘 다 "바깥에서 온 글자가
 * 식별자가 되지 않는다" 입니다.
 *
 * <p>허용 목록을 두는 것은 실수 방지용입니다. 나중에 누군가 설정
 * 파일에서 표 이름을 읽어 오게 고치면, 그 순간 여기서 막힙니다.
 */
public final class JdbcRawLogStore implements RawLogStore {

    /**
     * 이 구현이 다룰 수 있는 표.
     *
     * <p>둘 다 같은 컬럼 모양(원본 + 상태 + attempts + 시각)을 갖고
     * 있어야 합니다. 새 표를 붙이려면 여기에 적고 마이그레이션에서
     * 같은 모양을 만드십시오.
     */
    private static final Set<String> KNOWN_TABLES = Set.of("tr_sale_log", "etc_sale_log");
    private static final Set<String> KNOWN_REF_COLUMNS = Set.of("tran_no", "etc_no");

    private final DataSource dataSource;
    private final String table;
    private final String refColumn;
    private final String what;

    /**
     * @param table     원본 표. 허용 목록에 있어야 합니다.
     * @param refColumn 키의 마지막 컬럼 — TR 은 {@code tran_no}, ETC 는 {@code etc_no}.
     * @param what      오류 문구에 쓰는 이름 — "매출" / "ETC 매출".
     */
    public JdbcRawLogStore(DataSource dataSource, String table, String refColumn, String what) {
        if (!KNOWN_TABLES.contains(table)) {
            throw new IllegalArgumentException("모르는 원본 표입니다: " + table);
        }
        if (!KNOWN_REF_COLUMNS.contains(refColumn)) {
            throw new IllegalArgumentException("모르는 키 컬럼입니다: " + refColumn);
        }
        this.dataSource = dataSource;
        this.table = table;
        this.refColumn = refColumn;
        this.what = what;
    }

    @Override
    public Optional<Long> receive(RawLog log) {
        String sql = "INSERT INTO " + table
                + " (store_id, pos_no, business_date, " + refColumn + ", content_type, payload)"
                + " VALUES (?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (business_date, store_id, pos_no, " + refColumn + ") DO NOTHING"
                + " RETURNING id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, log.storeId());
            ps.setString(2, log.posNo());
            ps.setDate(3, Date.valueOf(log.businessDate()));
            ps.setString(4, log.refNo());
            ps.setString(5, log.contentType());
            ps.setBytes(6, log.payload());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(what + " 원본을 저장하지 못했습니다", e);
        }
    }

    @Override
    public List<RawLog> pending(int limit) {
        String sql = select() + " WHERE status = 'RECEIVED' ORDER BY received_at LIMIT ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(what + " 분해 대기 목록을 읽지 못했습니다", e);
        }
    }

    @Override
    public List<RawLog> failed(String storeId) {
        boolean everyStore = storeId == null || storeId.isBlank();
        // LIMIT 200 은 TR 이 원래 쓰던 값입니다. 공통으로 옮기면서 빠뜨릴
        // 뻔했습니다 — 대량 실패가 난 날 이 목록이 수만 줄이 되면 재처리
        // 화면이 열리지도 않습니다. 200 건이 넘게 실패했다면 한 건씩 볼
        // 일이 아니라 규칙을 고치고 "전부 다시" 를 누를 일입니다.
        String sql = select() + " WHERE status = 'FAILED'"
                + (everyStore ? "" : " AND store_id = ?")
                + " ORDER BY received_at LIMIT 200";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (!everyStore) {
                ps.setString(1, storeId);
            }
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(what + " 실패 목록을 읽지 못했습니다", e);
        }
    }

    @Override
    public void markFailed(long logId, String error) {
        String sql = "UPDATE " + table
                + " SET status = 'FAILED', error = ?, attempts = attempts + 1 WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, error);
            ps.setLong(2, logId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(what + " 실패를 적지 못했습니다", e);
        }
    }

    /**
     * {@code FAILED} 인 것만 되돌립니다. 이미 풀린 건을 되돌리면 멀쩡한
     * 매출이 다시 분해 대상이 되고, 그 사이에 조회한 사람은 없는 매출을
     * 봅니다.
     */
    @Override
    public boolean markForRetry(long logId) {
        String sql = "UPDATE " + table
                + " SET status = 'RECEIVED' WHERE id = ? AND status = 'FAILED'";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, logId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(what + " 재처리를 걸지 못했습니다", e);
        }
    }

    @Override
    public Counts counts(String storeId, LocalDate businessDate) {
        String sql = "SELECT count(*) AS logs,"
                + " count(*) FILTER (WHERE status = 'DECOMPOSED') AS decomposed,"
                + " count(*) FILTER (WHERE status = 'FAILED') AS failed"
                + " FROM " + table + " WHERE store_id = ? AND business_date = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setDate(2, Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Counts(rs.getLong("logs"), rs.getLong("decomposed"), rs.getLong("failed"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(what + " 건수를 세지 못했습니다", e);
        }
    }

    /**
     * 분해가 끝났다고 표시합니다 — <b>부르는 쪽의 트랜잭션 안에서</b>.
     *
     * <p>{@link com.sunmoon.platform.domain.intake.RawLogStore} 에 없는
     * 이유: 이 표시는 결과 행을 넣는 것과 <b>같은 트랜잭션</b>이어야
     * 하고, 그 경계를 아는 것은 결과를 쓰는 쪽뿐입니다. 연결을 받아야
     * 하므로 JDBC 를 아는 이 클래스에만 있습니다.
     */
    public void markDecomposed(Connection c, long logId) throws SQLException {
        String sql = "UPDATE " + table
                + " SET status = 'DECOMPOSED', error = NULL,"
                + "     attempts = attempts + 1, decomposed_at = now()"
                + " WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, logId);
            ps.executeUpdate();
        }
    }

    private String select() {
        return "SELECT id, store_id, pos_no, business_date, " + refColumn + " AS ref_no,"
                + " content_type, payload, status, error, attempts, received_at, decomposed_at"
                + " FROM " + table;
    }

    private static List<RawLog> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<RawLog> logs = new ArrayList<>();
            while (rs.next()) {
                logs.add(new RawLog(
                        rs.getLong("id"), rs.getString("store_id"), rs.getString("pos_no"),
                        rs.getObject("business_date", LocalDate.class), rs.getString("ref_no"),
                        rs.getString("content_type"), rs.getBytes("payload"),
                        RawLog.Status.valueOf(rs.getString("status")), rs.getString("error"),
                        rs.getInt("attempts"),
                        instant(rs.getTimestamp("received_at")),
                        instant(rs.getTimestamp("decomposed_at"))));
            }
            return logs;
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
