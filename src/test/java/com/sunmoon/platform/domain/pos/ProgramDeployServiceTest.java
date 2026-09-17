package com.sunmoon.platform.domain.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 프로그램 배포 (업무 정의서 §3.5).
 *
 * <p>마스터 배포와 같은 무게중심입니다 — <b>깔린 버전은 장비가 말해야
 * 올라갑니다.</b> 다른 점은 버전이 숫자가 아니라 이름이라는 것이고,
 * 그래서 "되돌리기"가 특별한 경우가 아니라 그냥 새 배포입니다.
 */
class ProgramDeployServiceTest {

    private static final String STORE = "store-01";
    private static final String OTHER_STORE = "store-02";
    private static final String TYPE = "POS";
    private static final String DEVICE = "pos-01";

    private FakePrograms repository;
    private ProgramDeployService service;

    @BeforeEach
    void setUp() {
        repository = new FakePrograms();
        service = new ProgramDeployService(repository);
    }

    private void release(String storeId, String program, String version, boolean mandatory) {
        service.publish(new ProgramRelease(null, storeId, TYPE, program, version,
                "https://files/" + program + "-" + version + ".zip", "sha256:x", mandatory,
                null, Instant.now()));
    }

    private ProgramPlan planFor(String program) {
        return service.plan(STORE, TYPE, DEVICE).stream()
                .filter(plan -> plan.program().equals(program))
                .findFirst().orElseThrow();
    }

    @Test
    void aDeviceWithNothingInstalledIsBehind() {
        release(null, "pos-main", "1.4.2", false);

        ProgramPlan plan = planFor("pos-main");

        assertEquals("1.4.2", plan.targetVersion());
        assertNull(plan.installedVersion());
        assertFalse(plan.upToDate());
        assertTrue(plan.packageUrl().endsWith("pos-main-1.4.2.zip"));
    }

    /**
     * <b>내려준 것과 깔린 것은 다른 일입니다.</b>
     *
     * <p>전달만으로 버전이 올라가면 회선이 끊겨 못 받은 장비가 최신으로
     * 보이고, 재전송 목록에서도 빠집니다 — 그 장비는 옛 프로그램으로
     * 결제를 받습니다.
     */
    @Test
    void offeringIsNotInstalling() {
        release(null, "pos-main", "1.4.2", false);
        service.issue(STORE, TYPE, DEVICE);

        assertNull(planFor("pos-main").installedVersion());
        assertFalse(planFor("pos-main").upToDate());
        assertEquals(1, service.unfinished(STORE).size());
    }

    @Test
    void anInstalledProgramLeavesTheRetryList() {
        release(null, "pos-main", "1.4.2", false);
        service.issue(STORE, TYPE, DEVICE);
        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.2",
                ProgramInstall.Status.INSTALLED, null);

        assertTrue(planFor("pos-main").upToDate());
        assertTrue(service.unfinished(STORE).isEmpty());
    }

    /**
     * 받는 데 실패한 것과 깔다 실패한 것은 <b>다른 상태로 남습니다</b>.
     *
     * <p>앞은 회선이나 파일 주소를 봐야 하고 뒤는 장비를 봐야 합니다.
     * 하나로 묶으면 매장에 나가서야 알게 됩니다.
     */
    @Test
    void downloadingAndInstallingFailSeparately() {
        release(null, "pos-main", "1.4.2", false);

        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.2",
                ProgramInstall.Status.DOWNLOADED, null);
        assertEquals(ProgramInstall.Status.DOWNLOADED, planFor("pos-main").status());

        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.2",
                ProgramInstall.Status.FAILED, "설치 중 디스크가 찼습니다");
        assertEquals(ProgramInstall.Status.FAILED, planFor("pos-main").status());
        assertEquals("설치 중 디스크가 찼습니다", planFor("pos-main").lastError());
        // 깔린 버전은 그대로 비어 있습니다 — 못 깔았으니까요.
        assertNull(planFor("pos-main").installedVersion());
    }

    /** 실패해도 그 전에 깔려 있던 버전은 살아 있습니다 — 되돌아간 것이 아니라 앞으로 못 간 것입니다. */
    @Test
    void aFailedUpgradeKeepsTheVersionTheDeviceActuallyHas() {
        release(null, "pos-main", "1.4.1", false);
        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.1",
                ProgramInstall.Status.INSTALLED, null);

        release(null, "pos-main", "1.4.2", false);
        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.2",
                ProgramInstall.Status.FAILED, "체크섬이 다릅니다");

        assertEquals("1.4.1", planFor("pos-main").installedVersion());
        assertFalse(planFor("pos-main").upToDate());
    }

    /**
     * 되돌리기가 특별한 경우가 아닙니다.
     *
     * <p>버전이 이름이라 크기를 비교하지 않습니다 — 최신은 가장 나중에
     * 낸 것입니다. 1.4.1 을 다시 내면 1.4.2 를 깐 장비가 대상이 됩니다.
     * 크기를 비교했다면 되돌리기에 특별한 코드가 필요했을 것입니다.
     */
    @Test
    void rollingBackIsJustAnotherRelease() {
        release(null, "pos-main", "1.4.2", false);
        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.2",
                ProgramInstall.Status.INSTALLED, null);
        assertTrue(planFor("pos-main").upToDate());

        release(null, "pos-main", "1.4.1", true);

        assertEquals("1.4.1", planFor("pos-main").targetVersion());
        assertFalse(planFor("pos-main").upToDate());
        assertTrue(planFor("pos-main").mandatory());
    }

    /**
     * 매장 지정 배포가 전 매장 배포를 이깁니다.
     *
     * <p>한 매장만 먼저 올려 보는 것이 배포의 정상 모양입니다. 그때
     * 전 매장 배포가 그것을 덮으면 시험 자체가 성립하지 않습니다.
     */
    @Test
    void aStoreSpecificReleaseBeatsTheGlobalOne() {
        release(null, "pos-main", "1.4.2", false);
        release(STORE, "pos-main", "1.5.0-beta", false);

        assertEquals("1.5.0-beta", planFor("pos-main").targetVersion());
        // 옆 매장은 전 매장 배포 그대로입니다.
        assertEquals("1.4.2", service.plan(OTHER_STORE, TYPE, DEVICE).get(0).targetVersion());
    }

    /** 최신인 것은 내려보내지 않습니다 — 매장 회선으로 매번 전체가 흐르면 안 됩니다. */
    @Test
    void issueOnlySendsWhatIsBehind() {
        release(null, "pos-main", "1.4.2", false);
        release(null, "card-module", "3.0.1", true);
        service.ack(STORE, TYPE, DEVICE, "pos-main", "1.4.2",
                ProgramInstall.Status.INSTALLED, null);

        List<ProgramPlan> sending = service.issue(STORE, TYPE, DEVICE);

        assertEquals(1, sending.size());
        assertEquals("card-module", sending.get(0).program());
    }

    private static final class FakePrograms implements ProgramRepository {

        private final List<ProgramRelease> releases = new ArrayList<>();
        private final Map<String, ProgramInstall> installs = new HashMap<>();
        private long nextId = 1;

        private static String key(String store, String type, String no, String program) {
            return store + "/" + type + "/" + no + "/" + program;
        }

        @Override
        public long publish(ProgramRelease release) {
            // 같은 (매장, 유형, 프로그램, 버전) 은 덮어씁니다 — 실제
            // 저장소의 유일 인덱스가 하는 일입니다.
            releases.removeIf(r -> java.util.Objects.equals(r.storeId(), release.storeId())
                    && r.deviceType().equals(release.deviceType())
                    && r.program().equals(release.program())
                    && r.version().equals(release.version()));
            long id = nextId++;
            releases.add(new ProgramRelease(id, release.storeId(), release.deviceType(),
                    release.program(), release.version(), release.packageUrl(), release.checksum(),
                    release.mandatory(), release.note(), Instant.now().plusNanos(id)));
            return id;
        }

        @Override
        public List<ProgramRelease> currentFor(String storeId, String deviceType) {
            Map<String, ProgramRelease> latest = new java.util.LinkedHashMap<>();
            List<ProgramRelease> candidates = new ArrayList<>(releases.stream()
                    .filter(r -> r.deviceType().equals(deviceType))
                    .filter(r -> r.storeId() == null || r.storeId().equals(storeId))
                    .toList());
            // 매장 지정이 먼저, 그다음 늦게 낸 것이 먼저.
            candidates.sort((a, b) -> {
                int byScope = Boolean.compare(a.global(), b.global());
                if (byScope != 0) {
                    return byScope;
                }
                return b.releasedAt().compareTo(a.releasedAt());
            });
            for (ProgramRelease release : candidates) {
                latest.putIfAbsent(release.program(), release);
            }
            return List.copyOf(latest.values());
        }

        @Override
        public List<ProgramRelease> releases(String storeId) {
            return releases.stream()
                    .filter(r -> r.storeId() == null || r.storeId().equals(storeId))
                    .toList();
        }

        @Override
        public List<ProgramInstall> installsOf(String storeId, String deviceType, String deviceNo) {
            return installs.values().stream()
                    .filter(i -> i.storeId().equals(storeId)
                            && i.deviceType().equals(deviceType)
                            && i.deviceNo().equals(deviceNo))
                    .toList();
        }

        @Override
        public List<ProgramInstall> installsOf(String storeId) {
            return installs.values().stream().filter(i -> i.storeId().equals(storeId)).toList();
        }

        @Override
        public void markOffered(String storeId, String deviceType, String deviceNo,
                                String program, String targetVersion) {
            String k = key(storeId, deviceType, deviceNo, program);
            ProgramInstall before = installs.get(k);
            installs.put(k, new ProgramInstall(storeId, deviceType, deviceNo, program,
                    before == null ? null : before.installedVersion(), targetVersion,
                    ProgramInstall.Status.OFFERED,
                    before == null ? 1 : before.attempts() + 1,
                    before == null ? null : before.lastError(),
                    Instant.now(), before == null ? null : before.installedAt()));
        }

        @Override
        public void ack(String storeId, String deviceType, String deviceNo, String program,
                        String version, ProgramInstall.Status status, String message) {
            String k = key(storeId, deviceType, deviceNo, program);
            ProgramInstall before = installs.get(k);
            String installedVersion = status == ProgramInstall.Status.INSTALLED
                    ? version : (before == null ? null : before.installedVersion());
            installs.put(k, new ProgramInstall(storeId, deviceType, deviceNo, program,
                    installedVersion, version, status,
                    before == null ? 1 : before.attempts(),
                    status == ProgramInstall.Status.FAILED ? message : null,
                    before == null ? null : before.offeredAt(),
                    status == ProgramInstall.Status.INSTALLED ? Instant.now()
                            : (before == null ? null : before.installedAt())));
        }

        @Override
        public List<ProgramInstall> unfinished(String storeId) {
            return installs.values().stream()
                    .filter(i -> i.storeId().equals(storeId))
                    .filter(i -> i.status() != ProgramInstall.Status.INSTALLED)
                    .toList();
        }

        @Override
        public Optional<ProgramInstall> find(String storeId, String deviceType, String deviceNo,
                                             String program) {
            return Optional.ofNullable(installs.get(key(storeId, deviceType, deviceNo, program)));
        }
    }
}
