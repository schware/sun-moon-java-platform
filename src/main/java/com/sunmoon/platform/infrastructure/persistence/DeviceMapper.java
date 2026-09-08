package com.sunmoon.platform.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DeviceMapper {

    @Select("""
            SELECT device_id AS deviceId, name, device_type AS deviceType, location, active
            FROM devices ORDER BY device_id
            """)
    List<DeviceRow> findAll();

    @Select("""
            SELECT device_id AS deviceId, name, device_type AS deviceType, location, active
            FROM devices WHERE device_type = #{deviceType} ORDER BY device_id
            """)
    List<DeviceRow> findByType(@Param("deviceType") String deviceType);

    @Select("""
            SELECT device_id AS deviceId, name, device_type AS deviceType, location, active
            FROM devices WHERE device_id = #{deviceId}
            """)
    DeviceRow findOne(@Param("deviceId") String deviceId);

    @Insert("""
            INSERT INTO devices (device_id, name, device_type, location, active)
            VALUES (#{deviceId}, #{name}, #{deviceType}, #{location}, #{active})
            """)
    void insert(DeviceRow row);

    @Update("""
            UPDATE devices SET name = #{name}, device_type = #{deviceType},
                   location = #{location}, active = #{active}
            WHERE device_id = #{deviceId}
            """)
    int update(DeviceRow row);

    @Delete("DELETE FROM devices WHERE device_id = #{deviceId}")
    void delete(@Param("deviceId") String deviceId);
}
