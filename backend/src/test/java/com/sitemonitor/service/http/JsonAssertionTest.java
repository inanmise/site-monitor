package com.sitemonitor.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP izlemesinin JSON doğrulaması (2026-10-01, onaylı öneri 9): küçük yol çözücü + karar kuralı.
 * Yeni bağımlılık yok — Jackson ağacı üzerinde dar bir JSONPath alt kümesi.
 */
class JsonAssertionTest {

    private static final String DOC = """
            {"status":"ok","code":200,"healthy":true,"none":null,
             "items":[{"id":7,"name":"a"},{"id":8,"name":"b"}],
             "a.b":{"c":"nokta"},"nested":{"deep":{"x":"y"}}}""";

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static JsonNode tree() throws Exception { return new ObjectMapper().readTree(DOC); }

    // ── Sözdizimi ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("parse: $.a.b[0].c ve a.b[0].c aynı parçaları üretir; $ tek başına kök; tırnaklı alan adı")
    void parse_validForms() {
        assertThat(JsonAssertion.parse("$.items[0].id")).containsExactly("items", 0, "id");
        assertThat(JsonAssertion.parse("items[0].id")).containsExactly("items", 0, "id");
        assertThat(JsonAssertion.parse("$")).isEmpty();
        assertThat(JsonAssertion.parse("$['a.b'].c")).containsExactly("a.b", "c");
        assertThat(JsonAssertion.parse("$[\"a.b\"][\"c\"]")).containsExactly("a.b", "c");
        assertThat(JsonAssertion.parse("  $.status  ")).containsExactly("status");
        assertThat(JsonAssertion.parse("$[1]")).containsExactly(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "$.", "$..a", "a..b", ".a", "$a", "$[", "$[x]", "$[-1]", "$['a", "$['']",
            "$[0", "a b", "$.a[0]x", "$.items[1234567890]"})
    @DisplayName("parse: geçersiz yollar konumlu sözdizimi hatasıyla reddedilir")
    void parse_invalidRejected(String path) {
        assertThatThrownBy(() -> JsonAssertion.parse(path))
                .isInstanceOf(JsonAssertion.JsonPathSyntaxException.class);
    }

    @Test
    @DisplayName("parse: hata konumu ve iki dilde neden taşır")
    void parse_errorCarriesPositionAndReasons() {
        try {
            JsonAssertion.parse("$.items[x]");
        } catch (JsonAssertion.JsonPathSyntaxException e) {
            assertThat(e.position()).isEqualTo(8);
            assertThat(e.reasonTr()).contains("dizi indeksi");
            assertThat(e.reasonEn()).contains("array index");
            return;
        }
        throw new AssertionError("hata bekleniyordu");
    }

    // ── Çözümleme ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolve: var olan yol, dizi indeksi, iç içe nesne; olmayan yol / taşan indeks null")
    void resolve() throws Exception {
        JsonNode root = tree();
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.status")).asText()).isEqualTo("ok");
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.items[1].name")).asText()).isEqualTo("b");
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("nested.deep.x")).asText()).isEqualTo("y");
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$['a.b'].c")).asText()).isEqualTo("nokta");
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$")).isObject()).isTrue();
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.missing"))).isNull();
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.items[5]"))).isNull();
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.status[0]"))).as("dizi olmayan düğümde indeks").isNull();
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.items.id"))).as("dizide alan adı").isNull();
        // JSON null değeri bir DÜĞÜMDÜR (yol var) — "yok" ile karışmaz
        assertThat(JsonAssertion.resolve(root, JsonAssertion.parse("$.none")).isNull()).isTrue();
    }

    // ── Karar ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaluate: beklenen boş → yol var ve null değil yeter")
    void evaluate_existsOnly() {
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.status", null)).isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.items[0]", "")).isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.none", null))
                .startsWith(JsonAssertion.FAIL_PREFIX).contains("$.none").contains("null");
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.missing", null))
                .isEqualTo(JsonAssertion.FAIL_PREFIX + "$.missing bulunamadı");
    }

    @Test
    @DisplayName("evaluate: metin, sayı ve boolean asText ile BİREBİR karşılaştırılır")
    void evaluate_equals() {
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.status", "ok")).isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.code", "200")).isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.healthy", "true")).isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.items[1].id", "8")).isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.none", "null")).as("açıkça null bekleniyorsa").isNull();
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$.status", "OK")).as("büyük/küçük harf duyarlı").isNotNull();
    }

    @Test
    @DisplayName("evaluate: uyuşmazlık iletisi yol, gelen ve beklenen değeri söyler")
    void evaluate_mismatchMessage() {
        String body = "{\"status\":\"degraded\"}";
        assertThat(JsonAssertion.evaluate(bytes(body), false, "$.status", "ok"))
                .isEqualTo("JSON doğrulaması başarısız: $.status = \"degraded\" (beklenen \"ok\")");
    }

    @Test
    @DisplayName("evaluate: ayrıştırılamayan / boş / tavanı aşan gövde DOWN nedeni üretir")
    void evaluate_unparseableEmptyTruncated() {
        assertThat(JsonAssertion.evaluate(bytes("<html>bakım</html>"), false, "$.status", "ok"))
                .isEqualTo(JsonAssertion.FAIL_PREFIX + "yanıt gövdesi geçerli JSON değil");
        assertThat(JsonAssertion.evaluate(new byte[0], false, "$.status", null))
                .isEqualTo(JsonAssertion.FAIL_PREFIX + "yanıt gövdesi boş");
        assertThat(JsonAssertion.evaluate(null, false, "$.status", null))
                .isEqualTo(JsonAssertion.FAIL_PREFIX + "yanıt gövdesi boş");
        assertThat(JsonAssertion.evaluate(bytes(DOC), true, "$.status", "ok"))
                .startsWith(JsonAssertion.FAIL_PREFIX).contains("sınırını aşıyor");
    }

    @Test
    @DisplayName("evaluate: uzun değer iletide kırpılır (kontrol kaydı şişmez)")
    void evaluate_longValueClipped() {
        String longVal = "x".repeat(500);
        String msg = JsonAssertion.evaluate(bytes("{\"v\":\"" + longVal + "\"}"), false, "$.v", "ok");
        assertThat(msg).hasSizeLessThan(300).contains("…");
    }

    @Test
    @DisplayName("evaluate: nesne/dizi düğümü sıkıştırılmış JSON metniyle karşılaştırılır")
    void evaluate_containerAsJson() {
        assertThat(JsonAssertion.evaluate(bytes("{\"a\":[1,2]}"), false, "$.a", "[1,2]")).isNull();
        assertThat(JsonAssertion.evaluate(bytes("{\"a\":{\"b\":1}}"), false, "$.a", "{\"b\":1}")).isNull();
        assertThat(JsonAssertion.evaluate(bytes("{\"a\":{\"b\":1}}"), false, "$.a", "{\"b\":2}")).isNotNull();
    }

    @Test
    @DisplayName("evaluate: kayıtta doğrulanmamış (bozuk) yol da kontrolü patlatmaz — DOWN nedeni olur")
    void evaluate_invalidPathNeverThrows() {
        assertThat(JsonAssertion.evaluate(bytes(DOC), false, "$[x]", null))
                .startsWith(JsonAssertion.FAIL_PREFIX).contains("geçersiz JSON yolu");
    }
}
