package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * KIOSK 주문의 값은 <b>메뉴판에서</b> 매깁니다 (store-app.md §9).
 *
 * <p>화면이 보낸 값을 믿으면 화면을 고친 누군가가 1원에 살 수 있습니다. 앱 주문이 서버에서
 * 메뉴판 값을 다시 꺼내는 것과 같은 규칙입니다. KIOSK 가 보내는 것은 <b>무엇을 몇 개</b>
 * (menuCode, quantity) 이고, 이름·단가·주방은 그 순간의 메뉴판에서 옵니다.
 */
public final class KioskPricing {

    static final int MAX_QUANTITY = 99;
    private static final ObjectMapper JSON = new ObjectMapper();

    private KioskPricing() {
    }

    /** 값을 매긴 줄들, 또는 거절 이유. */
    public record Result(ArrayNode lines, String refusal) {
    }

    public static Result price(JsonNode requested, JsonNode board) {
        Map<String, JsonNode> menus = new HashMap<>();
        for (JsonNode menu : board.path("menus")) {
            menus.put(menu.path("menuCode").asText(), menu);
        }
        ArrayNode priced = JSON.createArrayNode();
        for (JsonNode line : requested) {
            String code = line.path("menuCode").asText("");
            JsonNode menu = menus.get(code);
            if (code.isBlank() || menu == null) {
                return new Result(null, "메뉴판에 없는 메뉴입니다: " + code);
            }
            if (menu.path("soldOut").asBoolean(false) || !menu.path("orderable").asBoolean(true)) {
                return new Result(null, "지금 주문할 수 없는 메뉴입니다: " + menu.path("name").asText(code));
            }
            if (!menu.path("price").isNumber()) {
                return new Result(null, "값이 정해지지 않은 메뉴입니다: " + menu.path("name").asText(code));
            }
            int quantity = line.path("quantity").asInt(0);
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                return new Result(null, "수량이 올바르지 않습니다: " + quantity);
            }
            ObjectNode out = JSON.createObjectNode();
            out.put("menuCode", code);
            out.put("menuName", menu.path("name").asText(code));
            out.put("quantity", quantity);
            out.set("unitPrice", menu.get("price"));
            if (menu.hasNonNull("kdsDeviceId")) {
                out.put("kdsDeviceId", menu.get("kdsDeviceId").asText());
            }
            priced.add(out);
        }
        if (priced.isEmpty()) {
            return new Result(null, "담긴 메뉴가 없습니다");
        }
        return new Result(priced, null);
    }

    /** 매긴 값의 합 — 결제 금액과 맞는지 보는 데 씁니다. */
    public static long total(ArrayNode lines) {
        long sum = 0;
        for (JsonNode line : lines) {
            sum += Math.round(line.path("unitPrice").asDouble() * line.path("quantity").asInt());
        }
        return sum;
    }
}
