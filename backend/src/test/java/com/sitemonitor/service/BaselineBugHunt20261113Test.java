package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.service.baseline.BaselineSampleStore;
import com.sitemonitor.service.baseline.BaselineSchemaPatch;
import com.sitemonitor.service.baseline.BaselineService;
import com.sitemonitor.service.baseline.BaselineTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dinamik taban çizgisi — 2026-11-13 hata taraması düzeltmelerinin SchedulerService düzeyi kapıları: elle "Şimdi kontrol et"
 * açık bant alarmını sahte kapatmaz (#1, dört elle yol), kritik bant aşımı alarmı KRİTİK seviyeye taşır (#6) ve yapılandırma
 * hiç okunamamışken SLOW kalemi üretilmez (#10). Gerçek H2 örnek deposu + gerçek {@link BaselineService}; MOS sahte (elle yolun
 * ürettiği kalemler yakalanır) — #6'nın tırmanma yolu gerçek MOS ile.
 */
class BaselineBugHunt20261113Test {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    R33SweepHarness h;
    MonitorBaselineConfigRepository configRepo;
    BaselineSampleStore store;
    BaselineService baseline;

    static MonitorBaselineConfig cfg(String type, long id) {
        MonitorBaselineConfig c = new MonitorBaselineConfig();
        c.setMonitorType(type);
        c.setMonitorId(id);
        c.setEnabled(true);
        return c;   // öntanımlı: yukarı, k 2,5 / 3,5, pencere 30, en az 10 örnek
    }

    @BeforeEach
    void setup() throws Exception {
        h = new R33SweepHarness();
        store = new BaselineSampleStore(mp.jdbc());
        new BaselineSchemaPatch(mp.jdbc(), store).apply();
        configRepo = mock(MonitorBaselineConfigRepository.class);
        List<MonitorBaselineConfig> on = new ArrayList<>();
        for (String t : List.of(BaselineTypes.HTTP, BaselineTypes.PING, BaselineTypes.PAGESPEED, BaselineTypes.SCRIPTED)) {
            on.add(cfg(t, 1L));
            on.add(cfg(t, 2L));   // id 2: AÇIK ama örnek yok (taban kuruluyor)
            // ~100 ms'lik kararlı geçmiş (id 1) — bant ≈ 95…105
            for (int i = 0; i < 20; i++) store.append(t, 1L, 1_000_000L + i * 60_000L, 98 + (i % 5), false);
        }
        when(configRepo.findByEnabledTrue()).thenReturn(on);
        baseline = new BaselineService(configRepo, store);
        ReflectionTestUtils.setField(baseline, "globalEnabled", true);
        ReflectionTestUtils.setField(baseline, "cacheMs", 30_000L);
        ReflectionTestUtils.setField(h.scheduler, "baselineService", baseline);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    /** Elle yolun MOS'a verdiği (manual=true) SLOW kalemleri. */
    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> manualSlowItems(String slowType) {
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> cap = ArgumentCaptor.forClass(List.class);
        try {
            verify(h.outage, atLeastOnce()).handleSweepResults(eq(slowType), cap.capture(), eq(true));
        } catch (Throwable notCalled) {
            return List.of();   // HTTP: kalem yoksa çağrı da yok
        }
        List<MonitoringOutageService.SweepItem> out = new ArrayList<>();
        for (List<MonitoringOutageService.SweepItem> l : cap.getAllValues()) out.addAll(l);
        return out;
    }

    private PingMonitor ping(long id) {
        PingMonitor m = R33SweepHarness.ping(id, "gw" + id + ".example.com");
        m.setSlowResponseEnabled(false);   // sabit yavaşlık KAPALI — kullanıcı bandı kullanıyor
        return m;
    }

    private static Map<String, Object> pingRaw(long rtt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("up", true);
        r.put("rtt_ms", rtt);
        r.put("packet_loss", 0);
        return r;
    }

    // ── #1 — elle kontrol ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 PING: taban açık + sabit kapalı + hedef hâlâ yavaş → elle kontrol 'sağlıklı' kalem ÜRETMEZ (bant dışı = down)")
    void manualPingSlowStaysDown() {
        h.scheduler.evaluatePingNow(ping(1), pingRaw(500));
        List<MonitoringOutageService.SweepItem> items = manualSlowItems(EscalationService.TYPE_PING_SLOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).as("eskiden sabit yolun sentetik 'up'ı → kurtarma zinciri bant alarmını kapatıyordu").isFalse();
        assertThat(items.get(0).detail()).isEqualTo(BaselineService.SLOW_DETAIL);
        assertThat(store.count(BaselineTypes.PING, 1L)).as("elle kontrol örnek YAZMAZ").isEqualTo(20);
    }

    @Test
    @DisplayName("#1 PING: bant içi elle sonuç kurtarmayı BANT recheck'iyle başlatır — hedef yeniden yavaşsa recheck 'down' (kapatmaz)")
    void manualPingInBandUsesBaselineRecheck() {
        h.scheduler.evaluatePingNow(ping(1), pingRaw(101));
        List<MonitoringOutageService.SweepItem> items = manualSlowItems(EscalationService.TYPE_PING_SLOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).isTrue();
        assertThat(items.get(0).detail()).isEqualTo(BaselineService.SLOW_DETAIL);
        when(h.pingChecker.check(anyString(), any(), anyInt(), anyInt())).thenReturn(pingRaw(500));
        assertThat(items.get(0).recheck().get().get("status"))
                .as("kurtarma adımı sabit eşiği değil öğrenen bandı sorar").isEqualTo("down");
    }

    @Test
    @DisplayName("#1 PING: taban kuruluyor (örnek yok) → elle kontrol SLOW kalemi üretmez (ne açar ne kapatır)")
    void manualPingBuildingNoItem() {
        h.scheduler.evaluatePingNow(ping(2), pingRaw(500));
        assertThat(manualSlowItems(EscalationService.TYPE_PING_SLOW)).isEmpty();
    }

    @Test
    @DisplayName("#1 PING: taban KAPALI izlemede elle yol bugünkü gibi (sabit yol; çağıranın haritası değişmez)")
    void manualPingBaselineOffUnchanged() {
        Map<String, Object> raw = pingRaw(500);
        h.scheduler.evaluatePingNow(ping(3), raw);   // id 3: yapılandırma yok
        List<MonitoringOutageService.SweepItem> items = manualSlowItems(EscalationService.TYPE_PING_SLOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).detail()).isNotEqualTo(BaselineService.SLOW_DETAIL);   // sabit eşik kalemi
        assertThat(raw).doesNotContainKey(BaselineService.CTX_ON);
    }

    @Test
    @DisplayName("#1 HTTP: sabit eşik (2000 ms) altı ama bant dışı (500 ms) elle sonuç → bant kalemi down (eskiden sabit 'up')")
    void manualHttpOutOfBand() {
        HttpMonitor m = R33SweepHarness.http(1, "https://pay.example.com/");
        m.setSlowResponseEnabled(true);
        m.setSlowThresholdMs(2000);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("ok", true);
        raw.put("http_status", 200);
        raw.put("response_ms", 500);
        h.scheduler.evaluateHttpNow(m, raw);
        List<MonitoringOutageService.SweepItem> items = manualSlowItems(EscalationService.TYPE_HTTP_SLOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).isFalse();
        assertThat(items.get(0).detail()).isEqualTo(BaselineService.SLOW_DETAIL);
        assertThat(raw).doesNotContainKey(BaselineService.CTX_ON);   // çağıranın haritası değişmedi
    }

    @Test
    @DisplayName("#1 SAYFA HIZI: sabit eşikler içinde ama bant dışı elle sonuç → bant kalemi down (eskiden within_thresholds = sağlıklı)")
    void manualPageSpeedOutOfBand() {
        PageSpeedMonitor m = R33SweepHarness.pageSpeed(1, "https://shop.example.com/");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "OK");
        raw.put("reachable", true);
        raw.put("within_thresholds", true);
        raw.put("response_ms", 500);
        h.scheduler.evaluatePageSpeedNow(m, raw);
        List<MonitoringOutageService.SweepItem> items = manualSlowItems(EscalationService.TYPE_PAGESPEED_SLOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).isFalse();
        assertThat(items.get(0).detail()).isEqualTo(BaselineService.SLOW_DETAIL);
        assertThat(raw).doesNotContainKey(BaselineService.CTX_ON);
    }

    @Test
    @DisplayName("#1 SENTETİK: sabit yavaşlık kapalı + bant dışı elle koşum → bant kalemi down (eskiden sentetik 'up')")
    void manualScriptedOutOfBand() {
        ScriptedMonitor m = R33SweepHarness.scripted(1, "Ödeme akışı");
        m.setSlowResponseEnabled(false);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "PASS");
        raw.put("up", true);
        raw.put("response_ms", 500);
        raw.put("detail", "PASS");
        h.scheduler.evaluateScriptedNow(m, raw);
        List<MonitoringOutageService.SweepItem> items = manualSlowItems(EscalationService.TYPE_SCRIPTED_SLOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).isFalse();
        assertThat(items.get(0).detail()).isEqualTo(BaselineService.SLOW_DETAIL);
        assertThat(store.count(BaselineTypes.SCRIPTED, 1L)).isEqualTo(20);
    }

    // ── #10 — yapılandırma hiç okunamadı ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#10 açılışta yapılandırma okunamadı (BİLİNMİYOR) → zamanlanmış ve elle yolda SLOW kalemi YOK (sabit 'up' bant alarmını kapatmaz)")
    void unknownConfigProducesNoSlowItem() {
        MonitorBaselineConfigRepository failing = mock(MonitorBaselineConfigRepository.class);
        when(failing.findByEnabledTrue()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        BaselineService svc = new BaselineService(failing, store);
        ReflectionTestUtils.setField(svc, "globalEnabled", true);
        ReflectionTestUtils.setField(svc, "cacheMs", 30_000L);
        ReflectionTestUtils.setField(h.scheduler, "baselineService", svc);

        HttpMonitor m = R33SweepHarness.http(1, "https://pay.example.com/");
        m.setSlowResponseEnabled(true);
        m.setSlowThresholdMs(2000);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "up");
        r.put("response_ms", 100L);
        ReflectionTestUtils.invokeMethod(h.scheduler, "baselineHttpScheduled", m, r);
        assertThat(r.get(BaselineService.CTX_ON)).isEqualTo(true);
        assertThat(r).doesNotContainKey(BaselineService.CTX_EVALUATED);
        assertThat(h.scheduler.httpSlowSweepItem(m, r)).as("sabit yol (100 ≤ 2000) 'up' üretip bant alarmını kapatırdı").isNull();
        assertThat(store.count(BaselineTypes.HTTP, 1L)).as("bilinmezken örnek yazılmaz").isEqualTo(20);

        h.scheduler.evaluatePingNow(ping(1), pingRaw(101));
        assertThat(manualSlowItems(EscalationService.TYPE_PING_SLOW)).isEmpty();
    }

    // ── #6 — kritik katsayı ───────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> verdictCtx(long ms, String level) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "up");
        r.put("response_ms", ms);
        r.put(BaselineService.CTX_ON, true);
        r.put(BaselineService.CTX_EVALUATED, true);
        r.put(BaselineService.CTX_LEVEL, level);
        r.put(BaselineService.CTX_VALUE, ms);
        r.put(BaselineService.CTX_MEAN, 100.0);
        r.put(BaselineService.CTX_LOW, 95.0);
        r.put(BaselineService.CTX_HIGH, 105.0);
        r.put(BaselineService.CTX_DEVIATION, 40.0);
        return r;
    }

    @Test
    @DisplayName("#6 kritik bant aşımı → kalemin alarm seviyesi CRITICAL; uyarı aşımı izlemenin kendi seviyesinde kalır")
    void criticalBandRaisesLevel() {
        HttpMonitor m = R33SweepHarness.http(1, "https://pay.example.com/");
        m.setAlertLevel("HIGH");
        var crit = h.scheduler.httpSlowSweepItem(m, verdictCtx(500, "critical"));
        assertThat(crit.ctxExtra().get("alert_level")).isEqualTo("CRITICAL");
        var warn = h.scheduler.httpSlowSweepItem(m, verdictCtx(200, "warning"));
        assertThat(warn.ctxExtra().get("alert_level")).as("uyarı: izlemenin seviyesi (sabit yolla aynı)").isEqualTo("HIGH");
        assertThat(warn.up()).isFalse();
    }

    @Test
    @DisplayName("#6 açık UYARI bant alarmı + kritik aşım → gerçek MOS tırmanma yoluna KRİTİK bağlamla gider (ESCALATION)")
    void criticalBandEscalatesOpenAlarm() throws Exception {
        R33SweepHarness real = new R33SweepHarness(true);
        ReflectionTestUtils.setField(real.scheduler, "baselineService", baseline);
        HttpMonitor m = R33SweepHarness.http(1, "https://pay.example.com/");
        AlertEvent open = new AlertEvent();
        open.setId(77L);
        open.setDomain(m.getUrl());
        open.setAlertType(EscalationService.TYPE_HTTP_SLOW);
        open.setAlertLevel("WARNING");
        open.setTeamId(m.getTeamId());
        open.setResolved(false);
        open.setCreatedAt("2026-11-13T06:00:00");
        open.setContextJson("{\"monitor_id\":1,\"team_id\":" + m.getTeamId() + "}");
        AlertEventRepository alerts = (AlertEventRepository) ReflectionTestUtils.getField(real.outage, "alertEventRepo");
        when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));

        var item = real.scheduler.httpSlowSweepItem(m, verdictCtx(500, "critical"));
        real.outage.handleSweepResults(EscalationService.TYPE_HTTP_SLOW, List.of(item));

        verify(real.escalation).processConfirmedOutage(eq(m.getUrl()), eq(EscalationService.TYPE_HTTP_SLOW), anyString(),
                argThat(ctx -> "CRITICAL".equals(ctx.get("alert_level"))));
        verify(real.escalation, never()).processConfirmedOutage(eq(m.getUrl()), eq(EscalationService.TYPE_HTTP_SLOW), anyString(),
                argThat(ctx -> !"CRITICAL".equals(ctx.get("alert_level"))));
    }
}
