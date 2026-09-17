package com.sunmoon.platform.domain.pos;

import java.time.Instant;

/** 장비별 적용 결과 한 줄 (§3.6 "장비별 수신, 적용, 실패 결과와 적용 버전을 관리한다"). */
public record MasterDispatch(
        String storeId, String deviceType, String deviceNo, MasterType masterType,
        Long appliedVersion, Status status, int attempts, String lastError,
        Instant sentAt, Instant appliedAt) {

    public enum Status {
        /** 보냈고 아직 답이 없습니다. 실패가 아닙니다 — 아직 모릅니다. */
        SENT,
        APPLIED,
        /** 장비가 적용에 실패했다고 답했습니다. 지우지 않고 재전송 대상으로 남깁니다. */
        FAILED
    }
}
