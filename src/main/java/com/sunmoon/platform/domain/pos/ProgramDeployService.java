package com.sunmoon.platform.domain.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로그램 배포 (업무 정의서 §3.5).
 *
 * <p>마스터 배포(§3.6)와 같은 규칙 위에 있습니다 — <b>깔린 버전은
 * 장비가 말해야 올라갑니다</b>. 내려줬다고 올리면 못 받은 장비가 최신인
 * 줄 알게 되고, 그 장비는 옛 프로그램으로 결제를 받습니다.
 *
 * <p>다른 점이 둘입니다:
 *
 * <ol>
 *   <li>버전이 숫자가 아니라 이름({@code 1.4.2})이라 크기를 비교하지
 *       않습니다. 최신은 <b>가장 나중에 낸 것</b>이고, 장비가 그것과
 *       다른 것을 들고 있으면 대상입니다. 되돌린 배포도 이 규칙으로
 *       저절로 처리됩니다 — 1.4.1 을 다시 내면 1.4.2 를 깐 장비가
 *       대상이 됩니다.
 *   <li>중간 상태가 있습니다. 받다 실패한 것과 깔다 실패한 것은
 *       손볼 곳이 다릅니다.
 * </ol>
 */
public final class ProgramDeployService {

    private static final Logger log = LoggerFactory.getLogger(ProgramDeployService.class);

    private final ProgramRepository repository;

    public ProgramDeployService(ProgramRepository repository) {
        this.repository = repository;
    }

    public long publish(ProgramRelease release) {
        long id = repository.publish(release);
        log.info("{} {} {} 배포 — {}{}",
                release.global() ? "전 매장" : release.storeId(),
                release.deviceType(), release.program(), release.version(),
                release.mandatory() ? " (필수)" : "");
        return id;
    }

    /**
     * 그 장비가 깔아야 할 것. <b>읽기만</b> 합니다.
     *
     * <p>이미 최신인 것도 목록에 넣고 {@link ProgramPlan#upToDate()} 로
     * 표시합니다 — 관제 화면은 "이 장비가 무엇을 들고 있나"를 묻지
     * "무엇이 밀렸나"만 묻지 않습니다.
     */
    public List<ProgramPlan> plan(String storeId, String deviceType, String deviceNo) {
        Map<String, ProgramInstall> installed = new HashMap<>();
        for (ProgramInstall install : repository.installsOf(storeId, deviceType, deviceNo)) {
            installed.put(install.program(), install);
        }

        List<ProgramPlan> plans = new ArrayList<>();
        for (ProgramRelease release : repository.currentFor(storeId, deviceType)) {
            ProgramInstall install = installed.get(release.program());
            plans.add(new ProgramPlan(
                    release.program(), release.version(),
                    install == null ? null : install.installedVersion(),
                    release.packageUrl(), release.checksum(), release.mandatory(),
                    install == null ? ProgramInstall.Status.OFFERED : install.status(),
                    install == null ? null : install.lastError()));
        }
        return plans;
    }

    /** 계획을 세우고 내려준 것으로 적습니다. 돌려주는 것은 밀린 것만입니다. */
    public List<ProgramPlan> issue(String storeId, String deviceType, String deviceNo) {
        List<ProgramPlan> sending = plan(storeId, deviceType, deviceNo).stream()
                .filter(plan -> !plan.upToDate())
                .toList();

        for (ProgramPlan plan : sending) {
            repository.markOffered(storeId, deviceType, deviceNo, plan.program(), plan.targetVersion());
        }
        if (!sending.isEmpty()) {
            log.info("{}/{}/{} 프로그램 {}건 — {}", storeId, deviceType, deviceNo, sending.size(),
                    sending.stream().map(p -> p.program() + " " + p.targetVersion()).toList());
        }
        return sending;
    }

    public void ack(String storeId, String deviceType, String deviceNo, String program,
                    String version, ProgramInstall.Status status, String message) {
        repository.ack(storeId, deviceType, deviceNo, program, version, status, message);
        if (status == ProgramInstall.Status.FAILED) {
            log.warn("{}/{}/{} {} {} 실패: {}", storeId, deviceType, deviceNo, program, version, message);
        } else {
            log.info("{}/{}/{} {} {} {}", storeId, deviceType, deviceNo, program, version, status);
        }
    }

    public List<ProgramInstall> unfinished(String storeId) {
        return repository.unfinished(storeId);
    }

    public List<ProgramInstall> installsOf(String storeId) {
        return repository.installsOf(storeId);
    }

    public List<ProgramRelease> releases(String storeId) {
        return repository.releases(storeId);
    }
}
