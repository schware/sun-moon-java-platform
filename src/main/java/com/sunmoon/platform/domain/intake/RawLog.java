package com.sunmoon.platform.domain.intake;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 단말이 올린 <b>원본 한 건</b> — 아직 해석하지 않은 바이트.
 *
 * <p>이 플랫폼에는 같은 모양의 업무가 둘 있습니다: TR 매출(§3.1~3.2)과
 * ETC 매출(§3.3). 둘 다 <b>받아서 적고, 나중에 배치가 풀고, 실패하면
 * 멈추고 사람을 기다립니다.</b> 그 수명주기가 이 패키지입니다.
 *
 * <p><b>무엇이 다른가</b>는 일부러 여기 없습니다 — 분해가 무엇이고
 * 결과를 어느 표에 넣는지는 종류마다 다르고, 그건 {@link IntakeHandler}
 * 가 가져갑니다. 그 선을 안 그으면 "공통" 안에 종류별 분기가 쌓입니다.
 *
 * @param refNo 키의 마지막 자리. TR 은 거래번호, ETC 는 ETC번호입니다.
 *              <b>이름을 {@code tranNo} 로 두지 않은 이유</b>: 그러면
 *              ETC 를 읽는 사람이 매번 "거래번호가 아닌데 왜 거래번호라고
 *              쓰여 있지"를 넘겨야 합니다.
 * @param payload 해석하지 않은 바이트. 지금은 JSON 이지만 단말의 전문
 *              형식은 바뀔 수 있고, 그때 이 표를 다시 만들지 않으려면
 *              받는 쪽이 내용을 몰라야 합니다.
 */
public record RawLog(
        Long id,
        String storeId,
        String posNo,
        LocalDate businessDate,
        String refNo,
        String contentType,
        byte[] payload,
        Status status,
        String error,
        int attempts,
        Instant receivedAt,
        Instant decomposedAt) {

    public enum Status {
        /** 받아서 적었습니다. 아직 분해 전 — <b>배치가 집어 가는 것은 이것뿐입니다.</b> */
        RECEIVED,
        /** 분해까지 끝났습니다. */
        DECOMPOSED,
        /**
         * 분해하다 실패했습니다. <b>멈추고 사람을 기다립니다.</b>
         *
         * <p>지우지 않습니다 — 원본은 그대로 남고, 규칙을 고친 뒤
         * 재처리로 되돌리면 됩니다. 버리면 그 건은 영영 매출이 안 됩니다.
         */
        FAILED
    }

    /** 사람이 읽는 이름 — 내부 id 가 아니라 업무 키로 보여줍니다. */
    public String describe() {
        return businessDate + " " + posNo + " / " + refNo;
    }
}
