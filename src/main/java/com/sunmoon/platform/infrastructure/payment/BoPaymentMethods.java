package com.sunmoon.platform.infrastructure.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 결제수단이 BO 에서 켜져 있나 — KIOSK 주문 (store-app.md §9). 앱 주문(APP-SERVER
 * {@code JdbcAcceptedMethods})과 <b>같은 규칙</b>입니다.
 *
 * <ul>
 *   <li>켜져 있음 → ACCEPTED</li>
 *   <li>꺼져 있거나 목록에 없음 → DISABLED ("브랜드가 받기로 한 것" 의 표라 빈 칸은 안 받음)</li>
 *   <li>못 읽음(뷰 없음·BO 못 닿음) → UNKNOWN — <b>막지 않고</b> 경고. 기준정보를 읽는 길 하나가
 *       장사를 멈추게 하면 안 됩니다</li>
 * </ul>
 */
public final class BoPaymentMethods {

    public enum Acceptance { ACCEPTED, DISABLED, UNKNOWN }

    private static final Logger log = LoggerFactory.getLogger(BoPaymentMethods.class);

    private final DataSource dataSource;

    public BoPaymentMethods(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Acceptance of(String methodCode) {
        if (dataSource == null) {
            return Acceptance.UNKNOWN;
        }
        String sql = "SELECT active FROM v_bo_payment_method WHERE pay_method_code = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, methodCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1) ? Acceptance.ACCEPTED : Acceptance.DISABLED;
            }
        } catch (Exception unreadable) {
            log.warn("결제수단 목록을 읽지 못했습니다 — {} 을 받고 진행합니다 (v_bo_payment_method 확인)",
                    methodCode, unreadable);
            return Acceptance.UNKNOWN;
        }
    }
}
