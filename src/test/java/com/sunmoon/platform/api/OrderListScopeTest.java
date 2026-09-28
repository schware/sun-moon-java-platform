package com.sunmoon.platform.api;

import io.netty.handler.codec.http.FullHttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 페어링된 POS·KDS 는 자기 매장 주문만 받습니다. */
class OrderListScopeTest {

    @Test
    void onlyTheCallersStoreIsKept() throws Exception {
        String all = "[{\"id\":1,\"storeId\":\"store-01\"},{\"id\":2,\"storeId\":\"store-02\"},"
                + "{\"id\":3,\"storeId\":\"store-01\",\"lines\":[{\"seq\":1}]}]";

        FullHttpResponse response = OrderProxyEndpoints.ownStoreOnly(all, "store-01");
        String body = response.content().toString(StandardCharsets.UTF_8);

        assertEquals(200, response.status().code());
        assertTrue(body.contains("\"id\":1") && body.contains("\"id\":3"));
        assertFalse(body.contains("store-02"));
        assertTrue(body.contains("\"lines\":[{\"seq\":1}]"), "주문 모양은 그대로");
    }

    @Test
    void somethingThatIsNotAListBecomesAnEmptyList() throws Exception {
        FullHttpResponse response = OrderProxyEndpoints.ownStoreOnly("{\"error\":\"x\"}", "store-01");
        assertEquals("[]", response.content().toString(StandardCharsets.UTF_8));
    }
}
