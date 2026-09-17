package com.sunmoon.platform.domain.sale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 원본을 Header / Detail / Payment / ETC 로 풉니다 (§3.2).
 *
 * <p>형식을 아는 유일한 곳입니다. 받는 쪽은 바이트만 다루고, 저장하는
 * 쪽은 이미 풀린 것만 다룹니다 — POS의 전문이 JSON이 아니게 되는 날
 * 바뀌는 것은 이 클래스 하나입니다.
 *
 * <p>없는 값에 0을 넣지 않습니다. 할인이 없는 거래와 할인 금액을
 * 안 보낸 거래는 다른 일이고, 뒤쪽은 원본이 잘못된 것입니다 — 다만
 * 실제로 POS가 안 보내는 것이 흔해서, 합계에 영향이 없는 값
 * (discount, tax)만 0으로 채웁니다. 합계에 들어가는 값이 비면
 * 그대로 터뜨려서 FAILED 로 남깁니다.
 */
public final class JsonSaleDecomposer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public DecomposedSale decompose(byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
            return new DecomposedSale(
                    header(root.path("header")),
                    details(root.path("details")),
                    payments(root.path("payments")),
                    etc(root.path("etc")));
        } catch (Exception e) {
            throw new SaleDecomposeException("원본을 풀지 못했습니다: " + e.getMessage(), e);
        }
    }

    private DecomposedSale.Header header(JsonNode node) {
        if (node.isMissingNode()) {
            throw new SaleDecomposeException("header 가 없습니다");
        }
        return new DecomposedSale.Header(
                required(node, "storeId"),
                required(node, "posNo"),
                LocalDate.parse(required(node, "businessDate")),
                required(node, "tranNo"),
                node.path("tranType").asText("SALE"),
                amount(node, "totalAmount"),
                optionalAmount(node, "discountAmount"),
                optionalAmount(node, "taxAmount"),
                node.hasNonNull("soldAt") ? Instant.parse(node.get("soldAt").asText()) : null);
    }

    private List<DecomposedSale.Detail> details(JsonNode node) {
        List<DecomposedSale.Detail> lines = new ArrayList<>();
        int seq = 0;
        for (JsonNode d : node) {
            seq++;
            lines.add(new DecomposedSale.Detail(
                    d.path("detailSeq").asInt(seq),
                    d.path("menuCode").asText(null),
                    required(d, "menuName"),
                    d.path("quantity").asInt(1),
                    amount(d, "unitPrice"),
                    amount(d, "detailAmount"),
                    optionalAmount(d, "discountAmount")));
        }
        if (lines.isEmpty()) {
            // 팔린 것이 없는 거래는 거래가 아닙니다. 빈 채로 통과시키면
            // 금액만 있고 무엇을 팔았는지 모르는 매출이 쌓입니다.
            throw new SaleDecomposeException("details 가 비어 있습니다");
        }
        return lines;
    }

    private List<DecomposedSale.Payment> payments(JsonNode node) {
        List<DecomposedSale.Payment> lines = new ArrayList<>();
        int seq = 0;
        for (JsonNode p : node) {
            seq++;
            lines.add(new DecomposedSale.Payment(
                    p.path("paymentSeq").asInt(seq),
                    required(p, "paymentType"),
                    amount(p, "paymentAmount"),
                    p.path("approvalNo").asText(null),
                    p.path("cardNoMasked").asText(null)));
        }
        return lines;
    }

    private List<DecomposedSale.Etc> etc(JsonNode node) {
        List<DecomposedSale.Etc> lines = new ArrayList<>();
        int seq = 0;
        // 이름과 값의 쌍입니다. 무엇이 올지 아직 안 정해져서
        // (§3.3), 오는 것을 그대로 담습니다.
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            seq++;
            lines.add(new DecomposedSale.Etc(seq, entry.getKey(),
                    entry.getValue().isNull() ? null : entry.getValue().asText()));
        }
        return lines;
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new SaleDecomposeException(field + " 이(가) 없습니다");
        }
        return value.asText();
    }

    /** 합계에 들어가는 값. 비어 있으면 원본이 잘못된 것이라 터뜨립니다. */
    private static BigDecimal amount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new SaleDecomposeException(field + " 이(가) 없습니다");
        }
        return value.decimalValue();
    }

    /** 합계에 영향이 없는 값. POS가 안 보내는 것이 흔해서 0으로 봅니다. */
    private static BigDecimal optionalAmount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? BigDecimal.ZERO : value.decimalValue();
    }
}
