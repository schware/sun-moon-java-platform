package com.sunmoon.platform.domain.pos;

/**
 * 개점·마감 IRT 가 나르는 사건 (업무 정의서 §3.4).
 *
 * <p>POS가 보내는 것은 {@link #OPEN} 과 {@link #CLOSE} 둘뿐입니다.
 * {@link #REOPEN} 은 <b>서버가 붙이는 이름</b>입니다 — 닫혀 있던
 * 단말이 다시 열면 그게 재개점이고, 그 판단은 이전 상태를 아는 쪽만
 * 할 수 있습니다.
 *
 * <p>POS가 직접 "재개점"이라고 말하게 두지 않는 이유: 재부팅했거나
 * 로컬 DB를 새로 깐 POS는 자기가 오늘 이미 열었다는 것을 모릅니다.
 * 그 POS는 개점이라고 말할 것이고, 서버 이력에는 재개점이 빠집니다.
 */
public enum BusinessEvent {
    OPEN,
    CLOSE,
    REOPEN;

    /** POS가 보낼 수 있는 값인가. REOPEN 은 서버만 씁니다. */
    public static BusinessEvent reported(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("event is required");
        }
        BusinessEvent event = switch (raw.trim().toUpperCase()) {
            case "OPEN" -> OPEN;
            case "CLOSE" -> CLOSE;
            default -> throw new IllegalArgumentException("event must be OPEN or CLOSE: " + raw);
        };
        return event;
    }
}
