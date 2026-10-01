package com.sunmoon.platform.domain.kiosk;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KIOSK 관리자 PIN (store-app.md §5.3). BO 가 정하고, 셸이 물어 확인합니다.
 *
 * <p>PIN 은 4~8자리 숫자라 경우의 수가 적습니다. 그래서 <b>해시를 셸에 내려보내지 않고</b>
 * 이 서버에서만 비교하며, 기기마다 10분에 5번 틀리면 그 10분 동안 막습니다.
 */
public final class KioskPins {

    static final int ITERATIONS = 120_000;
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(10);

    public record Stored(String hash, String salt, int iterations) {
    }

    /** 저장소 — 매장·기기마다 하나. */
    public interface Store {
        Optional<Stored> find(String storeId, String deviceId);

        void save(String storeId, String deviceId, Stored pin, String setBy);
    }

    public enum Result { OK, WRONG, NOT_SET, LOCKED }

    private final Store store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public KioskPins(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public static boolean acceptable(String pin) {
        return pin != null && pin.matches("[0-9]{4,8}");
    }

    public void set(String storeId, String deviceId, String pin, String setBy) {
        if (!acceptable(pin)) {
            throw new IllegalArgumentException("PIN 은 숫자 4~8자리입니다");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        store.save(storeId, deviceId, new Stored(
                Base64.getEncoder().encodeToString(derive(pin, salt, ITERATIONS)),
                Base64.getEncoder().encodeToString(salt), ITERATIONS), setBy);
        failures.remove(storeId + "|" + deviceId);
    }

    public Result check(String storeId, String deviceId, String pin) {
        String key = storeId + "|" + deviceId;
        Instant now = clock.instant();
        Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
                recent.removeFirst();
            }
            if (recent.size() >= MAX_FAILURES) {
                return Result.LOCKED;
            }
        }
        Optional<Stored> stored = store.find(storeId, deviceId);
        if (stored.isEmpty()) {
            return Result.NOT_SET;
        }
        boolean ok = acceptable(pin) && MessageDigest.isEqual(
                Base64.getDecoder().decode(stored.get().hash()),
                derive(pin, Base64.getDecoder().decode(stored.get().salt()), stored.get().iterations()));
        if (ok) {
            synchronized (recent) {
                recent.clear();
            }
            return Result.OK;
        }
        synchronized (recent) {
            recent.addLast(now);
        }
        return Result.WRONG;
    }

    static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PIN 해시를 만들지 못했습니다", e);
        }
    }
}
