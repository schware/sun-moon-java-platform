package com.sunmoon.platform.domain.terminal;

/**
 * Answers whether a device id is one this platform knows.
 *
 * <p>The master data lives in BO, in a different service and a different
 * database, so this is a port rather than a query. The default
 * implementation accepts any well-formed id — see
 * {@link AcceptKnownFormatDirectory} for exactly what that does and does
 * not prove.
 */
public interface DeviceDirectory {

    boolean isRegistered(String deviceId);
}
