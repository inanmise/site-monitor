package com.sitemonitor.service;

import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.PageCheck;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uygulama katmanı satırlarının İSTEMLİ doldurulması (HSTS, karışık içerik).
 *
 * <p>Kullanıcı bildirimi (2026-08-23): "Check now dediğimde de not checked diyor" — satırlar
 * tanımlıydı ama hiçbir şey onları DOLDURMUYORDU. Bu testler doldurma yolunu ve sınırlarını pinler:
 * mevcut sayfa izlemesi varsa yeniden HTML çekilmemeli, erişilemeyen hedef UNKNOWN olmalı (FAIL
 * değil) ve bir probe patlarsa diğeri yine koşmalı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateAppLayerProbeTest {

    @Mock HstsDiagnosticsService hstsService;
    @Mock PageCheckerService pageChecker;
    @Mock PageMonitorRepository pageMonitorRepo;
    @Mock PageCheckRepository pageCheckRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @InjectMocks CertificateAppLayerProbe probe;

    private static PageCheckerService.PageCheckResult pageResult(boolean reachable, int mixed) {
        return new PageCheckerService.PageCheckResult("OK", reachable, 200, 12L,
                10, 0, 0, mixed, 1, null, null, null, List.of());
    }

    private LatestCheck stored() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("a.example.com");
        when(latestCheckRepo.findById("a.example.com")).thenReturn(Optional.of(lc));
        return lc;
    }

    // ── HSTS ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ENFORCED → açık; ABSENT ve NOT_ENFORCED → eksik; bağlantı hatası → UNKNOWN")
    void hstsVerdictMapping() {
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "ENFORCED"));
        assertThat(probe.checkHsts("a.example.com", 443, false)).isEqualTo("ENABLED");

        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "ABSENT"));
        assertThat(probe.checkHsts("a.example.com", 443, false)).isEqualTo("MISSING");

        // max-age=0 politikayı SİLER: başlık var diye "açık" saymak yanlış olurdu.
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "NOT_ENFORCED"));
        assertThat(probe.checkHsts("a.example.com", 443, false)).isEqualTo("MISSING");

        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "CONNECT_FAILED"));
        assertThat(probe.checkHsts("a.example.com", 443, false)).isEqualTo("UNKNOWN");
    }

    /**
     * İzlemenin vekil tercihi tanılamaya AYNEN iner.
     *
     * <p>Parametre yolda yutulursa tanılama kararı yalnız global yapılandırmadan türer;
     * {@code use_proxy=Hayır} olan bir hedef için sertifika kontrolü doğrudan bağlanıp başlığı
     * bulurken bu probe vekilden geçmeye çalışıp bağlanamaz ve satır "Doğrulanamadı" kalır.
     */
    @Test
    @DisplayName("use_proxy tercihi tanılamaya AYNEN geçer (yutulursa iki ekran ayrışır)")
    void forceProxyReachesDiagnostics() {
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean()))
                .thenReturn(Map.of("verdict", "ENFORCED"));

        probe.checkHsts("a.example.com", 443, false);
        verify(hstsService).diagnose("a.example.com", 443, false);

        probe.checkHsts("a.example.com", 443, true);
        verify(hstsService).diagnose("a.example.com", 443, true);
    }

    @Test
    @DisplayName("UNKNOWN'un GEREKÇESİ kaydedilir; sağlam sonuçta not TEMİZLENİR")
    void unknownStoresReason() {
        LatestCheck lc = stored();
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(
                Map.of("verdict", "CONNECT_FAILED", "error", "connect timed out",
                       "proxy_reason", "monitor_prefers_direct"));

        probe.refresh("a.example.com", 443, false);

        // "Doğrulanamadı" deyip nedenini söylememek kullanıcıyı tam olarak koda bakmaya zorluyordu.
        assertThat(lc.getHstsNote()).contains("connect timed out").contains("monitor_prefers_direct");

        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "ENFORCED"));
        probe.refresh("a.example.com", 443, false);
        // Eski gerekçe ASILI KALMAZ: sorun geçtikten sonra da görünmesi yanıltıcı olurdu.
        assertThat(lc.getHstsNote()).isNull();
    }

    // ── Karışık içerik ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Sayfa izlemesi VARSA onun sonucu kullanılır — yeniden HTML ÇEKİLMEZ")
    void prefersExistingPageMonitor() {
        PageMonitor m = new PageMonitor();
        m.setId(7L);
        PageCheck last = new PageCheck();
        last.setMixedContentCount(0);
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue("a.example.com")).thenReturn(List.of(m));
        when(pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(7L)).thenReturn(Optional.of(last));

        assertThat(probe.checkMixedContent("a.example.com", 443)).isEqualTo("CLEAN");
        verify(pageChecker, never()).scanMixedContent(anyString(), anyInt());
    }

    @Test
    @DisplayName("İzlemelerden HERHANGİ birinde karışık içerik varsa satır kirlidir")
    void anyMonitorWithMixedContentWins() {
        PageMonitor clean = new PageMonitor(); clean.setId(1L);
        PageMonitor dirty = new PageMonitor(); dirty.setId(2L);
        PageCheck ok = new PageCheck(); ok.setMixedContentCount(0);
        PageCheck bad = new PageCheck(); bad.setMixedContentCount(3);
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue("a.example.com"))
                .thenReturn(List.of(clean, dirty));
        when(pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(1L)).thenReturn(Optional.of(ok));
        when(pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(2L)).thenReturn(Optional.of(bad));

        assertThat(probe.checkMixedContent("a.example.com", 443)).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("Sayfa izlemesi YOKSA tek seferlik tarama koşar (443 dışı port URL'e yazılır)")
    void fallsBackToOnDemandScan() {
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 2));

        assertThat(probe.checkMixedContent("a.example.com", 8443)).isEqualTo("MIXED");
        verify(pageChecker).scanMixedContent("https://a.example.com:8443/", 8000);
    }

    @Test
    @DisplayName("443'te port URL'e YAZILMAZ (kanonik adres)")
    void defaultPortIsOmittedFromUrl() {
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 0));

        assertThat(probe.checkMixedContent("a.example.com", 443)).isEqualTo("CLEAN");
        verify(pageChecker).scanMixedContent("https://a.example.com/", 8000);
    }

    @Test
    @DisplayName("Sayfa ERİŞİLEMEZSE UNKNOWN — erişilemeyeni 'karışık içerik var' diye raporlamayız")
    void unreachablePageIsUnknown() {
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(false, 0));

        assertThat(probe.checkMixedContent("a.example.com", 443)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("İzleme var ama hiç kontrol sonucu yoksa taramaya düşer")
    void monitorWithoutResultsFallsBack() {
        PageMonitor m = new PageMonitor(); m.setId(7L);
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of(m));
        when(pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(anyLong())).thenReturn(Optional.empty());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 0));

        assertThat(probe.checkMixedContent("a.example.com", 443)).isEqualTo("CLEAN");
        verify(pageChecker).scanMixedContent(anyString(), anyInt());
    }

    // ── Kalıcılaştırma ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Sonuçlar tarihleriyle birlikte kaydedilir — sonraki açılışta 'kontrol edilmedi' demez")
    void resultsArePersistedWithTimestamps() {
        LatestCheck lc = stored();
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "ENFORCED"));
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 0));

        probe.refresh("a.example.com", 443, false);

        ArgumentCaptor<LatestCheck> cap = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestCheckRepo).save(cap.capture());
        LatestCheck saved = cap.getValue();
        assertThat(saved.getHstsStatus()).isEqualTo("ENABLED");
        assertThat(saved.getMixedContentStatus()).isEqualTo("CLEAN");
        assertThat(saved.getHstsAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(saved.getMixedContentAt()).isNotNull();
        assertThat(lc).isSameAs(saved);
    }

    @Test
    @DisplayName("Bir probe patlarsa DİĞERİ yine koşar ve UNKNOWN olarak kaydedilir")
    void oneFailingProbeDoesNotBlockTheOther() {
        stored();
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenThrow(new RuntimeException("ağ yok"));
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 0));

        probe.refresh("a.example.com", 443, false);

        ArgumentCaptor<LatestCheck> cap = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestCheckRepo).save(cap.capture());
        assertThat(cap.getValue().getHstsStatus()).isEqualTo("UNKNOWN");
        assertThat(cap.getValue().getMixedContentStatus()).isEqualTo("CLEAN");
    }

    @Test
    @DisplayName("Hiç kontrol edilmemiş domainde yazacak satır yok — çökmez, kaydetmez")
    void neverCheckedDomainIsSkipped() {
        when(latestCheckRepo.findById("yok.example.com")).thenReturn(Optional.empty());
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "ENFORCED"));
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 0));

        probe.refresh("yok.example.com", 443, false);

        verify(latestCheckRepo, never()).save(any());
    }

    @Test
    @DisplayName("Kaydetme patlarsa tazeleme yine tamamlanır (best-effort sözleşmesi)")
    void saveFailureIsSwallowed() {
        stored();
        when(hstsService.diagnose(anyString(), anyInt(), anyBoolean())).thenReturn(Map.of("verdict", "ABSENT"));
        when(pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(anyString())).thenReturn(List.of());
        when(pageChecker.scanMixedContent(anyString(), anyInt())).thenReturn(pageResult(true, 0));
        when(latestCheckRepo.save(any())).thenThrow(new RuntimeException("tablo kilitli"));

        probe.refresh("a.example.com", 443, false);   // istisna DIŞARI sızmamalı
    }
}
