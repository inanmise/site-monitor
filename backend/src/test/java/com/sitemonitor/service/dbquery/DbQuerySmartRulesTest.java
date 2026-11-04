package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.dbquery.DbQueryEvaluator.History;
import com.sitemonitor.service.dbquery.DbQueryEvaluator.Outcome;
import com.sitemonitor.service.dbquery.DbQueryEvaluator.RuleResult;
import com.sitemonitor.service.dbquery.DbQueryRules.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Akıllı kurallar (dbq2 D): olağan dışı değer ({@code ANOMALY}) ve donmuş değer ({@code FROZEN}) — doğrulama (katı kayıt,
 * gevşek okuma), parmak izi (kolon değeri kuralıyla aynı ölçü), değerlendirme doğruluk tabloları (σ = 0, yön, eşik sınırı, az
 * örnek, taban sıfırlama, eşit değer serisi), geçmiş planı / seçimi ({@link DbQueryHistory}, mevsim penceresi), alarm metni ve
 * eşik denemesinin kayan geçmişle canlı kuralla AYNI kararı vermesi.
 */
class DbQuerySmartRulesTest {

    static Rule rule(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        DbQueryRules.Parsed p = DbQueryRules.fromApi(List.of(m));
        assertThat(p.errors()).as("geçerli kural").isEmpty();
        return p.rules().get(0);
    }

    static Map<String, String> errors(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        Map<String, String> out = new LinkedHashMap<>();
        for (DbQueryRules.FieldError e : DbQueryRules.fromApi(List.of(m)).errors()) out.put(e.field(), e.code());
        return out;
    }

    static DbQueryRunner.QueryResult value(Object v) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList(v));
        return new DbQueryRunner.QueryResult(true, 5, 1, false, List.of(new DbQueryRunner.Column("adet", "NUMERIC")), rows,
                false, null, null, null);
    }

    static List<Double> ds(double... v) {
        List<Double> out = new ArrayList<>();
        for (double x : v) out.add(x);
        return out;
    }

    static Outcome eval(Rule r, Object v, History h) {
        return DbQueryEvaluator.evaluate(List.of(r), "ANY", value(v),
                new DbQueryEvaluator.Baselines(Map.of(), Map.of(), h == null ? Map.of() : Map.of(r.id(), h)), "tr");
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("doğrulama")
    class Validation {

        @Test
        @DisplayName("varsayılanlar: anomali altı parametre her zaman yazılır; donma kritik yoksa runs_critical yazılmaz")
        void defaults() {
            Rule a = rule("kind", "anomaly", "column", "adet");
            assertThat(a.kind()).isEqualTo("ANOMALY");
            assertThat(a.aggregate()).isEqualTo("FIRST");
            assertThat(a.operator()).isNull();
            assertThat(a.anomaly()).isEqualTo(DbQueryRules.Anomaly.defaults());
            Map<String, Object> api = DbQueryRules.toApi(List.of(a)).get(0);
            assertThat(api).containsEntry("direction", "both").containsEntry("k_warning", 2.5).containsEntry("k_critical", 3.5)
                    .containsEntry("window", 30).containsEntry("min_samples", 10).containsEntry("season", "none")
                    .doesNotContainKeys("operator", "warning", "critical", "scope", "change", "runs_warning");

            Rule f = rule("kind", "FROZEN", "column", "adet", "aggregate", "max");
            assertThat(f.frozen()).isEqualTo(new DbQueryRules.Frozen(6, null));
            assertThat(f.aggregate()).isEqualTo("MAX");
            Map<String, Object> fApi = DbQueryRules.toApi(List.of(f)).get(0);
            assertThat(fApi).containsEntry("runs_warning", 6).doesNotContainKeys("runs_critical", "k_warning", "operator");
        }

        @Test
        @DisplayName("gönderilen operatör / eşik yok sayılır; büyük harf yön / mevsim ve sayı metni kabul")
        void lenientInputShapes() {
            Rule a = rule("kind", "ANOMALY", "column", "adet", "operator", "GT", "warning", 10, "direction", "UP",
                    "season", "Daily", "k_warning", "2", "k_critical", 4, "window", "40", "min_samples", 12.0);
            assertThat(a.operator()).isNull();
            assertThat(a.warning()).isNull();
            assertThat(a.anomaly()).isEqualTo(new DbQueryRules.Anomaly("up", 2, 4, 40, 12, "daily"));
            // Kritik gönderilmedi, uyarı varsayılan kritiği aşıyor → kritik = uyarı
            Rule b = rule("kind", "ANOMALY", "column", "adet", "k_warning", 5);
            assertThat(b.anomaly().kCritical()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("alan hataları rules[N].<parametre> — sınırlar ve ilişkiler")
        void fieldErrors() {
            assertThat(errors("kind", "ANOMALY")).containsKey("rules[0].column");
            assertThat(errors("kind", "ANOMALY", "column", "a", "aggregate", "MEDIAN")).containsKey("rules[0].aggregate");
            assertThat(errors("kind", "ANOMALY", "column", "a", "direction", "sideways"))
                    .containsEntry("rules[0].direction", "DBQUERY_RULE_ANOMALY");
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_warning", 0.5)).containsKey("rules[0].k_warning");
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_warning", 11)).containsKey("rules[0].k_warning");
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_warning", "çok")).containsKey("rules[0].k_warning");
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_warning", 3, "k_critical", 2))
                    .containsEntry("rules[0].k_critical", "DBQUERY_RULE_ANOMALY");
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_critical", 10.5)).containsKey("rules[0].k_critical");
            assertThat(errors("kind", "ANOMALY", "column", "a", "window", 9)).containsKey("rules[0].window");
            assertThat(errors("kind", "ANOMALY", "column", "a", "window", 201)).containsKey("rules[0].window");
            assertThat(errors("kind", "ANOMALY", "column", "a", "window", 30.5)).containsKey("rules[0].window");
            assertThat(errors("kind", "ANOMALY", "column", "a", "min_samples", 4)).containsKey("rules[0].min_samples");
            assertThat(errors("kind", "ANOMALY", "column", "a", "window", 20, "min_samples", 21)).containsKey("rules[0].min_samples");
            assertThat(errors("kind", "ANOMALY", "column", "a", "season", "monthly")).containsKey("rules[0].season");
            assertThat(errors("kind", "FROZEN", "column", "a", "runs_warning", 1)).containsEntry("rules[0].runs_warning", "DBQUERY_RULE_FROZEN");
            assertThat(errors("kind", "FROZEN", "column", "a", "runs_warning", 101)).containsKey("rules[0].runs_warning");
            assertThat(errors("kind", "FROZEN", "column", "a", "runs_warning", true)).containsKey("rules[0].runs_warning");
            assertThat(errors("kind", "FROZEN", "column", "a", "runs_warning", 6, "runs_critical", 6)).containsKey("rules[0].runs_critical");
            assertThat(errors("kind", "FROZEN", "column", "a", "runs_critical", 101)).containsKey("rules[0].runs_critical");
            // sınırlar dahil geçerli
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_warning", 1, "k_critical", 10, "window", 10, "min_samples", 10))
                    .isEmpty();
            assertThat(errors("kind", "ANOMALY", "column", "a", "k_warning", 3, "k_critical", 3, "window", 200, "min_samples", 5))
                    .isEmpty();
            assertThat(errors("kind", "FROZEN", "column", "a", "runs_warning", 2, "runs_critical", 100)).isEmpty();
        }

        @Test
        @DisplayName("gevşek okuma: geçerli saklı kural aynen döner (eşitlik); sınır dışı parametre kuralı DÜŞÜRMEZ, sınıra / varsayılana çekilir")
        void storedRoundTrip() {
            Rule a = rule("kind", "ANOMALY", "column", "adet", "direction", "down", "k_warning", 2.2, "k_critical", 3.3,
                    "window", 50, "min_samples", 20, "season", "weekly");
            Rule f = rule("id", "f", "kind", "FROZEN", "column", "adet", "runs_warning", 4, "runs_critical", 9);
            String json = DbQueryRules.toJson(List.of(a, f));
            assertThat(DbQueryRules.fromJson(json)).containsExactly(a, f);
            assertThat(DbQueryRules.fromJson("[{\"id\":\"x\",\"kind\":\"ANOMALY\",\"column\":\"a\",\"min_samples\":3,"
                    + "\"window\":500,\"k_warning\":4,\"k_critical\":2,\"direction\":\"yan\",\"season\":\"aylık\"}]"))
                    .singleElement().satisfies(r -> assertThat(r.anomaly())
                            .isEqualTo(new DbQueryRules.Anomaly("both", 4, 4, 200, 5, "none")));
            assertThat(DbQueryRules.fromJson("[{\"id\":\"x\",\"kind\":\"FROZEN\",\"column\":\"a\"}]"))
                    .singleElement().satisfies(r -> assertThat(r.frozen()).isEqualTo(new DbQueryRules.Frozen(6, null)));
            assertThat(DbQueryRules.fromJson("[{\"id\":\"x\",\"kind\":\"FROZEN\",\"column\":\"a\",\"runs_warning\":500,"
                    + "\"runs_critical\":7}]")).singleElement()
                    .satisfies(r -> assertThat(r.frozen()).isEqualTo(new DbQueryRules.Frozen(100, null)));
            // parametresiz saklı anomali (başka sürüm) → varsayılanlar; aynı listedeki öteki kural etkilenmez
            assertThat(DbQueryRules.fromJson("[{\"id\":\"x\",\"kind\":\"ANOMALY\",\"column\":\"a\"},"
                    + "{\"id\":\"y\",\"kind\":\"ROW_COUNT\",\"operator\":\"GT\",\"warning\":1}]"))
                    .hasSize(2).first().satisfies(r -> assertThat(r.anomaly()).isEqualTo(DbQueryRules.Anomaly.defaults()));
            // kolon yok → kural yine düşer (ölçü tanımsız)
            assertThat(DbQueryRules.fromJson("[{\"id\":\"x\",\"kind\":\"ANOMALY\"}]")).isEmpty();
        }
    }

    // ── Parmak izi ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parmak izi: anomali / donma = aynı kolon + hesaplı kolon değeri kuralı; eşik parametreleri girmez, kolon / hesap girer")
    void fingerprint() {
        String cv = DbQueryRules.fingerprint(rule("kind", "COLUMN_VALUE", "column", "Adet", "operator", "GT", "warning", 1));
        String an = DbQueryRules.fingerprint(rule("kind", "ANOMALY", "column", "adet"));
        String an2 = DbQueryRules.fingerprint(rule("kind", "ANOMALY", "column", "adet", "k_warning", 4, "window", 90,
                "season", "weekly", "direction", "up"));
        String fr = DbQueryRules.fingerprint(rule("kind", "FROZEN", "column", "adet", "runs_warning", 9));
        assertThat(an).isEqualTo(cv).isEqualTo(an2).isEqualTo(fr);
        assertThat(DbQueryRules.fingerprint(rule("kind", "ANOMALY", "column", "adet", "aggregate", "SUM"))).isNotEqualTo(an);
        assertThat(DbQueryRules.fingerprint(rule("kind", "ANOMALY", "column", "tutar"))).isNotEqualTo(an);
    }

    // ── Olağan dışı değer ───────────────────────────────────────────────────

    @Nested
    @DisplayName("olağan dışı değer")
    class Anomaly {

        /** Taban [48, 48, 50, 52, 52]: ortalama 50, örnek σ 2 (taban 50 × %2 = 1 < 2) → olağan [45, 55]. */
        final History base = new History(ds(48, 48, 50, 52, 52), false, false);

        @ParameterizedTest(name = "{0} yönü, değer {1} → {2}")
        @CsvSource({
                "both, 50, NONE", "both, 55, NONE", "both, 56, WARNING", "both, 57, WARNING", "both, 58, CRITICAL",
                "both, 45, NONE", "both, 44, WARNING", "both, 43, WARNING", "both, 42, CRITICAL",
                "up, 44, NONE", "up, 42, NONE", "up, 56, WARNING", "up, 58, CRITICAL",
                "down, 58, NONE", "down, 56, NONE", "down, 44, WARNING", "down, 42, CRITICAL"})
        void truthTable(String direction, double v, String level) {
            Rule r = rule("kind", "ANOMALY", "column", "adet", "direction", direction, "min_samples", 5);
            RuleResult rr = eval(r, BigDecimal.valueOf(v), base).results().get(0);
            assertThat(rr.evaluated()).isTrue();
            assertThat(rr.level()).as("eşik sınırı olağan sayılır (sapma k'yi AŞMALI)").isEqualTo(level);
            assertThat(rr.passed()).isEqualTo("NONE".equals(level));
            assertThat(rr.measure()).isEqualTo(v);
            assertThat(rr.compared()).isEqualTo(v);
            assertThat(rr.stats().mean()).isEqualTo(50.0);
            assertThat(rr.stats().sigma()).isEqualTo(2.0);
            assertThat(rr.stats().bandLow()).isEqualTo(45.0);
            assertThat(rr.stats().bandHigh()).isEqualTo(55.0);
            assertThat(rr.stats().deviation()).isEqualTo((v - 50) / 2);
            assertThat(rr.stats().samples()).isEqualTo(5);
        }

        @Test
        @DisplayName("okunur metin: bant + sapma; yön tek uç; beklenen metin katsayı + taban")
        void texts() {
            Rule both = rule("id", "a", "label", "Bekleyen", "kind", "ANOMALY", "column", "adet", "min_samples", 5);
            RuleResult rr = eval(both, 58, base).results().get(0);
            assertThat(rr.actual()).isEqualTo("58 · olağan 45–55 · sapma +4σ");
            assertThat(rr.expected()).isEqualTo("olağan aralıkta (±2,5σ uyarı · ±3,5σ kritik) · taban: son 30 çalıştırma");
            assertThat(eval(rule("kind", "ANOMALY", "column", "adet", "min_samples", 5, "direction", "up"), 42, base)
                    .results().get(0).actual()).isEqualTo("42 · olağan ≤ 55 · sapma −4σ");
            RuleResult down = eval(rule("kind", "ANOMALY", "column", "adet", "min_samples", 5, "direction", "down",
                    "season", "weekly", "window", 12), 42, base).results().get(0);
            assertThat(down.actual()).isEqualTo("42 · olağan ≥ 45 · sapma −4σ");
            assertThat(down.expected()).isEqualTo("olağandan düşük olmamalı (−2,5σ uyarı · −3,5σ kritik) · taban: önceki 12 "
                    + "haftanın aynı gün ve saati");
            assertThat(DbQueryEvaluator.evaluate(List.of(both), "ANY", value(58),
                    new DbQueryEvaluator.Baselines(Map.of(), Map.of(), Map.of("a", base)), "en").results().get(0).actual())
                    .isEqualTo("58 · usual 45–55 · deviation +4σ");
        }

        @Test
        @DisplayName("σ = 0: göreli taban (|ortalama| × %2); ortalama da 0 ise farklı her değer sınırsız sapma (kritik)")
        void zeroSigma() {
            Rule r = rule("kind", "ANOMALY", "column", "adet", "min_samples", 5);
            History flat = new History(ds(100, 100, 100, 100, 100), false, false);
            RuleResult ok = eval(r, 104, flat).results().get(0);
            assertThat(ok.stats().sigma()).isEqualTo(2.0);
            assertThat(ok.level()).isEqualTo("NONE");
            assertThat(eval(r, 106, flat).results().get(0).level()).isEqualTo("WARNING");
            assertThat(eval(r, 108, flat).results().get(0).level()).isEqualTo("CRITICAL");
            assertThat(eval(r, 92, flat).results().get(0).level()).isEqualTo("CRITICAL");

            History zeros = new History(ds(0, 0, 0, 0, 0, 0), false, false);
            RuleResult same = eval(r, 0, zeros).results().get(0);
            assertThat(same.level()).isEqualTo("NONE");
            assertThat(same.stats().deviation()).isEqualTo(0.0);
            assertThat(same.actual()).isEqualTo("0 · olağan 0 · sapma +0σ");
            RuleResult one = eval(r, 1, zeros).results().get(0);
            assertThat(one.level()).isEqualTo("CRITICAL");
            assertThat(one.stats().deviation()).isEqualTo(DbQueryEvaluator.UNBOUNDED_DEVIATION);
            assertThat(one.actual()).isEqualTo("1 · olağan 0 · sapma +sınırsız");
            Rule down = rule("kind", "ANOMALY", "column", "adet", "min_samples", 5, "direction", "down");
            assertThat(eval(down, 1, zeros).results().get(0).level()).as("aşağı yön yüksek değeri saymaz").isEqualTo("NONE");
        }

        @Test
        @DisplayName("az örnek: taban kuruluyor (n / min) — ihlal değil, kanıt eksikliği değil; ölçü ve grafik değeri yazılır")
        void buildingBaseline() {
            Rule r = rule("id", "a", "kind", "ANOMALY", "column", "adet", "min_samples", 5);
            Outcome o = eval(r, 9_999, new History(ds(1, 2, 3, 4), false, false));
            RuleResult rr = o.results().get(0);
            assertThat(rr.evaluated()).isFalse();
            assertThat(rr.passed()).isTrue();
            assertThat(rr.note()).isEqualTo(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
            assertThat(rr.actual()).isEqualTo("9.999 · taban kuruluyor (4 / 5)");
            assertThat(rr.stats().samples()).isEqualTo(4);
            assertThat(rr.stats().minSamples()).isEqualTo(5);
            assertThat(rr.stats().mean()).isNull();
            assertThat(rr.measure()).isEqualTo(9_999.0);
            assertThat(o.status()).isEqualTo("OK");
            assertThat(o.inconclusive()).as("kanıt eksikliği değil → kontrol UNKNOWN olmaz").isFalse();
            assertThat(o.configUnevaluable()).isFalse();
            assertThat(o.metricValue()).as("grafik akıllı kuralın değerini çizer").isEqualTo(9_999.0);
            assertThat(DbQueryEvaluator.WAITING_NOTES).contains(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
            assertThat(DbQueryAlarmText.thresholdVerdict("OK", DbQueryAlarmText.toApi(o.results()), "ANY"))
                    .as("açık eşik alarmı kapanabilir").isEqualTo(DbQueryAlarmText.VERDICT_OK);
            // Geçmiş hiç yok (yeni izleme / deneme) → yine taban kuruluyor
            assertThat(eval(r, 1, null).results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
        }

        @Test
        @DisplayName("parmak izi / sorgu değişti, örnek yok → baseline_reset (bekleme notu); geçmiş okunamadı → kanıt eksikliği")
        void resetAndUnreadable() {
            Rule r = rule("id", "a", "kind", "ANOMALY", "column", "adet");
            RuleResult reset = eval(r, 5, new History(List.of(), false, true)).results().get(0);
            assertThat(reset.note()).isEqualTo(DbQueryEvaluator.NOTE_BASELINE_RESET);
            assertThat(reset.evaluated()).isFalse();
            Outcome un = DbQueryEvaluator.evaluate(List.of(r), "ANY", value(5), new DbQueryEvaluator.Baselines(Map.of(),
                    Map.of("a", DbQueryEvaluator.NOTE_PREVIOUS_UNREADABLE)), "tr");
            assertThat(un.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_PREVIOUS_UNREADABLE);
            assertThat(un.results().get(0).measure()).as("ölçü yine yazılır (sonraki çalıştırmanın örneği)").isEqualTo(5.0);
            assertThat(un.inconclusive()).isTrue();
        }

        @Test
        @DisplayName("pencere: yalnız en yeni window örneği; değer yok / kolon yok canlı kurallar gibi")
        void windowAndValueNotes() {
            List<Double> h = new ArrayList<>();
            for (int i = 0; i < 10; i++) h.add(i % 2 == 0 ? 48.0 : 52.0);
            for (int i = 0; i < 20; i++) h.add(10_000.0);   // pencere dışı (daha eski) — hesaba girmez
            Rule r = rule("kind", "ANOMALY", "column", "adet", "window", 10, "min_samples", 5);
            RuleResult rr = eval(r, 50, new History(h, false, false)).results().get(0);
            assertThat(rr.stats().samples()).isEqualTo(10);
            assertThat(rr.stats().mean()).isEqualTo(50.0);
            assertThat(rr.level()).isEqualTo("NONE");

            RuleResult nul = eval(r, null, new History(h, false, false)).results().get(0);
            assertThat(nul.note()).isEqualTo(DbQueryEvaluator.NOTE_NULL_VALUE);
            assertThat(nul.passed()).isTrue();
            DbQueryRunner.QueryResult noCol = new DbQueryRunner.QueryResult(true, 1, 1, false,
                    List.of(new DbQueryRunner.Column("x", "INT")), List.of(Arrays.asList((Object) 1)), false, null, null, null);
            Outcome missing = DbQueryEvaluator.evaluate(List.of(r), "ANY", noCol, DbQueryEvaluator.Baselines.NONE, "tr");
            assertThat(missing.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_COLUMN_MISSING);
            assertThat(missing.inconclusive()).isTrue();
        }

        @Test
        @DisplayName("geçmişin hepsi ≥ 0 ise bandın alt ucu 0'ın altına yazılmaz (karar ham sapmayla)")
        void bandFloorForCounts() {
            Rule r = rule("kind", "ANOMALY", "column", "adet", "min_samples", 5);
            RuleResult rr = eval(r, 30, new History(ds(0, 0, 0, 10, 0), false, false)).results().get(0);
            assertThat(rr.stats().bandLow()).isEqualTo(0.0);
            assertThat(rr.actual()).startsWith("30 · olağan 0–");
            assertThat(rr.level()).isEqualTo("CRITICAL");
        }
    }

    // ── Donmuş değer ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("donmuş değer")
    class Frozen {

        final Rule r = rule("id", "f", "kind", "FROZEN", "column", "adet", "runs_warning", 3, "runs_critical", 5);

        @Test
        @DisplayName("eşit değer serisi: dizi uyarıya / kritiğe ulaşır; farklı değer ya da kesin kırılma diziyi kesinleştirir")
        void truthTable() {
            // önceki 2 aynı → dizi 3 = uyarı (kritik için geçmiş yetmese de uyarı kesin)
            RuleResult w = eval(r, 7, new History(ds(7, 7), false, false)).results().get(0);
            assertThat(w.level()).isEqualTo("WARNING");
            assertThat(w.stats().sameRuns()).isEqualTo(3);
            assertThat(w.actual()).isEqualTo("aynı değer 3 çalıştırmadır · 7");
            // önceki 4 aynı → dizi 5 = kritik
            RuleResult c = eval(r, 7, new History(ds(7, 7, 7, 7), false, false)).results().get(0);
            assertThat(c.level()).isEqualTo("CRITICAL");
            assertThat(c.stats().sameRuns()).isEqualTo(5);
            // farklı değer → dizi kesin 2, uyarı değil
            RuleResult changed = eval(r, 7, new History(ds(7, 8, 8, 8), false, false)).results().get(0);
            assertThat(changed.evaluated()).isTrue();
            assertThat(changed.level()).isEqualTo("NONE");
            assertThat(changed.stats().sameRuns()).isEqualTo(2);
            assertThat(changed.actual()).isEqualTo("aynı değer 2 çalıştırmadır · 7");
            RuleResult fresh = eval(r, 9, new History(ds(7, 7, 7, 7), false, false)).results().get(0);
            assertThat(fresh.actual()).isEqualTo("değer değişti · 9");
            assertThat(fresh.passed()).isTrue();
            // kesin kırılma (değeri boş çalıştırma) → dizi 2 kesin
            RuleResult ended = eval(r, 7, new History(ds(7), true, false)).results().get(0);
            assertThat(ended.evaluated()).isTrue();
            assertThat(ended.level()).isEqualTo("NONE");
            assertThat(eval(r, 7, null).results().get(0).expected())
                    .isEqualTo("değer 3 çalıştırma boyunca aynı kalmamalı (uyarı) · 5 çalıştırma (kritik)");
        }

        @Test
        @DisplayName("az örnek: dizi sürüyor ama geçmiş bitti → taban kuruluyor (alt sınır); sıfırlanmış geçmiş → baseline_reset")
        void building() {
            RuleResult b = eval(r, 7, new History(ds(7), false, false)).results().get(0);
            assertThat(b.evaluated()).isFalse();
            assertThat(b.note()).isEqualTo(DbQueryEvaluator.NOTE_BUILDING_BASELINE);
            assertThat(b.actual()).isEqualTo("aynı değer en az 2 çalıştırmadır · 7");
            assertThat(b.stats().samples()).isEqualTo(1);
            assertThat(b.stats().minSamples()).isEqualTo(2);
            RuleResult none = eval(r, 7, null).results().get(0);
            assertThat(none.actual()).isEqualTo("7 · taban kuruluyor (0 / 2)");
            assertThat(eval(r, 7, new History(List.of(), false, true)).results().get(0).note())
                    .isEqualTo(DbQueryEvaluator.NOTE_BASELINE_RESET);
        }

        @Test
        @DisplayName("sayısal eşitlik: tam sayılar birebir, ondalıkta yuvarlama farkı aynı sayılır; kritik yoksa yalnız uyarı")
        void numericEquality() {
            assertThat(DbQueryEvaluator.sameValue(0.1 + 0.2, 0.3)).isTrue();
            assertThat(DbQueryEvaluator.sameValue(1e12, 1e12 + 1)).isFalse();
            assertThat(DbQueryEvaluator.sameValue(5, 5.0)).isTrue();
            assertThat(DbQueryEvaluator.sameValue(5, 5.0001)).isFalse();
            Rule warnOnly = rule("kind", "FROZEN", "column", "adet", "runs_warning", 3);
            RuleResult rr = eval(warnOnly, 0.1 + 0.2, new History(ds(0.3, 0.3, 0.3, 0.3, 0.3), false, false)).results().get(0);
            assertThat(rr.level()).isEqualTo("WARNING");
            assertThat(rr.stats().sameRuns()).isEqualTo(6);
        }
    }

    // ── Geçmiş planı / seçimi ────────────────────────────────────────────────

    @Nested
    @DisplayName("geçmiş (DbQueryHistory)")
    class HistoryPlan {

        final Instant ref = Instant.parse("2026-11-05T10:00:00Z");

        DbQueryHistory.Row row(long id, Instant at, String hash, String fp, Double measure, String ruleId) {
            Map<String, RuleResult> m = new HashMap<>();
            if (ruleId != null) m.put(ruleId, new RuleResult(ruleId, null, "COLUMN_VALUE", null, null, "NONE", true,
                    measure != null, null, measure, measure, fp));
            return new DbQueryHistory.Row(id, at, hash, m);
        }

        @Test
        @DisplayName("plan: mevsimsiz anomali / donma en yeni N satır; günlük + haftalık hedef günleri tekil; ikisi birlikte ≤ 500")
        void plan() {
            DbQueryHistory.Plan p1 = DbQueryHistory.plan(List.of(rule("kind", "ANOMALY", "column", "a", "window", 40),
                    rule("id", "f", "kind", "FROZEN", "column", "a", "runs_warning", 6, "runs_critical", 60)), ref, 300);
            assertThat(p1.recentLimit()).isEqualTo(59);
            assertThat(p1.ranges()).isEmpty();
            DbQueryHistory.Plan p2 = DbQueryHistory.plan(List.of(
                    rule("kind", "ANOMALY", "column", "a", "season", "daily", "window", 10),
                    rule("id", "w", "kind", "ANOMALY", "column", "a", "season", "weekly", "window", 10)), ref, 300);
            assertThat(p2.recentLimit()).isZero();
            assertThat(p2.ranges()).hasSize(10 + 9);   // 1..10 gün ∪ 7, 14, …, 70 (7 iki kez sayılmaz)
            assertThat(p2.seasonalLimit()).isEqualTo(DbQueryHistory.MAX_ROWS);
            long[] first = p2.ranges().get(0);
            assertThat(first[0]).isEqualTo(ref.toEpochMilli() - 86_400_000L - 150_000 - 1_000);
            assertThat(first[1]).isEqualTo(ref.toEpochMilli() - 86_400_000L + 150_000 + 1_000);
            DbQueryHistory.Plan p3 = DbQueryHistory.plan(List.of(rule("kind", "ANOMALY", "column", "a", "window", 200),
                    rule("id", "d", "kind", "ANOMALY", "column", "a", "season", "daily", "window", 200)), ref, 60);
            assertThat(p3.recentLimit() + p3.seasonalLimit()).isEqualTo(DbQueryHistory.MAX_ROWS);
            assertThat(DbQueryHistory.plan(List.of(rule("kind", "ROW_COUNT", "operator", "GT", "warning", 1)), ref, 60).empty())
                    .isTrue();
            assertThat(DbQueryHistory.tolerance(60)).isEqualTo(30_000);
            assertThat(DbQueryHistory.tolerance(7_200)).isEqualTo(30 * 60_000L);
        }

        @Test
        @DisplayName("yakın geçmiş: sorgu / tanım değişiminde durur (reset), kural eksikse durur, değersiz satır anomalide örnek değil, donmada kırılma")
        void recentWalk() {
            Rule a = rule("id", "r", "kind", "ANOMALY", "column", "adet", "window", 10);
            Rule f = rule("id", "r", "kind", "FROZEN", "column", "adet", "runs_warning", 3, "runs_critical", 8);
            String fp = DbQueryRules.fingerprint(a);
            List<DbQueryHistory.Row> rows = List.of(
                    row(9, ref.minusSeconds(60), "h", fp, 5.0, "r"),
                    row(8, ref.minusSeconds(120), "h", fp, null, "r"),
                    row(7, ref.minusSeconds(180), "h", fp, 6.0, "r"),
                    row(6, ref.minusSeconds(240), "h", "ffffffffffffffff", 7.0, "r"),   // eski tanım → dur
                    row(5, ref.minusSeconds(300), "h", fp, 8.0, "r"));
            DbQueryHistory.Plan plan = DbQueryHistory.plan(List.of(a), ref, 60);
            History ha = DbQueryHistory.histories(List.of(a), rows, plan, "h").get("r");
            assertThat(ha.values()).containsExactly(5.0, 6.0);
            assertThat(ha.reset()).isTrue();
            History hf = DbQueryHistory.histories(List.of(f), rows, plan, "h").get("r");
            assertThat(hf.values()).containsExactly(5.0);
            assertThat(hf.ended()).as("değersiz satır diziyi kesin kırar").isTrue();
            // başka sorgu özeti → dur (reset)
            History other = DbQueryHistory.histories(List.of(a), List.of(row(9, ref, "h2", fp, 1.0, "r")), plan, "h").get("r");
            assertThat(other.values()).isEmpty();
            assertThat(other.reset()).isTrue();
            // kural eklenmeden önceki satır → dur, sıfırlama değil (yeni kural)
            History added = DbQueryHistory.histories(List.of(a), List.of(row(9, ref, "h", fp, 1.0, "r"),
                    row(8, ref.minusSeconds(60), "h", null, null, null)), plan, "h").get("r");
            assertThat(added.values()).containsExactly(1.0);
            assertThat(added.reset()).isFalse();
            // pencere sınırı
            List<DbQueryHistory.Row> many = new ArrayList<>();
            for (int i = 0; i < 30; i++) many.add(row(100 - i, ref.minusSeconds(60L * (i + 1)), "h", fp, (double) i, "r"));
            assertThat(DbQueryHistory.histories(List.of(a), many, plan, "h").get("r").values()).hasSize(10);
            assertThat(DbQueryHistory.histories(List.of(f), many, plan, "h").get("r").values()).as("donma: runsMax − 1")
                    .hasSize(7);
        }

        @Test
        @DisplayName("mevsim penceresi: önceki günlerin aynı saatine ± tolerans, en yakın satır; dışı / boş gün atlanır; haftalık 7 gün adım")
        void seasonalWindow() {
            Rule d = rule("id", "r", "kind", "ANOMALY", "column", "adet", "season", "daily", "window", 10, "min_samples", 5);
            String fp = DbQueryRules.fingerprint(d);
            long day = 86_400L;
            List<DbQueryHistory.Row> rows = new ArrayList<>(List.of(
                    row(50, ref.minusSeconds(day - 100), "h", fp, 11.0, "r"),        // 1. gün, 100 sn → içeride
                    row(49, ref.minusSeconds(day + 20), "h", fp, 12.0, "r"),         // 1. gün, 20 sn → EN YAKIN
                    row(48, ref.minusSeconds(2 * day + 151), "h", fp, 20.0, "r"),    // 2. gün, 151 sn → tolerans dışı
                    row(47, ref.minusSeconds(3 * day), "h", fp, 30.0, "r"),          // 3. gün tam
                    row(46, ref.minusSeconds(3 * day + 3600), "h", fp, 99.0, "r"),   // aynı gün başka saat → yok sayılır
                    row(45, ref.minusSeconds(5 * day - 150), "h", fp, 50.0, "r")));  // 5. gün, sınırda (150 sn) → içeride
            DbQueryHistory.Plan plan = DbQueryHistory.plan(List.of(d), ref, 300);   // tolerans 150 sn
            History h = DbQueryHistory.histories(List.of(d), rows, plan, "h").get("r");
            assertThat(h.values()).containsExactly(12.0, 30.0, 50.0);

            Rule w = rule("id", "r", "kind", "ANOMALY", "column", "adet", "season", "weekly", "window", 10);
            List<DbQueryHistory.Row> wrows = List.of(
                    row(60, ref.minusSeconds(day), "h", fp, 1.0, "r"),               // dün — haftalıkta yok
                    row(59, ref.minusSeconds(7 * day), "h", fp, 7.0, "r"),
                    row(58, ref.minusSeconds(14 * day + 60), "h", fp, 14.0, "r"),
                    row(57, ref.minusSeconds(21 * day), "h", "ffffffffffffffff", 21.0, "r"),   // eski tanım → dur
                    row(56, ref.minusSeconds(28 * day), "h", fp, 28.0, "r"));
            History hw = DbQueryHistory.histories(List.of(w), wrows, DbQueryHistory.plan(List.of(w), ref, 300), "h").get("r");
            assertThat(hw.values()).containsExactly(7.0, 14.0);
            assertThat(hw.reset()).isTrue();
        }
    }

    // ── Alarm metni ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("alarm metni: tür etiketleri, taban kuruluyor durumu, ihlal özeti bant ve sapmayı taşır, API biçimi taban özetini yazar")
    void alarmText() {
        assertThat(DbQueryAlarmText.kindLabel("ANOMALY")).isEqualTo("Olağan dışı değer");
        assertThat(DbQueryAlarmText.kindLabel("FROZEN")).isEqualTo("Donmuş değer");
        assertThat(DbQueryAlarmText.noteLabel(DbQueryEvaluator.NOTE_BUILDING_BASELINE)).startsWith("taban kuruluyor");
        Rule r = rule("id", "a", "label", "Bekleyen sipariş", "kind", "ANOMALY", "column", "adet", "min_samples", 5);
        Outcome building = eval(r, 7, new History(ds(1, 2), false, false));
        List<Map<String, Object>> b = DbQueryAlarmText.toApi(building.results());
        assertThat(b.get(0)).containsEntry("samples", 2).containsEntry("min_samples", 5).doesNotContainKey("band_low");
        assertThat(DbQueryAlarmText.ruleStateText(b.get(0))).isEqualTo("Taban kuruluyor (2 / 5)");
        Outcome breach = eval(r, 58, new History(ds(48, 48, 50, 52, 52), false, false));
        List<Map<String, Object>> api = DbQueryAlarmText.toApi(breach.results());
        assertThat(api.get(0)).containsEntry("baseline_mean", 50.0).containsEntry("baseline_sigma", 2.0)
                .containsEntry("band_low", 45.0).containsEntry("band_high", 55.0).containsEntry("deviation", 4.0)
                .containsEntry("samples", 5);
        assertThat(DbQueryAlarmText.ruleStateText(api.get(0))).isEqualTo("Kritik eşik ihlali");
        assertThat(DbQueryAlarmText.breachSummary(api)).isEqualTo("Bekleyen sipariş: gelen 58 · olağan 45–55 · sapma +4σ · "
                + "beklenen olağan aralıkta (±2,5σ uyarı · ±3,5σ kritik) · taban: son 30 çalıştırma (kritik eşik)");
        // rule_results_json gidiş-dönüş: taban özeti korunur
        List<RuleResult> back = DbQueryEvaluator.fromJson(DbQueryAlarmText.toJson(breach.results()));
        assertThat(back.get(0).stats()).isEqualTo(breach.results().get(0).stats());
        assertThat(DbQueryEvaluator.fromJson(DbQueryEvaluator.toJson(breach.results())).get(0).stats())
                .isEqualTo(breach.results().get(0).stats());
        Rule f = rule("id", "f", "kind", "FROZEN", "column", "adet", "runs_warning", 3);
        Map<String, Object> fApi = DbQueryAlarmText.toApi(eval(f, 4, new History(ds(4, 4), false, false)).results()).get(0);
        assertThat(fApi).containsEntry("same_runs", 3).containsEntry("samples", 2).containsEntry("min_samples", 2)
                .doesNotContainKey("baseline_mean");
    }

    // ── Eşik denemesi (kayan geçmiş) ─────────────────────────────────────────

    @Nested
    @DisplayName("eşik denemesi")
    class Backtest {

        final Instant t0 = Instant.parse("2026-10-01T00:00:00Z");

        DbQueryBacktest.Row stored(long id, Instant at, Rule asStored, double v) {
            RuleResult rr = new RuleResult(asStored.id(), null, asStored.kind(), null, null, "NONE", true, true, null, v, v,
                    DbQueryRules.fingerprint(asStored));
            return new DbQueryBacktest.Row(id, at, true, "h", List.of(rr));
        }

        @Test
        @DisplayName("donmuş değer: 5,5,5,6,6,6,6 (dizi 3) → 2 alarm, 3 uyarı; pencere öncesi satırlar yalnız taban")
        void frozenReplay() {
            Rule f = rule("id", "f", "kind", "FROZEN", "column", "adet", "runs_warning", 3);
            DbQueryBacktest bt = new DbQueryBacktest(List.of(f), "ANY", "h", t0, 300);
            double[] vals = {5, 5, 5, 6, 6, 6, 6};
            for (int i = 0; i < vals.length; i++) bt.accept(stored(i + 1, t0.plusSeconds(300L * i), f, vals[i]));
            Map<String, Object> rep = bt.report(false);
            assertThat(rep.get("would_open_alarms")).isEqualTo(2);
            assertThat(((Map<?, ?>) rep.get("would_breach")).get("warning")).isEqualTo(3);
            // "taban kuruluyor" ihlal değil → çalıştırma kararlı (ihlalsiz) sayılır; kural sayacı yalnız değerlendirileni sayar
            assertThat(rep.get("evaluated")).isEqualTo(7);
            Map<?, ?> pr = (Map<?, ?>) ((List<?>) rep.get("per_rule")).get(0);
            assertThat(pr.get("evaluated")).as("ilk iki satır taban kuruluyor").isEqualTo(5);
        }

        @Test
        @DisplayName("kolon değeri kuralının saklı ölçüleri anomaliyle hemen denenir; tanım değişince taban sıfırlanır")
        void columnValueHistoryServesAnomaly() {
            Rule stored = rule("id", "r", "kind", "COLUMN_VALUE", "column", "adet", "operator", "GT", "warning", 1_000);
            Rule an = rule("id", "r", "kind", "ANOMALY", "column", "adet", "window", 10, "min_samples", 5);
            Rule otherCol = rule("id", "r", "kind", "COLUMN_VALUE", "column", "tutar", "operator", "GT", "warning", 1);
            DbQueryBacktest bt = new DbQueryBacktest(List.of(an), "ANY", "h", t0, 300);
            long id = 1;
            for (int i = 0; i < 8; i++) bt.accept(stored(id++, t0.plusSeconds(300L * id), stored, i % 2 == 0 ? 48 : 52));
            bt.accept(stored(id++, t0.plusSeconds(300L * id), stored, 80));   // ihlal
            bt.accept(stored(id++, t0.plusSeconds(300L * id), otherCol, 50));  // başka ölçü → sıfırla
            bt.accept(stored(id++, t0.plusSeconds(300L * id), stored, 500));   // taban yeniden kuruluyor → ihlal değil
            Map<String, Object> rep = bt.report(false);
            assertThat(((Map<?, ?>) rep.get("would_breach")).get("critical")).isEqualTo(1);
            assertThat(rep.get("would_open_alarms")).isEqualTo(1);
            Map<?, ?> pr = (Map<?, ?>) ((List<?>) rep.get("per_rule")).get(0);
            assertThat(pr.get("evaluated")).isEqualTo(4);   // 6., 7., 8. ve 9. satır (10. başka ölçü, 11. taban kuruluyor)
            assertThat(rep.get("evaluated")).as("başka ölçülü satır karar veremez").isEqualTo(10);
        }

        @Test
        @DisplayName("geriye okuma: akıllı kurallar aralığa göre; mevsimsel gün / hafta; en çok 60 gün")
        void lookback() {
            assertThat(DbQueryBacktest.lookbackMs(List.of(rule("kind", "ANOMALY", "column", "a", "window", 30)), 300))
                    .isEqualTo(30 * 300_000L * 3 / 2 + 3_600_000L);
            assertThat(DbQueryBacktest.lookbackMs(List.of(rule("kind", "ANOMALY", "column", "a", "season", "daily",
                    "window", 30)), 300)).isEqualTo(30 * 86_400_000L + 31 * 60_000L);
            assertThat(DbQueryBacktest.lookbackMs(List.of(rule("kind", "ANOMALY", "column", "a", "season", "weekly",
                    "window", 30)), 300)).isEqualTo(DbQueryBacktest.MAX_SMART_LOOKBACK_MS);
            assertThat(DbQueryBacktest.lookbackMs(List.of(rule("kind", "FROZEN", "column", "a", "runs_warning", 10)), 60))
                    .isEqualTo(10 * 60_000L * 3 / 2 + 3_600_000L);
            assertThat(DbQueryBacktest.lookbackMs(List.of(rule("kind", "ROW_COUNT", "operator", "GT", "warning", 1)), 60)).isZero();
        }

        /**
         * Kayan geçmişle yeniden oynatma, CANLI kuralla ({@link DbQueryHistory#histories} + değerlendirici) satır satır aynı
         * kararı verir: rastgele seri (taban + ara sıra sıçrama + değersiz satırlar), mevsimsiz / günlük / donmuş.
         */
        @ParameterizedTest(name = "{0}")
        @CsvSource({"none", "daily", "frozen"})
        void replayMatchesLiveRule(String mode) {
            Rule r = switch (mode) {
                case "daily" -> rule("id", "r", "kind", "ANOMALY", "column", "adet", "season", "daily", "window", 10,
                        "min_samples", 5);
                case "frozen" -> rule("id", "r", "kind", "FROZEN", "column", "adet", "runs_warning", 3, "runs_critical", 5);
                default -> rule("id", "r", "kind", "ANOMALY", "column", "adet", "window", 12, "min_samples", 5);
            };
            String fp = DbQueryRules.fingerprint(r);
            Random rnd = new Random(42);
            long step = "daily".equals(mode) ? 6 * 3_600L : 300L;   // günlükte 6 saatte bir (her gün aynı 4 saat)
            int n = "daily".equals(mode) ? 60 : 120;
            List<DbQueryHistory.Row> rows = new ArrayList<>();
            DbQueryBacktest bt = new DbQueryBacktest(List.of(r), "ANY", "h", t0, (int) step);
            int liveWarn = 0, liveCrit = 0, liveEval = 0;
            for (int i = 0; i < n; i++) {
                Instant at = t0.plusSeconds(step * i);
                Double v;
                if ("frozen".equals(mode)) v = rnd.nextInt(10) < 6 ? 7.0 : (double) rnd.nextInt(3);
                else v = rnd.nextInt(15) == 0 ? 400.0 : 100 + rnd.nextInt(9);
                if (rnd.nextInt(12) == 0) v = null;   // değersiz çalıştırma (NULL)
                // canlı kural: o ana kadarki satırlar (yeniden eskiye)
                List<DbQueryHistory.Row> desc = new ArrayList<>(rows);
                java.util.Collections.reverse(desc);
                DbQueryHistory.Plan plan = DbQueryHistory.plan(List.of(r), at, (int) step);
                History h = DbQueryHistory.histories(List.of(r), desc, plan, "h").get("r");
                if (v != null) {
                    RuleResult live = "frozen".equals(mode)
                            ? DbQueryEvaluator.frozenAgainst(r, v, h, null, null, "", false)
                            : DbQueryEvaluator.anomalyAgainst(r, v, h, null, null, "", false);
                    if (live.evaluated()) {
                        liveEval++;
                        if ("WARNING".equals(live.level())) liveWarn++;
                        if ("CRITICAL".equals(live.level())) liveCrit++;
                    }
                }
                RuleResult stored = new RuleResult("r", null, r.kind(), null, null, "NONE", true, v != null,
                        v == null ? DbQueryEvaluator.NOTE_NULL_VALUE : null, v, v, fp);
                rows.add(new DbQueryHistory.Row(i + 1, at, "h", Map.of("r", stored)));
                bt.accept(new DbQueryBacktest.Row(i + 1, at, true, "h", List.of(stored)));
            }
            Map<String, Object> rep = bt.report(false);
            Map<?, ?> pr = (Map<?, ?>) ((List<?>) rep.get("per_rule")).get(0);
            assertThat(liveEval).as("seri kararlar üretmeli").isPositive();
            assertThat(liveWarn + liveCrit).as("seri ihlal içermeli").isPositive();
            assertThat(pr.get("evaluated")).isEqualTo(liveEval);
            assertThat(pr.get("warning")).isEqualTo(liveWarn);
            assertThat(pr.get("critical")).isEqualTo(liveCrit);
        }
    }
}
