package com.sunmoon.platform.domain.operator;

/** {@code superAdmin} bypasses all {@link OperatorScreenPermission} checks entirely — the "전체 관리자 (개발자)" tier (see docs/adr/0004). */
public record Operator(long id, String username, String passwordHash, String displayName, boolean superAdmin, boolean active) {
}
