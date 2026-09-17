package com.sunmoon.platform.domain.reprocess;

import com.sunmoon.platform.domain.pos.MasterDispatch;
import com.sunmoon.platform.domain.pos.MasterRepository;
import com.sunmoon.platform.domain.pos.MasterType;

import java.util.List;

/**
 * 적용에 실패한 마스터를 다시 받게 만듭니다 (§3.6, §5.8).
 *
 * <p>매출과 달리 <b>이 서버가 다시 할 수 없습니다</b> — 마스터는 장비가
 * 가져갑니다. 여기서 하는 일은 실패 표시를 지워 "다시 받아야 할 것"으로
 * 되돌리는 것까지이고, 실제 재전송은 그 장비가 다음에 물어볼 때입니다.
 *
 * <p>그 차이를 답에 적습니다. 안 적으면 운영자는 눌렀는데 목록이 그대로인
 * 것을 보고 계속 누르게 되고, 시도 횟수만 늘어납니다.
 */
public final class MasterReprocessor implements Reprocessor {

    private final MasterRepository masters;

    public MasterReprocessor(MasterRepository masters) {
        this.masters = masters;
    }

    @Override
    public String kind() {
        return "MASTER";
    }

    @Override
    public String label() {
        return "마스터 배포";
    }

    /**
     * {@code unfinished} 가 아니라 <b>실패한 것만</b> 고릅니다.
     *
     * <p>SENT 는 실패가 아니라 아직 모르는 상태라, 재처리 화면에 섞으면
     * 고칠 것이 없는 줄이 목록을 채웁니다 — 그러면 진짜 실패가 묻힙니다.
     */
    @Override
    public List<FailedItem> failed(String storeId) {
        return masters.unfinished(storeId).stream()
                .filter(d -> d.status() == MasterDispatch.Status.FAILED)
                .map(d -> new FailedItem(
                        // 키가 넷이라 합쳐서 한 값으로 만듭니다. 되돌릴 때
                        // 다시 가릅니다 — 화면이 넷을 들고 다니게 하면
                        // 목록과 되돌리기가 서로 다른 모양이 됩니다.
                        d.deviceType() + "/" + d.deviceNo() + "/" + d.masterType().name(),
                        d.storeId(),
                        d.deviceType() + " " + d.deviceNo() + " / " + d.masterType().label(),
                        d.lastError(),
                        d.attempts(),
                        d.sentAt()))
                .toList();
    }

    @Override
    public Outcome retry(String storeId, String id) {
        String[] parts = id.split("/", 3);
        if (parts.length != 3) {
            return Outcome.notFound(id);
        }
        return MasterType.of(parts[2])
                .map(type -> masters.clearFailure(storeId, parts[0], parts[1], type)
                        ? Outcome.queued("실패 표시를 지웠습니다 — 그 장비가 다음에 물어볼 때 다시 받습니다")
                        : Outcome.notFound(id))
                .orElseGet(() -> Outcome.notFound(id));
    }
}
