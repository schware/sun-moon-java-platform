package com.sunmoon.platform.domain.terminal;

/**
 * The kinds of terminal that connect here. Matches the {@code DEVICE_TYPE}
 * common-code group BO manages, but is an enum rather than a lookup: this
 * server has to route an event to "every KDS" before any database is
 * consulted, so the categories have to exist in code.
 */
public enum TerminalType {
    /** Takes orders: shows a placed order, accepts or rejects it. */
    POS,
    /** Kitchen display: shows accepted orders, reports production complete. */
    KDS,
    /** Store-facing display: shows what is ready. */
    DID
}
