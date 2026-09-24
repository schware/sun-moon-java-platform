package com.sunmoon.platform.infrastructure.etc;

import com.sunmoon.platform.domain.etc.EtcEntry;
import com.sunmoon.platform.domain.etc.JsonEtcDecomposer;
import com.sunmoon.platform.domain.intake.IntakeHandler;
import com.sunmoon.platform.domain.intake.RawLog;
import com.sunmoon.platform.infrastructure.intake.JdbcRawLogStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * ETC 원본 하나를 풀어 {@code etc_sale_detail} 에 넣고 끝났다고
 * 표시합니다 (업무 정의서 §3.3).
 *
 * <p><b>한 트랜잭션입니다.</b> 상세만 들어가고 원본이 아직
 * {@code RECEIVED} 면 다음 배치가 같은 것을 또 풀고, 반대면 상세 없는
 * {@code DECOMPOSED} 가 남습니다. TR 쪽의 {@code JdbcSaleRepository.store}
 * 가 같은 이유로 같은 모양입니다.
 *
 * <p>다시 돌리는 경우를 위해 <b>먼저 지웁니다.</b> 재처리로 되돌린
 * 건은 예전 상세를 갖고 있고, 안 지우면 두 벌이 쌓입니다.
 */
public final class JdbcEtcSaleHandler implements IntakeHandler {

    private final DataSource dataSource;
    private final JdbcRawLogStore store;
    private final JsonEtcDecomposer decomposer;

    public JdbcEtcSaleHandler(DataSource dataSource, JdbcRawLogStore store,
                              JsonEtcDecomposer decomposer) {
        this.dataSource = dataSource;
        this.store = store;
        this.decomposer = decomposer;
    }

    @Override
    public void handle(RawLog raw) {
        // 분해가 먼저입니다. 원본이 잘못됐으면 IntakeException 이 나고,
        // 그때는 데이터베이스를 건드리지 않은 채로 배치가 FAILED 로
        // 넘깁니다 — 열 필요 없는 트랜잭션을 열지 않습니다.
        List<EtcEntry> entries = decomposer.decompose(raw.payload());

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM etc_sale_detail WHERE log_id = ?")) {
                    ps.setLong(1, raw.id());
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO etc_sale_detail
                          (log_id, store_id, pos_no, business_date, etc_no,
                           detail_seq, name, value)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (EtcEntry entry : entries) {
                        ps.setLong(1, raw.id());
                        ps.setString(2, raw.storeId());
                        ps.setString(3, raw.posNo());
                        ps.setDate(4, Date.valueOf(raw.businessDate()));
                        ps.setString(5, raw.refNo());
                        ps.setInt(6, entry.seq());
                        ps.setString(7, entry.name());
                        ps.setString(8, entry.value());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                store.markDecomposed(c, raw.id());
                c.commit();
            } catch (SQLException | RuntimeException failed) {
                c.rollback();
                throw failed;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            // **IntakeException 으로 바꾸지 않습니다.** 원본이 잘못된
            // 것이 아니라 저장이 안 된 것이고, 배치는 그 둘을 다르게
            // 다뤄야 합니다 — 여기서 바꿔 던지면 멀쩡한 원본이 FAILED
            // 로 낙인찍혀 사람 손을 기다리게 됩니다.
            throw new IllegalStateException("ETC 매출 상세를 저장하지 못했습니다", e);
        }
    }
}
