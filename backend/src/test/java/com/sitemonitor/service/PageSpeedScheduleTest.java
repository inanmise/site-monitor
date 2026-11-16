package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.dbquery.DbQuerySchedule;
import com.sitemonitor.util.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa Hızı 2. sürüm madde 14 (2026-11-15): ortak zamanlama hesabının Sayfa Hızı türü — alt sınır 5 dk, varsayılan aralık
 * 30 dk, "ölçüm" metinleri; sentetik tür bayt-özdeş kalır (aynı alt sınır ve metin). Genel bakışın gecikme kuralı zamanlamalı
 * Sayfa Hızı izlemesine de uygulanır.
 */
class PageSpeedScheduleTest {

    private static final Instant NOW = LocalDateTime.parse("2026-11-09T10:00:00").atZone(ZoneId.of("Europe/Istanbul")).toInstant();

    @Test
    @DisplayName("Sayfa Hızı türü: alt sınır 5 dk, varsayılan 30 dk; sentetik tür 60 sn / 300 sn (değişmedi)")
    void kindLimits() {
        assertThat(ScriptedSchedule.Kind.PAGESPEED.minIntervalSeconds).isEqualTo(PageSpeedMonitor.MIN_INTERVAL_SECONDS).isEqualTo(300);
        assertThat(ScriptedSchedule.Kind.PAGESPEED.defaultIntervalSeconds).isEqualTo(1800);
        assertThat(ScriptedSchedule.Kind.SCRIPTED.minIntervalSeconds).isEqualTo(DbQuerySchedule.MIN_INTERVAL_SECONDS);
        assertThat(ScriptedSchedule.Kind.SCRIPTED.defaultIntervalSeconds).isEqualTo(300);
    }

    @Test
    @DisplayName("2 dk'lık pencereli aralık Sayfa Hızı'nda hata (metin 'ölçüm' + 5 dakika), sentetikte geçerli")
    void windowedIntervalFloorPerKind() {
        Map<String, Object> body = Map.of("schedule_mode", "INTERVAL", "interval_seconds", 120,
                "windows", List.of(Map.of("days", List.of(1, 2, 3, 4, 5), "from", "09:00", "to", "18:00")));
        DbQuerySchedule.Spec spec = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(body));
        var ps = ScriptedSchedule.validate(spec, NOW, Set.of(), ScriptedSchedule.Kind.PAGESPEED);
        assertThat(ps).isNotEmpty();
        assertThat(ps.get(0).field()).isEqualTo("interval_seconds");
        assertThat(ps.get(0).message()).contains("Sayfa Hızı").contains("5 dakika");
        assertThat(ScriptedSchedule.validate(spec, NOW, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("dakikalık cron Sayfa Hızı'nda CRON_TOO_FREQUENT (kod sabit, metin Sayfa Hızı'na göre); İngilizce metin de var")
    void cronTooFrequent() {
        DbQuerySchedule.Spec spec = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "CRON", "cron_expression", "*/2 * * * *", "interval_seconds", 1800)));
        var ps = ScriptedSchedule.validate(spec, NOW, Set.of(), ScriptedSchedule.Kind.PAGESPEED);
        assertThat(ps).anySatisfy(fe -> {
            assertThat(fe.code()).isEqualTo(DbQuerySchedule.CODE_CRON_TOO_FREQUENT);
            assertThat(fe.message()).contains("5 dakika");
        });
        assertThat(Msg.class).isNotNull();
    }

    @Test
    @DisplayName("fromBody: saklı aralık yoksa türün varsayılanı (Sayfa Hızı 1800, sentetik 300)")
    void fromBodyDefaultInterval() {
        Map<String, Object> body = Map.of("schedule_mode", "CRON", "cron_expression", "0 8 * * *");
        assertThat(ScriptedSchedule.fromBody(body, (PageSpeedMonitor) null, ScriptedSchedule.Kind.PAGESPEED).intervalSeconds()).isEqualTo(1800);
        assertThat(ScriptedSchedule.fromBody(body, (ScriptedMonitor) null).intervalSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("önizleme Sayfa Hızı alt sınırını kullanır; sentetik önizleme değişmedi")
    void previewPerKind() {
        Map<String, Object> body = Map.of("schedule_mode", "INTERVAL", "interval_seconds", 120);
        assertThat(ScriptedSchedule.preview(body, NOW, Set.of(), ScriptedSchedule.Kind.PAGESPEED).get("valid")).isEqualTo(false);
        assertThat(ScriptedSchedule.preview(body, NOW, Set.of()).get("valid")).isEqualTo(true);
    }

    @Test
    @DisplayName("genel bakış: zamanlamalı Sayfa Hızı izlemesi planlı ölçümü kaçırınca gecikmiş; pencere dışında beklemek gecikme değil")
    void overviewStaleRule() {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setScheduleMode("INTERVAL");
        m.setIntervalSeconds(1800);
        m.setWindowsJson("[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        assertThat(ScriptedSchedule.hasPlan(m)).isTrue();
        // Cumartesi 12:00: son ölçüm cuma 17:40 → sonraki planlı ölçüm pazartesi 09:00 → gecikmiş DEĞİL
        Instant sat = LocalDateTime.parse("2026-11-07T12:00:00").atZone(ZoneId.of("Europe/Istanbul")).toInstant();
        assertThat(MonitoringOverviewService.isScriptedStale(m, "2026-11-06T14:40:00", sat, Set.of())).isFalse();
        // Pazartesi 10:00: son ölçüm cuma 17:40 → pazartesi 09:00 + 10 dk pay geçti → gecikmiş
        assertThat(MonitoringOverviewService.isScriptedStale(m, "2026-11-06T14:40:00", NOW, Set.of())).isTrue();
    }

    @Test
    @DisplayName("samePlan: aynı plan true; CRON'da aralık farkı plan değil; kip / ifade / pencere / tatil / ek tarih farkı false")
    void samePlanRule() {
        DbQuerySchedule.Spec cronA = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "CRON", "cron_expression", "0 9 * * 1-5", "interval_seconds", 86400)));
        DbQuerySchedule.Spec cronB = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "CRON", "cron_expression", " 0 9 * * 1-5 ", "interval_seconds", 600)));
        assertThat(ScriptedSchedule.samePlan(cronA, cronB)).isTrue();
        DbQuerySchedule.Spec cronC = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "CRON", "cron_expression", "0 10 * * 1-5")));
        assertThat(ScriptedSchedule.samePlan(cronA, cronC)).isFalse();
        Map<String, Object> win = Map.of("days", List.of(1, 2, 3, 4, 5), "from", "09:00", "to", "18:00");
        DbQuerySchedule.Spec ivA = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "INTERVAL", "interval_seconds", 1800, "windows", List.of(win))));
        DbQuerySchedule.Spec ivB = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "INTERVAL", "interval_seconds", 1800, "windows", List.of(win))));
        assertThat(ScriptedSchedule.samePlan(ivA, ivB)).isTrue();
        DbQuerySchedule.Spec ivInterval = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "INTERVAL", "interval_seconds", 900, "windows", List.of(win))));
        assertThat(ScriptedSchedule.samePlan(ivA, ivInterval)).isFalse();
        DbQuerySchedule.Spec ivHoliday = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "INTERVAL", "interval_seconds", 1800, "windows", List.of(win), "skip_holidays", true)));
        assertThat(ScriptedSchedule.samePlan(ivA, ivHoliday)).isFalse();
        assertThat(ScriptedSchedule.samePlan(ivA, cronA)).isFalse();
        assertThat(ScriptedSchedule.samePlan(ivA, null)).isFalse();
        // Saklı kolonlardan okunan tanım gövdeden gelenle aynı planı söyler (form her kayıtta zamanlamayı gönderir)
        PageSpeedMonitor m = new PageSpeedMonitor();
        ScriptedSchedule.apply(ivA, m, NOW, Set.of());
        assertThat(ScriptedSchedule.samePlan(ScriptedSchedule.specOf(m), ivB)).isTrue();
    }

    @Test
    @DisplayName("İnceleme 2026-11-17: aynı CRON planında ETKİN aralık farkı (aylık cron 30 ↔ 31 gün) plan değişimi DEĞİL — "
            + "sameSchedule plan sıfırlamaz, apply saklı etkin aralığı korur; aralık kipinde aralık farkı yine değişimdir")
    void sameCronPlanKeepsEffectiveInterval() {
        PageSpeedMonitor stored = new PageSpeedMonitor();
        stored.setScheduleMode("CRON");
        stored.setCronExpression("0 9 1 * *");
        stored.setIntervalSeconds(2_592_000);   // önceki kayıt anının ortancası (30 gün)
        PageSpeedMonitor now = new PageSpeedMonitor();
        now.setScheduleMode("CRON");
        now.setCronExpression("0 9 1 * *");
        now.setIntervalSeconds(2_678_400);      // 31 gün (bu kayıt anının ortancası)
        assertThat(ScriptedSchedule.sameSchedule(stored, now)).isTrue();
        now.setCronExpression("0 10 1 * *");
        assertThat(ScriptedSchedule.sameSchedule(stored, now)).isFalse();
        // Aralık kipi: aralık farkı değişimdir (eski kural)
        PageSpeedMonitor ivA = new PageSpeedMonitor();
        ivA.setScheduleMode("INTERVAL");
        ivA.setIntervalSeconds(1800);
        ivA.setWindowsJson("[{\"days\":[1],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        PageSpeedMonitor ivB = new PageSpeedMonitor();
        ivB.setScheduleMode("INTERVAL");
        ivB.setIntervalSeconds(900);
        ivB.setWindowsJson(ivA.getWindowsJson());
        assertThat(ScriptedSchedule.sameSchedule(ivA, ivB)).isFalse();

        // apply(…, stored): gövde aynı planı söylüyor → saklı etkin aralık korunur (yeniden hesaplanan ortanca yazılmaz)
        DbQuerySchedule.Spec body = ScriptedSchedule.specOf(stored);
        PageSpeedMonitor target = new PageSpeedMonitor();
        ScriptedSchedule.apply(body, target, NOW, Set.of(), stored);
        assertThat(target.getScheduleMode()).isEqualTo("CRON");
        assertThat(target.getIntervalSeconds()).isEqualTo(2_592_000);
        // Kanıtın kendisi: bu anda yeniden hesaplanan ortanca saklı değerden FARKLIDIR (aylık cron, 19 aralığın ortancası 31 gün)
        // Saklı tanımsız apply ortancayı yazar (yeni kayıt) — saklı değerden farklı olduğu kanıtlanır
        PageSpeedMonitor fresh = new PageSpeedMonitor();
        ScriptedSchedule.apply(body, fresh, NOW, Set.of());
        assertThat(fresh.getIntervalSeconds()).isEqualTo(2_678_400);
        // Plan DEĞİŞTİYSE yeni etkin aralık yazılır
        DbQuerySchedule.Spec weekly = DbQuerySchedule.fromApi(new java.util.LinkedHashMap<>(Map.of(
                "schedule_mode", "CRON", "cron_expression", "0 9 * * 1", "interval_seconds", 2_592_000)));
        PageSpeedMonitor changed = new PageSpeedMonitor();
        ScriptedSchedule.apply(weekly, changed, NOW, Set.of(), stored);
        assertThat(changed.getIntervalSeconds()).isEqualTo(604_800);
    }
}
