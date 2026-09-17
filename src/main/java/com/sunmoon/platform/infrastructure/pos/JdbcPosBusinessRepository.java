package com.sunmoon.platform.infrastructure.pos;

import com.sunmoon.platform.domain.pos.BusinessEvent;
import com.sunmoon.platform.domain.pos.BusinessState;
import com.sunmoon.platform.domain.pos.BusinessStatus;
import com.sunmoon.platform.domain.pos.IrtResult;
import com.sunmoon.platform.domain.pos.PosBusinessIrt;
import com.sunmoon.platform.domain.pos.PosBusinessRepository;

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

/**
 * 개점·마감의 JDBC 저장소 (§3.4).
 *
 * <p>Spring 이 없는 runtime 이라 트랜잭션을 손으로 엽니다
 * (docs/adr/0002). 대신 경계가 눈에 보입니다 — {@link #apply} 는
 * 현재 상태와 이력이 <b>한 덩어리</b>여야 하고, 그게 코드에 그대로
 * 적혀 있습니다.
 */
public final class JdbcPosBusinessRepository implements PosBusinessRepository {

    /** Postgres 의 unique_violation. 유일 제약이 막았다는 뜻이고, 여기서는 오류가 아닙니다. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public JdbcPosBusinessRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<BusinessState> find(String storeId, String posNo, LocalDate businessDate) {
        String sql = """
                SELECT store_id, pos_no, business_date, status, last_event, open_count,
                       event_at, received_at
                  FROM pos_business_state
                 WHERE store_id = ? AND pos_no = ? AND business_date = ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, posNo);
            ps.setDate(3, Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(state(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("개점 상태를 읽지 못했습니다", e);
        }
    }

    @Override
    public int apply(BusinessState state, BusinessEvent event, IrtResult result, String message,
                     Instant eventAt, String irtId) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // MERGE (문서 §3.4). 판매일자 + 매장 + POS 하나에 한 줄이고,
                // 재개점은 같은 줄을 다시 덮습니다 — 새 줄이 생기면 "지금
                // 열려 있나"에 답이 둘이 됩니다.
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO pos_business_state
                          (store_id, pos_no, business_date, status, last_event, open_count,
                           event_at, received_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                        ON CONFLICT (store_id, pos_no, business_date) DO UPDATE
                           SET status = EXCLUDED.status,
                               last_event = EXCLUDED.last_event,
                               open_count = EXCLUDED.open_count,
                               event_at = EXCLUDED.event_at,
                               received_at = EXCLUDED.received_at,
                               updated_at = now()
                        """)) {
                    ps.setString(1, state.storeId());
                    ps.setString(2, state.posNo());
                    ps.setDate(3, Date.valueOf(state.businessDate()));
                    ps.setString(4, state.status().name());
                    ps.setString(5, state.lastEvent().name());
                    ps.setInt(6, state.openCount());
                    ps.setTimestamp(7, Timestamp.from(state.eventAt()));
                    ps.executeUpdate();
                }

                int seq = insertHistory(c, state.storeId(), state.posNo(), state.businessDate(),
                        event, result, message, eventAt, irtId);
                c.commit();
                return seq;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("개점 상태를 저장하지 못했습니다", e);
        }
    }

    @Override
    public int record(PosBusinessIrt irt, IrtResult result, String message) {
        try (Connection c = dataSource.getConnection()) {
            return insertHistory(c, irt.storeId(), irt.posNo(), irt.businessDate(),
                    irt.event(), result, message, irt.eventAt(), irt.irtId());
        } catch (SQLException e) {
            throw new IllegalStateException("개점 이력을 저장하지 못했습니다", e);
        }
    }

    /**
     * 이력 한 줄. 처리 순번은 같은 키 안에서 1부터입니다.
     *
     * <p>{@code MAX(seq)+1} 을 같은 문장 안에서 고르고, 부딪히면 다시
     * 시도합니다. 같은 POS의 IRT 가 동시에 둘 들어오는 일은 드물지만
     * 불가능하지 않고(재시도와 원래 전문이 겹치는 순간), 그때 순번이
     * 겹치면 유일 제약이 막습니다 — 애플리케이션에서 미리 세어 두는
     * 방식은 그 확인과 쓰기 사이에 창이 남습니다.
     *
     * <p>같은 IRT 가 두 번 오면 이력은 하나입니다. 먼저 찾아보는 것은
     * 흔한 경우를 빨리 끝내기 위해서이고, <b>진짜 방패는 인덱스</b>
     * 입니다 — 그래서 아래에서 제약 위반도 함께 받습니다.
     */
    private int insertHistory(Connection c, String storeId, String posNo, LocalDate businessDate,
                              BusinessEvent event, IrtResult result, String message,
                              Instant eventAt, String irtId) throws SQLException {
        if (irtId != null) {
            Optional<Integer> already = seqOfIrt(c, irtId);
            if (already.isPresent()) {
                return already.get();
            }
        }

        String sql = """
                INSERT INTO pos_business_hist
                  (store_id, pos_no, business_date, seq, event, result, message, event_at, irt_id)
                SELECT ?, ?, ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ?
                  FROM pos_business_hist
                 WHERE store_id = ? AND pos_no = ? AND business_date = ?
                RETURNING seq
                """;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, storeId);
                ps.setString(2, posNo);
                ps.setDate(3, Date.valueOf(businessDate));
                ps.setString(4, event.name());
                ps.setString(5, result.name());
                ps.setString(6, message);
                ps.setTimestamp(7, Timestamp.from(eventAt));
                ps.setString(8, irtId);
                ps.setString(9, storeId);
                ps.setString(10, posNo);
                ps.setDate(11, Date.valueOf(businessDate));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                if (!UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    throw e;
                }
                // 같은 IRT 였다면 이미 적힌 순번이 답입니다.
                if (irtId != null) {
                    Optional<Integer> already = seqOfIrt(c, irtId);
                    if (already.isPresent()) {
                        return already.get();
                    }
                }
                // 순번이 겹친 것이니 다시 셉니다.
            }
        }
        throw new IllegalStateException("이력 순번을 정하지 못했습니다: " + storeId + "/" + posNo);
    }

    private Optional<Integer> seqOfIrt(Connection c, String irtId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT seq FROM pos_business_hist WHERE irt_id = ?")) {
            ps.setString(1, irtId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
            }
        }
    }

    @Override
    public List<BusinessState> statesOf(String storeId, LocalDate businessDate) {
        String sql = """
                SELECT store_id, pos_no, business_date, status, last_event, open_count,
                       event_at, received_at
                  FROM pos_business_state
                 WHERE store_id = ? AND business_date = ?
                 ORDER BY pos_no
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setDate(2, Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
                List<BusinessState> states = new ArrayList<>();
                while (rs.next()) {
                    states.add(state(rs));
                }
                return states;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("개점 상태 목록을 읽지 못했습니다", e);
        }
    }

    @Override
    public List<BusinessHistoryEntry> historyOf(String storeId, String posNo, LocalDate businessDate) {
        String sql = """
                SELECT seq, event, result, message, event_at, received_at, irt_id
                  FROM pos_business_hist
                 WHERE store_id = ? AND pos_no = ? AND business_date = ?
                 ORDER BY seq
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, posNo);
            ps.setDate(3, Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
                List<BusinessHistoryEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new BusinessHistoryEntry(
                            rs.getInt("seq"),
                            BusinessEvent.valueOf(rs.getString("event")),
                            IrtResult.valueOf(rs.getString("result")),
                            rs.getString("message"),
                            rs.getTimestamp("event_at").toInstant(),
                            rs.getTimestamp("received_at").toInstant(),
                            rs.getString("irt_id")));
                }
                return entries;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("개점 이력을 읽지 못했습니다", e);
        }
    }

    private static BusinessState state(ResultSet rs) throws SQLException {
        return new BusinessState(
                rs.getString("store_id"), rs.getString("pos_no"),
                rs.getObject("business_date", LocalDate.class),
                BusinessStatus.valueOf(rs.getString("status")),
                BusinessEvent.valueOf(rs.getString("last_event")),
                rs.getInt("open_count"),
                rs.getTimestamp("event_at").toInstant(),
                rs.getTimestamp("received_at").toInstant());
    }
}
