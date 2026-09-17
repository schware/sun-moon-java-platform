package com.sunmoon.platform.domain.pos;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 공지와 긴급 메시지, 그리고 누가 봤는가 (§3.5). */
public interface NoticeRepository {

    long create(Notice notice);

    /** 지금 그 장비에 걸려 있는 것. 기간이 지난 것은 빠집니다. */
    List<Notice> activeFor(String storeId, String deviceType, Instant when);

    /** BO가 보는 목록 — 지난 것까지 전부. */
    List<Notice> all(String storeId, int limit);

    Optional<Notice> find(long id);

    /**
     * 그 장비가 봤다고 적습니다.
     *
     * <p>일반 공지에는 필요 없지만 긴급에는 필요합니다 — 안 본 장비가
     * 있으면 전화를 걸어야 하고, 그 목록이 없으면 전부에게 걸게 됩니다.
     */
    void acknowledge(long noticeId, String storeId, String deviceType, String deviceNo);

    List<Receipt> receiptsOf(long noticeId);

    record Receipt(String storeId, String deviceType, String deviceNo, Instant acknowledgedAt) {
    }
}
