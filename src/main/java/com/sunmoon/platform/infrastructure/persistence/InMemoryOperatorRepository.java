package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.operator.Operator;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.OperatorScreenPermission;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Fake adapter — wired in by default (see docs/adr/0005). */
public final class InMemoryOperatorRepository implements OperatorRepository {

    private final Map<Long, Operator> byId = new ConcurrentHashMap<>();
    private final Map<String, Long> idByUsername = new ConcurrentHashMap<>();
    private final Map<Long, List<OperatorScreenPermission>> permissionsByOperatorId = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public Optional<Operator> findByUsername(String username) {
        Long id = idByUsername.get(username);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<OperatorScreenPermission> findPermissions(long operatorId) {
        return permissionsByOperatorId.getOrDefault(operatorId, List.of());
    }

    @Override
    public Operator create(String username, String passwordHash, String displayName, boolean superAdmin) {
        long id = nextId.getAndIncrement();
        Operator operator = new Operator(id, username, passwordHash, displayName, superAdmin, true);
        byId.put(id, operator);
        idByUsername.put(username, id);
        permissionsByOperatorId.put(id, List.of());
        return operator;
    }

    @Override
    public boolean existsAny() {
        return !byId.isEmpty();
    }
}
