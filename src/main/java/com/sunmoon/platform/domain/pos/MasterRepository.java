package com.sunmoon.platform.domain.pos;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 마스터 버전과 장비별 적용 결과 (§3.6). 마스터의 내용은 여기 없습니다 — BO의 View 가 갖고 있습니다. */
public interface MasterRepository {

    /** 그 매장의 마스터별 현재 버전. 등록되지 않은 마스터는 아예 빠집니다. */
    Map<MasterType, Long> versionsOf(String storeId);

    /** 마스터가 바뀌었습니다. 버전을 하나 올리고 새 버전을 돌려줍니다. */
    long bumpVersion(String storeId, MasterType masterType);

    List<MasterDispatch> dispatchesOf(String storeId, String deviceType, String deviceNo);

    /** 보냈다는 사실만 적습니다 — 적용 버전은 건드리지 않습니다. */
    void markSent(String storeId, String deviceType, String deviceNo, MasterType masterType);

    /**
     * 장비가 답했습니다.
     *
     * <p>{@code applied} 가 참일 때만 적용 버전이 올라갑니다. 이 한
     * 줄이 §3.6 전체의 무게중심입니다 — 보냈다고 올리면 못 받은 장비가
     * 최신으로 보이고, 그 장비는 영영 옛 마스터로 영업합니다.
     */
    void ack(String storeId, String deviceType, String deviceNo, MasterType masterType,
             long version, boolean applied, String message);

    /** 아직 적용되지 않았거나 실패한 것 — 재전송 대상 (§3.6 "누락이나 실패 건을 재전송한다"). */
    List<MasterDispatch> unfinished(String storeId);

    Optional<MasterDispatch> find(String storeId, String deviceType, String deviceNo, MasterType masterType);

    /**
     * 실패 표시를 지워 <b>다시 받아야 할 것</b>으로 되돌립니다 (§5.8).
     *
     * <p>여기서 할 수 있는 것이 이것까지입니다 — 마스터는 <b>장비가
     * 가져갑니다</b>. 서버가 밀 수 없으므로 실제 재전송은 그 장비가 다음에
     * 물어볼 때 일어나고, 화면은 그 사실을 말해 줘야 합니다.
     *
     * <p>적용 버전은 건드리지 않습니다. 실패했다고 장비가 뭔가를 잃은
     * 것이 아니라 <b>앞으로 못 간 것</b>이라, 여전히 그 전 버전을
     * 들고 있습니다.
     */
    boolean clearFailure(String storeId, String deviceType, String deviceNo, MasterType masterType);
}
