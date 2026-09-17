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

    /**
     * 그 <b>장비</b>가 이미 확인한 공지들.
     *
     * <p>2026-09-18에 붙었습니다. 그전에는 조회에 장비 번호가 아예 없어서,
     * 확인을 눌러도 다음 조회에 같은 긴급 메시지가 또 왔습니다 — 서버는
     * 확인 기록을 갖고 있었는데 <b>읽지를 않았습니다</b>.
     *
     * <p>공지를 목록에서 빼지 않고 <b>표시만</b> 하는 이유: 한 번 읽고
     * 사라지는 글이면 내용을 되짚을 수가 없습니다. 화면을 가로막는 것은
     * 안 본 것만, 목록에는 본 것도 남습니다.
     */
    java.util.Set<Long> acknowledgedBy(String storeId, String deviceType, String deviceNo);

    record Receipt(String storeId, String deviceType, String deviceNo, Instant acknowledgedAt) {
    }
}
