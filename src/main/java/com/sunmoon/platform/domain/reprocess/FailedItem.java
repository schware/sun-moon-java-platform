package com.sunmoon.platform.domain.reprocess;

import java.time.Instant;

/**
 * 되돌려야 할 실패 한 건.
 *
 * <p>세 군데(매출 분해, 마스터 적용, 프로그램 설치)가 실패를 다르게
 * 적고 있었습니다. 표도 다르고 칸 이름도 다른데, 사람이 묻는 것은
 * 언제나 같습니다 — <b>무엇이, 왜, 몇 번이나</b>.
 *
 * @param summary 사람이 읽는 한 줄. "pos-01 / 000503" 처럼 그 건을
 *                가리키는 말입니다. 내부 id 만 보여주면 운영자는 그게
 *                무엇인지 알 수 없습니다.
 * @param error   실패 사유. <b>그대로</b> 보여줍니다 — 요약하면 다시
 *                돌려도 되는지 판단할 근거가 사라집니다.
 */
public record FailedItem(
        String id,
        String storeId,
        String summary,
        String error,
        int attempts,
        Instant failedAt) {
}
