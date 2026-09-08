package com.sunmoon.platform.domain.commoncode;

import java.util.List;
import java.util.Optional;

/**
 * Port. Real adapter: {@code infrastructure.persistence.MyBatisCommonCodeRepository}
 * (PostgreSQL — not live-verified, docs/adr/0005). Fake, wired in by
 * default: {@code infrastructure.persistence.InMemoryCommonCodeRepository}.
 */
public interface CommonCodeRepository {
    List<CommonCode> findAll();

    List<CommonCode> findByGroup(String groupCode);

    Optional<CommonCode> findOne(String groupCode, String code);

    /** Fails (throws {@link IllegalStateException}) if (groupCode, code) already exists — that's what distinguishes 신규 from 저장. */
    void create(CommonCode commonCode);

    /** Fails if (groupCode, code) doesn't exist yet. */
    void save(CommonCode commonCode);

    void delete(String groupCode, String code);
}
