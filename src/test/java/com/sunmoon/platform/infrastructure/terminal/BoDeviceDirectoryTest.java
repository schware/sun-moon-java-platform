package com.sunmoon.platform.infrastructure.terminal;

import com.sunmoon.platform.domain.terminal.AcceptKnownFormatDirectory;
import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 여기서 지키는 것은 하나입니다 — <b>명단을 모르는 것과 명단에 없는
 * 것은 다르다.</b>
 *
 * <p>뒤집으면 실제로 일어나는 일: postgres_fdw 뷰가 아직 안 걸린
 * 서버에 이 버전이 올라가고, 전 매장의 단말이 조용히 못 붙습니다.
 * 증상은 "이 단말은 등록되지 않았습니다"로 보여서, 원인을 BO의 장비
 * 화면에서 찾게 됩니다 — 거기에는 멀쩡히 등록돼 있는데도요.
 */
class BoDeviceDirectoryTest {

    private static final DeviceDirectory FORMAT = new AcceptKnownFormatDirectory();

    @Test
    void 명단을_한_번도_못_읽었으면_막지_않는다() {
        DeviceDirectory directory = new BoDeviceDirectory(new UnreachableDataSource(), FORMAT);

        assertTrue(directory.isRegistered("store-01", "pos-01", "POS"));
    }

    @Test
    void 명단을_못_읽어도_모양이_아닌_것은_거른다() {
        DeviceDirectory directory = new BoDeviceDirectory(new UnreachableDataSource(), FORMAT);

        // 데이터베이스를 못 읽는 것이 "아무거나 받는다"는 뜻은 아닙니다.
        assertFalse(directory.isRegistered("store-01", "", "POS"));
        assertFalse(directory.isRegistered("store-01", null, "POS"));
        assertFalse(directory.isRegistered("store-01", "pos 01", "POS"));
    }

    /** 붙을 수 없는 데이터베이스. fdw 뷰가 아직 없는 서버와 같은 상황입니다. */
    private static final class UnreachableDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("relation \"v_bo_devices\" does not exist");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
