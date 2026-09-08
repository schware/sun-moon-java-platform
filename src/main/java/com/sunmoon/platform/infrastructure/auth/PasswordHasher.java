package com.sunmoon.platform.infrastructure.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

/** BCrypt wrapper — no Spring Security to supply this, so it's one small, well-known library instead (see docs/adr/0004). */
public final class PasswordHasher {

    private static final int COST_FACTOR = 12;

    public static String hash(String plaintextPassword) {
        return BCrypt.withDefaults().hashToString(COST_FACTOR, plaintextPassword.toCharArray());
    }

    public static boolean matches(String plaintextPassword, String hash) {
        return BCrypt.verifyer().verify(plaintextPassword.toCharArray(), hash).verified;
    }

    private PasswordHasher() {
    }
}
