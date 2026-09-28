package com.sunmoon.platform.infrastructure.print;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 매장의 프린터 — BO 장비 명단(v_bo_devices)에서 (store-app.md §6.3).
 *
 * <p>프린터는 <b>BO 장비</b>입니다: {@code device_type} 이 {@code RECEIPT_PRINTER} /
 * {@code LABEL_PRINTER}, 주소는 {@code ip_address}·{@code port}(기본 9100). 셸은 이 목록에
 * 있는 곳으로만 찍습니다 — 화면이 준 주소로 main 이 아무 데나 연결하지 않게.
 */
public final class BoPrinters {

    public static final String RECEIPT = "RECEIPT_PRINTER";
    public static final String LABEL = "LABEL_PRINTER";

    public record Printer(String deviceId, String storeId, String type, String name, String ip, int port) {
    }

    private final DataSource dataSource;

    public BoPrinters(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<Printer> of(String storeId) {
        String sql = "SELECT device_id, store_id, device_type, name, ip_address, port FROM v_bo_devices "
                + "WHERE active AND store_id = ? AND upper(device_type) IN ('RECEIPT_PRINTER', 'LABEL_PRINTER') "
                + "ORDER BY device_type, device_id";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            List<Printer> printers = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer port = (Integer) rs.getObject("port");
                    printers.add(new Printer(rs.getString("device_id"), rs.getString("store_id"),
                            rs.getString("device_type").toUpperCase(java.util.Locale.ROOT), rs.getString("name"),
                            rs.getString("ip_address"), port == null ? 9100 : port));
                }
            }
            return printers;
        } catch (Exception e) {
            throw new IllegalStateException("BO 프린터 명단을 읽지 못했습니다 (v_bo_devices 에 ip_address·port 가 있는지)", e);
        }
    }

    /** 그 매장의 라벨 프린터. 여럿이면 첫째 — 한 매장에 라벨 프린터는 보통 하나입니다. */
    public Optional<Printer> labelPrinter(String storeId) {
        return of(storeId).stream().filter(p -> LABEL.equals(p.type())).findFirst();
    }
}
