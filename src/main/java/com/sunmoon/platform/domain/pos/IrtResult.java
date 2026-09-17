package com.sunmoon.platform.domain.pos;

/**
 * IRT 한 건을 어떻게 처리했나. 이력에 그대로 남습니다.
 *
 * <p>"받았지만 상태를 바꾸지 않았다"가 두 가지인 것이 핵심입니다.
 * {@link #DUPLICATE} 는 POS가 같은 말을 두 번 한 것이고,
 * {@link #IGNORED_STALE} 은 <b>지나간 사건이 뒤늦게 도착한 것</b>
 * 입니다. 앞은 정상이고 뒤는 POS의 통신이 밀리고 있다는 신호라,
 * 하나로 묶으면 볼 수 있어야 할 것이 안 보입니다.
 */
public enum IrtResult {
    /** 상태를 바꿨습니다. */
    APPLIED,
    /** 이미 그 상태였습니다 — 오류가 아닙니다. */
    DUPLICATE,
    /** 지금 상태보다 오래된 사건이라 반영하지 않았습니다. */
    IGNORED_STALE
}
