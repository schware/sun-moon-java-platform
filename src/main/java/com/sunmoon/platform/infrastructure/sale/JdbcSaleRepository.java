package com.sunmoon.platform.infrastructure.sale;

import com.sunmoon.platform.domain.sale.DecomposedSale;
import com.sunmoon.platform.domain.sale.SaleLog;
import com.sunmoon.platform.domain.sale.SaleRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 매출 원본과 분해 결과의 JDBC 저장소.
 *
 * <p>이 runtime 에는 Spring 이 없습니다 (docs/adr/0002) — 트랜잭션도
 * 어노테이션이 아니라 손으로 엽니다. 대신 경계가 눈에 보입니다:
 * {@link #store}는 지우기·Header·Detail·Payment·ETC·상태 변경이
 * <b>한 덩어리</b>여야 하고, 그게 코드에 그대로 적혀 있습니다.
 */
public final class JdbcSaleRepository implements SaleRepository {

    private final DataSource dataSource;

    public JdbcSaleRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * {@code ON CONFLICT DO NOTHING} 이라 같은 거래가 두 번 와도 행이
     * 하나입니다. 돌아오는 것이 비어 있으면 <b>이미 받은 거래</b>이고,
     * 그건 실패가 아니라 정상적인 답입니다 — POS는 그걸 보고 안심하고
     * 자기 쪽에서 지웁니다.
     */
    @Override
    public Optional<Long> receive(SaleLog log) {
        String sql = """
                INSERT INTO tr_sale_log
                  (store_id, pos_no, business_date, tran_no, content_type, payload)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (business_date, store_id, pos_no, tran_no) DO NOTHING
                RETURNING id
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, log.storeId());
            ps.setString(2, log.posNo());
            ps.setDate(3, Date.valueOf(log.businessDate()));
            ps.setString(4, log.tranNo());
            ps.setString(5, log.contentType());
            ps.setBytes(6, log.payload());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("매출 원본을 저장하지 못했습니다", e);
        }
    }

    @Override
    public List<SaleLog> pending(int limit) {
        String sql = """
                SELECT id, store_id, pos_no, business_date, tran_no, content_type, payload,
                       status, error, attempts, received_at, decomposed_at
                  FROM tr_sale_log
                 WHERE status <> 'DECOMPOSED'
                 ORDER BY received_at
                 LIMIT ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SaleLog> logs = new ArrayList<>();
                while (rs.next()) {
                    logs.add(new SaleLog(
                            rs.getLong("id"), rs.getString("store_id"), rs.getString("pos_no"),
                            rs.getObject("business_date", LocalDate.class), rs.getString("tran_no"),
                            rs.getString("content_type"), rs.getBytes("payload"),
                            SaleLog.Status.valueOf(rs.getString("status")), rs.getString("error"),
                            rs.getInt("attempts"),
                            instant(rs.getTimestamp("received_at")),
                            instant(rs.getTimestamp("decomposed_at"))));
                }
                return logs;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("분해 대기 목록을 읽지 못했습니다", e);
        }
    }

    @Override
    public void store(long logId, DecomposedSale sale) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 다시 돌리는 경우를 위해 먼저 지웁니다. Header 를 지우면
                // Detail/Payment/ETC 가 CASCADE 로 함께 갑니다.
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM tr_sale_header WHERE log_id = ?")) {
                    ps.setLong(1, logId);
                    ps.executeUpdate();
                }

                long headerId = insertHeader(c, logId, sale.header());
                insertDetails(c, logId, headerId, sale.details());
                insertPayments(c, logId, headerId, sale.payments());
                insertEtc(c, logId, headerId, sale.etc());

                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE tr_sale_log
                           SET status = 'DECOMPOSED', error = NULL,
                               attempts = attempts + 1, decomposed_at = now()
                         WHERE id = ?
                        """)) {
                    ps.setLong(1, logId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("분해 결과를 저장하지 못했습니다: log " + logId, e);
        }
    }

    private long insertHeader(Connection c, long logId, DecomposedSale.Header h) throws SQLException {
        String sql = """
                INSERT INTO tr_sale_header
                  (log_id, store_id, pos_no, business_date, tran_no, tran_type,
                   total_amount, discount_amount, tax_amount, sold_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, logId);
            ps.setString(2, h.storeId());
            ps.setString(3, h.posNo());
            ps.setDate(4, Date.valueOf(h.businessDate()));
            ps.setString(5, h.tranNo());
            ps.setString(6, h.tranType());
            ps.setBigDecimal(7, h.totalAmount());
            ps.setBigDecimal(8, h.discountAmount());
            ps.setBigDecimal(9, h.taxAmount());
            ps.setTimestamp(10, h.soldAt() == null ? null : Timestamp.from(h.soldAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        }
    }

    private void insertDetails(Connection c, long logId, long headerId,
                               List<DecomposedSale.Detail> details) throws SQLException {
        String sql = """
                INSERT INTO tr_sale_detail
                  (log_id, header_id, detail_seq, menu_code, menu_name, quantity,
                   unit_price, detail_amount, discount_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (DecomposedSale.Detail d : details) {
                ps.setLong(1, logId);
                ps.setLong(2, headerId);
                ps.setInt(3, d.detailSeq());
                ps.setString(4, d.menuCode());
                ps.setString(5, d.menuName());
                ps.setInt(6, d.quantity());
                ps.setBigDecimal(7, d.unitPrice());
                ps.setBigDecimal(8, d.detailAmount());
                ps.setBigDecimal(9, d.discountAmount());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertPayments(Connection c, long logId, long headerId,
                                List<DecomposedSale.Payment> payments) throws SQLException {
        if (payments.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO tr_sale_payment
                  (log_id, header_id, payment_seq, payment_type, payment_amount,
                   approval_no, card_no_masked)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (DecomposedSale.Payment p : payments) {
                ps.setLong(1, logId);
                ps.setLong(2, headerId);
                ps.setInt(3, p.paymentSeq());
                ps.setString(4, p.paymentType());
                ps.setBigDecimal(5, p.paymentAmount());
                ps.setString(6, p.approvalNo());
                ps.setString(7, p.cardNoMasked());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertEtc(Connection c, long logId, long headerId,
                           List<DecomposedSale.Etc> etc) throws SQLException {
        if (etc.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO tr_sale_etc (log_id, header_id, etc_seq, etc_key, etc_value)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (DecomposedSale.Etc e : etc) {
                ps.setLong(1, logId);
                ps.setLong(2, headerId);
                ps.setInt(3, e.etcSeq());
                ps.setString(4, e.key());
                ps.setString(5, e.value());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public List<SaleLog> failed(String storeId) {
        String sql = """
                SELECT id, store_id, pos_no, business_date, tran_no, content_type, payload,
                       status, error, attempts, received_at, decomposed_at
                  FROM tr_sale_log
                 WHERE status = 'FAILED' AND (? IS NULL OR store_id = ?)
                 ORDER BY received_at
                 LIMIT 200
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, storeId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SaleLog> logs = new ArrayList<>();
                while (rs.next()) {
                    logs.add(new SaleLog(
                            rs.getLong("id"), rs.getString("store_id"), rs.getString("pos_no"),
                            rs.getObject("business_date", LocalDate.class), rs.getString("tran_no"),
                            rs.getString("content_type"), rs.getBytes("payload"),
                            SaleLog.Status.valueOf(rs.getString("status")), rs.getString("error"),
                            rs.getInt("attempts"),
                            instant(rs.getTimestamp("received_at")),
                            instant(rs.getTimestamp("decomposed_at"))));
                }
                return logs;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("실패한 매출 목록을 읽지 못했습니다", e);
        }
    }

    /**
     * FAILED 를 RECEIVED 로 되돌립니다 — 배치가 다음 회차에 집어 갑니다.
     *
     * <p>{@code status = 'FAILED'} 조건이 붙어 있는 것이 중요합니다.
     * 이미 분해된 건을 되돌리면 멀쩡한 매출이 다시 풀리고, 그 사이에
     * 조회하면 없는 것처럼 보입니다.
     */
    @Override
    public boolean markForRetry(long logId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tr_sale_log SET status = 'RECEIVED' WHERE id = ? AND status = 'FAILED'")) {
            ps.setLong(1, logId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("매출 재처리를 걸지 못했습니다", e);
        }
    }

    @Override
    public void markFailed(long logId, String error) {
        String sql = """
                UPDATE tr_sale_log
                   SET status = 'FAILED', error = ?, attempts = attempts + 1
                 WHERE id = ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            // 사유를 그대로 남깁니다. 운영자가 무엇이 빠졌는지 보고
            // 재처리할 수 있어야 합니다 (§3.2).
            ps.setString(1, error);
            ps.setLong(2, logId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("분해 실패를 기록하지 못했습니다: log " + logId, e);
        }
    }

    /**
     * 원본 건수와 분해 결과의 금액을 나란히 셉니다 (§3.2 정합성).
     *
     * <p>두 숫자를 한 쿼리에서 뽑는 이유: 따로 물으면 그 사이에 배치가
     * 돌아 서로 다른 순간을 보게 되고, 그러면 "안 맞는다"가 사실이
     * 아닐 수 있습니다.
     */
    @Override
    public Totals totals(String storeId, LocalDate businessDate) {
        String sql = """
                SELECT count(*) AS logs,
                       count(*) FILTER (WHERE l.status = 'DECOMPOSED') AS decomposed,
                       count(*) FILTER (WHERE l.status = 'FAILED') AS failed,
                       coalesce(sum(h.total_amount), 0) AS amount
                  FROM tr_sale_log l
                  LEFT JOIN tr_sale_header h ON h.log_id = l.id
                 WHERE l.business_date = ?
                   AND (? IS NULL OR l.store_id = ?)
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(businessDate));
            ps.setString(2, storeId);
            ps.setString(3, storeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Totals(rs.getLong("logs"), rs.getLong("decomposed"),
                        rs.getLong("failed"), rs.getBigDecimal("amount"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("매출 집계를 읽지 못했습니다", e);
        }
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
