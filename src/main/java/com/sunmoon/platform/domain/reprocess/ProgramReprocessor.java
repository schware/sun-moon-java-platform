package com.sunmoon.platform.domain.reprocess;

import com.sunmoon.platform.domain.pos.ProgramInstall;
import com.sunmoon.platform.domain.pos.ProgramRepository;

import java.util.List;

/**
 * 설치에 실패한 프로그램을 다시 받게 만듭니다 (§3.5, §5.8).
 *
 * <p>마스터와 같습니다 — 장비가 가져가는 구조라 서버가 밀 수 없습니다.
 *
 * <p>다만 상태를 {@code OFFERED} 로 되돌립니다. 마스터와 다른 점:
 * 프로그램이 FAILED 라는 것은 이미 <b>내려줬다</b>는 뜻이라 OFFERED 가
 * 거짓말이 아닙니다. 마스터는 보낸 적 없이 실패했을 수 있어서 상태를
 * 건드리지 않습니다.
 */
public final class ProgramReprocessor implements Reprocessor {

    private final ProgramRepository programs;

    public ProgramReprocessor(ProgramRepository programs) {
        this.programs = programs;
    }

    @Override
    public String kind() {
        return "PROGRAM";
    }

    @Override
    public String label() {
        return "프로그램 설치";
    }

    @Override
    public List<FailedItem> failed(String storeId) {
        return programs.unfinished(storeId).stream()
                .filter(i -> i.status() == ProgramInstall.Status.FAILED)
                .map(i -> new FailedItem(
                        i.deviceType() + "/" + i.deviceNo() + "/" + i.program(),
                        i.storeId(),
                        i.deviceType() + " " + i.deviceNo() + " / " + i.program()
                                + " " + (i.targetVersion() == null ? "" : i.targetVersion()),
                        i.lastError(),
                        i.attempts(),
                        i.offeredAt()))
                .toList();
    }

    @Override
    public Outcome retry(String storeId, String id) {
        String[] parts = id.split("/", 3);
        if (parts.length != 3) {
            return Outcome.notFound(id);
        }
        return programs.clearFailure(storeId, parts[0], parts[1], parts[2])
                ? Outcome.queued("실패 표시를 지웠습니다 — 그 장비가 다음에 물어볼 때 다시 받습니다")
                : Outcome.notFound(id);
    }
}
