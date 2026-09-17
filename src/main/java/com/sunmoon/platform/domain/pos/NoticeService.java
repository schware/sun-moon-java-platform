package com.sunmoon.platform.domain.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 공지와 긴급 메시지 (업무 정의서 §3.5).
 *
 * <p>규칙 하나: <b>미는 것은 덤이고 남기는 것이 본체입니다.</b>
 *
 * <p>긴급 메시지를 WebSocket 으로 밀자는 것이 문서의 제안인데, 밀기만
 * 하면 그 순간 꺼져 있던 장비는 영영 모릅니다. 그래서 순서가 정해져
 * 있습니다 — <b>먼저 적고, 그다음에 밉니다.</b> 미는 데 실패해도 적힌
 * 것은 남고, 장비가 켜질 때 조회로 봅니다. 반대로 했다면 밀기에 성공하고
 * 적기에 실패한 메시지가 "아무도 모르는데 누군가는 본" 상태가 됩니다.
 *
 * <p>미는 일 자체는 이 클래스가 하지 않습니다. {@link Broadcaster} 로
 * 받는 이유: 이 도메인이 Netty 채널을 알면 테스트할 때마다 소켓이
 * 필요해지고, 미는 방식이 바뀔 때(Redis 경유 등) 여기가 함께 바뀝니다.
 */
public final class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    /** 긴급 메시지를 그 매장 단말들에 밀어 넣습니다. 도달한 단말 수를 돌려줍니다. */
    @FunctionalInterface
    public interface Broadcaster {
        int push(Notice notice);

        /** 밀 곳이 없는 환경 — 적히기는 그대로 적힙니다. */
        Broadcaster NONE = notice -> 0;
    }

    private final NoticeRepository repository;
    private final Broadcaster broadcaster;
    private final Clock clock;

    public NoticeService(NoticeRepository repository, Broadcaster broadcaster, Clock clock) {
        this.repository = repository;
        this.broadcaster = broadcaster == null ? Broadcaster.NONE : broadcaster;
        this.clock = clock;
    }

    /** @param pushed 긴급일 때 실제로 밀어 넣은 단말 수. 일반 공지는 0입니다. */
    public record Published(long id, int pushed) {
    }

    public Published publish(Notice notice) {
        long id = repository.create(notice);

        if (!notice.urgent()) {
            log.info("공지 등록 #{} — {}", id, notice.title());
            return new Published(id, 0);
        }

        int pushed;
        try {
            pushed = broadcaster.push(new Notice(id, notice.storeId(), notice.deviceType(),
                    notice.title(), notice.body(), true, notice.startsAt(), notice.endsAt(),
                    notice.createdBy(), notice.createdAt()));
        } catch (RuntimeException pushFailed) {
            // 적힌 것은 이미 남았습니다. 못 민 것은 사고이지만, 여기서
            // 예외를 올리면 등록까지 실패한 것처럼 보이고 운영자는
            // 같은 메시지를 다시 씁니다.
            log.error("긴급 메시지 #{} 를 밀지 못했습니다 — 조회로는 보입니다", id, pushFailed);
            pushed = 0;
        }
        log.warn("긴급 메시지 #{} — {} ({}개 단말에 전달)", id, notice.title(), pushed);
        return new Published(id, pushed);
    }

    /** 그 장비가 지금 봐야 할 것. */
    public List<Notice> activeFor(String storeId, String deviceType) {
        return repository.activeFor(storeId, deviceType, clock.instant());
    }

    public List<Notice> all(String storeId, int limit) {
        return repository.all(storeId, limit);
    }

    public void acknowledge(long noticeId, String storeId, String deviceType, String deviceNo) {
        repository.acknowledge(noticeId, storeId, deviceType, deviceNo);
    }

    public List<NoticeRepository.Receipt> receiptsOf(long noticeId) {
        return repository.receiptsOf(noticeId);
    }

    public Instant now() {
        return clock.instant();
    }
}
