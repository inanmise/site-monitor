package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.service.keyword.KeywordFold;
import com.sitemonitor.service.keyword.rules.KeywordRule.Kind;
import com.sitemonitor.service.keyword.rules.KeywordRule.Op;
import com.sitemonitor.service.keyword.rules.KeywordRule.Scope;
import com.sitemonitor.service.keyword.rules.KeywordRule.ScopeType;
import com.sitemonitor.service.keyword.rules.KeywordRule.Threshold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saf kural değerlendirici (kw sözleşmesi §6.2): VE / VEYA × seviye doğruluk tablosu, METİN katlama, bölge notları, özet,
 * sonuç JSON'u.
 */
class KeywordRuleEvaluatorTest {

    /** Sayfa: "ok" 1, "hata" 3, "kritik" 4, "yok" 0. */
    static final String PAGE = "ok hata hata hata kritik kritik kritik kritik";

    static KeywordRule text(String id, String p, Op op, int n, Threshold crit) {
        return new KeywordRule(id, Kind.TEXT, p, false, op, n, crit, null, null);
    }

    /** sağlanır */
    static final KeywordRule OK = text("ok", "ok", Op.GTE, 1, null);
    /** ihlal, kritik eşiksiz → MONITOR */
    static final KeywordRule MON = text("mon", "yok", Op.GTE, 1, null);
    /** "hata" 3: uyarı LT 2 ihlal, kritik LT 5 sağlanır → WARNING */
    static final KeywordRule WARN = text("warn", "hata", Op.LT, 2, new Threshold(Op.LT, 5));
    /** "kritik" 4: kritik LT 3 ihlal → CRITICAL */
    static final KeywordRule CRIT = text("crit", "kritik", Op.LT, 2, new Threshold(Op.LT, 3));

    /** logic, izleme seviyesi, kurallar, ihlal?, birleşik seviye, breach_level. */
    static final Object[][] TABLE = {
            {"AND", "WARNING", List.of(OK), false, null, null},
            {"AND", "WARNING", List.of(OK, MON), true, "WARNING", null},
            {"AND", "HIGH", List.of(OK, MON), true, "HIGH", null},
            {"AND", "WARNING", List.of(WARN, MON), true, "WARNING", "WARNING"},   // eşitte açık seviye yeğlenir
            {"AND", "HIGH", List.of(WARN, MON), true, "HIGH", null},              // en yüksek = izleme seviyesi
            {"AND", "HIGH", List.of(CRIT, MON), true, "CRITICAL", "CRITICAL"},
            {"AND", "CRITICAL", List.of(CRIT, MON), true, "CRITICAL", "CRITICAL"},
            {"AND", "WARNING", List.of(WARN, CRIT), true, "CRITICAL", "CRITICAL"},
            {"OR", "WARNING", List.of(OK, MON), false, null, null},               // biri sağlandı
            {"OR", "WARNING", List.of(WARN, CRIT), true, "WARNING", "WARNING"},   // en düşük (en iyi kural)
            {"OR", "HIGH", List.of(MON, CRIT), true, "HIGH", null},
            {"OR", "HIGH", List.of(MON, WARN), true, "WARNING", "WARNING"},
            {"OR", "WARNING", List.of(MON, WARN), true, "WARNING", "WARNING"},
            {"OR", "CRITICAL", List.of(MON), true, "CRITICAL", null},
            {"AND", null, List.of(MON), true, "WARNING", null},                   // seviye yok → WARNING
            {"xyz", "HIGH", List.of(OK, MON), true, "HIGH", null},                // bilinmeyen mantık → AND
    };

    @Test
    @DisplayName("VE / VEYA × seviye doğruluk tablosu (birleşik seviye + breach_level)")
    @SuppressWarnings("unchecked")
    void truthTable() {
        KeywordContent content = KeywordContent.builder(PAGE).build();
        for (Object[] row : TABLE) {
            var e = KeywordRuleEvaluator.evaluate((List<KeywordRule>) row[2], (String) row[0], content, (String) row[1]);
            String label = row[0] + "/" + row[1] + "/" + ((List<KeywordRule>) row[2]).stream().map(KeywordRule::id).toList();
            assertThat(e.breached()).as(label).isEqualTo(row[3]);
            assertThat(e.level()).as(label).isEqualTo(row[4]);
            assertThat(e.breachLevel()).as(label).isEqualTo(row[5]);
            if (!(Boolean) row[3]) assertThat(e.summary()).as(label).isNull();
            else assertThat(e.summary()).as(label).isNotBlank();
        }
    }

    @Test
    @DisplayName("Kural sonucu: adet, ok, seviye (NONE / WARNING / CRITICAL / MONITOR); kritik eşik önce denetlenir")
    void perRuleLevels() {
        KeywordContent content = KeywordContent.builder(PAGE).build();
        var e = KeywordRuleEvaluator.evaluate(List.of(OK, MON, WARN, CRIT), "AND", content, "HIGH");
        assertThat(e.results()).extracting(KeywordRuleEvaluator.RuleResult::level)
                .containsExactly("NONE", "MONITOR", "WARNING", "CRITICAL");
        assertThat(e.results()).extracting(KeywordRuleEvaluator.RuleResult::count).containsExactly(1, 0, 3, 4);
        assertThat(e.results()).extracting(KeywordRuleEvaluator.RuleResult::ok).containsExactly(true, false, false, false);
        assertThat(e.multi()).isTrue();
        assertThat(e.primary().id()).isEqualTo("ok");
        // kritik eşik sağlanır + uyarı sağlanır → NONE
        var good = KeywordRuleEvaluator.evaluate(List.of(text("g", "hata", Op.LT, 5, new Threshold(Op.LT, 9))), "AND",
                content, "WARNING");
        assertThat(good.breached()).isFalse();
        assertThat(good.results().get(0).level()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("METİN: Türkçe katlama (İ/I/ı/i) duyarsızda; duyarlıda birebir; ilk eşleşme konumu")
    void textFolding() {
        KeywordContent c = KeywordContent.builder("Hoş geldiniz — İLETİŞİM / iletişim").build();
        var ci = KeywordRuleEvaluator.evaluate(List.of(text("a", "İletişim", Op.GTE, 2, null)), "AND", c, null);
        assertThat(ci.results().get(0).count()).isEqualTo(2);
        assertThat(ci.breached()).isFalse();
        assertThat(ci.primary().matchStart()).isEqualTo(15);
        var cs = KeywordRuleEvaluator.evaluate(List.of(new KeywordRule("a", Kind.TEXT, "İLETİŞİM", true, Op.GTE, 2,
                null, null, null)), "AND", c, null);
        assertThat(cs.results().get(0).count()).isEqualTo(1);
        assertThat(cs.breached()).isTrue();
    }

    @Test
    @DisplayName("DÜZENLİ İFADE kuralı: sayım + katlama; geçersiz saklı desen sayılamaz (0)")
    void regexRule() {
        KeywordContent c = KeywordContent.builder("Sürüm v2.10 ve V3.1, ayrıca v10").build();
        var e = KeywordRuleEvaluator.evaluate(List.of(new KeywordRule("r", Kind.REGEX, "v\\d+\\.\\d+", false, Op.GTE, 2,
                null, null, null)), "AND", c, null);
        assertThat(e.results().get(0).count()).isEqualTo(2);
        assertThat(e.breached()).isFalse();
        var bad = KeywordRuleEvaluator.evaluate(List.of(new KeywordRule("r", Kind.REGEX, "(bozuk", false, Op.GTE, 1,
                null, null, null)), "AND", c, null);
        assertThat(bad.results().get(0).count()).isZero();
        assertThat(bad.breached()).isTrue();
    }

    @Test
    @DisplayName("Notlar: COUNT_CAPPED (METİN tavanı), SCAN_TRUNCATED (DÜZENLİ İFADE 512 K)")
    void notes() {
        String many = "a".repeat(KeywordFold.MAX_COUNT + 10);
        var t = KeywordRuleEvaluator.evaluate(List.of(text("t", "a", Op.GTE, 1, null)), "AND",
                KeywordContent.builder(many).build(), null);
        assertThat(t.results().get(0).note()).isEqualTo(KeywordRuleEvaluator.NOTE_COUNT_CAPPED);
        String big = "x".repeat(SafeRegex.REGEX_SCAN_CAP + 5) + "zz";
        var r = KeywordRuleEvaluator.evaluate(List.of(new KeywordRule("r", Kind.REGEX, "zz", true, Op.LTE, 0, null, null,
                null)), "AND", KeywordContent.builder(big).build(), null);
        assertThat(r.results().get(0).note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCAN_TRUNCATED);
        assertThat(r.breached()).as("kırpılan bölüm aranmaz — sayım alt sınır").isFalse();
    }

    @Test
    @DisplayName("Bölge bulunamadı / geçersiz → yön ne olursa olsun İHLAL, adet 0; seviye uyarı düzeyi; tek kuralda scopeNote")
    void scopeNotes() {
        String html = "<html><body><div id='fiyat'>100 TL</div></body></html>";
        KeywordRule absentCss = new KeywordRule("a", Kind.TEXT, "TL", false, Op.LTE, 0, null,
                new Scope(ScopeType.CSS, "#yok"), null);
        var e = KeywordRuleEvaluator.evaluate(List.of(absentCss), "AND", KeywordContent.builder(html).build(), "HIGH");
        assertThat(e.breached()).as("içermemeli kuralı bile bölge yoksa ihlal").isTrue();
        assertThat(e.results().get(0).note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
        assertThat(e.results().get(0).level()).isEqualTo("MONITOR");
        assertThat(e.level()).isEqualTo("HIGH");
        assertThat(e.scopeNote()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
        assertThat(e.summary()).contains("içerik bölgesi bulunamadı").contains("#yok");

        KeywordRule critCss = new KeywordRule("b", Kind.TEXT, "TL", false, Op.LT, 2, new Threshold(Op.LT, 5),
                new Scope(ScopeType.CSS, "#yok"), null);
        var e2 = KeywordRuleEvaluator.evaluate(List.of(critCss), "AND", KeywordContent.builder(html).build(), "HIGH");
        assertThat(e2.results().get(0).level()).as("kritik eşikli kuralda bölge yok = WARNING").isEqualTo("WARNING");
        assertThat(e2.breachLevel()).isEqualTo("WARNING");

        KeywordRule json = new KeywordRule("j", Kind.TEXT, "UP", false, Op.GTE, 1, null, new Scope(ScopeType.JSON, "$.status"), null);
        var inv = KeywordRuleEvaluator.evaluate(List.of(json), "AND", KeywordContent.builder(html).build(), null);
        assertThat(inv.results().get(0).note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_INVALID);
        assertThat(inv.summary()).contains("JSON");
        // çok kuralda scopeNote yok (neden kodu RULES_NOT_MET)
        var multi = KeywordRuleEvaluator.evaluate(List.of(absentCss, OK), "AND", KeywordContent.builder(html).build(), null);
        assertThat(multi.scopeNote()).isNull();
    }

    @Test
    @DisplayName("Özet: tek kural '« X » n kez (beklenen: …)'; çok kural 'n/m kural sağlanmadı'; VEYA 'hiçbiri'; ≤ 300; etiket yeğlenir")
    void summaryText() {
        KeywordContent c = KeywordContent.builder(PAGE).build();
        var one = KeywordRuleEvaluator.evaluate(List.of(MON), "AND", c, null);
        assertThat(one.summary()).isEqualTo("« yok » 0 kez (beklenen: en az 1 kez)");
        var two = KeywordRuleEvaluator.evaluate(List.of(OK, MON, CRIT), "AND", c, null);
        assertThat(two.summary()).startsWith("2/3 kural sağlanmadı: ").contains("kritik eşik");
        var or = KeywordRuleEvaluator.evaluate(List.of(MON, CRIT), "OR", c, null);
        assertThat(or.summary()).startsWith("Kuralların hiçbiri sağlanmadı (2/2): ");
        KeywordRule labeled = new KeywordRule("l", Kind.TEXT, "yok", false, Op.GTE, 1, null, null, "Sepete ekle düğmesi");
        assertThat(KeywordRuleEvaluator.evaluate(List.of(labeled), "AND", c, null).summary()).contains("Sepete ekle düğmesi");
        List<KeywordRule> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(text("k" + i, "eksik-ifade-" + "x".repeat(40) + i, Op.GTE, 1, null));
        assertThat(KeywordRuleEvaluator.evaluate(ten, "AND", c, null).summary().length())
                .isLessThanOrEqualTo(KeywordRuleEvaluator.SUMMARY_MAX);
    }

    @Test
    @DisplayName("rule_results JSON: sözleşme anahtarları, ≤ 4000, gidiş-dönüş (parseResults); bozuk → boş liste")
    void resultsJson() {
        KeywordContent c = KeywordContent.builder(PAGE).build();
        var e = KeywordRuleEvaluator.evaluate(List.of(OK, CRIT), "AND", c, null);
        String json = e.resultsJson();
        assertThat(json.length()).isLessThanOrEqualTo(KeywordRuleEvaluator.RESULTS_JSON_MAX);
        List<Map<String, Object>> back = KeywordRuleEvaluator.parseResults(json);
        assertThat(back).hasSize(2);
        assertThat(back.get(1)).containsOnlyKeys("id", "kind", "pattern_preview", "count", "ok", "level", "note")
                .containsEntry("id", "crit").containsEntry("count", 4).containsEntry("ok", false).containsEntry("level", "CRITICAL");
        assertThat(e.resultsApi()).isEqualTo(back);
        assertThat(KeywordRuleEvaluator.parseResults("bozuk")).isEmpty();
        assertThat(KeywordRuleEvaluator.parseResults(null)).isEmpty();
        // 10 uzun desen + uzun kimlikler → önizleme kısalır ama JSON sığar
        List<KeywordRule> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new KeywordRule("kural-" + "x".repeat(12) + i, Kind.TEXT, "p".repeat(255), false, Op.GTE, 1, null, null, null));
        }
        String big = KeywordRuleEvaluator.evaluate(ten, "AND", c, null).resultsJson();
        assertThat(big).isNotNull();
        assertThat(big.length()).isLessThanOrEqualTo(KeywordRuleEvaluator.RESULTS_JSON_MAX);
    }

    @Test
    @DisplayName("Boş kural listesi / boş içerik → ihlal yok (fırlatmaz)")
    void empty() {
        assertThat(KeywordRuleEvaluator.evaluate(List.of(), "AND", KeywordContent.builder("x").build(), null).breached()).isFalse();
        assertThat(KeywordRuleEvaluator.evaluate(null, "AND", null, null).breached()).isFalse();
        assertThat(KeywordRuleEvaluator.normalizeLevel("high")).isEqualTo("HIGH");
        assertThat(KeywordRuleEvaluator.normalizeLevel("??")).isEqualTo("WARNING");
    }
}
