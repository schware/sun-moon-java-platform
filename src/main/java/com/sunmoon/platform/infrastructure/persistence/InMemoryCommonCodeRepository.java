package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.commoncode.CommonCode;
import com.sunmoon.platform.domain.commoncode.CommonCodeRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fake adapter — wired in by default (see docs/adr/0005). */
public final class InMemoryCommonCodeRepository implements CommonCodeRepository {

    private final Map<String, CommonCode> byKey = new ConcurrentHashMap<>();

    @Override
    public List<CommonCode> findAll() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(CommonCode::groupCode).thenComparingInt(CommonCode::sortOrder))
                .toList();
    }

    @Override
    public List<CommonCode> findByGroup(String groupCode) {
        return byKey.values().stream()
                .filter(c -> c.groupCode().equals(groupCode))
                .sorted(Comparator.comparingInt(CommonCode::sortOrder))
                .toList();
    }

    @Override
    public Optional<CommonCode> findOne(String groupCode, String code) {
        return Optional.ofNullable(byKey.get(key(groupCode, code)));
    }

    @Override
    public void create(CommonCode commonCode) {
        String key = key(commonCode.groupCode(), commonCode.code());
        if (byKey.putIfAbsent(key, commonCode) != null) {
            throw new IllegalStateException("common code already exists: " + key);
        }
    }

    @Override
    public void save(CommonCode commonCode) {
        String key = key(commonCode.groupCode(), commonCode.code());
        if (!byKey.containsKey(key)) {
            throw new IllegalStateException("common code does not exist: " + key);
        }
        byKey.put(key, commonCode);
    }

    @Override
    public void delete(String groupCode, String code) {
        byKey.remove(key(groupCode, code));
    }

    private static String key(String groupCode, String code) {
        return groupCode + "|" + code;
    }
}
