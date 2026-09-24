package com.sunmoon.platform.domain.intake;

/**
 * 원본이 잘못돼서 풀 수 없습니다.
 *
 * <p><b>저장이 안 되는 것과 구별합니다.</b> 이것이 나면 원본을
 * {@code FAILED} 로 낙인찍고 사람을 기다립니다. 반면 데이터베이스가
 * 잠깐 안 되는 것은 원본 탓이 아니므로 낙인찍지 않고 다음 회차에 다시
 * 시도합니다 — 그 구분이 {@link IntakeJob} 의 catch 두 개입니다.
 */
public class IntakeException extends RuntimeException {

    public IntakeException(String message) {
        super(message);
    }

    public IntakeException(String message, Throwable cause) {
        super(message, cause);
    }
}
