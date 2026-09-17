package com.sunmoon.platform.domain.pos;

/**
 * 그 장비에 이번에 무엇을 보내야 하는가 (§3.6 공통 처리 기준).
 *
 * <p>{@link #FULL} 과 {@link #DELTA} 를 가르는 기준은 장비가 <b>말해 준</b>
 * 적용 버전입니다. 서버가 보낸 기록이 아닙니다 — 보냈지만 못 받은
 * 장비를 최신으로 치면, 그 장비는 영영 옛 마스터로 영업합니다.
 */
public enum DispatchMode {
    /** 한 번도 받은 적이 없거나 복구 중. 전체를 보냅니다. */
    FULL,
    /** 버전 차이만큼. */
    DELTA,
    /** 보낼 것이 없습니다. */
    UP_TO_DATE
}
