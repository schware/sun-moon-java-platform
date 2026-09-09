package com.sunmoon.platform.domain.terminal;

/**
 * The unit terminals are addressed by: a kind of screen, in one store.
 *
 * <p>Type alone was the key until stores existed, and that was a bug
 * waiting for a second store — "is a POS connected" would have been
 * answered yes because some *other* branch had one, and an order would
 * have gone to a counter that never placed it. A store's screens are only
 * ever a store's own.
 */
public record TerminalGroup(String storeId, TerminalType type) {

    public TerminalGroup {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("storeId is required");
        }
    }

    /** Used as a ChannelGroup name, so it has to read well in a log line. */
    public String label() {
        return storeId + "/" + type;
    }
}
