package com.sunmoon.platform.domain.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마스터 IRT 배포의 규칙 (업무 정의서 §3.6).
 *
 * <p>무게중심은 <b>적용 버전은 장비가 말해야 올라간다</b>는 한 줄
 * 입니다. 보냈다고 올리면 못 받은 장비가 최신으로 보이고, 그 장비는
 * 아무도 챙기지 않는 채로 옛 메뉴·옛 가격으로 영업합니다.
 */
class MasterDispatchServiceTest {

    private static final String STORE = "store-01";
    private static final String TYPE = "POS";
    private static final String DEVICE = "pos-01";

    private FakeMasters repository;
    private MasterDispatchService service;

    @BeforeEach
    void setUp() {
        repository = new FakeMasters();
        service = new MasterDispatchService(repository);
    }

    private Optional<MasterPlan> planFor(MasterType type) {
        return service.plan(STORE, TYPE, DEVICE).stream()
                .filter(plan -> plan.masterType() == type)
                .findFirst();
    }

    /**
     * 한 번도 받지 못한 장비에는 <b>전체</b>를 보냅니다.
     *
     * <p>변경분만 보내면 그 장비는 빈 마스터에 변경분만 얹은 채로
     * 영업합니다 — 메뉴가 세 개뿐인 POS.
     */
    @Test
    void aDeviceThatNeverReceivedAnythingGetsTheWholeMaster() {
        service.bumpVersion(STORE, MasterType.MENU);

        MasterPlan plan = planFor(MasterType.MENU).orElseThrow();

        assertEquals(DispatchMode.FULL, plan.mode());
        assertNull(plan.appliedVersion());
    }

    @Test
    void aDeviceOneVersionBehindGetsTheDifference() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.ack(STORE, TYPE, DEVICE, MasterType.MENU, 1, true, null);
        long second = service.bumpVersion(STORE, MasterType.MENU);

        MasterPlan plan = planFor(MasterType.MENU).orElseThrow();

        assertEquals(DispatchMode.DELTA, plan.mode());
        assertEquals(1L, plan.appliedVersion());
        assertEquals(second, plan.currentVersion());
    }

    @Test
    void aDeviceOnTheCurrentVersionGetsNothing() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.ack(STORE, TYPE, DEVICE, MasterType.MENU, 1, true, null);

        MasterPlan plan = planFor(MasterType.MENU).orElseThrow();

        assertEquals(DispatchMode.UP_TO_DATE, plan.mode());
        assertTrue(service.issue(STORE, TYPE, DEVICE).isEmpty());
    }

    /**
     * <b>보낸 것과 적용된 것은 다른 일입니다.</b>
     *
     * <p>전송만으로 적용 버전이 올라가면, 회선이 끊겨 못 받은 장비가
     * 최신으로 보입니다. 그러면 재전송 목록에서도 빠지고, 그 장비는
     * 영영 뒤처진 채로 남습니다.
     */
    @Test
    void sendingDoesNotCountAsApplying() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.issue(STORE, TYPE, DEVICE);

        MasterPlan stillBehind = planFor(MasterType.MENU).orElseThrow();
        assertEquals(DispatchMode.FULL, stillBehind.mode());
        assertNull(stillBehind.appliedVersion());
        // 그리고 여전히 재전송 대상입니다.
        assertEquals(1, service.unfinished(STORE).size());
    }

    /** 장비가 실패를 답하면 적용 버전은 그대로입니다 — 새 버전을 못 받았을 뿐 옛 버전은 살아 있습니다. */
    @Test
    void aFailedApplyKeepsTheOldVersion() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.ack(STORE, TYPE, DEVICE, MasterType.MENU, 1, true, null);
        service.bumpVersion(STORE, MasterType.MENU);
        service.ack(STORE, TYPE, DEVICE, MasterType.MENU, 2, false, "터치키 파일이 깨졌습니다");

        MasterPlan plan = planFor(MasterType.MENU).orElseThrow();

        assertEquals(1L, plan.appliedVersion());
        assertEquals(DispatchMode.DELTA, plan.mode());
        assertEquals("터치키 파일이 깨졌습니다", plan.lastError());
        assertEquals(1, service.unfinished(STORE).size());
    }

    /** 적용에 성공하면 재전송 목록에서 빠집니다. */
    @Test
    void anAppliedMasterLeavesTheRetryList() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.issue(STORE, TYPE, DEVICE);
        service.ack(STORE, TYPE, DEVICE, MasterType.MENU, 1, true, null);

        assertTrue(service.unfinished(STORE).isEmpty());
    }

    /**
     * 장비가 현재보다 높은 버전을 들고 있으면 전체를 보냅니다.
     *
     * <p>마스터를 되돌렸거나 장비를 다른 매장에서 옮겨 온 경우입니다.
     * 변경분으로는 못 맞춥니다 — 되돌릴 변경분이 없습니다.
     */
    @Test
    void aDeviceAheadOfTheServerIsResentInFull() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.ack(STORE, TYPE, DEVICE, MasterType.MENU, 9, true, null);

        assertEquals(DispatchMode.FULL, planFor(MasterType.MENU).orElseThrow().mode());
    }

    /** 그 매장에 등록되지 않은 마스터는 목록에 없습니다 — 보낼 것이 없는 것과 최신인 것은 다릅니다. */
    @Test
    void aMasterNeverRegisteredForThisStoreIsNotInThePlan() {
        service.bumpVersion(STORE, MasterType.MENU);

        List<MasterPlan> plans = service.plan(STORE, TYPE, DEVICE);

        assertEquals(1, plans.size());
        assertEquals(MasterType.MENU, plans.get(0).masterType());
    }

    /** 같은 번호의 POS와 KIOSK는 서로를 덮지 않습니다 — 키에 장비 유형이 들어가는 이유입니다. */
    @Test
    void aPosAndAKioskWithTheSameNumberAreDifferentDevices() {
        service.bumpVersion(STORE, MasterType.MENU);
        service.ack(STORE, "POS", "01", MasterType.MENU, 1, true, null);

        List<MasterPlan> kiosk = service.plan(STORE, "KIOSK", "01");

        assertEquals(DispatchMode.FULL, kiosk.get(0).mode());
    }

    private static final class FakeMasters implements MasterRepository {

        private final Map<String, Map<MasterType, Long>> versions = new HashMap<>();
        private final Map<String, MasterDispatch> dispatches = new HashMap<>();

        private static String key(String store, String type, String no, MasterType master) {
            return store + "/" + type + "/" + no + "/" + master;
        }

        @Override
        public Map<MasterType, Long> versionsOf(String storeId) {
            return versions.getOrDefault(storeId, new EnumMap<>(MasterType.class));
        }

        @Override
        public long bumpVersion(String storeId, MasterType masterType) {
            Map<MasterType, Long> store =
                    versions.computeIfAbsent(storeId, k -> new EnumMap<>(MasterType.class));
            long next = store.getOrDefault(masterType, 0L) + 1;
            store.put(masterType, next);
            return next;
        }

        @Override
        public List<MasterDispatch> dispatchesOf(String storeId, String deviceType, String deviceNo) {
            List<MasterDispatch> found = new ArrayList<>();
            for (MasterDispatch dispatch : dispatches.values()) {
                if (dispatch.storeId().equals(storeId)
                        && dispatch.deviceType().equals(deviceType)
                        && dispatch.deviceNo().equals(deviceNo)) {
                    found.add(dispatch);
                }
            }
            return found;
        }

        @Override
        public void markSent(String storeId, String deviceType, String deviceNo, MasterType masterType) {
            String k = key(storeId, deviceType, deviceNo, masterType);
            MasterDispatch before = dispatches.get(k);
            dispatches.put(k, new MasterDispatch(storeId, deviceType, deviceNo, masterType,
                    before == null ? null : before.appliedVersion(),
                    MasterDispatch.Status.SENT,
                    before == null ? 1 : before.attempts() + 1,
                    before == null ? null : before.lastError(),
                    Instant.now(), before == null ? null : before.appliedAt()));
        }

        @Override
        public void ack(String storeId, String deviceType, String deviceNo, MasterType masterType,
                        long version, boolean applied, String message) {
            String k = key(storeId, deviceType, deviceNo, masterType);
            MasterDispatch before = dispatches.get(k);
            Long appliedVersion = applied ? version : (before == null ? null : before.appliedVersion());
            dispatches.put(k, new MasterDispatch(storeId, deviceType, deviceNo, masterType,
                    appliedVersion,
                    applied ? MasterDispatch.Status.APPLIED : MasterDispatch.Status.FAILED,
                    before == null ? 1 : before.attempts(),
                    applied ? null : message,
                    before == null ? null : before.sentAt(),
                    applied ? Instant.now() : (before == null ? null : before.appliedAt())));
        }

        @Override
        public List<MasterDispatch> unfinished(String storeId) {
            List<MasterDispatch> found = new ArrayList<>();
            for (MasterDispatch dispatch : dispatches.values()) {
                if (dispatch.storeId().equals(storeId)
                        && dispatch.status() != MasterDispatch.Status.APPLIED) {
                    found.add(dispatch);
                }
            }
            return found;
        }

        @Override
        public Optional<MasterDispatch> find(String storeId, String deviceType, String deviceNo,
                                             MasterType masterType) {
            return Optional.ofNullable(dispatches.get(key(storeId, deviceType, deviceNo, masterType)));
        }
    }
}
