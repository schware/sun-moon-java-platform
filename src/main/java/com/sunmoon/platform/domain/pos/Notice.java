package com.sunmoon.platform.domain.pos;

import java.time.Instant;

/**
 * 공지 또는 긴급 메시지 (업무 정의서 §3.5).
 *
 * <p>둘이 <b>같은 표</b>입니다. 긴급은 미는 방식이 다를 뿐, 결국
 * 조회로도 읽혀야 합니다 — 그 순간 꺼져 있던 장비가 켜졌을 때 못 보면
 * 긴급이 아닙니다. 밀기만 하고 남기지 않으면 그 장비는 영영 모릅니다.
 *
 * @param storeId    null 이면 전 매장.
 * @param deviceType null 이면 전 장비 유형.
 * @param endsAt     null 이면 끝이 없습니다. 지난 공지도 지우지 않는
 *                   이유는 "그때 뭐라고 붙었었나"의 답이기 때문입니다.
 */
public record Notice(
        Long id, String storeId, String deviceType, String title, String body,
        boolean urgent, Instant startsAt, Instant endsAt,
        String createdBy, Instant createdAt) {

    public Notice {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        startsAt = startsAt == null ? Instant.now() : startsAt;
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt is before startsAt");
        }
    }

    public boolean activeAt(Instant when) {
        return !startsAt.isAfter(when) && (endsAt == null || endsAt.isAfter(when));
    }

    /** 그 장비에게 걸린 공지인가. null 은 "전부"라서 언제나 맞습니다. */
    public boolean appliesTo(String store, String type) {
        return (storeId == null || storeId.equals(store))
                && (deviceType == null || deviceType.equalsIgnoreCase(type));
    }
}
