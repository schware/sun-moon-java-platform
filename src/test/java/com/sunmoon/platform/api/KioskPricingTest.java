package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** KIOSK 의 값은 메뉴판에서 (store-app.md §9). */
class KioskPricingTest {

    private final ObjectMapper json = new ObjectMapper();
    private final JsonNode board;

    KioskPricingTest() throws Exception {
        board = json.readTree("""
                {"menus":[
                  {"menuCode":"m1","name":"짜장면","price":7000,"soldOut":false,"orderable":true,"kdsDeviceId":"kds-01"},
                  {"menuCode":"m2","name":"짬뽕","price":8000,"soldOut":true,"orderable":true},
                  {"menuCode":"m3","name":"탕수육","price":null,"soldOut":false,"orderable":true}
                ]}""");
    }

    @Test
    void thePriceComesFromTheBoardNotTheScreen() throws Exception {
        KioskPricing.Result r = KioskPricing.price(json.readTree("""
                [{"menuCode":"m1","menuName":"1원짜리","quantity":2,"unitPrice":1}]"""), board);

        assertNull(r.refusal());
        assertEquals(7000, r.lines().get(0).path("unitPrice").asInt());
        assertEquals("짜장면", r.lines().get(0).path("menuName").asText());
        assertEquals("kds-01", r.lines().get(0).path("kdsDeviceId").asText());
        assertEquals(14000, KioskPricing.total(r.lines()));
    }

    @Test
    void unknownSoldOutOrUnpricedMenusAreRefused() throws Exception {
        assertNotNull(KioskPricing.price(json.readTree("[{\"menuCode\":\"zz\",\"quantity\":1}]"), board).refusal());
        assertNotNull(KioskPricing.price(json.readTree("[{\"menuCode\":\"m2\",\"quantity\":1}]"), board).refusal());
        assertNotNull(KioskPricing.price(json.readTree("[{\"menuCode\":\"m3\",\"quantity\":1}]"), board).refusal());
    }

    @Test
    void sillyQuantitiesAreRefused() throws Exception {
        assertNotNull(KioskPricing.price(json.readTree("[{\"menuCode\":\"m1\",\"quantity\":0}]"), board).refusal());
        assertNotNull(KioskPricing.price(json.readTree("[{\"menuCode\":\"m1\",\"quantity\":100}]"), board).refusal());
    }
}
