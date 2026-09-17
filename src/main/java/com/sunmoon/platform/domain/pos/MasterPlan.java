package com.sunmoon.platform.domain.pos;

/**
 * 장비 하나 · 마스터 하나에 대한 이번 배포 계획 (§3.6).
 *
 * @param appliedVersion 장비가 적용했다고 말한 버전. null 이면 한 번도
 *                       받지 못한 장비이고, 그때가 {@link DispatchMode#FULL} 입니다.
 */
public record MasterPlan(MasterType masterType, long currentVersion,
                         Long appliedVersion, DispatchMode mode, String lastError) {

    public boolean needsSending() {
        return mode != DispatchMode.UP_TO_DATE;
    }
}
