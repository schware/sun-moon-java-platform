package com.sunmoon.platform.domain.etc;

import com.sunmoon.platform.domain.intake.IntakeException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ETC 원본을 이름·값으로 펴는 규칙 (업무 정의서 §3.3).
 *
 * <p>구조가 아직 안 정해진 것을 담는 표라, <b>이름이 곧 나중에 컬럼으로
 * 옮길 때의 지도</b>입니다. 이름이 어디서 왔는지를 잃으면 옮길 수가
 * 없습니다.
 */
class JsonEtcDecomposerTest {

    private final JsonEtcDecomposer decomposer = new JsonEtcDecomposer();

    private Map<String, String> decompose(String json) {
        return decomposer.decompose(json.getBytes(StandardCharsets.UTF_8)).stream()
                .collect(Collectors.toMap(EtcEntry::name, e -> e.value() == null ? "<null>" : e.value(),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void 중첩은_점으로_잇는다() {
        Map<String, String> out = decompose("""
                {"header":{"storeId":"store-01","etcNo":"E1"},"coupon":{"code":"C9","amount":1000}}
                """);

        assertEquals("store-01", out.get("header.storeId"));
        assertEquals("C9", out.get("coupon.code"));
        assertEquals("1000", out.get("coupon.amount"));
    }

    @Test
    void 배열은_자리번호를_붙인다() {
        Map<String, String> out = decompose("""
                {"items":[{"name":"A"},{"name":"B"}]}
                """);

        assertEquals("A", out.get("items[0].name"));
        assertEquals("B", out.get("items[1].name"));
    }

    @Test
    void null_은_0이_아니라_빈_값이다() {
        // "값이 없다" 와 "0" 은 다른 일입니다.
        List<EtcEntry> entries = decomposer.decompose("{\"memo\":null}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, entries.size());
        assertEquals("memo", entries.get(0).name());
        assertNull(entries.get(0).value());
    }

    @Test
    void 순번은_1부터_이어진다() {
        List<EtcEntry> entries = decomposer.decompose("{\"a\":1,\"b\":2,\"c\":3}".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(1, 2, 3), entries.stream().map(EtcEntry::seq).toList(),
                "상세 표의 (log_id, detail_seq) 유일 제약이 이 값에 기댑니다");
    }

    @Test
    void 너무_깊으면_원본이_잘못된_것이다() {
        // 11단계 — 제한은 10입니다.
        String deep = "{\"a\":".repeat(11) + "1" + "}".repeat(11);

        IntakeException e = assertThrows(IntakeException.class,
                () -> decomposer.decompose(deep.getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("깊습니다"));
    }

    @Test
    void 비었거나_JSON_이_아니면_원본이_잘못된_것이다() {
        // 셋 다 IntakeException 이어야 배치가 FAILED 로 멈춥니다. 다른
        // 예외면 "저장 사고" 로 읽혀 30초마다 다시 풀립니다.
        assertThrows(IntakeException.class, () -> decomposer.decompose("{}".getBytes()));
        assertThrows(IntakeException.class, () -> decomposer.decompose("not json".getBytes()));
        assertThrows(IntakeException.class, () -> decomposer.decompose("null".getBytes()));
    }
}
