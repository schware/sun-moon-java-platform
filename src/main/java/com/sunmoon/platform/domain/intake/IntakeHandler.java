package com.sunmoon.platform.domain.intake;

/**
 * 원본 하나를 풀어서 자기 표에 넣고, <b>끝났다고 표시까지</b> 합니다.
 *
 * <p>셋을 한 덩어리로 맡기는 이유는 <b>트랜잭션</b>입니다. 결과 행은
 * 들어갔는데 원본이 아직 {@code RECEIVED} 면 다음 배치가 같은 것을 다시
 * 풀고, 반대면 결과 없는 {@code DECOMPOSED} 가 남습니다. 둘 다
 * 한 트랜잭션 안에 있어야 하고, 그 트랜잭션의 경계를 아는 것은
 * 결과를 쓰는 쪽뿐입니다.
 *
 * <p>그래서 {@link IntakeJob} 은 <b>성공하면 아무것도 안 합니다</b> —
 * 표시는 이미 여기서 됐습니다.
 */
@FunctionalInterface
public interface IntakeHandler {

    /**
     * @throws IntakeException 원본이 잘못된 경우. 그 건은 FAILED 가 됩니다.
     * @throws RuntimeException 그 밖의 사고(저장 실패 등). 원본은 그대로
     *         두고 다음 회차에 다시 시도합니다.
     */
    void handle(RawLog raw);
}
