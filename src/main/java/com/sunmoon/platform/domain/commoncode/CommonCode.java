package com.sunmoon.platform.domain.commoncode;

/** A single row in a shared reference/code table — e.g. (groupCode="ORDER_STATUS", code="PENDING", name="대기", sortOrder=1). */
public record CommonCode(String groupCode, String code, String name, int sortOrder, boolean active) {
}
