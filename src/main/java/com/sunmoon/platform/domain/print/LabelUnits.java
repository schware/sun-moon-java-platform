package com.sunmoon.platform.domain.print;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 하나 → 라벨 단위들 (store-app.md §7.2). ▶ 기본값: <b>줄 × 수량</b> — 음료 3잔이면 3장.
 *
 * <p>content 는 이 순간의 값으로 고정됩니다: {@code pickupNo, menuName, unitNo, unitTotal,
 * tableLabel, orderedAt, copyNo}. 재출력은 이것을 복사할 뿐 다시 그리지 않습니다.
 *
 * <p>Order 의 JSON 을 받습니다 — 이 서버는 Order 의 모델을 들고 있지 않고, 필요한 몇 칸만
 * 읽습니다. 줄이 없는 옛 주문은 주문 이름으로 한 장.
 */
public final class LabelUnits {

    /** 한 주문에서 찍을 수 있는 최대 장수. 수량 오타(999잔)로 프린터가 한 시간 도는 것을 막습니다. */
    public static final int MAX_UNITS_PER_ORDER = 60;

    private LabelUnits() {
    }

    public static List<PrintJob.Unit> of(JsonNode order) {
        List<PrintJob.Unit> units = new ArrayList<>();
        String pickupNo = text(order, "pickupNo");
        String tableLabel = tableLabel(order);
        String orderedAt = text(order, "placedAt");
        JsonNode lines = order.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            units.add(new PrintJob.Unit(1, 1, 1,
                    content(pickupNo, text(order, "menuName"), 1, 1, tableLabel, orderedAt)));
            return units;
        }
        for (JsonNode line : lines) {
            int seq = line.path("seq").asInt(units.size() + 1);
            int quantity = Math.max(1, line.path("quantity").asInt(1));
            String menuName = text(line, "menuName");
            for (int unit = 1; unit <= quantity; unit++) {
                if (units.size() >= MAX_UNITS_PER_ORDER) {
                    return units;
                }
                units.add(new PrintJob.Unit(seq, unit, quantity,
                        content(pickupNo, menuName, unit, quantity, tableLabel, orderedAt)));
            }
        }
        return units;
    }

    private static Map<String, Object> content(String pickupNo, String menuName, int unitNo, int unitTotal,
                                               String tableLabel, String orderedAt) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("pickupNo", pickupNo);
        c.put("menuName", menuName);
        c.put("unitNo", unitNo);
        c.put("unitTotal", unitTotal);
        c.put("tableLabel", tableLabel);
        c.put("orderedAt", orderedAt);
        c.put("copyNo", 0);
        return c;
    }

    /** 단말 화면의 tableLabel 과 같은 모양: "2층 5번" / "5번" / 없음. */
    static String tableLabel(JsonNode order) {
        String table = text(order, "tableNo");
        if (table == null) {
            return null;
        }
        JsonNode floor = order.path("floor");
        return floor.isNumber() ? floor.asInt() + "층 " + table + "번" : table + "번";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
