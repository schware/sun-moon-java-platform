package com.sunmoon.platform.domain.pos;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 개점·마감의 현재 상태와 이력 (§3.4). */
public interface PosBusinessRepository {

    Optional<BusinessState> find(String storeId, String posNo, LocalDate businessDate);

    /**
     * 현재 상태를 덮고 이력 한 줄을 붙입니다 — <b>한 트랜잭션</b>으로.
     *
     * <p>둘이 갈리면 최악의 조합이 남습니다: 상태는 마감인데 이력에는
     * 그 마감이 없는 단말. 정산이 "언제 닫았나"를 물을 때 답할 것이
     * 없고, 상태를 되짚어 고칠 근거도 없습니다.
     *
     * @return 이 사건에 매겨진 처리 순번
     */
    int apply(BusinessState state, BusinessEvent event, IrtResult result, String message,
              java.time.Instant eventAt, String irtId);

    /**
     * 상태를 바꾸지 않고 이력만 남깁니다 — 중복이거나 지나간 사건일 때.
     *
     * <p>버리지 않는 이유: 늦게 도착한 마감이 쌓이는 단말은 통신이
     * 밀리고 있다는 뜻이고, 그 신호는 이력에만 남습니다.
     */
    int record(PosBusinessIrt irt, IrtResult result, String message);

    /** 그날 그 매장의 단말들. 관제 화면이 읽는 자리입니다. */
    List<BusinessState> statesOf(String storeId, LocalDate businessDate);

    /** 한 단말의 그날 이력 — 정산·감사가 읽습니다. */
    List<BusinessHistoryEntry> historyOf(String storeId, String posNo, LocalDate businessDate);

    record BusinessHistoryEntry(int seq, BusinessEvent event, IrtResult result, String message,
                                java.time.Instant eventAt, java.time.Instant receivedAt, String irtId) {
    }
}
