package com.sunmoon.platform.domain.pairing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기기와 짝을 맺는 일 (umbrella docs/design/store-app.md §3).
 *
 * <pre>
 *   BO ─ 코드 발급 ─→ 6자리, 10분, 한 번만 (매장·번호·종류·역할이 코드에 묶임)
 *   단말 ─ 코드 ─→ 토큰 (그 뒤로 토큰이 신원)
 * </pre>
 *
 * <h2>무차별 대입</h2>
 *
 * <p>6자리는 백만 개입니다. 설계는 "같은 IP 에서 1분에 10번 실패하면 차단" 이지만
 * 이 서버의 엔드포인트는 요청만 받고 출발지 주소를 모릅니다. 그래서 <b>서버
 * 전체로</b> 셉니다: 1분에 {@value #MAX_FAILURES_PER_MINUTE}번 실패하면 그 1분
 * 동안 페어링을 전부 막습니다. 공격자가 페어링을 잠깐 못 하게 만들 수는 있지만
 * (페어링은 드문 일이고 1분 뒤 다시 하면 됩니다), 10분짜리 코드 하나를 맞힐
 * 확률은 살아 있는 코드 수 × 200 / 1,000,000 에 묶입니다.
 *
 * <h2>토큰 확인은 캐시</h2>
 *
 * <p>단말은 15초마다 부릅니다. 부를 때마다 데이터베이스에 묻지 않고
 * {@value #CACHE_SECONDS}초 기억합니다. 대가: <b>revoke 는 이 서버 안에서는
 * 즉시지만</b>(캐시를 비웁니다), 다른 경로로 표를 고치면 최대
 * {@value #CACHE_SECONDS}초 늦습니다.
 */
public final class PairingService implements TerminalTokens {

    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    static final int MAX_FAILURES_PER_MINUTE = 20;
    static final int CACHE_SECONDS = 60;

    private final PairingStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private final Deque<Instant> failures = new ArrayDeque<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<DeviceIdentity> device, Instant until) {
    }

    public PairingService(PairingStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public record Issued(String code, Instant expiresAt) {
    }

    public record Paired(DeviceIdentity device, String token) {
    }

    /** 페어링이 거절된 이유. 단말 화면이 그대로 보여 줍니다. */
    public enum Refusal { UNKNOWN_CODE, THROTTLED }

    public static final class PairingRefused extends RuntimeException {
        private final Refusal refusal;

        PairingRefused(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    public Issued issue(DeviceIdentity device, String issuedBy) {
        validate(device);
        Instant now = clock.instant();
        String code;
        int tries = 0;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
            if (++tries > 20) {
                throw new IllegalStateException("살아 있는 코드와 안 겹치는 코드를 못 뽑았습니다");
            }
        } while (store.liveCodeExists(code, now));
        Instant expiresAt = now.plus(CODE_LIFETIME);
        store.issue(code, device, issuedBy, now, expiresAt);
        return new Issued(code, expiresAt);
    }

    public Paired pair(String rawCode) {
        Instant now = clock.instant();
        if (throttled(now)) {
            throw new PairingRefused(Refusal.THROTTLED);
        }
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) {
            fail(now);
            throw new PairingRefused(Refusal.UNKNOWN_CODE);
        }
        String token = newToken();
        Optional<DeviceIdentity> device = store.pair(code, hash(token), now);
        if (device.isEmpty()) {
            fail(now);
            throw new PairingRefused(Refusal.UNKNOWN_CODE);
        }
        // 이 기기의 옛 토큰이 캐시에 남아 있으면 60초 동안 통합니다 — 비웁니다.
        cache.clear();
        return new Paired(device.get(), token);
    }

    @Override
    public Optional<DeviceIdentity> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = hash(token.trim());
        Instant now = clock.instant();
        Cached hit = cache.get(hash);
        if (hit != null && hit.until().isAfter(now)) {
            return hit.device();
        }
        Optional<DeviceIdentity> device = store.findByTokenHash(hash);
        if (device.isPresent()) {
            store.touch(hash, now);
        }
        if (cache.size() > 10_000) {
            cache.clear();   // 모르는 토큰을 쏟아부어 기억을 불리는 것을 막습니다
        }
        cache.put(hash, new Cached(device, now.plusSeconds(CACHE_SECONDS)));
        return device;
    }

    public boolean revoke(String storeId, String deviceId, String type) {
        int revoked = store.revoke(storeId, deviceId, type, clock.instant());
        cache.clear();
        return revoked > 0;
    }

    public List<PairingStore.PairedDevice> devices(String storeId) {
        return store.devices(storeId, clock.instant());
    }

    private static void validate(DeviceIdentity device) {
        if (device.storeId() == null || device.storeId().isBlank()
                || device.deviceId() == null || device.deviceId().isBlank()) {
            throw new IllegalArgumentException("storeId 와 deviceId 가 필요합니다");
        }
        if (!DeviceIdentity.TYPES.contains(device.type())) {
            throw new IllegalArgumentException("deviceType 은 POS, KDS, DID 중 하나입니다");
        }
    }

    private synchronized boolean throttled(Instant now) {
        prune(now);
        return failures.size() >= MAX_FAILURES_PER_MINUTE;
    }

    private synchronized void fail(Instant now) {
        prune(now);
        failures.addLast(now);
    }

    private void prune(Instant now) {
        Instant cutoff = now.minusSeconds(60);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
