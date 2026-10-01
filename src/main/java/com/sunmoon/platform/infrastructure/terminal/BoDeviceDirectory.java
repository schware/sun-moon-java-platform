package com.sunmoon.platform.infrastructure.terminal;

import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 단말이 BO에 등록된 장비인가 (2026-09-16 이후 미뤄 뒀던 자리).
 *
 * <p>지금까지 이 서버는 id의 <b>모양</b>만 봤습니다. 형식만 맞으면
 * 아무 단말이나 붙었고, 이 서버는 공인 IP로 열려 있습니다. BO의 장비
 * 화면이 그 명단을 이미 갖고 있는데도 물어볼 길이 없었습니다 — BO의
 * API는 운영자 세션을 요구하고, 서비스 사이의 자격 증명은 정한 적이
 * 없습니다.
 *
 * <p><b>그래서 API가 아니라 뷰로 읽습니다</b> (umbrella docs/adr/0008).
 * APP-SERVER가 BO 마스터를 읽는 방법과 같습니다: postgres_fdw 로
 * {@code bo_service.devices} 를 이 데이터베이스에 {@code v_bo_devices}
 * 로 걸어 두고 읽기만 합니다. 세션도, 공유 비밀도 필요 없고, 권한은
 * 데이터베이스가 읽기 전용으로 잡아 줍니다.
 *
 * <h2>BO가 죽어도 단말은 붙어야 합니다</h2>
 *
 * <p>이것이 이 클래스의 핵심 판단입니다. 명단을 <b>붙을 때마다 읽지
 * 않고</b> 주기적으로 받아 들고 있습니다. 이유:
 *
 * <ul>
 *   <li>BO는 재배포마다 내려갑니다. 그때 전 매장의 단말이 끊기면
 *       기준정보를 고치는 일이 장사를 멈추게 합니다.
 *   <li>명단은 거의 안 바뀝니다. 장비를 새로 놓는 것은 드문 일이고,
 *       그때 1분 늦는 것은 아무 문제가 아닙니다.
 *   <li>연결 시도마다 데이터베이스를 넘어가면, 재접속이 몰리는 순간
 *       (서버 재시작 직후)에 가장 느려집니다.
 * </ul>
 *
 * <p>그리고 <b>한 번도 못 읽었으면 막지 않습니다.</b> 명단을 모르는
 * 것과 명단에 없는 것은 다른 일입니다. 모르는 채로 막으면 fdw 설정이
 * 아직 안 된 서버에서 모든 단말이 조용히 못 붙고, 그 증상은
 * "이 단말은 등록되지 않았습니다"로 보여서 원인을 엉뚱한 데서 찾게
 * 됩니다. 대신 <b>크게 로그를 남깁니다</b>.
 */
public final class BoDeviceDirectory implements DeviceDirectory {

    private static final Logger log = LoggerFactory.getLogger(BoDeviceDirectory.class);

    /**
     * 명단을 다시 읽는 주기. 장비가 새로 놓이는 빈도에 맞춘 값이지
     * 부하에 맞춘 값이 아닙니다 — 표가 수십 줄입니다.
     */
    private static final Duration REFRESH = Duration.ofMinutes(1);

    private final DataSource dataSource;
    private final DeviceDirectory fallback;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.never());

    /**
     * @param fallback 명단을 한 번도 못 읽었을 때 쓸 판단. 보통
     *                 {@code AcceptKnownFormatDirectory} 입니다.
     */
    public BoDeviceDirectory(DataSource dataSource, DeviceDirectory fallback) {
        this.dataSource = dataSource;
        this.fallback = fallback;
    }

    /**
     * 읽어 둔 명단과, 그걸 읽은 때. {@code kdsRoles} 는 KDS 줄의 (매장|장비) → MAIN/SUB
     * — BO 장비 화면의 "KDS 역할" 입니다. 역할이 빈 줄은 넣지 않습니다(모름).
     */
    private record Snapshot(Set<String> keys, Map<String, String> kdsRoles, Instant readAt, boolean everRead) {
        static Snapshot never() {
            return new Snapshot(Set.of(), Map.of(), Instant.EPOCH, false);
        }

        boolean stale(Instant now) {
            return !everRead || readAt.plus(REFRESH).isBefore(now);
        }
    }

    @Override
    public boolean isRegistered(String storeId, String deviceId, String type) {
        // 모양이 아닌 것은 명단을 보기 전에 거릅니다. 명단에 있을 수
        // 없는 값으로 조회를 만들 이유가 없습니다.
        if (!fallback.isRegistered(storeId, deviceId, type)) {
            return false;
        }

        Snapshot current = refreshIfStale();
        if (!current.everRead()) {
            // 명단을 모르는 것과 명단에 없는 것은 다릅니다.
            log.warn("BO 장비 명단을 아직 못 읽었습니다 — 모양만 보고 받습니다 ({}/{}/{}). "
                    + "v_bo_devices 뷰가 걸려 있는지 확인하세요", storeId, deviceId, type);
            return true;
        }

        if (current.keys().contains(key(storeId, deviceId, type))) {
            return true;
        }
        // 매장이 안 적힌 옛 장비 줄. BO에 store_id 가 생기기 전에
        // 등록된 것들이라 비어 있고, 그 줄들까지 막으면 지금 붙어
        // 있는 단말이 다음 재접속에서 떨어집니다.
        if (current.keys().contains(key(null, deviceId, type))) {
            log.debug("매장 없이 등록된 장비입니다 — BO에서 매장을 채워 주세요 ({}/{})", deviceId, type);
            return true;
        }
        log.warn("등록되지 않은 단말의 접속 시도: 매장={} 장비={} 종류={}", storeId, deviceId, type);
        return false;
    }

    private Snapshot refreshIfStale() {
        Snapshot current = snapshot.get();
        if (!current.stale(Instant.now())) {
            return current;
        }
        try {
            Snapshot fresh = read();
            snapshot.set(fresh);
            log.info("BO 장비 명단 {}건을 읽었습니다", fresh.keys().size());
            return fresh;
        } catch (RuntimeException unreadable) {
            if (current.everRead()) {
                // 들고 있던 것으로 계속 갑니다. BO가 내려가 있는 동안
                // 단말이 끊기면 안 됩니다 — 기준정보를 고치는 일이
                // 장사를 멈추게 해서는 안 됩니다.
                log.warn("BO 장비 명단을 갱신하지 못했습니다 — {}에 읽은 것으로 계속합니다",
                        current.readAt(), unreadable);
                // 다시 시도할 수 있도록 읽은 시각만 밀어 둡니다.
                snapshot.set(new Snapshot(current.keys(), current.kdsRoles(), Instant.now(), true));
                return current;
            }
            log.error("BO 장비 명단을 한 번도 읽지 못했습니다 — 모양만 보고 받습니다", unreadable);
            return current;
        }
    }

    @Override
    public Optional<String> kdsRole(String storeId, String deviceId) {
        Snapshot current = refreshIfStale();
        if (!current.everRead()) {
            return Optional.empty();
        }
        String role = current.kdsRoles().get(roleKey(storeId, deviceId));
        if (role == null) {
            role = current.kdsRoles().get(roleKey(null, deviceId));
        }
        return Optional.ofNullable(role);
    }

    @Override
    public boolean hasKdsMain(String storeId) {
        String prefix = roleKey(storeId, "");
        return refreshIfStale().kdsRoles().entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith(prefix) && "MAIN".equals(e.getValue()));
    }

    private Snapshot read() {
        // active 만 봅니다. 장비를 뺀 것과 없던 장비는 BO에서 다른
        // 일이지만, 여기서는 같은 답입니다 — 지금 붙으면 안 됩니다.
        String sql = "SELECT store_id, device_id, terminal_type, kds_role FROM v_bo_devices WHERE active";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Set<String> keys = new HashSet<>();
            Map<String, String> roles = new HashMap<>();
            while (rs.next()) {
                String type = rs.getString("terminal_type");
                keys.add(key(rs.getString("store_id"), rs.getString("device_id"), type));
                // 역할이 빈 KDS 는 "모름" — BO 에서 역할을 정하기 전의 줄입니다. SUB 로 읽으면
                // 메인으로 쓰던 화면이 생산 화면으로 바뀌어 수령할 곳이 없어집니다.
                String role = rs.getString("kds_role");
                if ("KDS".equalsIgnoreCase(type) && role != null && !role.isBlank()) {
                    roles.put(roleKey(rs.getString("store_id"), rs.getString("device_id")),
                            "MAIN".equalsIgnoreCase(role) ? "MAIN" : "SUB");
                }
            }
            return new Snapshot(Set.copyOf(keys), Map.copyOf(roles), Instant.now(), true);
        } catch (Exception e) {
            throw new IllegalStateException("BO 장비 명단을 읽지 못했습니다", e);
        }
    }

    /**
     * 대소문자를 접습니다. BO 화면에서 {@code POS} 로 고른 것과 단말이
     * 보내는 {@code pos} 가 같은 장비를 뜻하는데, 여기서 갈리면
     * "등록했는데 안 붙는다"가 됩니다.
     */
    private static String roleKey(String storeId, String deviceId) {
        String store = storeId == null || storeId.isBlank() ? "*" : storeId.toLowerCase(Locale.ROOT);
        return store + "|" + (deviceId == null ? "" : deviceId.toLowerCase(Locale.ROOT));
    }

    private static String key(String storeId, String deviceId, String type) {
        String store = storeId == null || storeId.isBlank()
                ? "*" : storeId.toLowerCase(Locale.ROOT);
        String kind = type == null || type.isBlank()
                ? "*" : type.toLowerCase(Locale.ROOT);
        return store + "|" + (deviceId == null ? "" : deviceId.toLowerCase(Locale.ROOT)) + "|" + kind;
    }
}
