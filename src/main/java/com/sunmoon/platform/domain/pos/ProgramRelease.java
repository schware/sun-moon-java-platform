package com.sunmoon.platform.domain.pos;

import java.time.Instant;

/**
 * 장비가 깔아야 할 프로그램 한 벌 (업무 정의서 §3.5 프로그램 배포).
 *
 * @param storeId   null 이면 전 매장. 한 매장만 먼저 올려 보고 퍼뜨리는
 *                  것이 실제 운영 방식이라, 매장별 배포가 예외가 아니라
 *                  기본 모양이어야 합니다.
 * @param version   {@code 1.4.2} 같은 <b>이름</b>입니다. 숫자가 아니라서
 *                  크기를 비교하지 않습니다 — 최신은 가장 나중에 낸 것이고,
 *                  장비가 그것과 다른 것을 들고 있으면 대상입니다.
 * @param checksum  받은 파일이 그 파일인지 장비가 확인할 근거. 없이 설치하면
 *                  중간에 잘린 파일도 설치됩니다.
 * @param mandatory 참이면 장비가 미루지 않습니다 — 결제 모듈 긴급 패치 같은 것.
 */
public record ProgramRelease(
        Long id, String storeId, String deviceType, String program, String version,
        String packageUrl, String checksum, boolean mandatory, String note,
        Instant releasedAt) {

    public ProgramRelease {
        require(deviceType, "deviceType");
        require(program, "program");
        require(version, "version");
        require(packageUrl, "packageUrl");
    }

    /** 전 매장 배포인가. */
    public boolean global() {
        return storeId == null;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
