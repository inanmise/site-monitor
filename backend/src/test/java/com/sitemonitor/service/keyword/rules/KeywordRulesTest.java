package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.keyword.KeywordFold;
import com.sitemonitor.service.keyword.rules.KeywordRule.Kind;
import com.sitemonitor.service.keyword.rules.KeywordRule.Op;
import com.sitemonitor.service.keyword.rules.KeywordRule.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kural biçimi (kw sözleşmesi §6.1): istek doğrulaması (sıkı, alan anahtarlı 400), saklı değer (hoşgörülü), eski tek kelime
 * eşlemesi, basit tek kural, B10 anlamsız koşullar ve iki eşik aileleri.
 */
class KeywordRulesTest {

    private static Map<String, Object> rule(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String fieldOf(Runnable r) {
        try {
            r.run();
        } catch (FieldValidationException e) {
            return e.getFields().keySet().iterator().next();
        }
        throw new AssertionError("400 bekleniyordu");
    }

    @Test
    @DisplayName("null → null (anahtar yok sayılır); [] → boş liste (rules_json temizlenir); liste değil → 400 rules")
    void nullEmptyAndShape() {
        assertThat(KeywordRules.parseRequest(null)).isNull();
        assertThat(KeywordRules.parseRequest(List.of())).isEmpty();
        assertThat(fieldOf(() -> KeywordRules.parseRequest("x"))).isEqualTo("rules");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of("x")))).isEqualTo("rules[0]");
    }

    @Test
    @DisplayName("Tam kural: tür, desen, harf, koşul, kritik, bölge, etiket okunur; varsayılanlar TEXT / GTE 1 / sayfa")
    void fullRuleAndDefaults() {
        List<KeywordRule> rules = KeywordRules.parseRequest(List.of(
                rule("id", "a1", "kind", "regex", "pattern", "Sipariş no: \\d{6}", "case_sensitive", true,
                        "operator", "lt", "count", 5, "critical", rule("operator", "LT", "count", 20),
                        "scope", rule("type", "css", "value", " #liste "), "label", " Siparişler "),
                rule("pattern", "Fiyat")));
        KeywordRule a = rules.get(0);
        assertThat(a.id()).isEqualTo("a1");
        assertThat(a.kind()).isEqualTo(Kind.REGEX);
        assertThat(a.caseSensitive()).isTrue();
        assertThat(a.operator()).isEqualTo(Op.LT);
        assertThat(a.count()).isEqualTo(5);
        assertThat(a.critical()).isEqualTo(new KeywordRule.Threshold(Op.LT, 20));
        assertThat(a.scopeType()).isEqualTo(ScopeType.CSS);
        assertThat(a.scopeValue()).isEqualTo("#liste");
        assertThat(a.label()).isEqualTo("Siparişler");
        KeywordRule b = rules.get(1);
        assertThat(b.id()).isEqualTo("r2");   // kimlik yoksa sıra kimliği
        assertThat(b.kind()).isEqualTo(Kind.TEXT);
        assertThat(b.operator()).isEqualTo(Op.GTE);
        assertThat(b.count()).isEqualTo(1);
        assertThat(b.scopeType()).isEqualTo(ScopeType.PAGE);
        assertThat(b.scopeValue()).isNull();
        assertThat(b.caseSensitive()).isFalse();
    }

    @Test
    @DisplayName("Otomatik kimlik açık kimliklerle çakışmaz; kimlik biçimi / tekrar → 400 rules[i].id")
    void ids() {
        List<KeywordRule> r = KeywordRules.parseRequest(List.of(rule("pattern", "a"), rule("id", "r1", "pattern", "b")));
        assertThat(r).extracting(KeywordRule::id).containsExactly("r2", "r1");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("id", "Büyük", "pattern", "a")))))
                .isEqualTo("rules[0].id");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("id", "x", "pattern", "a"),
                rule("id", "x", "pattern", "b"))))).isEqualTo("rules[1].id");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("id", "a".repeat(21), "pattern", "a")))))
                .isEqualTo("rules[0].id");
    }

    @Test
    @DisplayName("Sınırlar: ≤ 10 kural, TEXT ≤ 255, REGEX ≤ 200, satır sonu yok, boş desen yok, etiket ≤ 80")
    void limits() {
        List<Object> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add(rule("pattern", "k" + i));
        assertThat(fieldOf(() -> KeywordRules.parseRequest(eleven))).isEqualTo("rules");
        assertThat(KeywordRules.parseRequest(List.of(rule("pattern", "x".repeat(255))))).hasSize(1);
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "x".repeat(256)))))).isEqualTo("rules[0].pattern");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "x".repeat(201))))))
                .isEqualTo("rules[0].pattern");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a\nb"))))).isEqualTo("rules[0].pattern");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "  "))))).isEqualTo("rules[0].pattern");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "label", "x".repeat(81))))))
                .isEqualTo("rules[0].label");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "kind", "GLOB"))))).isEqualTo("rules[0].kind");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "NE")))))
                .isEqualTo("rules[0].operator");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "count", 1.5))))).isEqualTo("rules[0].count");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "count", -1))))).isEqualTo("rules[0].count");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "case_sensitive", "evet")))))
                .isEqualTo("rules[0].case_sensitive");
    }

    @Test
    @DisplayName("REGEX deseni kayıtta derlenir — sözdizimi hatası, boş eşleşme, karmaşıklık → 400 rules[i].pattern")
    void regexValidatedOnSave() {
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "(abc")))))
                .isEqualTo("rules[0].pattern");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "a*")))))
                .isEqualTo("rules[0].pattern");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "((a{100}){100}){100}")))))
                .isEqualTo("rules[0].pattern");
        assertThat(KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "v\\d+\\.\\d+")))).hasSize(1);
    }

    @Test
    @DisplayName("B10 anlamsız koşul: GTE 0, LT 0, tavan üstü, LTE tavan, GT tavan → 400 (eski tek alan yolunda matchCount)")
    void meaninglessConditions() {
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "GTE", "count", 0)))))
                .isEqualTo("rules[0].count");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "LT", "count", 0)))))
                .isEqualTo("rules[0].count");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "ab", "count", 10_001)))))
                .isEqualTo("rules[0].count");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("kind", "REGEX", "pattern", "ab",
                "operator", "LTE", "count", SafeRegex.MAX_COUNT))))).isEqualTo("rules[0].count");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "GT",
                "count", KeywordFold.MAX_COUNT))))).isEqualTo("rules[0].count");
        // anlamlı uç değerler geçer
        assertThat(KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "LTE", "count", 0)))).hasSize(1);
        assertThat(KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "LT", "count", 1)))).hasSize(1);
        assertThat(KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "EQ", "count", 0)))).hasSize(1);
        // eski tek alan yolu
        assertThatThrownBy(() -> KeywordRules.requireMeaningfulLegacy("GTE", 0))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("matchCount"));
        assertThatThrownBy(() -> KeywordRules.requireMeaningfulLegacy("LT", 0)).isInstanceOf(FieldValidationException.class);
        KeywordRules.requireMeaningfulLegacy("GTE", 1);
        KeywordRules.requireMeaningfulLegacy(null, null);
        KeywordRules.requireMeaningfulLegacy("LTE", 0);
    }

    /** İki eşik doğruluk tablosu: uyarı işleci, uyarı adedi, kritik işleci, kritik adedi, geçerli mi. */
    static final Object[][] TWO_LEVEL = {
            {"LT", 5, "LT", 20, true},    // ≥ 5 uyarı, ≥ 20 kritik
            {"LTE", 4, "LTE", 19, true},
            {"LT", 5, "LTE", 19, true},   // etkin sınır 4 < 19
            {"LT", 5, "LTE", 4, false},   // eşit sınır (4 = 4)
            {"LT", 20, "LT", 5, false},   // ters
            {"GTE", 10, "GTE", 3, true},  // < 10 uyarı, < 3 kritik
            {"GT", 9, "GTE", 3, true},    // etkin 10 > 3
            {"GTE", 10, "GT", 9, false},  // etkin 10 = 10
            {"GTE", 3, "GTE", 10, false}, // ters
            {"GTE", 10, "LT", 3, false},  // aile farklı
            {"EQ", 5, "LT", 3, false},    // EQ ile kritik yok
            {"LT", 5, "EQ", 9, false},
    };

    @Test
    @DisplayName("İki eşik: aynı yön ailesi + daha geniş etkin sınır; EQ ile kritik yok; eşitlik ret → 400 rules[i].critical")
    void twoLevelFamilies() {
        for (Object[] row : TWO_LEVEL) {
            Map<String, Object> r = rule("pattern", "hata", "operator", row[0], "count", row[1],
                    "critical", rule("operator", row[2], "count", row[3]));
            if ((Boolean) row[4]) {
                assertThat(KeywordRules.parseRequest(List.of(r))).as(java.util.Arrays.toString(row)).hasSize(1);
            } else {
                assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(r)))).as(java.util.Arrays.toString(row))
                        .isEqualTo("rules[0].critical");
            }
        }
        // kritik de anlamlı olmalı (GTE 0 asla ihlal edilmez)
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "operator", "GTE", "count", 3,
                "critical", rule("operator", "GTE", "count", 0)))))).isEqualTo("rules[0].critical");
        assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "critical", "x")))))
                .isEqualTo("rules[0].critical");
    }

    @Test
    @DisplayName("Bölge: PAGE / boş → null; CSS ve JSON doğrulanır (boş, geçersiz, :matches, ~=, çift :has → 400 rules[i].scope)")
    void scopes() {
        assertThat(KeywordRules.parseRequest(List.of(rule("pattern", "a", "scope", rule("type", "PAGE")))).get(0).scope()).isNull();
        assertThat(KeywordRules.parseRequest(List.of(rule("pattern", "a", "scope", rule("type", "JSON", "value", "$.items[0].ad"))))
                .get(0).scopeValue()).isEqualTo("$.items[0].ad");
        for (Map<String, Object> bad : List.of(
                rule("type", "CSS", "value", ""),
                rule("type", "CSS", "value", "div[[["),
                rule("type", "CSS", "value", "p:matches((a+)+b)"),
                rule("type", "CSS", "value", "a[href~=x]"),
                rule("type", "CSS", "value", "div:has(p):has(span)"),
                rule("type", "JSON", "value", ""),
                rule("type", "JSON", "value", "$..x"),
                rule("type", "XPATH", "value", "//a"))) {
            assertThat(fieldOf(() -> KeywordRules.parseRequest(List.of(rule("pattern", "a", "scope", bad)))))
                    .as(bad.toString()).isEqualTo("rules[0].scope");
        }
    }

    @Test
    @DisplayName("Mantık: AND / OR (harf duyarsız); boş → null; başka → 400 ruleLogic; saklı bozuk → AND")
    void logic() {
        assertThat(KeywordRules.parseLogic("or")).isEqualTo("OR");
        assertThat(KeywordRules.parseLogic(" AND ")).isEqualTo("AND");
        assertThat(KeywordRules.parseLogic("")).isNull();
        assertThat(KeywordRules.parseLogic(null)).isNull();
        assertThat(fieldOf(() -> KeywordRules.parseLogic("ANY"))).isEqualTo("ruleLogic");
        assertThat(KeywordRules.effectiveLogic(null)).isEqualTo("AND");
        assertThat(KeywordRules.effectiveLogic("x")).isEqualTo("AND");
        assertThat(KeywordRules.effectiveLogic("or")).isEqualTo("OR");
    }

    @Test
    @DisplayName("JSON gidiş-dönüş: toJson → readStored aynı kurallar; snake_case anahtarlar; boş → null")
    void jsonRoundTrip() {
        List<KeywordRule> rules = KeywordRules.parseRequest(List.of(
                rule("id", "a", "kind", "REGEX", "pattern", "v\\d+", "operator", "GTE", "count", 2,
                        "critical", rule("operator", "GTE", "count", 1), "scope", rule("type", "JSON", "value", "$.v")),
                rule("id", "b", "pattern", "Stokta yok", "operator", "LTE", "count", 0, "label", "Stok")));
        String json = KeywordRules.toJson(rules);
        assertThat(json).contains("\"case_sensitive\":false").contains("\"pattern\":\"v\\\\d+\"").contains("\"scope\":null");
        assertThat(KeywordRules.readStored(json)).isEqualTo(rules);
        assertThat(KeywordRules.toJson(List.of())).isNull();
        assertThat(KeywordRules.toApi(rules).get(1)).containsEntry("label", "Stok").containsEntry("critical", null);
    }

    @Test
    @DisplayName("Saklı değer hoşgörülü: bozuk JSON → boş; bozuk kural düşer; tutarsız kritik düşer (kural kalır)")
    void lenientStored() {
        assertThat(KeywordRules.readStored("{bozuk")).isEmpty();
        assertThat(KeywordRules.readStored("{\"a\":1}")).isEmpty();
        List<KeywordRule> r = KeywordRules.readStored("[{\"id\":\"a\",\"pattern\":\"\"},"
                + "{\"id\":\"b\",\"pattern\":\"x\",\"operator\":\"??\",\"count\":\"z\",\"critical\":{\"operator\":\"GTE\",\"count\":5}},"
                + "{\"pattern\":\"y\",\"kind\":\"REGEX\",\"scope\":{\"type\":\"CSS\",\"value\":\"#a\"}}]");
        assertThat(r).hasSize(2);
        assertThat(r.get(0).id()).isEqualTo("b");
        assertThat(r.get(0).operator()).isEqualTo(Op.GTE);
        assertThat(r.get(0).count()).isEqualTo(1);
        assertThat(r.get(0).critical()).as("GTE 1 / kritik GTE 5 tutarsız").isNull();
        assertThat(r.get(1).kind()).isEqualTo(Kind.REGEX);
        assertThat(r.get(1).scopeType()).isEqualTo(ScopeType.CSS);
    }

    @Test
    @DisplayName("Eski alanlar: etkin kural rules_json yoksa keyword/operator/count/cs'den (id r1); basit tek kural; ayna")
    void legacy() {
        List<KeywordRule> eff = KeywordRules.effective(null, "Giriş", "LTE", 0, true);
        assertThat(eff).hasSize(1);
        KeywordRule r = eff.get(0);
        assertThat(r.id()).isEqualTo("r1");
        assertThat(r.kind()).isEqualTo(Kind.TEXT);
        assertThat(r.operator()).isEqualTo(Op.LTE);
        assertThat(r.count()).isEqualTo(0);
        assertThat(r.caseSensitive()).isTrue();
        assertThat(r.absenceRule()).isTrue();
        assertThat(KeywordRules.isSimple(eff)).isTrue();
        assertThat(KeywordRules.effective(null, null, null, null, null)).isEmpty();
        assertThat(KeywordRules.effective("bozuk", "x", null, null, null)).hasSize(1);
        assertThat(KeywordRules.effective(null, "x", "??", null, null).get(0).operator()).isEqualTo(Op.GTE);

        List<KeywordRule> two = KeywordRules.parseRequest(List.of(rule("pattern", "a"), rule("pattern", "b")));
        assertThat(KeywordRules.isSimple(two)).isFalse();
        assertThat(KeywordRules.isSimple(KeywordRules.parseRequest(List.of(rule("pattern", "a", "label", "L"))))).isFalse();
        assertThat(KeywordRules.isSimple(KeywordRules.parseRequest(List.of(rule("pattern", "a", "kind", "REGEX"))))).isFalse();
        KeywordRules.LegacyMirror mirror = KeywordRules.legacyMirror(KeywordRules.parseRequest(List.of(
                rule("kind", "REGEX", "pattern", "v\\d+", "operator", "LT", "count", 3, "case_sensitive", true), rule("pattern", "b"))));
        assertThat(mirror).isEqualTo(new KeywordRules.LegacyMirror("v\\d+", "LT", 3, true));
        assertThat(KeywordRules.legacyMirror(List.of())).isNull();
    }

    @Test
    @DisplayName("B12: hiç geçmemeli = LTE 0, EQ 0, LT 1 (LT 1 dahil)")
    void absence() {
        assertThat(KeywordRule.absence(Op.LTE, 0)).isTrue();
        assertThat(KeywordRule.absence(Op.EQ, 0)).isTrue();
        assertThat(KeywordRule.absence(Op.LT, 1)).isTrue();
        assertThat(KeywordRule.absence(Op.LT, 2)).isFalse();
        assertThat(KeywordRule.absence(Op.GTE, 1)).isFalse();
        assertThat(KeywordRule.absence(null, 0)).isFalse();
    }

    @Test
    @DisplayName("İşleç eşliği: KeywordRule.Op.healthy / phraseTr = KeywordCheckerService.evaluate / opPhrase (tek anlam)")
    void opParityWithLegacy() {
        for (Op op : Op.values()) {
            for (int n = 0; n <= 4; n++) {
                for (int c = 0; c <= 6; c++) {
                    assertThat(op.healthy(c, n)).as(op + " " + c + " " + n).isEqualTo(KeywordCheckerService.evaluate(c, op.name(), n));
                }
                assertThat(op.phraseTr(n)).isEqualTo(KeywordCheckerService.opPhrase(op.name(), n));
            }
        }
    }

    @Test
    @DisplayName("rules_json ≤ 16 KB — 10 tam boy kural sığar")
    void jsonFits() {
        List<Object> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> r = new HashMap<>(rule("pattern", "p".repeat(255), "label", "l".repeat(80),
                    "scope", rule("type", "JSON", "value", "$." + "a".repeat(290))));
            ten.add(r);
        }
        List<KeywordRule> parsed = KeywordRules.parseRequest(ten);
        assertThat(KeywordRules.toJson(parsed).length()).isLessThanOrEqualTo(KeywordRules.MAX_JSON_CHARS);
    }
}
