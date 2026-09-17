package com.sunmoon.platform.domain.pos;

import java.util.List;
import java.util.Optional;

/** POS가 보고한 것만 담습니다 — 연결과 영업 상태는 여기 없습니다 (§3.5). */
public interface PosLiveRepository {

    /** 같은 단말의 이전 보고를 덮습니다. 이력이 필요한 축이 아닙니다 — 필요한 것은 '지금'입니다. */
    void report(PosHeartbeat heartbeat);

    Optional<PosHeartbeat> lastReport(String storeId, String posNo);

    /** 그 매장에서 보고한 적이 있는 단말 전부. */
    List<PosHeartbeat> reportsOf(String storeId);
}
