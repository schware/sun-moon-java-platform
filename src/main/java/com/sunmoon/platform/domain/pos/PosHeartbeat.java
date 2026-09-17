package com.sunmoon.platform.domain.pos;

import java.time.Instant;

/**
 * POS가 주기적으로 보내는 자기 보고 (§3.5).
 *
 * <p>개점·마감 IRT 와 정반대의 성격입니다 — 그쪽은 일어났을 때만 오고
 * 놓치면 끝이지만, 이쪽은 계속 오고 <b>안 오는 것 자체가 정보</b>입니다.
 * 그래서 마지막 시각을 반드시 들고 있어야 합니다.
 *
 * @param devices 연결 장비 목록의 원본 JSON. 장비마다 모양이 달라
 *                해석하지 않고 그대로 담습니다 — 해석은 나중에 관제
 *                화면이 필요해질 때 붙입니다.
 */
public record PosHeartbeat(
        String storeId, String posNo,
        AxisState application, AxisState device, AxisState data,
        String programVersion, String dbVersion, String devices,
        Instant reportedAt) {

    public PosHeartbeat {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("storeId is required");
        }
        if (posNo == null || posNo.isBlank()) {
            throw new IllegalArgumentException("posNo is required");
        }
        application = application == null ? AxisState.UNKNOWN : application;
        device = device == null ? AxisState.UNKNOWN : device;
        data = data == null ? AxisState.UNKNOWN : data;
        reportedAt = reportedAt == null ? Instant.now() : reportedAt;
    }
}
