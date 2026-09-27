package com.sunmoon.platform.domain.sale;

import com.sunmoon.platform.domain.intake.IntakeException;

/**
 * 원본을 풀지 못했습니다. 메시지가 그대로 {@code tr_sale_log.error} 에
 * 남아서, 운영자가 무엇이 빠졌는지 보고 재처리할 수 있습니다 (§3.2).
 *
 * <p>{@link IntakeException} 을 잇는 이유 (2026-09-27): 공통 배치가
 * <b>이 예외만 보고 원본을 FAILED 로 낙인찍습니다.</b> 그 밖의 예외는
 * 저장 사고로 보고 원본을 그대로 둔 채 다음 회차에 다시 시도합니다.
 * 이걸 안 이으면 TR 의 잘못된 원본이 FAILED 가 안 되고 30초마다 다시
 * 풀립니다 — 2026-09-18에 388번 풀던 그 사고로 돌아갑니다.
 */
public class SaleDecomposeException extends IntakeException {

    public SaleDecomposeException(String message) {
        super(message);
    }

    public SaleDecomposeException(String message, Throwable cause) {
        super(message, cause);
    }
}
