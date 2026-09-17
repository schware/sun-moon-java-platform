package com.sunmoon.platform.domain.pos;

import java.util.List;
import java.util.Optional;

/** 프로그램 배포의 목표와 장비별 결과 (§3.5). */
public interface ProgramRepository {

    long publish(ProgramRelease release);

    /**
     * 그 매장 그 장비 유형이 받아야 할 <b>최신</b> 배포들.
     *
     * <p>매장 지정 배포가 전 매장 배포를 이깁니다. 한 매장만 먼저 올려
     * 보는 것이 배포의 정상 모양이고, 그때 전 매장 배포가 그것을 덮으면
     * 시험이 성립하지 않습니다.
     */
    List<ProgramRelease> currentFor(String storeId, String deviceType);

    List<ProgramRelease> releases(String storeId);

    List<ProgramInstall> installsOf(String storeId, String deviceType, String deviceNo);

    List<ProgramInstall> installsOf(String storeId);

    /** 내려줬다는 사실만 적습니다 — 깔린 버전은 건드리지 않습니다. */
    void markOffered(String storeId, String deviceType, String deviceNo,
                     String program, String targetVersion);

    /**
     * 장비가 답했습니다.
     *
     * <p>{@code INSTALLED} 일 때만 깔린 버전이 올라갑니다. 마스터와
     * 같은 이유입니다 — 내려준 것과 깔린 것은 다른 일입니다.
     */
    void ack(String storeId, String deviceType, String deviceNo, String program,
             String version, ProgramInstall.Status status, String message);

    List<ProgramInstall> unfinished(String storeId);

    Optional<ProgramInstall> find(String storeId, String deviceType, String deviceNo, String program);

    /**
     * 실패 표시를 지워 다시 받아야 할 것으로 되돌립니다 (§5.8).
     *
     * <p>마스터와 같습니다 — 장비가 가져가는 구조라 서버가 밀 수 없고,
     * 실제 재설치는 그 장비가 다음에 물어볼 때 일어납니다.
     *
     * <p>깔린 버전은 건드리지 않습니다. 설치에 실패했다고 전에 깔려
     * 있던 것이 사라진 것은 아닙니다.
     */
    boolean clearFailure(String storeId, String deviceType, String deviceNo, String program);
}
