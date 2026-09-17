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
 * @param posTime POS가 스스로 생각하는 지금 시각 (§3.5 System Date 동기화).
 *                {@code reportedAt} 과 다릅니다: 저것은 이 보고가 만들어진
 *                시각이고 이것은 <b>POS의 시계</b>입니다. 둘을 서버 시각과
 *                견주어야 시계가 틀어진 단말을 찾을 수 있습니다.
 *
 *                <p>왜 중요한가: POS 시각이 틀어지면 판매일자가 틀어지고,
 *                판매일자가 틀어지면 그날 매출이 다른 날로 갑니다
 *                (umbrella docs/adr/0006). 영수증의 시각도 손님이 봅니다.
 * @param driftSeconds 서버 - POS. 양수면 POS가 느립니다. 서버가 계산해
 *                넣는 값이라 POS가 보내는 것이 아닙니다.
 */
public record PosHeartbeat(
        String storeId, String posNo,
        AxisState application, AxisState device, AxisState data,
        String programVersion, String dbVersion, String devices,
        Instant posTime, Integer driftSeconds, Instant reportedAt) {

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

    /** 서버 시각을 기준으로 시계 차이를 채운 사본. POS가 시각을 안 보내면 그대로입니다. */
    public PosHeartbeat measuredAgainst(Instant serverNow) {
        if (posTime == null) {
            return this;
        }
        long drift = java.time.Duration.between(posTime, serverNow).toSeconds();
        return new PosHeartbeat(storeId, posNo, application, device, data,
                programVersion, dbVersion, devices, posTime, (int) drift, reportedAt);
    }
}
