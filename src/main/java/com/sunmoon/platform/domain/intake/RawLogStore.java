package com.sunmoon.platform.domain.intake;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 원본의 <b>수명주기</b>만 다룹니다 — 받고, 대기 목록을 주고, 실패를
 * 적고, 되돌립니다.
 *
 * <p><b>분해 결과는 여기 없습니다.</b> TR 은 Header/Detail/Payment/ETC
 * 네 표로 가고 ETC 는 이름·값 한 표로 갑니다 — 그 차이를 이 인터페이스에
 * 끌고 들어오면 "공통" 안에 종류별 분기가 생깁니다. 결과를 쓰는 일은
 * {@link IntakeHandler} 가 가져갑니다.
 *
 * <p>구현은 하나입니다({@code JdbcRawLogStore}) — 표 이름과 키 컬럼만
 * 다르게 주어 TR 과 ETC 두 벌로 씁니다.
 */
public interface RawLogStore {

    /**
     * 원본을 적습니다. 같은 건이 이미 있으면 {@code empty} 입니다.
     *
     * <p><b>이미 받은 것은 실패가 아닙니다.</b> 배치가 밀린 것을 다시
     * 올리거나 응답을 못 받은 단말이 재시도하는 것은 정상이고, 그때
     * 두 번째가 "이미 받았다"로 읽혀야 단말이 안심하고 자기 쪽에서
     * 지웁니다. 여기서 오류를 내면 단말은 영원히 다시 보냅니다.
     *
     * <p>막는 것은 애플리케이션이 아니라 <b>유일 제약</b>입니다 —
     * "있나 보고 없으면 넣기" 는 그 둘 사이에 창이 남습니다.
     */
    Optional<Long> receive(RawLog log);

    /**
     * 아직 안 푼 것. 오래 기다린 것부터.
     *
     * <p><b>실패한 것은 안 들어갑니다.</b> 이 한 줄이 2026-09-18에 TR
     * 에서 배운 것입니다 — 처음에는 {@code status <> 'DECOMPOSED'} 로
     * 골랐는데, 그러면 영원히 못 푸는 건을 30초마다 다시 풉니다.
     * 실서버에서 거래 하나를 이틀 동안 388번 다시 풀고 있었습니다.
     *
     * <p>나쁜 것이 셋이었습니다: 쓸데없는 일을 하고, 30초마다 에러
     * 로그를 찍어 진짜 문제를 묻고, 무엇보다 <b>{@code attempts} 가
     * 뜻을 잃습니다</b> — "몇 번 시도했나"가 "얼마나 오래 깨져 있었나"가
     * 되어 사람이 볼 건을 고르는 신호로 못 씁니다.
     */
    List<RawLog> pending(int limit);

    /** 원본이 잘못돼서 못 풀었습니다. 원본은 그대로 두고 사유만 적습니다. */
    void markFailed(long logId, String error);

    /**
     * 다시 풀도록 되돌립니다 (§5.8 재처리).
     *
     * <p>사유를 <b>지우지 않습니다.</b> 다시 돌려서 또 실패하면 새 사유가
     * 덮어쓰고, 성공하면 분해가 지웁니다 — 그 사이에 화면을 여는 사람은
     * "무엇 때문에 다시 돌린 건인지" 를 볼 수 있어야 합니다.
     *
     * <p>시도 횟수도 그대로 둡니다. 세 번째 재처리라는 사실 자체가
     * "이건 사람이 봐야 한다" 는 신호입니다.
     *
     * @return 되돌릴 것이 실제로 있었는가. 이미 풀렸거나 없는 건이면 거짓.
     */
    boolean markForRetry(long logId);

    /**
     * 지금 실패해 있는 것들.
     *
     * <p>{@link #pending} 과 다릅니다 — 저건 "아직 안 한 것" 이고 이건
     * "해 봤는데 안 된 것" 입니다. 앞은 배치가 알아서 집어 가지만 뒤는
     * 사람이 봐야 합니다.
     */
    List<RawLog> failed(String storeId);

    /** 그 날 그 매장의 건수 — 정합성 확인용. */
    Counts counts(String storeId, LocalDate businessDate);

    /**
     * @param logs       받은 원본
     * @param decomposed 풀린 것
     * @param failed     실패해 멈춰 있는 것
     *
     * <p>{@code logs} 와 {@code decomposed} 가 같아야 정상입니다.
     * 벌어져 있으면 그 차이가 곧 밀린 양입니다.
     */
    record Counts(long logs, long decomposed, long failed) {
    }
}
