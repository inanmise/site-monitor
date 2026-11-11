package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.service.baseline.BaselineSampleStore;
import com.sitemonitor.service.baseline.BaselineSchemaPatch;
import com.sitemonitor.service.baseline.BaselineService;
import com.sitemonitor.service.baseline.BaselineTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP_SLOW'un "öğrenen bant" kipinin SchedulerService builder'ına bağlanması (2026-11-12). {@code httpSlowSweepItem} içine
 * enjekte edilen {@code baseline_*} anahtarlarının doğru SweepItem'a (ya da kaleme/null'a) dönüştüğünü doğrudan doğrular;
 * örnek yazımı + verdict {@code BaselineServiceTest}'te, "kapalıyken birebir aynı" {@code ClassicSweepGoldenTest} /
 * {@code ScheduledResultRoundTripTest}'te.
 */
class BaselineHttpSweepTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    R33SweepHarness h;
    MonitorBaselineConfigRepository configRepo;

    @BeforeEach
    void setup() throws Exception {
        h = new R33SweepHarness();
        BaselineSampleStore store = new BaselineSampleStore(mp.jdbc());
        new BaselineSchemaPatch(mp.jdbc(), store).apply();
        configRepo = mock(MonitorBaselineConfigRepository.class);
        MonitorBaselineConfig cfg = new MonitorBaselineConfig();
        cfg.setMonitorType(BaselineTypes.HTTP);
        cfg.setMonitorId(1L);
        cfg.setEnabled(true);
        when(configRepo.findByEnabledTrue()).thenReturn(List.of(cfg));
        BaselineService baseline = new BaselineService(configRepo, store);
        ReflectionTestUtils.setField(baseline, "globalEnabled", true);
        ReflectionTestUtils.setField(baseline, "cacheMs", 30_000L);
        ReflectionTestUtils.setField(h.scheduler, "baselineService", baseline);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    private static Map<String, Object> result(long respMs, boolean on, Boolean evaluated, String level) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "up");
        r.put("response_ms", respMs);
        if (on) {
            r.put(BaselineService.CTX_ON, true);
            r.put(BaselineService.CTX_EVALUATED, evaluated);
            if (level != null) r.put(BaselineService.CTX_LEVEL, level);
            r.put(BaselineService.CTX_MEAN, 100.0);
            r.put(BaselineService.CTX_HIGH, 105.0);
            r.put(BaselineService.CTX_LOW, 95.0);
            r.put(BaselineService.CTX_DEVIATION, 40.0);
        }
        return r;
    }

    private HttpMonitor monitor(boolean fixedSlow, Integer fixedThreshold) {
        HttpMonitor m = R33SweepHarness.http(1, "https://ornek.test/");
        m.setSlowResponseEnabled(fixedSlow);
        m.setSlowThresholdMs(fixedThreshold);
        return m;
    }

    @Test
    void bant_disi_yavas_kalem_uretir_kararli_detail() {
        var item = h.scheduler.httpSlowSweepItem(monitor(false, null),
                result(500, true, true, "critical"));
        assertThat(item).isNotNull();
        assertThat(item.alertType()).isEqualTo(EscalationService.TYPE_HTTP_SLOW);
        assertThat(item.detail()).isEqualTo(BaselineService.SLOW_DETAIL);   // KARARLI anahtar
        assertThat(item.up()).isFalse();                                    // yavaş = sağlıklı değil
    }

    @Test
    void bant_ici_saglikli_kalem() {
        var item = h.scheduler.httpSlowSweepItem(monitor(false, null),
                result(101, true, true, "none"));
        assertThat(item).isNotNull();
        assertThat(item.up()).isTrue();   // bant içinde → sağlıklı (açık alarm kapanır)
        assertThat(item.detail()).isEqualTo(BaselineService.SLOW_DETAIL);
    }

    @Test
    void taban_kuruluyor_kalem_yok() {
        // evaluated=false → henüz karar yok → kalem YOK (D-7: açık alarm sahte kapanmaz)
        assertThat(h.scheduler.httpSlowSweepItem(monitor(false, null),
                result(500, true, false, null))).isNull();
    }

    @Test
    void baseline_kapali_sabit_esik_yolu_korunur() {
        // CTX_ON yok → sabit eşik: slowResponseEnabled açık, 500 > 300 → yavaş kalem (bugünkü davranış)
        var item = h.scheduler.httpSlowSweepItem(monitor(true, 300), result(500, false, null, null));
        assertThat(item).isNotNull();
        assertThat(item.detail()).isEqualTo("> 300 ms");   // sabit eşik metni
        assertThat(item.up()).isFalse();
    }

    @Test
    void baseline_kapali_sabit_esik_kapali_kalem_yok() {
        // CTX_ON yok + slowResponseEnabled kapalı → kalem yok (bugünkü davranış)
        assertThat(h.scheduler.httpSlowSweepItem(monitor(false, null), result(500, false, null, null))).isNull();
    }
}
