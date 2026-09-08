package com.sunmoon.platform.domain.operator;

import java.util.List;
import java.util.Optional;

/**
 * Port. Real adapter: {@code infrastructure.persistence.MyBatisOperatorRepository}
 * (PostgreSQL — not live-verified, docs/adr/0005). Fake, wired in by
 * default: {@code infrastructure.persistence.InMemoryOperatorRepository}.
 */
public interface OperatorRepository {
    Optional<Operator> findByUsername(String username);

    List<OperatorScreenPermission> findPermissions(long operatorId);

    Operator create(String username, String passwordHash, String displayName, boolean superAdmin);

    boolean existsAny();
}
