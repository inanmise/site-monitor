package com.sitemonitor.service.baseline;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.service.baseline.BaselineMath.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BaselineService} — pod başına düzenleme: açık yapılandırma kararı, zamanlanmış örnek YAZAR (recheck yazmaz), taban
 * kurulur, kapalıyken null. Gerçek H2 deposu + Mockito yapılandırma repo'su.
 */
class BaselineServiceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String HTTP = BaselineTypes.HTTP;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    MonitorBaselineConfigRepository configRepo;
    BaselineSampleStore store;
    BaselineService svc;

    @BeforeEach
    void setup() {
        store = new BaselineSampleStore(mp.jdbc());
        new BaselineSchemaPatch(mp.jdbc(), store).apply();
        configRepo = mock(MonitorBaselineConfigRepository.class);
        when(configRepo.findByEnabledTrue()).thenReturn(List.of());
        svc = new BaselineService(configRepo, store);
        ReflectionTestUtils.setField(svc, "globalEnabled", true);
        ReflectionTestUtils.setField(svc, "cacheMs", 30_000L);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    private static MonitorBaselineConfig cfg(long id, boolean enabled) {
        MonitorBaselineConfig c = new MonitorBaselineConfig();
        c.setMonitorType(HTTP);
        c.setMonitorId(id);
        c.setEnabled(enabled);
        return c;   // boş parametreler → öntanımlı (yukarı, k 2,5/3,5, pencere 30, min 10)
    }

    private void enable(long id) {
        when(configRepo.findByEnabledTrue()).thenReturn(List.of(cfg(id, true)));
        svc.invalidate();
    }

    @Test
    void kapali_izleme_null_doner() {
        assertThat(svc.evaluateScheduled(HTTP, 1, 500, T0, 60)).isNull();   // yapılandırma yok
        assertThat(store.count(HTTP, 1)).isZero();                          // örnek de yazılmaz
    }

    @Test
    void desteklenmeyen_tur_null() {
        when(configRepo.findByEnabledTrue()).thenReturn(List.of(cfg(1, true)));
        svc.invalidate();
        assertThat(svc.evaluateScheduled("domain", 1, 500, T0, 60)).isNull();
    }

    @Test
    void global_anahtar_kapali_null() {
        enable(1);
        ReflectionTestUtils.setField(svc, "globalEnabled", false);
        assertThat(svc.evaluateScheduled(HTTP, 1, 500, T0, 60)).isNull();
    }

    @Test
    void acik_izleme_taban_kurar_sonra_karar_verir() {
        enable(1);
        // İlk 9 kontrol: taban kuruluyor (karar yok) ama örnek YAZILIR
        for (int i = 1; i <= 9; i++) {
            Verdict v = svc.evaluateScheduled(HTTP, 1, 100, T0 + i * 60_000L, 60);
            assertThat(v).isNotNull();
            assertThat(v.evaluated()).isFalse();   // < min 10
        }
        assertThat(store.count(HTTP, 1)).isEqualTo(9);
        // 10. örnekten sonra taban dolu: yavaş bir değer kritik olur
        svc.evaluateScheduled(HTTP, 1, 100, T0 + 10 * 60_000L, 60);   // 10. örnek
        Verdict slow = svc.evaluateScheduled(HTTP, 1, 500, T0 + 11 * 60_000L, 60);
        assertThat(slow.evaluated()).isTrue();
        assertThat(slow.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
        assertThat(slow.mean()).isEqualTo(100.0);
        assertThat(slow.breached()).isTrue();
    }

    @Test
    void recheck_ornek_yazmaz() {
        enable(1);
        for (int i = 1; i <= 12; i++) svc.evaluateScheduled(HTTP, 1, 100, T0 + i * 60_000L, 60);
        int before = store.count(HTTP, 1);
        Verdict rv = svc.evaluateRecheck(HTTP, 1, 500, T0 + 13 * 60_000L, 60);
        assertThat(rv.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);   // bant aynı
        assertThat(store.count(HTTP, 1)).isEqualTo(before);              // YAZMADI
    }

    @Test
    void gecerli_deger_kendi_tabanina_girmez() {
        enable(1);
        // 12 örnek 100; sonra çok yavaş 500 — 500 kendi kararına dahil edilmez (checkedMs'ten önce okunur)
        for (int i = 1; i <= 12; i++) svc.evaluateScheduled(HTTP, 1, 100, T0 + i * 60_000L, 60);
        Verdict v = svc.evaluateScheduled(HTTP, 1, 500, T0 + 13 * 60_000L, 60);
        assertThat(v.mean()).isEqualTo(100.0);   // 500 bantta değil
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
    }

    @Test
    void ornek_temizle() {
        enable(1);
        for (int i = 1; i <= 5; i++) svc.evaluateScheduled(HTTP, 1, 100, T0 + i * 60_000L, 60);
        svc.clearSamples(HTTP, 1);
        assertThat(store.count(HTTP, 1)).isZero();
    }

    @Test
    void mevsimsel_gunluk_yalniz_ayni_slotu_degerlendirir() {
        long DAY = BaselineSampleStore.DAY_MS;
        MonitorBaselineConfig c = cfg(1, true);
        c.setSeason(BaselineParams.SEASON_DAILY);
        when(configRepo.findByEnabledTrue()).thenReturn(List.of(c));
        svc.invalidate();
        // 10 gün, her gün aynı saat (~08:00) ~100 → o slotun tabanı dolar
        for (int d = 0; d < 10; d++) svc.evaluateScheduled(HTTP, 1, 100, T0 + d * DAY, 300);
        // 11. gün aynı saat, yavaş 500 → kritik (mevsimsel slot tabanı 100)
        Verdict slot = svc.evaluateScheduled(HTTP, 1, 500, T0 + 10 * DAY, 300);
        assertThat(slot.evaluated()).isTrue();
        assertThat(slot.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
        assertThat(slot.mean()).isEqualTo(100.0);
        // Aynı gün BAŞKA saat (14:00): o slotta geçmiş yok → karar YOK (kuruluyor), sahte alarm yok
        Verdict other = svc.evaluateScheduled(HTTP, 1, 500, T0 + 10 * DAY + 6 * 3_600_000L, 300);
        assertThat(other.evaluated()).isFalse();
    }

    @Test
    void anyEnabled_yansitir() {
        assertThat(svc.anyEnabled()).isFalse();
        enable(1);
        assertThat(svc.anyEnabled()).isTrue();
    }

    @Test
    void ana_anahtar_canli_kapatinca_null() {
        enable(1);
        assertThat(svc.evaluateScheduled(HTTP, 1, 500, T0, 60)).isNotNull();   // ana anahtar açık
        com.sitemonitor.service.AppSettingsService settings = mock(com.sitemonitor.service.AppSettingsService.class);
        when(settings.getBoolean(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        ReflectionTestUtils.setField(svc, "appSettings", settings);
        assertThat(svc.evaluateScheduled(HTTP, 1, 500, T0 + 1000, 60)).isNull();   // CANLI kapandı → değerlendirme yok
        assertThat(svc.anyEnabled()).isFalse();
    }

    // ── 2026-11-13 hata taraması ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ilk_okuma_basarisiz_bilinmiyor_ve_5sn_sonra_yeniden_denenir() {
        when(configRepo.findByEnabledTrue())
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .thenReturn(List.of(cfg(1, true)));
        svc.invalidate();
        // #10: hiç okunamadı → BİLİNMİYOR (kapalı DEĞİL), config null (değerlendirme yok)
        assertThat(svc.state(HTTP, 1)).isEqualTo(BaselineService.State.UNKNOWN);
        assertThat(svc.config(HTTP, 1)).isNull();
        // Başarısız okuma 30 sn değil ~5 sn sonra yeniden denenir
        ReflectionTestUtils.setField(svc, "loadedAt", System.currentTimeMillis() - (BaselineService.FAILED_RETRY_MS + 1_000));
        assertThat(svc.state(HTTP, 1)).isEqualTo(BaselineService.State.ENABLED);
        assertThat(svc.state(HTTP, 2)).isEqualTo(BaselineService.State.DISABLED);   // başarılı okuma: yapılandırma yok = kapalı
    }

    @Test
    void okuma_hatasi_onceki_basarili_goruntuyu_korur() {
        enable(1);
        assertThat(svc.state(HTTP, 1)).isEqualTo(BaselineService.State.ENABLED);
        when(configRepo.findByEnabledTrue()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        svc.invalidate();
        assertThat(svc.state(HTTP, 1)).isEqualTo(BaselineService.State.ENABLED);   // bilinen görüntü korunur
        assertThat(svc.config(HTTP, 1)).isNotNull();
    }

    @Test
    void ana_anahtar_kapali_ya_da_desteklenmeyen_tur_kapali_sayilir() {
        when(configRepo.findByEnabledTrue()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        svc.invalidate();
        assertThat(svc.state("domain", 1)).isEqualTo(BaselineService.State.DISABLED);
        ReflectionTestUtils.setField(svc, "globalEnabled", false);
        assertThat(svc.state(HTTP, 1)).isEqualTo(BaselineService.State.DISABLED);   // kapalı anahtar: kesin kapalı (sabit yol)
        assertThat(svc.switchOn()).isFalse();
    }

    @Test
    void hazirlik_mevsimselde_yalniz_gecmis_gunlerin_ayni_saatini_sayar() {
        long now = System.currentTimeMillis(), DAY = BaselineSampleStore.DAY_MS;
        BaselineParams daily = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 30, 10, BaselineParams.SEASON_DAILY);
        // Son 30 dakikada 25 örnek (bugün) — "aynı saat" SAYILMAZ (#3) → taban hazır değil
        for (int i = 0; i < 25; i++) store.append(HTTP, 1, now - (i + 1) * 60_000L, 100, false);
        Verdict v = svc.readiness(HTTP, 1, daily);
        assertThat(v.evaluated()).isFalse();
        assertThat(v.samples()).isZero();
        // Dün aynı saatin ±30 dk'sında 12 örnek → hazır (12 örnek)
        for (int i = 0; i < 12; i++) store.append(HTTP, 1, now - DAY - (i + 1) * 60_000L, 100, false);
        Verdict ready = svc.readiness(HTTP, 1, daily);
        assertThat(ready.evaluated()).isTrue();
        assertThat(ready.samples()).isEqualTo(12);
    }

    @Test
    void budama_izleme_basina_sinir_ve_durdurma() {
        for (int i = 1; i <= 20; i++) store.append(HTTP, 1, T0 + i * 1000, 100, false);
        assertThat(svc.prune(() -> false).deleted()).isZero();  // 20 ≤ KEEP_PER_MONITOR
        assertThat(store.count(HTTP, 1)).isEqualTo(20);
        BaselineSampleStore.PruneResult stopped = store.prune(5, () -> true);   // durdur → hiçbir şey silinmez, yarıda
        assertThat(stopped.partial()).isTrue();
        assertThat(stopped.deleted()).isZero();
        assertThat(store.count(HTTP, 1)).isEqualTo(20);
    }
}
