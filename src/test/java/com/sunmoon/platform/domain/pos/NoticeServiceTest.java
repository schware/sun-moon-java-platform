package com.sunmoon.platform.domain.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공지와 긴급 메시지 (업무 정의서 §3.5).
 *
 * <p>지키려는 것: <b>미는 것은 덤이고 남기는 것이 본체입니다.</b>
 * 밀기에 실패해도 적힌 것은 남아야 하고, 그 순간 꺼져 있던 장비가
 * 켜졌을 때 조회로 봐야 합니다.
 */
class NoticeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T05:00:00Z");

    private FakeNotices repository;

    @BeforeEach
    void setUp() {
        repository = new FakeNotices();
    }

    private NoticeService service(NoticeService.Broadcaster broadcaster) {
        return new NoticeService(repository, broadcaster, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Notice notice(String storeId, String deviceType, boolean urgent) {
        return new Notice(null, storeId, deviceType, "제목", "내용", urgent,
                NOW.minusSeconds(60), null, "bo-admin", NOW);
    }

    @Test
    void anOrdinaryNoticeIsStoredAndNotPushed() {
        List<Notice> pushed = new ArrayList<>();

        NoticeService.Published published =
                service(n -> { pushed.add(n); return 1; }).publish(notice("store-01", null, false));

        assertEquals(0, published.pushed());
        assertTrue(pushed.isEmpty());
        assertEquals(1, repository.stored.size());
    }

    @Test
    void anUrgentMessageIsPushedToConnectedTerminals() {
        NoticeService.Published published =
                service(n -> 3).publish(notice("store-01", "POS", true));

        assertEquals(3, published.pushed());
        assertEquals(1, repository.stored.size());
    }

    /**
     * <b>밀기에 실패해도 메시지는 남습니다.</b>
     *
     * <p>여기서 예외를 올리면 등록까지 실패한 것처럼 보이고, 운영자는
     * 같은 긴급 메시지를 다시 씁니다 — 그러면 표에 두 줄이 남고 장비는
     * 같은 것을 두 번 봅니다.
     */
    @Test
    void aFailedPushDoesNotLoseTheMessage() {
        NoticeService.Published published = service(n -> {
            throw new IllegalStateException("소켓이 죽었습니다");
        }).publish(notice("store-01", null, true));

        assertEquals(0, published.pushed());
        assertEquals(1, repository.stored.size());
        assertTrue(repository.stored.get(0).urgent());
    }

    /** 밀 곳이 없어도 (아무도 안 붙어 있어도) 실패가 아닙니다 — 켜지면 조회로 봅니다. */
    @Test
    void nobodyConnectedIsNotAFailure() {
        NoticeService.Published published =
                service(NoticeService.Broadcaster.NONE).publish(notice("store-01", null, true));

        assertEquals(0, published.pushed());
        assertEquals(1, repository.stored.size());
    }

    /** 미는 쪽에 넘어가는 공지에는 <b>id 가 채워져</b> 있어야 합니다 — 장비가 확인을 보낼 근거입니다. */
    @Test
    void thePushedNoticeCarriesItsId() {
        Set<Long> seen = new LinkedHashSet<>();

        service(n -> { seen.add(n.id()); return 1; }).publish(notice("store-01", null, true));

        assertEquals(Set.of(1L), seen);
    }

    /** 기간이 지난 공지는 조회에서 빠집니다 — 지우지는 않습니다. */
    @Test
    void anExpiredNoticeIsNotActive() {
        Notice expired = new Notice(null, "store-01", null, "지난 공지", "내용", false,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), null, NOW);

        assertFalse(expired.activeAt(NOW));
        assertTrue(expired.activeAt(NOW.minus(Duration.ofDays(1).plusHours(1))));
    }

    /** 매장과 장비 유형이 비어 있으면 전부에게 걸립니다. */
    @Test
    void anEmptyScopeMeansEveryone() {
        Notice everyone = notice(null, null, true);

        assertTrue(everyone.appliesTo("store-01", "POS"));
        assertTrue(everyone.appliesTo("store-99", "KDS"));
    }

    @Test
    void aScopedNoticeSkipsOtherStoresAndOtherKinds() {
        Notice justPos = notice("store-01", "POS", true);

        assertTrue(justPos.appliesTo("store-01", "POS"));
        assertFalse(justPos.appliesTo("store-02", "POS"));
        assertFalse(justPos.appliesTo("store-01", "KDS"));
    }

    @Test
    void anEmptyTitleOrBodyIsRefused() {
        // 제목 없는 긴급 메시지는 화면에 빈 줄로 뜹니다 — 받은 사람은
        // 고장인지 메시지인지 알 수 없습니다.
        assertThrows(IllegalArgumentException.class,
                () -> new Notice(null, null, null, " ", "내용", true, NOW, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new Notice(null, null, null, "제목", "", true, NOW, null, null, NOW));
    }

    @Test
    void anEndBeforeTheStartIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Notice(null, null, null, "제목", "내용", false,
                        NOW, NOW.minusSeconds(1), null, NOW));
    }

    private static final class FakeNotices implements NoticeRepository {
        private final List<Notice> stored = new ArrayList<>();
        private final List<Receipt> receipts = new ArrayList<>();
        private long nextId = 1;

        @Override
        public long create(Notice notice) {
            long id = nextId++;
            stored.add(new Notice(id, notice.storeId(), notice.deviceType(), notice.title(),
                    notice.body(), notice.urgent(), notice.startsAt(), notice.endsAt(),
                    notice.createdBy(), notice.createdAt()));
            return id;
        }

        @Override
        public List<Notice> activeFor(String storeId, String deviceType, Instant when) {
            return stored.stream()
                    .filter(n -> n.appliesTo(storeId, deviceType))
                    .filter(n -> n.activeAt(when))
                    .toList();
        }

        @Override
        public List<Notice> all(String storeId, int limit) {
            return stored;
        }

        @Override
        public Optional<Notice> find(long id) {
            return stored.stream().filter(n -> n.id() == id).findFirst();
        }

        /** 확인 기록. 실제 표처럼 <b>같은 장비가 두 번 확인해도 한 줄</b>입니다. */
        private final java.util.Map<Long, java.util.Set<String>> seen = new java.util.HashMap<>();

        @Override
        public void acknowledge(long noticeId, String storeId, String deviceType, String deviceNo) {
            boolean isNew = seen.computeIfAbsent(noticeId, k -> new java.util.LinkedHashSet<>())
                    .add(storeId + "|" + deviceType + "|" + deviceNo);
            if (isNew) {
                receipts.add(new Receipt(storeId, deviceType, deviceNo, Instant.now()));
            }
        }

        @Override
        public java.util.Set<Long> acknowledgedBy(String storeId, String deviceType, String deviceNo) {
            if (storeId == null || deviceType == null || deviceNo == null) {
                // 장비를 모르면 "아무것도 확인 안 했다"입니다. 전부 확인한
                // 것으로 치면 새 단말이 긴급 메시지를 영영 못 봅니다.
                return java.util.Set.of();
            }
            String who = storeId + "|" + deviceType + "|" + deviceNo;
            return seen.entrySet().stream()
                    .filter(e -> e.getValue().contains(who))
                    .map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public List<Receipt> receiptsOf(long noticeId) {
            return receipts;
        }
    }
}
