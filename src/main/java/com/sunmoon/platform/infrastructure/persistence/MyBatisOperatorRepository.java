package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.operator.Operator;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Optional;

/** Real adapter over PostgreSQL via MyBatis. Not live-verified — see docs/adr/0005. */
public final class MyBatisOperatorRepository implements OperatorRepository {

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisOperatorRepository(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Override
    public Optional<Operator> findByUsername(String username) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            OperatorRow row = session.getMapper(OperatorMapper.class).findByUsername(username);
            return Optional.ofNullable(row).map(MyBatisOperatorRepository::toDomain);
        }
    }

    @Override
    public List<OperatorScreenPermission> findPermissions(long operatorId) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(OperatorMapper.class).findPermissions(operatorId).stream()
                    .map(row -> new OperatorScreenPermission(
                            Screen.valueOf(row.getScreen()), row.isCanView(), row.isCanCreate(),
                            row.isCanSave(), row.isCanDelete()))
                    .toList();
        }
    }

    @Override
    public Operator create(String username, String passwordHash, String displayName, boolean superAdmin) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OperatorRow row = new OperatorRow();
            row.setUsername(username);
            row.setPasswordHash(passwordHash);
            row.setDisplayName(displayName);
            row.setSuperAdmin(superAdmin);
            session.getMapper(OperatorMapper.class).insert(row);
            return toDomain(row);
        }
    }

    @Override
    public boolean existsAny() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(OperatorMapper.class).count() > 0;
        }
    }

    private static Operator toDomain(OperatorRow row) {
        return new Operator(row.getId(), row.getUsername(), row.getPasswordHash(),
                row.getDisplayName(), row.isSuperAdmin(), row.isActive());
    }
}
