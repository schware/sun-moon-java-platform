package com.sunmoon.platform.infrastructure.pos;

import com.sunmoon.platform.domain.pos.ProgramInstall;
import com.sunmoon.platform.domain.pos.ProgramRelease;
import com.sunmoon.platform.domain.pos.ProgramRepository;

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

/** 프로그램 배포의 JDBC 저장소 (§3.5). */
public final class JdbcProgramRepository implements ProgramRepository {

    private final DataSource dataSource;

    public JdbcProgramRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public long publish(ProgramRelease release) {
        // 같은 버전을 다시 내면 새 줄이 생기지 않고 시각만 올라갑니다.
        // 되돌리기(1.4.1 을 다시 내는 것)가 그래야 동작합니다 — 그때
        // 필요한 것은 새 줄이 아니라 "이게 지금 최신"이라는 사실입니다.
        String sql = """
                INSERT INTO pos_program_release
                  (store_id, device_type, program, version, package_url, checksum,
                   mandatory, note, released_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (COALESCE(store_id, '*'), device_type, program, version) DO UPDATE
                   SET package_url = EXCLUDED.package_url,
                       checksum = EXCLUDED.checksum,
                       mandatory = EXCLUDED.mandatory,
                       note = EXCLUDED.note,
                       released_at = now()
                RETURNING id
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (release.storeId() == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, release.storeId());
            }
            ps.setString(2, release.deviceType());
            ps.setString(3, release.program());
            ps.setString(4, release.version());
            ps.setString(5, release.packageUrl());
            ps.setString(6, release.checksum());
            ps.setBoolean(7, release.mandatory());
            ps.setString(8, release.note());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 배포를 등록하지 못했습니다", e);
        }
    }

    /**
     * 프로그램마다 한 줄, 가장 나중에 낸 것.
     *
     * <p>{@code DISTINCT ON} 으로 고릅니다. 정렬 첫 키가 매장 지정
     * 여부인 것이 요점입니다 — 매장 지정 배포가 전 매장 배포를 이깁니다.
     * 한 매장만 먼저 올려 보는 것이 배포의 정상 모양이고, 그때 전 매장
     * 배포가 그것을 덮으면 시험이 성립하지 않습니다.
     */
    @Override
    public List<ProgramRelease> currentFor(String storeId, String deviceType) {
        String sql = """
                SELECT DISTINCT ON (program)
                       id, store_id, device_type, program, version, package_url,
                       checksum, mandatory, note, released_at
                  FROM pos_program_release
                 WHERE device_type = ? AND (store_id IS NULL OR store_id = ?)
                 ORDER BY program, (store_id IS NOT NULL) DESC, released_at DESC
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, deviceType);
            ps.setString(2, storeId);
            return releases(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 배포 대상을 읽지 못했습니다", e);
        }
    }

    @Override
    public List<ProgramRelease> releases(String storeId) {
        String sql = """
                SELECT id, store_id, device_type, program, version, package_url,
                       checksum, mandatory, note, released_at
                  FROM pos_program_release
                 WHERE store_id IS NULL OR store_id = ?
                 ORDER BY released_at DESC
                 LIMIT 200
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            return releases(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 배포 목록을 읽지 못했습니다", e);
        }
    }

    @Override
    public List<ProgramInstall> installsOf(String storeId, String deviceType, String deviceNo) {
        String sql = INSTALL_SELECT + " WHERE store_id = ? AND device_type = ? AND device_no = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            return installs(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 설치 결과를 읽지 못했습니다", e);
        }
    }

    @Override
    public List<ProgramInstall> installsOf(String storeId) {
        String sql = INSTALL_SELECT + " WHERE store_id = ? ORDER BY device_type, device_no, program";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            return installs(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 설치 결과를 읽지 못했습니다", e);
        }
    }

    @Override
    public void markOffered(String storeId, String deviceType, String deviceNo,
                            String program, String targetVersion) {
        String sql = """
                INSERT INTO pos_program_install
                  (store_id, device_type, device_no, program, target_version,
                   status, attempts, offered_at)
                VALUES (?, ?, ?, ?, ?, 'OFFERED', 1, now())
                ON CONFLICT (store_id, device_type, device_no, program) DO UPDATE
                   SET target_version = EXCLUDED.target_version,
                       status = 'OFFERED',
                       attempts = pos_program_install.attempts + 1,
                       offered_at = now()
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, program);
            ps.setString(5, targetVersion);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 전달 기록을 남기지 못했습니다", e);
        }
    }

    @Override
    public void ack(String storeId, String deviceType, String deviceNo, String program,
                    String version, ProgramInstall.Status status, String message) {
        boolean installed = status == ProgramInstall.Status.INSTALLED;
        // 실패해도 installed_version 은 그대로입니다 — 되돌아간 것이
        // 아니라 앞으로 못 간 것이라, 장비가 실제로 들고 있는 것은
        // 여전히 그 전 버전입니다.
        String sql = """
                INSERT INTO pos_program_install
                  (store_id, device_type, device_no, program,
                   installed_version, target_version, status, attempts, last_error,
                   offered_at, installed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, now(), ?)
                ON CONFLICT (store_id, device_type, device_no, program) DO UPDATE
                   SET installed_version = CASE WHEN EXCLUDED.status = 'INSTALLED'
                                                THEN EXCLUDED.installed_version
                                                ELSE pos_program_install.installed_version END,
                       target_version = EXCLUDED.target_version,
                       status = EXCLUDED.status,
                       last_error = EXCLUDED.last_error,
                       installed_at = CASE WHEN EXCLUDED.status = 'INSTALLED'
                                           THEN now() ELSE pos_program_install.installed_at END
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, program);
            if (installed) {
                ps.setString(5, version);
            } else {
                ps.setNull(5, Types.VARCHAR);
            }
            ps.setString(6, version);
            ps.setString(7, status.name());
            ps.setString(8, status == ProgramInstall.Status.FAILED ? message : null);
            if (installed) {
                ps.setTimestamp(9, Timestamp.from(Instant.now()));
            } else {
                ps.setNull(9, Types.TIMESTAMP);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 설치 결과를 남기지 못했습니다", e);
        }
    }

    @Override
    public List<ProgramInstall> unfinished(String storeId) {
        String sql = INSTALL_SELECT
                + " WHERE store_id = ? AND status <> 'INSTALLED' ORDER BY device_type, device_no, program";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            return installs(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 재전송 대상을 읽지 못했습니다", e);
        }
    }

    @Override
    public Optional<ProgramInstall> find(String storeId, String deviceType, String deviceNo, String program) {
        String sql = INSTALL_SELECT
                + " WHERE store_id = ? AND device_type = ? AND device_no = ? AND program = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, program);
            List<ProgramInstall> found = installs(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 설치 결과를 읽지 못했습니다", e);
        }
    }

    @Override
    public boolean clearFailure(String storeId, String deviceType, String deviceNo, String program) {
        // status 는 OFFERED 로 되돌립니다. 마스터와 다른 점: 프로그램은
        // 실패했다는 것 자체가 이미 "내려줬다"는 뜻이라, OFFERED 가
        // 거짓말이 아닙니다.
        String sql = """
                UPDATE pos_program_install
                   SET last_error = NULL, status = 'OFFERED'
                 WHERE store_id = ? AND device_type = ? AND device_no = ?
                   AND program = ? AND status = 'FAILED'
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, storeId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceNo);
            ps.setString(4, program);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("프로그램 재처리를 걸지 못했습니다", e);
        }
    }

    private static final String INSTALL_SELECT = """
            SELECT store_id, device_type, device_no, program, installed_version,
                   target_version, status, attempts, last_error, offered_at, installed_at
              FROM pos_program_install
            """;

    private static List<ProgramRelease> releases(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<ProgramRelease> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new ProgramRelease(
                        rs.getLong("id"), rs.getString("store_id"), rs.getString("device_type"),
                        rs.getString("program"), rs.getString("version"), rs.getString("package_url"),
                        rs.getString("checksum"), rs.getBoolean("mandatory"), rs.getString("note"),
                        rs.getTimestamp("released_at").toInstant()));
            }
            return rows;
        }
    }

    private static List<ProgramInstall> installs(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<ProgramInstall> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new ProgramInstall(
                        rs.getString("store_id"), rs.getString("device_type"), rs.getString("device_no"),
                        rs.getString("program"), rs.getString("installed_version"),
                        rs.getString("target_version"),
                        ProgramInstall.Status.of(rs.getString("status")),
                        rs.getInt("attempts"), rs.getString("last_error"),
                        instant(rs, "offered_at"), instant(rs, "installed_at")));
            }
            return rows;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
