package com.sunmoon.platform.config;

/**
 * Strips the host-specific prefix (e.g. {@code /home/schware/}) off
 * absolute filesystem paths before they reach externally-visible
 * endpoints (health details, metrics tags), keeping everything from
 * {@code apps/} onward.
 */
final class PathRedactor {

    private static final String MARKER = "/apps/";

    private PathRedactor() {
    }

    static String redact(String absolutePath) {
        int index = absolutePath.indexOf(MARKER);
        return index >= 0 ? absolutePath.substring(index + 1) : absolutePath;
    }
}
