package com.sunmoon.platform.transport.http.bo.commoncode;

import jakarta.validation.constraints.NotBlank;

/** Shared shape for both create (POST) and save (PUT) — the two 4-tier actions differ in which repository method the endpoint calls, not in what's in the body. */
public record CommonCodeRequest(
        @NotBlank String groupCode,
        @NotBlank String code,
        @NotBlank String name,
        int sortOrder,
        boolean active
) {
}
