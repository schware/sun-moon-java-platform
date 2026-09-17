package com.sunmoon.platform.domain.pos;

import java.time.Instant;
import java.time.LocalDate;

/**
 * POS가 개점·마감 때 한 번 보내는 전문 (§3.4).
 *
 * <p>"지속 통신이 아니라 이벤트성 통신"이라는 문서의 말이 이 형태로
 * 나타납니다 — 주기적으로 오는 것이 아니라 일어났을 때만 옵니다.
 * 그래서 <b>놓치면 다음 것이 메워 주지 않습니다</b>. 늦게라도 온
 * 사건을 버리지 않는 것이 여기의 규칙인 이유입니다.
 *
 * @param eventAt POS에서 일어난 시각. 서버가 받은 시각이 아닙니다.
 * @param irtId   원본 IRT 식별값. 같은 전문이 두 번 와도 이력이
 *                하나이게 하는 유일한 근거이고, 없으면 중복을 못 가립니다.
 */
public record PosBusinessIrt(
        String storeId, String posNo, LocalDate businessDate,
        BusinessEvent event, Instant eventAt, String irtId) {

    public PosBusinessIrt {
        require(storeId, "storeId");
        require(posNo, "posNo");
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        // 시각이 없으면 순서를 못 정합니다. 서버 시각으로 채우면
        // 밀려서 도착한 전문이 방금 일어난 일처럼 보입니다 — 그게
        // 바로 막으려는 것입니다.
        if (eventAt == null) {
            throw new IllegalArgumentException("eventAt is required");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
