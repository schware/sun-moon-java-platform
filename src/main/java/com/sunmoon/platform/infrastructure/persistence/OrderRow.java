package com.sunmoon.platform.infrastructure.persistence;

import java.math.BigDecimal;

/** Plain mutable POJO MyBatis maps result-set columns onto (id, customer_id, amount) before conversion to the domain {@code Order} record. */
public final class OrderRow {
    private long id;
    private String customerId;
    private BigDecimal amount;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
