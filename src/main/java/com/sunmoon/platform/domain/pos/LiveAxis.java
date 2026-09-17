package com.sunmoon.platform.domain.pos;

/**
 * Live Check 의 다섯 축 (업무 정의서 §3.5).
 *
 * <p>문서가 못 박은 한 문장에서 나온 것입니다: <b>"WebSocket 연결
 * 상태만으로 정상 영업 여부를 판단하지 않는다."</b>
 *
 * <p>왜 다섯인가 — 넷은 연결이 붙어 있어도 따로 나빠질 수 있고,
 * 하나로 묶으면 그 넷이 보이지 않습니다. 소켓은 멀쩡한데 POS 프로그램이
 * 죽어 있는 단말, 프로그램은 도는데 마감을 안 한 단말, 다 도는데
 * 영수증 프린터만 죽은 단말, 다 도는데 매출이 안 올라가는 단말 —
 * 네 경우 모두 "연결됨"입니다. 그리고 넷 다 매장에서는 다른 사고입니다.
 */
public enum LiveAxis {
    /** 네트워크·세션. 이 서버가 소켓을 들고 있는가 — 유일하게 서버가 혼자 아는 축입니다. */
    CONNECTION("연결"),
    /** POS 프로그램이 돌고 있는가. POS가 Heartbeat 로 말해 줘야 압니다. */
    APPLICATION("프로그램"),
    /** 개점·영업·마감. §3.4 의 상태를 그대로 읽습니다 — 따로 보고받지 않습니다. */
    BUSINESS("영업"),
    /** 프린터·드로어 등 주변 장비. */
    DEVICE("장비"),
    /** 매출과 인터페이스 처리. POS의 보고와 서버가 본 분해 실패를 함께 봅니다. */
    DATA("데이터");

    private final String label;

    LiveAxis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
