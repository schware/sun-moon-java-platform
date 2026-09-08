package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.commoncode.CommonCode;
import com.sunmoon.platform.domain.commoncode.CommonCodeRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Optional;

/** Real adapter over PostgreSQL via MyBatis. Not live-verified — see docs/adr/0005. */
public final class MyBatisCommonCodeRepository implements CommonCodeRepository {

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisCommonCodeRepository(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Override
    public List<CommonCode> findAll() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(CommonCodeMapper.class).findAll().stream()
                    .map(MyBatisCommonCodeRepository::toDomain)
                    .toList();
        }
    }

    @Override
    public List<CommonCode> findByGroup(String groupCode) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(CommonCodeMapper.class).findByGroup(groupCode).stream()
                    .map(MyBatisCommonCodeRepository::toDomain)
                    .toList();
        }
    }

    @Override
    public Optional<CommonCode> findOne(String groupCode, String code) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            CommonCodeRow row = session.getMapper(CommonCodeMapper.class).findOne(groupCode, code);
            return Optional.ofNullable(row).map(MyBatisCommonCodeRepository::toDomain);
        }
    }

    @Override
    public void create(CommonCode commonCode) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(CommonCodeMapper.class).insert(toRow(commonCode));
        }
    }

    @Override
    public void save(CommonCode commonCode) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            int updated = session.getMapper(CommonCodeMapper.class).update(toRow(commonCode));
            if (updated == 0) {
                throw new IllegalStateException(
                        "common code does not exist: " + commonCode.groupCode() + "|" + commonCode.code());
            }
        }
    }

    @Override
    public void delete(String groupCode, String code) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(CommonCodeMapper.class).delete(groupCode, code);
        }
    }

    private static CommonCode toDomain(CommonCodeRow row) {
        return new CommonCode(row.getGroupCode(), row.getCode(), row.getName(), row.getSortOrder(), row.isActive());
    }

    private static CommonCodeRow toRow(CommonCode commonCode) {
        CommonCodeRow row = new CommonCodeRow();
        row.setGroupCode(commonCode.groupCode());
        row.setCode(commonCode.code());
        row.setName(commonCode.name());
        row.setSortOrder(commonCode.sortOrder());
        row.setActive(commonCode.active());
        return row;
    }
}
