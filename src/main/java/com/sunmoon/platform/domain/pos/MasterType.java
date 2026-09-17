package com.sunmoon.platform.domain.pos;

import java.util.Optional;

/**
 * POS-Server 가 장비에 내려보내는 마스터 (업무 정의서 §3.6).
 *
 * <p>목록이 코드에 박혀 있는 것은 일부러입니다. 마스터가 하나 늘면
 * 내용만 늘어나는 것이 아니라 <b>어떤 장비가 그것을 받아야 하는가</b>가
 * 함께 정해져야 하고, 그 판단은 표에 행을 넣는 것으로는 안 됩니다.
 *
 * <p>내용은 여기 없습니다. BO의 View 가 갖고 있고, 이 서버는 버전과
 * 적용 결과만 관리합니다 — 내용을 복제하면 두 벌이 어긋나는 날이 옵니다.
 */
public enum MasterType {
    POS_DEVICE("POS 장비"),
    POS_SETTING("POS 설정"),
    MENU("메뉴"),
    TOUCH_KEY("터치키"),
    SET("세트"),
    SET_GROUP("세트 그룹"),
    RECEIPT("영수증"),
    RECEIPT_GROUP("영수증 그룹"),
    STORE("점포"),
    KITCHEN_DEVICE("주방 Device"),
    PROMOTION("프로모션"),
    PROMOTION_DETAIL("프로모션 상세"),
    PAYMENT_DISCOUNT("결제·할인");

    private final String label;

    MasterType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 모르는 이름은 예외가 아니라 빈 값입니다 — 장비가 낡은 이름을 보내도 전체가 실패하면 안 됩니다. */
    public static Optional<MasterType> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
