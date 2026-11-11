package com.sitemonitor.service.baseline;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BaselineConfigService} — doğrulama (sınır dışı / tutarsız / eksik), kaydet (geçiş tespiti), kapatmada örnek temizle.
 * Mockito repo + gerçek H2 deposu.
 */
class BaselineConfigServiceTest {

    private static final String HTTP = BaselineTypes.HTTP;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    MonitorBaselineConfigRepository repo;
    BaselineSampleStore store;
    BaselineService baselineService;
    BaselineConfigService svc;

    @BeforeEach
    void setup() {
        repo = mock(MonitorBaselineConfigRepository.class);
        when(repo.findByMonitorTypeAndMonitorId(any(), any())).thenReturn(Optional.empty());
        store = new BaselineSampleStore(mp.jdbc());
        new BaselineSchemaPatch(mp.jdbc(), store).apply();
        baselineService = new BaselineService(repo, store);
        ReflectionTestUtils.setField(baselineService, "globalEnabled", true);
        ReflectionTestUtils.setField(baselineService, "cacheMs", 30_000L);
        svc = new BaselineConfigService(repo, store, baselineService);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void enabled_bayragi_zorunlu() {
        var r = svc.save(HTTP, 1, body());
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).containsKey("enabled");
    }

    @Test
    void sinir_disi_deger_alan_hatasi() {
        var r = svc.save(HTTP, 1, body("enabled", true, "k_warning", 0.1, "window", 5));
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).containsKeys("k_warning", "window");
    }

    @Test
    void kritik_uyaridan_kucuk_hata() {
        var r = svc.save(HTTP, 1, body("enabled", true, "k_warning", 4.0, "k_critical", 2.0));
        assertThat(r.errors()).containsKey("k_critical");
    }

    @Test
    void gecersiz_yon_ve_mevsim() {
        var r = svc.save(HTTP, 1, body("enabled", true, "direction", "sideways", "season", "yearly"));
        assertThat(r.errors()).containsKeys("direction", "season");
    }

    @Test
    void min_ornek_pencereyi_asamaz() {
        var r = svc.save(HTTP, 1, body("enabled", true, "window", 12, "min_samples", 50));
        assertThat(r.errors()).containsKey("min_samples");
    }

    @Test
    void ac_kaydet_gecis_ve_persist() {
        var r = svc.save(HTTP, 1, body("enabled", true));
        assertThat(r.ok()).isTrue();
        assertThat(r.wasEnabled()).isFalse();
        assertThat(r.nowEnabled()).isTrue();
        assertThat(r.transition()).isTrue();
        verify(repo).save(any(MonitorBaselineConfig.class));
    }

    @Test
    void kapat_ornekleri_siler_ve_gecis() {
        // Önce açık bir yapılandırma + örnekler var
        MonitorBaselineConfig existing = new MonitorBaselineConfig();
        existing.setMonitorType(HTTP);
        existing.setMonitorId(1L);
        existing.setEnabled(true);
        when(repo.findByMonitorTypeAndMonitorId(eq(HTTP), eq(1L))).thenReturn(Optional.of(existing));
        for (int i = 1; i <= 5; i++) store.append(HTTP, 1, 1000L + i, 100, false);

        var r = svc.save(HTTP, 1, body("enabled", false));
        assertThat(r.ok()).isTrue();
        assertThat(r.wasEnabled()).isTrue();
        assertThat(r.nowEnabled()).isFalse();
        assertThat(r.transition()).isTrue();
        assertThat(store.count(HTTP, 1)).isZero();   // kapatıldı → örnekler silindi
    }

    @Test
    void view_ontanimli_dondurur() {
        Map<String, Object> v = svc.view(HTTP, 1);
        assertThat(v.get("enabled")).isEqualTo(false);
        assertThat(v.get("defaults")).isEqualTo(true);
        assertThat(v.get("direction")).isEqualTo(BaselineParams.DIRECTION_UP);
        assertThat(v.get("window")).isEqualTo(30);
        assertThat(v.get("sample_count")).isEqualTo(0);
    }
}
