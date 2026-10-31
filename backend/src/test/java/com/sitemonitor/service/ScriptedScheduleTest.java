package com.sitemonitor.service;

import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.dbquery.DbQuerySchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6r D2 (2026-10-31): {@link ScriptedSchedule} — hesap {@link DbQuerySchedule}'ündür (kopyalanmaz); burada sentetiğe özgü
 * kurallar: "plan gerekir mi", düz aralığın bugünkü yolu (kolonlar null, denetim yok), sentetik metinleri ve aynı kodlar.
 * Ayrıca {@code DbQuerySchedule}'e eklenen genel yardımcıların ({@code ofColumns}, {@code columns}, {@code hasPlan}) dbquery
 * davranışını DEĞİŞTİRMEDİĞİ kanıtlanır.
 */
class ScriptedScheduleTest {

    private static final Instant NOW = Instant.parse("2026-11-09T07:00:00Z");   // pazartesi 10:00 İstanbul
    private static final Set<LocalDate> NO_HOLIDAYS = Set.of();

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── plan gerekir mi ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasPlan: hiçbir zamanlama kolonu yok / INTERVAL + boş dizi → false; CRON, pencere, yasak, tatil, ek tarih → true")
    void hasPlanRules() {
        ScriptedMonitor m = new ScriptedMonitor();
        assertThat(ScriptedSchedule.hasPlan(m)).isFalse();
        m.setScheduleMode("INTERVAL");
        m.setWindowsJson("[]");
        m.setSkipHolidays(false);
        assertThat(ScriptedSchedule.hasPlan(m)).isFalse();
        m.setScheduleMode("cron");
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
        m.setScheduleMode(null);
        m.setWindowsJson("[{\"days\":[],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
        m.setWindowsJson(null);
        m.setBlackoutJson("[{\"days\":[],\"from\":\"01:00\",\"to\":\"02:00\"}]");
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
        m.setBlackoutJson(null);
        m.setSkipHolidays(true);
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
        m.setSkipHolidays(null);
        m.setSkipDatesJson("[\"2026-12-31\"]");
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
        assertThat(ScriptedSchedule.hasPlan((ScriptedMonitor) null)).isFalse();
    }

    @Test
    @DisplayName("gönderim satırı: 9 kolonlu satır okunur, eski 2 kolonlu satır plansız (bugünkü yol)")
    void rowHelpers() {
        Object[] legacy = {1L, 300};
        assertThat(ScriptedSchedule.rowHasPlan(legacy)).isFalse();
        assertThat(ScriptedSchedule.specOfRow(legacy)).isNull();
        assertThat(ScriptedSchedule.rowNextRunAt(legacy)).isNull();
        Object[] row = {2L, 86_400, "CRON", "0 8 * * *", null, null, true, null, "2026-11-10T05:00:00"};
        assertThat(ScriptedSchedule.rowHasPlan(row)).isTrue();
        DbQuerySchedule.Spec spec = ScriptedSchedule.specOfRow(row);
        assertThat(spec.mode()).isEqualTo("CRON");
        assertThat(spec.cron()).isEqualTo("0 8 * * *");
        assertThat(spec.skipHolidays()).isTrue();
        assertThat(ScriptedSchedule.rowNextRunAt(row)).isEqualTo("2026-11-10T05:00:00");
        Object[] flat = {3L, 300, null, null, null, null, null, null, null};
        assertThat(ScriptedSchedule.rowHasPlan(flat)).isFalse();
    }

    // ── gövde → tanım → kolon ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("present: yalnız zamanlama anahtarı varsa; aralık (intervalSeconds) tek başına zamanlama sayılmaz")
    void presentRule() {
        assertThat(ScriptedSchedule.present(body("intervalSeconds", 60))).isFalse();
        assertThat(ScriptedSchedule.present(body("windows", List.of()))).isTrue();
        assertThat(ScriptedSchedule.present(null)).isFalse();
    }

    @Test
    @DisplayName("düz aralık tanımı: kolonlar NULL yazılır, aralık gövdedeki değer; denetlenmez (30 sn bile)")
    void plainSpec_appliesAsNulls_noValidation() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setScheduleMode("CRON");
        m.setCronExpression("0 8 * * *");
        m.setSkipHolidays(true);
        DbQuerySchedule.Spec spec = ScriptedSchedule.fromBody(body("schedule_mode", "INTERVAL", "intervalSeconds", 30,
                "windows", List.of(), "skip_holidays", false), m);
        assertThat(ScriptedSchedule.validate(spec, NOW, NO_HOLIDAYS)).isEmpty();
        ScriptedSchedule.apply(spec, m, NOW, NO_HOLIDAYS);
        assertThat(m.getScheduleMode()).isNull();
        assertThat(m.getCronExpression()).isNull();
        assertThat(m.getSkipHolidays()).isNull();
        assertThat(m.getIntervalSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("güncelleme birleştirmesi: gelen anahtar saklı tanımın ÜSTÜNE; gelmeyen korunur; aralık takma adı kabul")
    void fromBodyMergesOverStored() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setIntervalSeconds(600);
        m.setWindowsJson("[{\"days\":[1],\"from\":\"09:00\",\"to\":\"12:00\"}]");
        DbQuerySchedule.Spec spec = ScriptedSchedule.fromBody(body("skip_holidays", true), m);
        assertThat(spec.intervalSeconds()).isEqualTo(600);
        assertThat(spec.windows()).hasSize(1);
        assertThat(spec.skipHolidays()).isTrue();
        DbQuerySchedule.Spec alias = ScriptedSchedule.fromBody(body("interval_seconds", 900, "windows", List.of()), m);
        assertThat(alias.intervalSeconds()).isEqualTo(900);
        assertThat(alias.windows()).isEmpty();
    }

    @Test
    @DisplayName("CRON tanımı: kip + ifade + ETKİN aralık yazılır (hafta içi 08:00 → medyan 1 gün)")
    void cronSpec_appliesEffectiveInterval() {
        ScriptedMonitor m = new ScriptedMonitor();
        DbQuerySchedule.Spec spec = ScriptedSchedule.fromBody(body("schedule_mode", "CRON", "cron_expression", " 0 8 * * 1-5 "), m);
        assertThat(ScriptedSchedule.validate(spec, NOW, NO_HOLIDAYS)).isEmpty();
        ScriptedSchedule.apply(spec, m, NOW, NO_HOLIDAYS);
        assertThat(m.getScheduleMode()).isEqualTo("CRON");
        assertThat(m.getCronExpression()).isEqualTo("0 8 * * 1-5");
        assertThat(m.getIntervalSeconds()).isEqualTo(86_400);
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
    }

    // ── doğrulama: kodlar dbquery'ninki, metinler sentetiğin ────────────────────────────────────────────────────────

    @Test
    @DisplayName("hatalar: dbquery kodları AYNEN; aralık / sık cron / hiç koşmama metinleri 'senaryo' der, 'sorgu' demez")
    void validationCodesAndWording() {
        var shortInterval = ScriptedSchedule.validate(ScriptedSchedule.fromBody(body("intervalSeconds", 30,
                "windows", List.of(Map.of("days", List.of(), "from", "09:00", "to", "18:00"))), null), NOW, NO_HOLIDAYS);
        assertThat(shortInterval).extracting(ScriptedSchedule.FieldError::field).containsExactly("interval_seconds");
        assertThat(shortInterval.get(0).code()).isEqualTo(DbQuerySchedule.CODE_INTERVAL);
        assertThat(shortInterval.get(0).message()).contains("senaryo").doesNotContain("sorgu");

        var tooFrequent = ScriptedSchedule.validate(ScriptedSchedule.fromBody(body("schedule_mode", "CRON",
                "cron_expression", "*/20 * * * * *"), null), NOW, NO_HOLIDAYS);
        assertThat(tooFrequent.get(0).code()).isEqualTo(DbQuerySchedule.CODE_CRON_TOO_FREQUENT);
        assertThat(tooFrequent.get(0).message()).contains("senaryo").doesNotContain("sorgu");

        var never = ScriptedSchedule.validate(ScriptedSchedule.fromBody(body(
                "windows", List.of(Map.of("days", List.of(), "from", "09:00", "to", "10:00")),
                "blackouts", List.of(Map.of("days", List.of(), "from", "08:00", "to", "11:00"))), null), NOW, NO_HOLIDAYS);
        assertThat(never).extracting(ScriptedSchedule.FieldError::code).contains(DbQuerySchedule.CODE_NEVER_RUNS);
        assertThat(never.get(0).message()).contains("senaryo").doesNotContain("sorgu");

        var badCron = ScriptedSchedule.validate(ScriptedSchedule.fromBody(body("schedule_mode", "CRON", "cron_expression", "x"), null),
                NOW, NO_HOLIDAYS);
        assertThat(badCron.get(0).field()).isEqualTo("cron_expression");
        assertThat(badCron.get(0).code()).isEqualTo(DbQuerySchedule.CODE_CRON_INVALID);

        var badMode = ScriptedSchedule.validate(ScriptedSchedule.fromBody(body("schedule_mode", "HOURLY"), null), NOW, NO_HOLIDAYS);
        assertThat(badMode.get(0).code()).isEqualTo(DbQuerySchedule.CODE_MODE);
    }

    @Test
    @DisplayName("önizleme: dbquery önizlemesiyle aynı anahtarlar; düz aralık da gösterilir (60 sn alt sınırıyla)")
    void previewShape() {
        Map<String, Object> ok = ScriptedSchedule.preview(body("schedule_mode", "INTERVAL", "interval_seconds", 600), NOW, NO_HOLIDAYS);
        assertThat(ok).containsOnlyKeys("valid", "errors", "next", "effective_interval_seconds", "never_runs");
        assertThat(ok.get("valid")).isEqualTo(true);
        assertThat((List<?>) ok.get("next")).hasSize(10);
        assertThat(((List<?>) ok.get("next")).get(0)).isEqualTo("2026-11-09T07:00:00");
        assertThat(ok.get("effective_interval_seconds")).isEqualTo(600L);
        Map<String, Object> camel = ScriptedSchedule.preview(body("intervalSeconds", 600), NOW, NO_HOLIDAYS);
        assertThat(camel.get("valid")).isEqualTo(true);
        Map<String, Object> holiday = ScriptedSchedule.preview(body("schedule_mode", "CRON", "cron_expression", "0 8 * * *",
                "skip_holidays", true), NOW, Set.of(LocalDate.parse("2026-11-10")));
        @SuppressWarnings("unchecked")
        List<String> hn = (List<String>) holiday.get("next");
        assertThat(hn).doesNotContain("2026-11-10T05:00:00").contains("2026-11-11T05:00:00");
    }

    @Test
    @DisplayName("API biçimi: next_run_at yalnız zamanlamalı izlemede; pencereler liste; bozuk JSON boş liste")
    void apiShape() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setIntervalSeconds(300);
        m.setNextRunAt("2030-01-01T00:00:00");   // düz aralıkta saklı değer yok sayılır
        Map<String, Object> plain = ScriptedSchedule.api(m);
        assertThat(plain).containsKeys("schedule_mode", "interval_seconds", "cron_expression", "windows", "blackouts",
                "skip_holidays", "skip_dates", "next_run_at", "effective_interval_seconds");
        assertThat(plain.get("schedule_mode")).isEqualTo("INTERVAL");
        assertThat(plain.get("next_run_at")).isNull();
        assertThat((List<?>) plain.get("windows")).isEmpty();
        m.setWindowsJson("{bozuk");                // elle bozulmuş kolon: gevşek okuma → boş liste (fırlatmaz)
        assertThat((List<?>) ScriptedSchedule.api(m).get("windows")).isEmpty();
        m.setWindowsJson("[{\"days\":[6,7],\"from\":\"10:00\",\"to\":\"12:00\"}]");
        Map<String, Object> planned = ScriptedSchedule.api(m);
        assertThat(planned.get("next_run_at")).isEqualTo("2030-01-01T00:00:00");
        assertThat(planned.get("windows").toString()).contains("10:00");
    }

    @Test
    @DisplayName("sameSchedule: aralık dahil yedi alan karşılaştırılır")
    void sameScheduleRule() {
        ScriptedMonitor a = new ScriptedMonitor(), b = new ScriptedMonitor();
        assertThat(ScriptedSchedule.sameSchedule(a, b)).isTrue();
        b.setIntervalSeconds(60);
        assertThat(ScriptedSchedule.sameSchedule(a, b)).isFalse();
        b.setIntervalSeconds(a.getIntervalSeconds());
        b.setSkipDatesJson("[\"2026-12-31\"]");
        assertThat(ScriptedSchedule.sameSchedule(a, b)).isFalse();
    }

    // ── DbQuerySchedule'e eklenen genel yardımcılar dbquery davranışını değiştirmez ─────────────────────────────────────

    @Test
    @DisplayName("DbQuerySchedule.ofColumns ≡ of(DbQueryMonitor); columns ≡ apply (dbquery bayt-özdeş)")
    void dbQueryHelpersParity() {
        DbQueryMonitor d = new DbQueryMonitor();
        d.setScheduleMode("cron");
        d.setCronExpression("0 8 * * 1-5");
        d.setIntervalSeconds(86_400);
        d.setWindowsJson("[{\"days\":[1,2],\"from\":\"07:00\",\"to\":\"20:00\"}]");
        d.setBlackoutJson("[{\"days\":[],\"from\":\"12:00\",\"to\":\"13:00\"}]");
        d.setSkipHolidays(true);
        d.setSkipDatesJson("[\"2026-12-31\",\"bozuk\"]");
        DbQuerySchedule.Spec a = DbQuerySchedule.of(d);
        DbQuerySchedule.Spec b = DbQuerySchedule.ofColumns(d.getScheduleMode(), d.getIntervalSeconds(), d.getCronExpression(),
                d.getWindowsJson(), d.getBlackoutJson(), d.getSkipHolidays(), d.getSkipDatesJson());
        assertThat(b).isEqualTo(a);
        assertThat(a.skipDates()).containsExactly("2026-12-31");
        assertThat(DbQuerySchedule.hasPlan(a)).isTrue();
        assertThat(DbQuerySchedule.hasPlan(new DbQuerySchedule.Spec("INTERVAL", 300, null, List.of(), List.of(), false, List.of())))
                .isFalse();

        DbQueryMonitor target = new DbQueryMonitor();
        DbQuerySchedule.apply(a, target, NOW, NO_HOLIDAYS);
        DbQuerySchedule.Columns c = DbQuerySchedule.columns(a, NOW, NO_HOLIDAYS);
        assertThat(target.getScheduleMode()).isEqualTo(c.scheduleMode()).isEqualTo("CRON");
        assertThat(target.getCronExpression()).isEqualTo(c.cronExpression());
        assertThat(target.getIntervalSeconds()).isEqualTo(c.intervalSeconds());
        assertThat(target.getWindowsJson()).isEqualTo(c.windowsJson());
        assertThat(target.getBlackoutJson()).isEqualTo(c.blackoutJson());
        assertThat(target.getSkipHolidays()).isEqualTo(c.skipHolidays());
        assertThat(target.getSkipDatesJson()).isEqualTo(c.skipDatesJson()).isEqualTo("[\"2026-12-31\"]");
        assertThat(DbQuerySchedule.of((DbQueryMonitor) null).mode()).isEqualTo("INTERVAL");
    }
}
