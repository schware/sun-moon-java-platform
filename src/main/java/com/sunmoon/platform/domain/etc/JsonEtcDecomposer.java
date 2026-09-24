package com.sunmoon.platform.domain.etc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.intake.IntakeException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ETC 원본 JSON 을 이름·값 줄로 풉니다 (업무 정의서 §3.3).
 *
 * <p>형식을 아는 유일한 곳입니다. 받는 쪽은 바이트만 다루고, 저장하는
 * 쪽은 이미 풀린 것만 다룹니다 — 단말의 전문이 JSON 이 아니게 되는 날
 * 바뀌는 것은 이 클래스 하나입니다. TR 쪽의
 * {@code JsonSaleDecomposer} 와 같은 자리입니다.
 *
 * <h2>중첩을 평평하게 폅니다</h2>
 *
 * <p>{@code {"a":{"b":1}}} 은 {@code a.b = 1} 이 됩니다. 구조가 아직
 * 안 정해진 것을 담는 표라 깊이를 표현할 방법이 없고, <b>점으로 이은
 * 이름이 나중에 진짜 컬럼으로 옮길 때 가장 덜 잃습니다</b> — 어느
 * 값이 어디서 왔는지가 이름에 남습니다.
 *
 * <p>배열은 자리번호를 붙입니다: {@code items[0].name}.
 */
public final class JsonEtcDecomposer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 너무 깊거나 너무 많은 원본을 막습니다. 잘못 만들어진 전문 하나가
     * 표를 수십만 줄로 채우는 것을 배치가 조용히 해내면 안 됩니다.
     */
    private static final int MAX_DEPTH = 10;
    private static final int MAX_ENTRIES = 2000;

    public List<EtcEntry> decompose(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception notJson) {
            throw new IntakeException("원본이 JSON 이 아닙니다: " + notJson.getMessage(), notJson);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IntakeException("원본이 비어 있습니다");
        }

        List<EtcEntry> entries = new ArrayList<>();
        flatten("", root, entries, 0);

        if (entries.isEmpty()) {
            // 아무것도 없는 ETC 는 ETC 가 아닙니다. 빈 채로 통과시키면
            // "받았는데 아무것도 없는" 줄이 조용히 쌓입니다.
            throw new IntakeException("풀어낼 값이 없습니다");
        }
        return entries;
    }

    private void flatten(String prefix, JsonNode node, List<EtcEntry> out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IntakeException("원본이 너무 깊습니다 (" + MAX_DEPTH + " 단계 초과): " + prefix);
        }
        if (out.size() > MAX_ENTRIES) {
            throw new IntakeException("원본의 값이 너무 많습니다 (" + MAX_ENTRIES + "개 초과)");
        }

        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                flatten(join(prefix, entry.getKey()), entry.getValue(), out, depth + 1);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out, depth + 1);
            }
            return;
        }
        // 잎입니다. 이름이 없으면(최상위가 스칼라인 원본) 자리를 하나 줍니다.
        String name = prefix.isEmpty() ? "value" : prefix;
        out.add(new EtcEntry(out.size() + 1, name, node.isNull() ? null : node.asText()));
    }

    private static String join(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }
}
