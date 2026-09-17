package com.sunmoon.platform.domain.pos;

/** 그 단말이 지금 영업 중인가. 사건({@link BusinessEvent})과 다릅니다 — 재개점한 POS도 상태는 OPEN 입니다. */
public enum BusinessStatus {
    OPEN,
    CLOSED
}
