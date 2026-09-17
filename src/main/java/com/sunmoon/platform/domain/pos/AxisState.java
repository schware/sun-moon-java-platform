package com.sunmoon.platform.domain.pos;

/**
 * 한 축의 상태.
 *
 * <p>{@link #UNKNOWN} 이 {@link #OK} 와 {@link #DOWN} 사이의 어중간한
 * 값이 아니라 <b>따로 있는 이유</b>: 보고가 끊긴 것과 나쁘다고 보고된
 * 것은 다른 일입니다. 앞은 통신을 봐야 하고 뒤는 단말을 봐야 합니다.
 *
 * <p>특히 UNKNOWN 을 OK 로 치면 죽은 POS가 영원히 정상으로 보입니다 —
 * 마지막으로 "정상"이라고 말한 뒤에 죽었기 때문입니다.
 */
public enum AxisState {
    OK,
    /** 돌고는 있지만 봐야 합니다 — 장비 하나가 빠졌다든가. */
    WARN,
    DOWN,
    /** 말해 준 적이 없거나, 마지막으로 말한 지 오래됐습니다. */
    UNKNOWN;

    public static AxisState reported(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknownWord) {
            return UNKNOWN;
        }
    }

    /** Grafana 의 정상/중단 구분에 맞춘 한 줄 요약. */
    public boolean normal() {
        return this == OK;
    }
}
