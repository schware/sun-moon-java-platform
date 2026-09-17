package com.sunmoon.platform.domain.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 마스터 IRT 배포의 규칙 (업무 정의서 §3.6).
 *
 * <p>여기서 관리하는 것은 마스터의 <b>내용이 아니라 버전과 적용
 * 결과</b>입니다. 내용은 BO가 만들고 View 로 내보냅니다 — 이 서버가
 * 한 벌 더 들고 있으면 둘이 어긋나는 날이 오고, 그날 어느 쪽이 맞는지
 * 아무도 모릅니다.
 *
 * <p>규칙 셋:
 *
 * <ol>
 *   <li><b>한 번도 받지 못한 장비에는 전체를 보냅니다.</b> 변경분만
 *       보내면 그 장비는 빈 마스터에 변경분만 얹은 채로 영업합니다
 *       (문서: "최초 설치나 복구 시 전체 마스터를 전송한다").
 *   <li><b>적용 버전은 장비가 말해야 올라갑니다.</b> 보낸 것과 적용된
 *       것은 다른 일입니다. 보냈다고 올리면 못 받은 장비가 최신으로
 *       보이고, 아무도 그 장비를 다시 챙기지 않습니다.
 *   <li><b>실패는 지우지 않습니다.</b> 그게 재전송 목록의 근거입니다.
 * </ol>
 *
 * <p>POS-Server 가 BO 마스터의 유일한 소비자가 아니라는 점도 문서에
 * 적혀 있습니다 — APP-SERVER 도 같은 View 를 읽습니다. 그래서 이
 * 클래스는 "마스터를 관리"하지 않고 <b>장비에 내려보낸 결과만</b>
 * 관리합니다.
 */
public final class MasterDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MasterDispatchService.class);

    private final MasterRepository repository;

    public MasterDispatchService(MasterRepository repository) {
        this.repository = repository;
    }

    /**
     * 그 장비가 지금 무엇을 받아야 하는가. <b>읽기만</b> 합니다.
     *
     * <p>조회와 전송 기록을 가른 이유: 관제 화면이 "이 장비가 뒤처져
     * 있나"를 보려고 부를 때마다 전송 시각이 갱신되면, 한 번도 보내지
     * 않은 마스터가 방금 보낸 것처럼 보입니다.
     */
    public List<MasterPlan> plan(String storeId, String deviceType, String deviceNo) {
        Map<MasterType, Long> versions = repository.versionsOf(storeId);
        Map<MasterType, MasterDispatch> applied = new EnumMap<>(MasterType.class);
        for (MasterDispatch dispatch : repository.dispatchesOf(storeId, deviceType, deviceNo)) {
            applied.put(dispatch.masterType(), dispatch);
        }

        List<MasterPlan> plans = new ArrayList<>();
        for (MasterType type : MasterType.values()) {
            Long current = versions.get(type);
            if (current == null) {
                // 그 매장에 아직 등록되지 않은 마스터입니다. 보낼 것이
                // 없는 것과 최신인 것은 다르므로 목록에 넣지 않습니다.
                continue;
            }
            MasterDispatch dispatch = applied.get(type);
            Long appliedVersion = dispatch == null ? null : dispatch.appliedVersion();
            plans.add(new MasterPlan(type, current, appliedVersion,
                    modeFor(current, appliedVersion),
                    dispatch == null ? null : dispatch.lastError()));
        }
        return plans;
    }

    /**
     * 계획을 세우고 <b>보낸 것으로 적습니다</b>.
     *
     * <p>돌려주는 것은 보낼 것만입니다. 최신인 마스터까지 내려보내면
     * 매장 회선으로 매번 전체가 흐릅니다.
     */
    public List<MasterPlan> issue(String storeId, String deviceType, String deviceNo) {
        List<MasterPlan> sending = plan(storeId, deviceType, deviceNo).stream()
                .filter(MasterPlan::needsSending)
                .toList();

        for (MasterPlan plan : sending) {
            repository.markSent(storeId, deviceType, deviceNo, plan.masterType());
        }
        if (!sending.isEmpty()) {
            log.info("{}/{}/{} 마스터 {}건 전송 — {}", storeId, deviceType, deviceNo, sending.size(),
                    sending.stream().map(p -> p.masterType() + ":" + p.mode()).toList());
        }
        return sending;
    }

    /**
     * 장비가 적용 결과를 답했습니다.
     *
     * <p>실패도 <b>받습니다</b>. 장비가 실패를 말할 수 없으면 서버는
     * "보냈는데 답이 없음"과 "받았지만 깨짐"을 구분할 수 없고, 둘은
     * 손봐야 할 곳이 다릅니다.
     */
    public void ack(String storeId, String deviceType, String deviceNo, MasterType masterType,
                    long version, boolean applied, String message) {
        repository.ack(storeId, deviceType, deviceNo, masterType, version, applied, message);
        if (applied) {
            log.info("{}/{}/{} {} v{} 적용", storeId, deviceType, deviceNo, masterType, version);
        } else {
            log.warn("{}/{}/{} {} v{} 적용 실패: {}", storeId, deviceType, deviceNo, masterType, version, message);
        }
    }

    /** 마스터가 바뀌었습니다 — BO(또는 BO-Interface)가 부르는 자리. */
    public long bumpVersion(String storeId, MasterType masterType) {
        long version = repository.bumpVersion(storeId, masterType);
        log.info("{} {} 마스터 v{}", storeId, masterType, version);
        return version;
    }

    /** 재전송 대상 — 아직 답이 없는 것과 실패한 것. */
    public List<MasterDispatch> unfinished(String storeId) {
        return repository.unfinished(storeId);
    }

    private static DispatchMode modeFor(long current, Long applied) {
        if (applied == null) {
            return DispatchMode.FULL;
        }
        if (applied == current) {
            return DispatchMode.UP_TO_DATE;
        }
        // 장비가 현재보다 높은 버전을 들고 있는 경우 — 마스터를 되돌렸거나
        // 장비를 다른 매장에서 옮겨 왔습니다. 변경분으로는 못 맞춥니다.
        return applied > current ? DispatchMode.FULL : DispatchMode.DELTA;
    }
}
