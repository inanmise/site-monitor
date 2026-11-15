package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.baseline.BaselineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sayfa Hızı zenginleştirme 1. sürüm — bağımsız inceleme düzeltmeleri (2026-11-14), zamanlayıcı ve posta tarafı.
 *
 * <ul>
 *   <li>B1: kısmi ölçümde kanıtlanamayan eşik "eşik içinde" SAYILMAZ — SLOW kalemi üretilmez, yeniden ölçüm "skipped" döner
 *       (açık bayt-bütçesi alarmı gerçek boyut bilinmezken sahte kapanmaz).</li>
 *   <li>B3: bant kuruluyorken yükleme DIŞI bir eşikle açılmış alarm, o eşikler içine dönünce kapanabilir.</li>
 *   <li>B6: bant kipinde sabit yükleme satırı posta tablosuna girmez; bant satırı yönünü taşır; indirme fazı gerçek istekten.</li>
 * </ul>
 */
class PageSpeedReviewFixTest {

    R33SweepHarness h;

    @BeforeEach
    void setup() throws Exception {
        h = new R33SweepHarness();
    }

    /** Sabit eşikli (bant kapalı) bir zamanlanmış sonuç: sayfa alındı. */
    private static Map<String, Object> fixed(boolean within, List<String> breached, List<String> undecided) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", within ? "OK" : "SLOW");
        r.put("reachable", true);
        r.put("within_thresholds", within);
        r.put("breached", breached);
        r.put("undecided", undecided);
        r.put("response_ms", 1200L);
        r.put("total_bytes", 300_000L);
        return r;
    }

    /** Öğrenen bant açık sonuç. */
    private static Map<String, Object> band(Boolean evaluated, String level, List<String> breached, List<String> undecided) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "OK");
        r.put("reachable", true);
        r.put("response_ms", 101L);
        r.put("breached", breached);
        r.put("undecided", undecided);
        r.put(BaselineService.CTX_ON, true);
        r.put(BaselineService.CTX_EVALUATED, evaluated);
        if (level != null) r.put(BaselineService.CTX_LEVEL, level);
        r.put(BaselineService.CTX_VALUE, 101L);
        r.put(BaselineService.CTX_MEAN, 100.0);
        r.put(BaselineService.CTX_HIGH, 105.0);
        r.put(BaselineService.CTX_LOW, 95.0);
        r.put(BaselineService.CTX_DEVIATION, 0.2);
        return r;
    }

    private List<MonitoringOutageService.SweepItem> slowItems(Map<String, Object> r) {
        List<MonitoringOutageService.SweepItem> down = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slow = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(h.scheduler, "addPageSpeedSweepItems",
                R33SweepHarness.pageSpeed(1, "https://ornek.test/"), r, down, slow);
        return slow;
    }

    // ── B1 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B1: kısmi ölçümde ihlal yok ama eşik kararsız → SLOW kalemi YOK (açık alarm 'çözüldü' ile kapanmaz)")
    void undecidedEmitsNoSlowItem() {
        assertThat(slowItems(fixed(true, List.of(), List.of("JS")))).isEmpty();
        // kararsız yok → bugünkü gibi sağlıklı kalem
        var ok = slowItems(fixed(true, List.of(), List.of()));
        assertThat(ok).hasSize(1);
        assertThat(ok.get(0).up()).isTrue();
        // kanıtlı ihlal varken kararsız eşik kalemi DURDURMAZ — kalem down
        var down = slowItems(fixed(false, List.of("SIZE"), List.of("JS")));
        assertThat(down).hasSize(1);
        assertThat(down.get(0).up()).isFalse();
    }

    @Test
    @DisplayName("B1: sabit eşikli yeniden ölçüm — kararsız eşik varken 'up' değil 'skipped'; kanıtlı ihlal 'down'; alınamadı 'skipped'")
    void slowRecheckOnUndecided() {
        assertThat(SchedulerService.pageSpeedSlowRecheck(fixed(true, List.of(), List.of("SIZE")))).containsEntry("status", "skipped");
        assertThat(SchedulerService.pageSpeedSlowRecheck(fixed(true, List.of(), List.of()))).containsEntry("status", "up");
        assertThat(SchedulerService.pageSpeedSlowRecheck(fixed(false, List.of("SIZE"), List.of()))).containsEntry("status", "down");
        Map<String, Object> unreachable = fixed(true, List.of(), List.of());
        unreachable.put("reachable", false);
        assertThat(SchedulerService.pageSpeedSlowRecheck(unreachable)).containsEntry("status", "skipped");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("config_error", true);
        assertThat(SchedulerService.pageSpeedSlowRecheck(cfg)).containsEntry("status", "up");
    }

    @Test
    @DisplayName("B1: bant kipinde bant içi ama yükleme DIŞI eşik kararsız → kalem YOK; yalnız LOAD kararsızsa (bant onun yerinde) kalem 'up'")
    void bandModeUndecided() {
        var m = R33SweepHarness.pageSpeed(1, "https://ornek.test/");
        MonitoringOutageService.SweepItem none = ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m,
                band(true, "none", List.of(), List.of("IMG")));
        assertThat(none).isNull();
        MonitoringOutageService.SweepItem loadOnly = ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m,
                band(true, "none", List.of(), List.of("LOAD")));
        assertThat(loadOnly).isNotNull();
        assertThat(loadOnly.up()).isTrue();
    }

    // ── B3 ───────────────────────────────────────────────────────────────────────────────────

    private AlertEventRepository openAlarm(String breachedJson) {
        AlertEventRepository repo = mock(AlertEventRepository.class);
        AlertEvent e = new AlertEvent();
        e.setDomain("https://ornek.test/");
        e.setAlertType(EscalationService.TYPE_PAGESPEED_SLOW);
        e.setContextJson(breachedJson == null ? null : "{\"breached_metrics\":" + breachedJson + "}");
        when(repo.findOpenAlert("https://ornek.test/", EscalationService.TYPE_PAGESPEED_SLOW)).thenReturn(Optional.of(e));
        ReflectionTestUtils.setField(h.scheduler, "pageSpeedAlertRepo", repo);
        return repo;
    }

    @Test
    @DisplayName("B3: bant kuruluyor + açık alarm TTFB ile açılmıştı + TTFB artık içinde → kalem 'up' (alarm kapanabilir)")
    void buildingBandClosesNonLoadAlarm() {
        openAlarm("[\"TTFB\"]");
        var m = R33SweepHarness.pageSpeed(1, "https://ornek.test/");
        MonitoringOutageService.SweepItem it = ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m,
                band(false, null, List.of(), List.of()));
        assertThat(it).isNotNull();
        assertThat(it.up()).isTrue();
    }

    @Test
    @DisplayName("B3: açık alarm YÜKLEME bandıyla açıldıysa ya da sebep okunamıyorsa bugünkü kural — kalem YOK (D-7)")
    void buildingBandKeepsLoadAlarm() {
        var m = R33SweepHarness.pageSpeed(1, "https://ornek.test/");
        openAlarm("[\"LOAD\",\"TTFB\"]");
        assertThat((Object) ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m,
                band(false, null, List.of(), List.of()))).isNull();
        openAlarm(null);
        assertThat((Object) ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m,
                band(false, null, List.of(), List.of()))).isNull();
        // kararsız eşik varken de kanıt yok → kalem yok
        openAlarm("[\"TTFB\"]");
        assertThat((Object) ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m,
                band(false, null, List.of(), List.of("TTFB")))).isNull();
    }

    @Test
    @DisplayName("B3: açık alarm okuması YALNIZ bant karar veremezken yapılır — değerlendirilmiş bantta depo sorgulanmaz")
    void openAlarmReadOnlyWhileBuilding() {
        AlertEventRepository repo = openAlarm("[\"TTFB\"]");
        var m = R33SweepHarness.pageSpeed(1, "https://ornek.test/");
        ReflectionTestUtils.invokeMethod(h.scheduler, "pageSpeedBaselineSlowItem", m, band(true, "none", List.of(), List.of()));
        verify(repo, never()).findOpenAlert("https://ornek.test/", EscalationService.TYPE_PAGESPEED_SLOW);
    }

    // ── B6 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B6a: bant kipinde sabit yükleme satırı tablodan DÜŞER (bant değerlendirilmese de); diğer satırlar kalır")
    void bandTableDropsFixedLoadRow() {
        String table = "LOAD\t3000\t3500\t1\t0\nTTFB\t300\t900\t1\t0";
        assertThat(SchedulerService.pageSpeedBandTable(table, null, 101L, false)).isEqualTo("TTFB\t300\t900\t1\t0");
        assertThat(SchedulerService.pageSpeedBandTable("LOAD\t3000\t3500\t1\t0", null, 101L, false)).isNull();
        String withBand = SchedulerService.pageSpeedBandTable(table, "95|105|up", 101L, false);
        assertThat(withBand.split("\n")[0]).isEqualTo("BAND\t95|105|up\t101\t0\t0");
        assertThat(withBand).doesNotContain("LOAD\t");
    }

    @Test
    @DisplayName("B6b: bant satırı yönüyle yazılır — yukarı '≤ üst', aşağı '≥ alt', iki yön 'alt – üst'")
    void bandRowFollowsDirection() {
        assertThat(SchedulerService.pageSpeedBandThreshold(95.4, 105.2, "up")).isEqualTo("95|105|up");
        assertThat(SchedulerService.pageSpeedBandThreshold(null, null, "up")).isNull();
        assertThat(EmailTemplateBuilder.psThresholdText(new EmailTemplateBuilder.PsRow("BAND", "95|105|up", "200", true, false)))
                .isEqualTo("≤ 105 ms");
        assertThat(EmailTemplateBuilder.psThresholdText(new EmailTemplateBuilder.PsRow("BAND", "95|105|down", "50", true, false)))
                .isEqualTo("≥ 95 ms");
        assertThat(EmailTemplateBuilder.psThresholdText(new EmailTemplateBuilder.PsRow("BAND", "95|105|both", "50", true, false)))
                .isEqualTo("95 – 105 ms");
    }

    @Test
    @DisplayName("B6d: indirme fazı GERÇEK istekteki gövde süresi (download_ms) — html_ms − ttfb_ms farkı kullanılmaz")
    void downloadPhaseFromRealRequest() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dns_ms", 12);
        r.put("html_ms", 900L);
        r.put("ttfb_ms", 300);
        r.put("download_ms", 140L);
        assertThat(SchedulerService.pageSpeedPhasesText(r)).contains("download\t140").doesNotContain("download\t600");
        r.remove("download_ms");
        assertThat(SchedulerService.pageSpeedPhasesText(r)).doesNotContain("download");
    }
}
