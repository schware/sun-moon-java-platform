package com.sunmoon.platform.domain.operator;

/** BO screen registry. A code-defined enum, not a DB table — the screen list changes with deployments, not by an operator's own action (see docs/adr/0004). */
public enum Screen {
    COMMON_CODE,
    DEVICE,
    OPERATOR
}
