package com.sunmoon.platform.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CommonCodeMapper {

    @Select("""
            SELECT group_code AS groupCode, code, name, sort_order AS sortOrder, active
            FROM common_codes ORDER BY group_code, sort_order
            """)
    List<CommonCodeRow> findAll();

    @Select("""
            SELECT group_code AS groupCode, code, name, sort_order AS sortOrder, active
            FROM common_codes WHERE group_code = #{groupCode} ORDER BY sort_order
            """)
    List<CommonCodeRow> findByGroup(@Param("groupCode") String groupCode);

    @Select("""
            SELECT group_code AS groupCode, code, name, sort_order AS sortOrder, active
            FROM common_codes WHERE group_code = #{groupCode} AND code = #{code}
            """)
    CommonCodeRow findOne(@Param("groupCode") String groupCode, @Param("code") String code);

    @Insert("""
            INSERT INTO common_codes (group_code, code, name, sort_order, active)
            VALUES (#{groupCode}, #{code}, #{name}, #{sortOrder}, #{active})
            """)
    void insert(CommonCodeRow row);

    @Update("""
            UPDATE common_codes SET name = #{name}, sort_order = #{sortOrder}, active = #{active}
            WHERE group_code = #{groupCode} AND code = #{code}
            """)
    int update(CommonCodeRow row);

    @Delete("DELETE FROM common_codes WHERE group_code = #{groupCode} AND code = #{code}")
    void delete(@Param("groupCode") String groupCode, @Param("code") String code);
}
