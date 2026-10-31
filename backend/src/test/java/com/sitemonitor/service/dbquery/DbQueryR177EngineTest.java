package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.dbquery.DbQueryEvaluator.Baselines;
import com.sitemonitor.service.dbquery.DbQueryEvaluator.Outcome;
import com.sitemonitor.service.dbquery.DbQueryEvaluator.RuleResult;
import com.sitemonitor.service.dbquery.DbQueryRules.FieldError;
import com.sitemonitor.service.dbquery.DbQueryRules.Parsed;
import com.sitemonitor.service.dbquery.DbQueryRules.Rule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r177 kural motoru: tazelik kuralı (ayrıştırma, yaş, notlar, zaman damgası okuma), değişim tabanı seçeneği + parmak izi,
 * değerlendirilemeyen sonuçların sınıfı (kanıt eksikliği / bekleme / yapılandırma), geriye dönük eşik denemesi.
 */
class DbQueryR177EngineTest {

    private static Map<String, Object> rule(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Rule one(Object... kv) {
        Parsed p = DbQueryRules.fromApi(List.of(rule(kv)));
        assertThat(p.errors()).as("hatalar").isEmpty();
        return p.rules().get(0);
    }

    private static List<String> errorFields(Object... kv) {
        return DbQueryRules.fromApi(List.of(rule(kv))).errors().stream().map(FieldError::field).toList();
    }

    private static DbQueryRunner.QueryResult result(List<String> cols, DbQueryRunner.DbClock clock, Object[]... rows) {
        List<List<Object>> r = new ArrayList<>();
        for (Object[] row : rows) r.add(Arrays.asList(row));
        return new DbQueryRunner.QueryResult(true, 5, r.size(), false,
                cols.stream().map(c -> new DbQueryRunner.Column(c, "VARCHAR")).toList(), r, false, null, null, "PG",
                clock, DbAccountPrivileges.Result.NOT_CHECKED);
    }

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final DbQueryRunner.DbClock CLOCK = new DbQueryRunner.DbClock(NOW, ZoneOffset.ofHours(3));

    // ── Ayrıştırma ───────────────────────────────────────────────────────────

    @Test
    void freshnessParsing() {
        Rule r = one("kind", "freshness", "column", "updated_at", "operator", "GT", "warning", 600, "critical", 3600);
        assertThat(r.kind()).isEqualTo(DbQueryRules.KIND_FRESHNESS);
        assertThat(r.aggregate()).isEqualTo("MAX");
        assertThat(one("kind", "FRESHNESS", "column", "t", "aggregate", "min", "operator", "GTE", "warning", 1).aggregate())
                .isEqualTo("MIN");
        assertThat(errorFields("kind", "FRESHNESS", "operator", "GT", "warning", 1)).containsExactly("rules[0].column");
        assertThat(errorFields("kind", "FRESHNESS", "column", "t", "operator", "LT", "warning", 1)).contains("rules[0].operator");
        assertThat(errorFields("kind", "FRESHNESS", "column", "t", "aggregate", "SUM", "operator", "GT", "warning", 1))
                .contains("rules[0].aggregate");
        // gidiş-dönüş
        Rule back = DbQueryRules.fromJson(DbQueryRules.toJson(List.of(r))).get(0);
        assertThat(back).isEqualTo(r);
    }

    @Test
    void changeBaselineParsing() {
        Rule y = one("kind", "CHANGE", "operator", "GT", "warning", 20, "change",
                Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "ANY", "baseline", "SAME_TIME_YESTERDAY"));
        assertThat(y.change().baseline()).isEqualTo(DbQueryRules.BASELINE_YESTERDAY);
        assertThat(DbQueryRules.toApi(List.of(y)).get(0).get("change").toString()).contains("same_time_yesterday");
        Rule p = one("kind", "CHANGE", "operator", "GT", "warning", 20, "change",
                Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "ANY"));
        assertThat(p.change().baselineOrPrevious()).isEqualTo(DbQueryRules.BASELINE_PREVIOUS);
        // varsayılan (önceki) API'de yazılmaz — eski istemcilerle aynı biçim
        assertThat(DbQueryRules.toApi(List.of(p)).get(0).get("change").toString()).doesNotContain("baseline");
        assertThat(errorFields("kind", "CHANGE", "operator", "GT", "warning", 20, "change",
                Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "ANY", "baseline", "last_month")))
                .containsExactly("rules[0].change.baseline");
        // eski saklı JSON (taban alanı yok) okunur
        assertThat(DbQueryRules.fromJson("[{\"id\":\"c\",\"kind\":\"CHANGE\",\"operator\":\"GT\",\"warning\":1,"
                + "\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"PERCENT\",\"direction\":\"ANY\"}}]").get(0).change()
                .baselineOrPrevious()).isEqualTo("previous");
    }

    @Test
    void fingerprintFollowsTheMeasureDefinitionOnly() {
        Rule a = one("id", "a", "kind", "COLUMN_VALUE", "column", "Tutar", "aggregate", "SUM", "operator", "GT", "warning", 5);
        Rule thr = one("id", "a", "kind", "COLUMN_VALUE", "column", "tutar", "aggregate", "SUM", "operator", "LT",
                "warning", 900, "critical", 1);
        Rule col = one("id", "a", "kind", "COLUMN_VALUE", "column", "adet", "aggregate", "SUM", "operator", "GT", "warning", 5);
        Rule agg = one("id", "a", "kind", "COLUMN_VALUE", "column", "tutar", "aggregate", "MAX", "operator", "GT", "warning", 5);
        assertThat(DbQueryRules.fingerprint(a)).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(DbQueryRules.fingerprint(thr)).isEqualTo(DbQueryRules.fingerprint(a));   // eşik / operatör girmez
        assertThat(DbQueryRules.fingerprint(col)).isNotEqualTo(DbQueryRules.fingerprint(a));
        assertThat(DbQueryRules.fingerprint(agg)).isNotEqualTo(DbQueryRules.fingerprint(a));
        Rule chRows = one("kind", "CHANGE", "operator", "GT", "warning", 5,
                "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "ANY"));
        Rule chRows2 = one("kind", "CHANGE", "operator", "GT", "warning", 50,
                "change", Map.of("of", "ROW_COUNT", "mode", "ABSOLUTE", "direction", "INCREASE", "baseline", "same_time_last_week"));
        Rule chCol = one("kind", "CHANGE", "column", "x", "operator", "GT", "warning", 5,
                "change", Map.of("of", "COLUMN", "mode", "PERCENT", "direction", "ANY"));
        assertThat(DbQueryRules.fingerprint(chRows2)).isEqualTo(DbQueryRules.fingerprint(chRows));   // kip / yön / taban girmez
        assertThat(DbQueryRules.fingerprint(chCol)).isNotEqualTo(DbQueryRules.fingerprint(chRows));
        Rule t1 = one("kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "warning", "HATA");
        Rule t2 = one("kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "warning", "ERROR");
        assertThat(DbQueryRules.fingerprint(t1)).isNotEqualTo(DbQueryRules.fingerprint(t2));
        assertThat(DbQueryRules.fingerprint(null)).isNull();
    }

    // ── Tazelik ──────────────────────────────────────────────────────────────

    @Test
    void freshnessAgeAgainstDatabaseClock() {
        Rule r = one("id", "f", "kind", "FRESHNESS", "column", "son", "operator", "GT", "warning", 600, "critical", 3600);
        // dilimsiz değer veritabanı diliminde (+03): 14:50 yerel = 11:50Z → 10 dk
        Outcome warn = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("son"), CLOCK,
                new Object[] {"2026-10-31T14:40:00"}, new Object[] {"2026-10-31 14:50:00"}), Baselines.NONE, "tr");
        RuleResult rr = warn.results().get(0);
        assertThat(rr.evaluated()).isTrue();
        assertThat(rr.measure()).isEqualTo(600.0);
        assertThat(rr.passed()).isTrue();   // GT 600: tam 600 sn ihlal değil
        assertThat(rr.actual()).isEqualTo("yaş 10 dk");
        assertThat(rr.fingerprint()).isEqualTo(DbQueryRules.fingerprint(r));
        Outcome crit = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("son"), CLOCK,
                new Object[] {"2026-10-31T10:00:00Z"}), Baselines.NONE, "en");
        assertThat(crit.status()).isEqualTo("BREACH");
        assertThat(crit.level()).isEqualTo("CRITICAL");
        assertThat(crit.results().get(0).actual()).isEqualTo("age 2 h");
        assertThat(crit.metricValue()).isEqualTo(7200.0);
        assertThat(crit.results().get(0).expected()).isEqualTo("age ≤ 10 min (warning) · age ≤ 1 h (critical)");
        // MIN = en eski
        Rule oldest = one("id", "o", "kind", "FRESHNESS", "column", "son", "aggregate", "MIN", "operator", "GT", "warning", 600);
        Outcome o = DbQueryEvaluator.evaluate(List.of(oldest), "ANY", result(List.of("son"), CLOCK,
                new Object[] {"2026-10-31T11:59:00Z"}, new Object[] {"2026-10-31T11:00:00+00:00"}), Baselines.NONE, "tr");
        assertThat(o.results().get(0).measure()).isEqualTo(3600.0);
        // gelecekteki değer: ihlal yok, okunur metin
        Outcome f = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("son"), CLOCK,
                new Object[] {"2026-10-31T12:05:00Z"}), Baselines.NONE, "tr");
        assertThat(f.status()).isEqualTo("OK");
        assertThat(f.results().get(0).actual()).startsWith("gelecekte");
    }

    @Test
    void freshnessUnevaluableNotes() {
        Rule r = one("id", "f", "kind", "FRESHNESS", "column", "son", "operator", "GT", "warning", 60);
        RuleResult noClock = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("son"), null,
                new Object[] {"2026-10-31T11:00:00Z"}), Baselines.NONE, "tr").results().get(0);
        assertThat(noClock.note()).isEqualTo(DbQueryEvaluator.NOTE_DB_CLOCK_UNAVAILABLE);
        RuleResult notTs = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("son"), CLOCK,
                new Object[] {12345}), Baselines.NONE, "tr").results().get(0);
        assertThat(notTs.note()).isEqualTo(DbQueryEvaluator.NOTE_NOT_TIMESTAMP);
        Outcome empty = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("son"), CLOCK), Baselines.NONE, "tr");
        assertThat(empty.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_NO_ROWS);
        // boş tablo "taze" değildir: kanıt eksikliği ve "taban kuruluyor" değil → ardışık değerlendirilemeyen alarmını besler
        assertThat(empty.inconclusive()).isTrue();
        assertThat(empty.configUnevaluable()).isTrue();
        RuleResult missing = DbQueryEvaluator.evaluate(List.of(r), "ANY", result(List.of("baska"), CLOCK,
                new Object[] {"x"}), Baselines.NONE, "tr").results().get(0);
        assertThat(missing.note()).isEqualTo(DbQueryEvaluator.NOTE_COLUMN_MISSING);
        // satır sayısı kuralında "satır yok" kanıt eksikliği DEĞİL (bugünkü davranış)
        assertThat(DbQueryEvaluator.isInconclusive(DbQueryRules.KIND_COLUMN_VALUE, DbQueryEvaluator.NOTE_NO_ROWS)).isFalse();
        assertThat(DbQueryEvaluator.isInconclusive(DbQueryRules.KIND_FRESHNESS, DbQueryEvaluator.NOTE_NULL_VALUE)).isTrue();
    }

    @Test
    void timestampReading() {
        ZoneOffset plus3 = ZoneOffset.ofHours(3);
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31T10:15:30", plus3)).isEqualTo(Instant.parse("2026-10-31T07:15:30Z"));
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31 10:15:30.1234567", ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-31T10:15:30.1234567Z"));
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31T10:15:30Z", plus3)).isEqualTo(Instant.parse("2026-10-31T10:15:30Z"));
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31 10:15:30 +03:00", ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-31T07:15:30Z"));
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31T10:15:30+0300", ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-31T07:15:30Z"));
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31T10:15:30-05", ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-31T15:15:30Z"));
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31", plus3)).isEqualTo(Instant.parse("2026-10-30T21:00:00Z"));
        // tahmin yok: sayı, mantıksal, bozuk metin, aşırı uzun
        assertThat(DbQueryEvaluator.timestampOf(1730000000L, plus3)).isNull();
        assertThat(DbQueryEvaluator.timestampOf(Boolean.TRUE, plus3)).isNull();
        assertThat(DbQueryEvaluator.timestampOf("dün öğlen", plus3)).isNull();
        assertThat(DbQueryEvaluator.timestampOf("2026-13-45T99:00:00", plus3)).isNull();
        assertThat(DbQueryEvaluator.timestampOf("2026-10-31T10:15:30" + "0".repeat(100), plus3)).isNull();
    }

    @Test
    void durationText() {
        assertThat(DbQueryEvaluator.duration(45, false)).isEqualTo("45 sn");
        assertThat(DbQueryEvaluator.duration(723, false)).isEqualTo("12 dk 3 sn");
        assertThat(DbQueryEvaluator.duration(3 * 3600 + 300, true)).isEqualTo("3 h 5 min");
        assertThat(DbQueryEvaluator.duration(2 * 86400 + 4 * 3600, false)).isEqualTo("2 g 4 sa");
        assertThat(DbQueryEvaluator.duration(3600, false)).isEqualTo("1 sa");
        assertThat(DbQueryEvaluator.duration(Double.NaN, false)).isEqualTo("—");
    }

    // ── Değişim tabanı ve notlar ─────────────────────────────────────────────

    @Test
    void baselineNotesAreWaitingNotConfig() {
        Rule c = one("id", "c", "kind", "CHANGE", "operator", "GT", "warning", 10,
                "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "INCREASE"));
        DbQueryRunner.QueryResult three = result(List.of("a"), null, new Object[] {1}, new Object[] {2}, new Object[] {3});
        Outcome reset = DbQueryEvaluator.evaluate(List.of(c), "ANY", three,
                new Baselines(Map.of(), Map.of("c", DbQueryEvaluator.NOTE_BASELINE_RESET)), "tr");
        RuleResult rr = reset.results().get(0);
        assertThat(rr.evaluated()).isFalse();
        assertThat(rr.note()).isEqualTo(DbQueryEvaluator.NOTE_BASELINE_RESET);
        assertThat(rr.measure()).isEqualTo(3.0);   // bu çalıştırma yeni taban
        assertThat(reset.status()).isEqualTo("OK");
        assertThat(reset.inconclusive()).isTrue();
        assertThat(reset.configUnevaluable()).isFalse();   // taban kuruluyor — alarm beslemez
        assertThat(DbQueryEvaluator.isConfigUnevaluable(DbQueryRules.KIND_CHANGE, DbQueryEvaluator.NOTE_NO_SEASONAL_BASELINE)).isFalse();
        assertThat(DbQueryEvaluator.isConfigUnevaluable(DbQueryRules.KIND_CHANGE, DbQueryEvaluator.NOTE_FIRST_RUN)).isFalse();
        assertThat(DbQueryEvaluator.isConfigUnevaluable(DbQueryRules.KIND_COLUMN_VALUE, DbQueryEvaluator.NOTE_COLUMN_MISSING)).isTrue();
        assertThat(DbQueryEvaluator.isConfigUnevaluable(DbQueryRules.KIND_CHANGE, DbQueryEvaluator.NOTE_PREVIOUS_UNREADABLE)).isTrue();
        Outcome withBase = DbQueryEvaluator.evaluate(List.of(c), "ANY", three, new Baselines(Map.of("c", 2.0), Map.of()), "tr");
        assertThat(withBase.status()).isEqualTo("BREACH");
        assertThat(withBase.results().get(0).compared()).isEqualTo(50.0);
        // kayıtta ne taban ne not → ilk çalıştırma
        assertThat(DbQueryEvaluator.evaluate(List.of(c), "ANY", three, Baselines.NONE, "tr").results().get(0).note())
                .isEqualTo(DbQueryEvaluator.NOTE_FIRST_RUN);
    }

    @Test
    void sameMeasureDefinitionAndLegacyRows() {
        Rule cur = one("id", "a", "kind", "COLUMN_VALUE", "column", "tutar", "aggregate", "SUM", "operator", "GT", "warning", 5);
        RuleResult stored = new RuleResult("a", null, "COLUMN_VALUE", "5", "", "NONE", true, true, null, 5.0, 5.0,
                DbQueryRules.fingerprint(cur));
        assertThat(DbQueryEvaluator.sameMeasureDefinition(stored, cur)).isTrue();
        Rule other = one("id", "a", "kind", "COLUMN_VALUE", "column", "adet", "aggregate", "SUM", "operator", "GT", "warning", 5);
        assertThat(DbQueryEvaluator.sameMeasureDefinition(stored, other)).isFalse();
        // parmak izi olmayan eski satır: tür aynıysa uyumlu (yayından sonra bir gün UNKNOWN olmasın)
        RuleResult legacy = new RuleResult("a", null, "COLUMN_VALUE", "5", "", "NONE", true, true, null, 5.0, 5.0);
        assertThat(DbQueryEvaluator.sameMeasureDefinition(legacy, other)).isTrue();
        RuleResult legacyOtherKind = new RuleResult("a", null, "ROW_COUNT", "5", "", "NONE", true, true, null, 5.0, 5.0);
        assertThat(DbQueryEvaluator.sameMeasureDefinition(legacyOtherKind, other)).isFalse();
        // JSON gidiş-dönüş parmak izini korur
        RuleResult back = DbQueryEvaluator.fromJson(DbQueryEvaluator.toJson(List.of(stored))).get(0);
        assertThat(back.fingerprint()).isEqualTo(stored.fingerprint());
        assertThat(DbQueryEvaluator.fromJson("[{\"id\":\"a\",\"measure\":2}]").get(0).fingerprint()).isNull();
    }

    // ── Geriye dönük deneme ──────────────────────────────────────────────────

    private static RuleResult measured(Rule r, double measure) {
        return new RuleResult(r.id(), null, r.kind(), "", "", "NONE", true, true, null, measure, measure,
                DbQueryRules.fingerprint(r));
    }

    private static DbQueryBacktest.Row row(long id, Instant at, String hash, RuleResult... rs) {
        return new DbQueryBacktest.Row(id, at, true, hash, List.of(rs));
    }

    @Test
    void backtestCountsBreachesAndAlarmEpisodes() {
        Rule stored = one("id", "r", "kind", "ROW_COUNT", "operator", "GT", "warning", 1000);
        Rule tryIt = one("id", "r", "kind", "ROW_COUNT", "operator", "GT", "warning", 10, "critical", 50);
        Instant from = NOW.minusSeconds(3600);
        DbQueryBacktest bt = new DbQueryBacktest(List.of(tryIt), "ANY", "h", from, 300);
        double[] values = {5, 20, 60, 5, 30, 4};
        for (int i = 0; i < values.length; i++)
            bt.accept(row(i + 1, from.plusSeconds(300L * i), "h", measured(stored, values[i])));
        bt.accept(new DbQueryBacktest.Row(99, from.plusSeconds(2000), false, "h", List.of()));   // sorgu çalışmadı
        bt.accept(row(100, from.plusSeconds(2100), "baska", measured(stored, 500)));            // başka sorgu
        Map<String, Object> rep = bt.report(false);
        assertThat(rep.get("runs")).isEqualTo(8);
        assertThat(rep.get("evaluated")).isEqualTo(6);
        assertThat(rep.get("would_breach")).isEqualTo(Map.of("warning", 2, "critical", 1));
        assertThat(rep.get("would_open_alarms")).isEqualTo(2);   // 20,60 tek alarm; 30 ikinci alarm
        assertThat(rep.get("failed_runs")).isEqualTo(1);
        assertThat(rep.get("skipped_other_query")).isEqualTo(1);
        assertThat(rep.get("first_at")).isEqualTo("2026-10-31T11:05:00");
        assertThat(rep.get("last_at")).isEqualTo("2026-10-31T11:20:00");
        assertThat(rep.get("truncated")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> pr = ((List<Map<String, Object>>) rep.get("per_rule")).get(0);
        assertThat(pr).containsEntry("id", "r").containsEntry("evaluated", 6).containsEntry("warning", 2)
                .containsEntry("critical", 1).containsEntry("skipped_reason", null);
    }

    @Test
    void backtestSkipsChangedDefinitionsAndMissingRules() {
        Rule stored = one("id", "a", "kind", "COLUMN_VALUE", "column", "tutar", "aggregate", "SUM", "operator", "GT", "warning", 5);
        Rule otherCol = one("id", "a", "kind", "COLUMN_VALUE", "column", "adet", "aggregate", "SUM", "operator", "GT", "warning", 5);
        Rule absent = one("id", "zz", "kind", "ROW_COUNT", "operator", "GT", "warning", 5);
        DbQueryBacktest bt = new DbQueryBacktest(List.of(otherCol, absent), "ANY", "h", NOW.minusSeconds(600), 300);
        bt.accept(row(1, NOW.minusSeconds(300), "h", measured(stored, 100)));
        Map<String, Object> rep = bt.report(true);
        assertThat(rep.get("evaluated")).isEqualTo(0);
        assertThat(rep.get("would_open_alarms")).isEqualTo(0);
        assertThat(rep.get("truncated")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pr = (List<Map<String, Object>>) rep.get("per_rule");
        assertThat(pr.get(0).get("skipped_reason")).isEqualTo(DbQueryBacktest.SKIP_DEFINITION_CHANGED);
        assertThat(pr.get(1).get("skipped_reason")).isEqualTo(DbQueryBacktest.SKIP_NOT_FOUND);
    }

    @Test
    void backtestChangeAgainstPreviousAndSeasonalBaseline() {
        Rule prev = one("id", "c", "kind", "CHANGE", "operator", "GT", "warning", 50,
                "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "INCREASE"));
        Instant from = NOW.minusSeconds(3600);
        DbQueryBacktest bt = new DbQueryBacktest(List.of(prev), "ANY", "h", from, 300);
        // pencere öncesi satır yalnız taban: 10 → 20 (%100) ihlal, 20 → 21 değil
        bt.accept(row(1, from.minusSeconds(300), "h", measured(prev, 10)));
        bt.accept(row(2, from, "h", measured(prev, 20)));
        bt.accept(row(3, from.plusSeconds(300), "h", measured(prev, 21)));
        Map<String, Object> rep = bt.report(false);
        assertThat(rep.get("runs")).isEqualTo(2);
        assertThat(rep.get("evaluated")).isEqualTo(2);
        assertThat(rep.get("would_breach")).isEqualTo(Map.of("warning", 1, "critical", 0));

        Rule weekly = one("id", "w", "kind", "CHANGE", "operator", "GT", "warning", 50,
                "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "INCREASE", "baseline", "same_time_last_week"));
        assertThat(DbQueryBacktest.lookbackMs(List.of(weekly))).isGreaterThan(7L * 86_400_000L);
        assertThat(DbQueryBacktest.lookbackMs(List.of(prev))).isZero();
        DbQueryBacktest s = new DbQueryBacktest(List.of(weekly), "ANY", "h", NOW.minusSeconds(3600), 300);
        Instant lastWeek = NOW.minusSeconds(7L * 86_400 + 60);   // tolerans içinde (± 2,5 dk)
        s.accept(row(1, lastWeek, "h", measured(weekly, 100)));
        s.accept(row(2, NOW.minusSeconds(1800), "h", measured(weekly, 1)));   // geçen hafta karşılığı yok → değerlendirilemez
        s.accept(row(3, NOW, "h", measured(weekly, 300)));                    // %200 artış → ihlal
        Map<String, Object> sr = s.report(false);
        assertThat(sr.get("evaluated")).isEqualTo(1);
        assertThat(sr.get("would_breach")).isEqualTo(Map.of("warning", 1, "critical", 0));
    }

    @Test
    void backtestAllLogicAndLegacyText() {
        Rule a = one("id", "a", "kind", "ROW_COUNT", "operator", "GT", "warning", 10);
        Rule t = one("id", "t", "kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "warning", "HATA");
        DbQueryBacktest bt = new DbQueryBacktest(List.of(a, t), "ALL", "h", NOW.minusSeconds(3600), 300);
        RuleResult textHit = new RuleResult("t", null, "TEXT_MATCH", "", "", "WARNING", false, true, null, 1.0, 1.0,
                DbQueryRules.fingerprint(t));
        RuleResult legacyText = new RuleResult("t", null, "TEXT_MATCH", "", "", "WARNING", false, true, null, 1.0, 1.0);
        bt.accept(row(1, NOW.minusSeconds(1200), "h", measured(a, 50), textHit));    // ikisi de ihlal → ALL ihlal
        bt.accept(row(2, NOW.minusSeconds(900), "h", measured(a, 1), textHit));      // biri geçti → ALL geçti
        bt.accept(row(3, NOW.minusSeconds(600), "h", measured(a, 50), legacyText));  // eski metin satırı → karar yok
        Map<String, Object> rep = bt.report(false);
        assertThat(rep.get("evaluated")).isEqualTo(2);
        assertThat(rep.get("would_breach")).isEqualTo(Map.of("warning", 1, "critical", 0));
    }
}
