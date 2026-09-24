package com.sunmoon.platform.domain.terminal;

/**
 * Answers whether a terminal is one this platform knows.
 *
 * <p>The master data lives in BO, in a different service and a different
 * database, so this is a port rather than a query. The default
 * implementation accepts any well-formed id — see
 * {@link AcceptKnownFormatDirectory} for exactly what that does and does
 * not prove.
 *
 * <p><b>장비는 id 하나가 아닙니다</b> (2026-09-24). 단말의 신원은
 * (매장, 번호, 종류)이고, 그것이 {@code TerminalId} 가 이미 쓰는
 * 규칙입니다 — 같은 매장의 POS와 KDS가 둘 다 {@code "01"} 일 수
 * 있어서 번호만으로는 가릅니다. 등록 여부를 물을 때만 번호 하나로
 * 물으면, 어느 매장의 어느 종류인지 모른 채 답하게 됩니다.
 */
public interface DeviceDirectory {

    /**
     * @param storeId  그 단말이 대는 매장. DID는 여러 매장을 볼 수
     *                 있으므로 그중 첫 번째가 옵니다.
     * @param deviceId 단말 번호
     * @param type     POS / KDS / DID
     */
    boolean isRegistered(String storeId, String deviceId, String type);
}
