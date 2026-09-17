package com.sunmoon.platform.domain.pos;

import java.time.Instant;

/**
 * 장비 하나가 프로그램 하나를 어디까지 했는가 (§3.5 "다운로드, 설치 및
 * 적용 결과를 관리한다").
 *
 * <p>마스터 배포와 달리 <b>중간 상태가 있습니다</b>. 받는 데 실패한
 * 장비와 깔다 실패한 장비는 손볼 곳이 다릅니다 — 앞은 회선이나 파일
 * 주소이고 뒤는 장비입니다. 하나로 묶으면 매장에 나가서야 알게 됩니다.
 *
 * @param installedVersion 장비가 "깔았다"고 말한 버전. null 이면 아직
 *                         아무것도 못 깔았습니다.
 * @param targetVersion    지금 받고 있거나 깔고 있는 버전. 실패하면 여기
 *                         남아서 무엇을 하다 실패했는지가 보입니다.
 */
public record ProgramInstall(
        String storeId, String deviceType, String deviceNo, String program,
        String installedVersion, String targetVersion, Status status,
        int attempts, String lastError, Instant offeredAt, Instant installedAt) {

    public enum Status {
        /** 서버가 "이걸 깔아라"라고 내려줬습니다. 아직 장비는 아무 말이 없습니다. */
        OFFERED,
        /** 파일은 받았습니다. 아직 안 깔았습니다. */
        DOWNLOADED,
        INSTALLED,
        FAILED;

        public static Status of(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("status is required");
            }
            return switch (raw.trim().toUpperCase()) {
                case "OFFERED" -> OFFERED;
                case "DOWNLOADED" -> DOWNLOADED;
                case "INSTALLED" -> INSTALLED;
                case "FAILED" -> FAILED;
                default -> throw new IllegalArgumentException(
                        "status must be OFFERED, DOWNLOADED, INSTALLED or FAILED: " + raw);
            };
        }
    }
}
