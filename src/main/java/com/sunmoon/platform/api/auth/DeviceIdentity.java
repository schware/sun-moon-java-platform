package com.sunmoon.platform.api.auth;

/**
 * 기기 토큰이 가리키는 기기 — 매장, 기기 번호, 종류(POS/KIOSK/KDS/DID).
 *
 * <p>1단계(페어링)에서 토큰이 생기기 전까지는 만들어지지 않습니다.
 */
public record DeviceIdentity(String storeId, String deviceId, String type) {
}
