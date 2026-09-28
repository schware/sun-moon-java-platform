package com.sunmoon.platform.infrastructure.print;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.print.PrintJob;
import com.sunmoon.platform.domain.print.PrintJobStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** pos_server {@code print_job} (V7). */
public final class JdbcPrintJobStore implements PrintJobStore {

    private static final String COLUMNS = "id, store_id, printer_id, kind, order_id, line_seq, unit_no, unit_total, "
            + "copy_no, original_id, content::text AS content, requested_by, requested_at, status, error, attempts, printed_at";

    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcPrintJobStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<PrintJob> createFirst(String storeId, String printerId, long orderId, List<PrintJob.Unit> units,
                                      String requestedBy) {
        // 이미 있는 단위는 건너뜁니다(유일 제약) — 주문 이벤트가 두 번 와도 라벨은 한 번.
        String sql = "INSERT INTO print_job (store_id, printer_id, kind, order_id, line_seq, unit_no, unit_total, "
                + "copy_no, content, requested_by) VALUES (?, ?, 'LABEL', ?, ?, ?, ?, 0, ?::jsonb, ?) "
                + "ON CONFLICT (order_id, line_seq, unit_no, copy_no) DO NOTHING RETURNING " + COLUMNS;
        List<PrintJob> created = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (PrintJob.Unit unit : units) {
                ps.setString(1, storeId);
                ps.setString(2, printerId);
                ps.setLong(3, orderId);
                ps.setInt(4, unit.lineSeq());
                ps.setInt(5, unit.unitNo());
                ps.setInt(6, unit.unitTotal());
                ps.setString(7, json.writeValueAsString(unit.content()));
                ps.setString(8, requestedBy);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        created.add(read(rs));
                    }
                }
            }
            return created;
        } catch (Exception e) {
            throw new IllegalStateException("라벨 작업을 만들지 못했습니다 (주문 " + orderId + ")", e);
        }
    }

    @Override
    public Optional<PrintJob> find(long id) {
        return query("SELECT " + COLUMNS + " FROM print_job WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public List<PrintJob> list(String storeId, PrintJob.Status status, Long orderId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM print_job WHERE store_id = ?");
        List<Object> args = new ArrayList<>(List.of(storeId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (orderId != null) {
            sql.append(" AND order_id = ?");
            args.add(orderId);
        }
        sql.append(" ORDER BY id LIMIT 500");
        return query(sql.toString(), args.toArray());
    }

    @Override
    public Optional<PrintJob> ack(long id, boolean ok, String error, Instant at) {
        String sql = ok
                ? "UPDATE print_job SET status = 'PRINTED', printed_at = ?, error = NULL, attempts = attempts + 1 "
                        + "WHERE id = ? AND status <> 'PRINTED' RETURNING " + COLUMNS
                : "UPDATE print_job SET status = 'FAILED', error = ?, attempts = attempts + 1 "
                        + "WHERE id = ? AND status <> 'PRINTED' RETURNING " + COLUMNS;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (ok) {
                ps.setTimestamp(1, Timestamp.from(at));
            } else {
                ps.setString(1, error == null ? "알 수 없는 오류" : error);
            }
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : find(id);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("라벨 작업 결과를 적지 못했습니다: " + id, e);
        }
    }

    @Override
    public Optional<PrintJob> reprint(long id, String requestedBy) {
        // 처음 작업(original) 기준으로 copy_no 를 이어 갑니다 — 재출력의 재출력도 같은 줄에.
        String sql = "WITH o AS ("
                + "  SELECT * FROM print_job WHERE id = (SELECT coalesce(original_id, id) FROM print_job WHERE id = ?)"
                + "), n AS ("
                + "  SELECT coalesce(max(p.copy_no), 0) + 1 AS copy_no FROM print_job p, o"
                + "   WHERE p.order_id = o.order_id AND p.line_seq IS NOT DISTINCT FROM o.line_seq"
                + "     AND p.unit_no IS NOT DISTINCT FROM o.unit_no"
                + ") INSERT INTO print_job (store_id, printer_id, kind, order_id, line_seq, unit_no, unit_total,"
                + "                         copy_no, original_id, content, requested_by)"
                + "  SELECT o.store_id, o.printer_id, o.kind, o.order_id, o.line_seq, o.unit_no, o.unit_total,"
                + "         n.copy_no, o.id, jsonb_set(o.content, '{copyNo}', to_jsonb(n.copy_no)), ?"
                + "    FROM o, n RETURNING " + COLUMNS;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, requestedBy);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("라벨을 다시 찍을 작업을 만들지 못했습니다: " + id, e);
        }
    }

    @Override
    public boolean requeue(String storeId, long id) {
        boolean anyStore = storeId == null || storeId.isBlank();
        String sql = "UPDATE print_job SET status = 'QUEUED', error = NULL WHERE id = ? AND status = 'FAILED'"
                + (anyStore ? "" : " AND store_id = ?");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            if (!anyStore) {
                ps.setString(2, storeId);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("라벨 작업을 다시 줄 세우지 못했습니다: " + id, e);
        }
    }

    /** 재처리 화면용 — 매장 무관이면 storeId 비움. */
    public List<PrintJob> failed(String storeId) {
        return storeId == null || storeId.isBlank()
                ? query("SELECT " + COLUMNS + " FROM print_job WHERE status = 'FAILED' ORDER BY id LIMIT 200")
                : query("SELECT " + COLUMNS + " FROM print_job WHERE status = 'FAILED' AND store_id = ? "
                        + "ORDER BY id LIMIT 200", storeId);
    }

    private List<PrintJob> query(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<PrintJob> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(read(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("라벨 작업을 읽지 못했습니다", e);
        }
    }

    private PrintJob read(ResultSet rs) throws SQLException {
        Map<String, Object> content;
        try {
            content = json.readValue(rs.getString("content"), new TypeReference<>() { });
        } catch (Exception e) {
            throw new SQLException("content 가 JSON 이 아닙니다", e);
        }
        return new PrintJob(
                rs.getLong("id"), rs.getString("store_id"), rs.getString("printer_id"), rs.getString("kind"),
                rs.getLong("order_id"), (Integer) rs.getObject("line_seq"), (Integer) rs.getObject("unit_no"),
                (Integer) rs.getObject("unit_total"), rs.getInt("copy_no"),
                (Long) rs.getObject("original_id"), content, rs.getString("requested_by"),
                instant(rs, "requested_at"), PrintJob.Status.valueOf(rs.getString("status")),
                rs.getString("error"), rs.getInt("attempts"), instant(rs, "printed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
