package com.sunmoon.platform.domain.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 줄 × 수량 (store-app.md §7.2). */
class LabelUnitsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void threeDrinksAreThreeLabels() throws Exception {
        List<PrintJob.Unit> units = LabelUnits.of(json.readTree("""
                {"pickupNo":"A-12","floor":2,"tableNo":"5","placedAt":"2026-09-28T03:00:00Z",
                 "lines":[{"seq":1,"menuName":"아메리카노","quantity":3},{"seq":2,"menuName":"라떼","quantity":1}]}
                """));

        assertEquals(4, units.size());
        assertEquals(3, units.get(2).unitNo());
        assertEquals(3, units.get(2).unitTotal());
        assertEquals("아메리카노", units.get(0).content().get("menuName"));
        assertEquals("2층 5번", units.get(0).content().get("tableLabel"));
        assertEquals(0, units.get(0).content().get("copyNo"));
        assertEquals(2, units.get(3).lineSeq());
    }

    @Test
    void anOldOrderWithoutLinesIsOneLabel() throws Exception {
        List<PrintJob.Unit> units = LabelUnits.of(json.readTree("""
                {"pickupNo":"7","menuName":"짜장면 외 1건"}
                """));
        assertEquals(1, units.size());
        assertEquals("짜장면 외 1건", units.get(0).content().get("menuName"));
        assertNull(units.get(0).content().get("tableLabel"));
    }

    @Test
    void aTypoInTheQuantityDoesNotRunThePrinterForAnHour() throws Exception {
        List<PrintJob.Unit> units = LabelUnits.of(json.readTree("""
                {"lines":[{"seq":1,"menuName":"물","quantity":999}]}
                """));
        assertEquals(LabelUnits.MAX_UNITS_PER_ORDER, units.size());
    }
}
