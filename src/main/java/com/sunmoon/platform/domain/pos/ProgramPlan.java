package com.sunmoon.platform.domain.pos;

/**
 * 그 장비가 지금 깔아야 할 것 하나 (§3.5).
 *
 * <p>{@link MasterPlan} 과 같은 자리이지만 답이 다릅니다 — 마스터는
 * "전체냐 변경분이냐"를 묻고, 프로그램은 <b>어느 버전을 어디서 받느냐</b>를
 * 묻습니다. 프로그램에는 변경분이 없습니다.
 */
public record ProgramPlan(
        String program, String targetVersion, String installedVersion,
        String packageUrl, String checksum, boolean mandatory,
        ProgramInstall.Status status, String lastError) {

    /** 이미 그 버전을 깔아 둔 장비인가. */
    public boolean upToDate() {
        return targetVersion.equals(installedVersion);
    }
}
