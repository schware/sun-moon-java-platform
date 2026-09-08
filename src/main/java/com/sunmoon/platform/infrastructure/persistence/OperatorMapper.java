package com.sunmoon.platform.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OperatorMapper {

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName,
                   super_admin AS superAdmin, active
            FROM operators WHERE username = #{username}
            """)
    OperatorRow findByUsername(@Param("username") String username);

    @Select("""
            SELECT screen, can_view AS canView, can_create AS canCreate,
                   can_save AS canSave, can_delete AS canDelete
            FROM operator_screen_permissions WHERE operator_id = #{operatorId}
            """)
    List<OperatorPermissionRow> findPermissions(@Param("operatorId") long operatorId);

    @Insert("""
            INSERT INTO operators (username, password_hash, display_name, super_admin, active)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{superAdmin}, true)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OperatorRow row);

    @Select("SELECT COUNT(*) FROM operators")
    long count();
}
