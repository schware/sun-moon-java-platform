package com.sunmoon.platform.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OrderMapper {

    @Select("""
            SELECT id, customer_id AS customerId, amount
            FROM orders
            ORDER BY id
            OFFSET #{offset} ROWS FETCH NEXT #{limit} ROWS ONLY
            """)
    List<OrderRow> findPage(@Param("offset") int offset, @Param("limit") int limit);
}
