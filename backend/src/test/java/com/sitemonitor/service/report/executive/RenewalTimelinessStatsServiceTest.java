package com.sitemonitor.service.report.executive;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.RenewalForecastService;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * İstatistikler zamanında yenileme oranı (2026-11-13, #11): aylık yönetici özetinin kuralı (dolmadan yenilenen ÷ (yenilemeler +
 * yenilenmeden dolanlar)) son 3 / 6 / 12 ayda; kapsam SY ya da UG takımı görülebilen aktif envanter; gruplar Vade Takvimi'nin
 * ortak belleğinden (ek sorgu yok).
 */
class RenewalTimelinessStatsServiceTest {

    static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private static Object[] g(String fp, String firstSeen, String notAfter) { return new Object[]{fp, firstSeen, notAfter}; }

    private RenewalTimelinessStatsService svc(RenewalForecastService forecast, CertificateInventoryRepository inv) {
        return new RenewalTimelinessStatsService(forecast, inv);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> window(Map<String, Object> out, int months) {
        return ((List<Map<String, Object>>) out.get("windows")).stream().filter(w -> Integer.valueOf(months).equals(w.get("months")))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("3 / 6 / 12 ay: dolmadan / dolduktan sonra / son dakika yenileme, yenilenmeden dolan; görülemeyen takım dışarıda")
    void windowsAndScope() {
        RenewalForecastService forecast = mock(RenewalForecastService.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        when(inv.findActiveDomainTeams()).thenReturn(List.of(
                new Object[]{"d1", 1L, null}, new Object[]{"d2", 1L, null}, new Object[]{"d3", 2L, 1L},
                new Object[]{"d4", 9L, null}, new Object[]{"d5", 1L, null}));
        when(forecast.sharedRenewalGroups()).thenReturn(Map.<String, List<Object[]>>of(
                // Şubat'ta, bitişe 9 gün kala (dolmadan) — yalnız 12 aylık pencerede
                "d1", List.<Object[]>of(g("f1", "2025-12-01T10:00:00", "2026-03-01T00:00:00"), g("f2", "2026-02-20T00:00:00", "2027-03-01T00:00:00")),
                // Eylül'de, dolduktan 4 gün sonra
                "d2", List.<Object[]>of(g("f1", "2025-09-01T10:00:00", "2026-09-01T00:00:00"), g("f2", "2026-09-05T00:00:00", "2027-09-01T00:00:00")),
                // Ekim'de doldu, yenilenmedi (UG takımı 1 → görülür)
                "d3", List.<Object[]>of(g("f1", "2025-10-15T10:00:00", "2026-10-15T00:00:00")),
                // Görülemeyen takım
                "d4", List.<Object[]>of(g("f1", "2025-10-01T10:00:00", "2026-10-01T00:00:00"), g("f2", "2026-09-01T00:00:00", "2027-10-01T00:00:00")),
                // Ekim'de, bitişe 3 gün kala (son dakika, yine de dolmadan)
                "d5", List.<Object[]>of(g("f1", "2025-10-20T10:00:00", "2026-10-20T00:00:00"), g("f2", "2026-10-17T00:00:00", "2027-10-20T00:00:00"))));
        // Saklama 400 gün: hiçbir pencere kırpılmaz (kırpma ayrı testte)
        Map<String, Object> out = svc(forecast, inv).compute(t -> t != null && t == 1L, NOW, 400);
        assertThat(out).containsEntry("domains", 4).containsEntry("retention_days", 400);
        assertThat(window(out, 12)).containsEntry("clamped", false);
        assertThat(window(out, 3)).containsEntry("renewals", 2).containsEntry("before_expiry", 1).containsEntry("after_expiry", 1)
                .containsEntry("last_minute", 1).containsEntry("lapses", 1).containsEntry("on_time_pct", 33.3);
        assertThat(window(out, 6)).containsEntry("renewals", 2).containsEntry("lapses", 1).containsEntry("on_time_pct", 33.3);
        assertThat(window(out, 12)).containsEntry("renewals", 3).containsEntry("before_expiry", 2).containsEntry("lapses", 1)
                .containsEntry("on_time_pct", 50.0);
    }

    @Test
    @DisplayName("pencerede yenileme / dolum yok → oran null (asla %0 ya da %100 uydurulmaz); grup okuması düşerse fırlar")
    void emptyAndFailure() {
        RenewalForecastService forecast = mock(RenewalForecastService.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        when(inv.findActiveDomainTeams()).thenReturn(List.<Object[]>of(new Object[]{"d1", 1L, null}));
        when(forecast.sharedRenewalGroups()).thenReturn(Map.<String, List<Object[]>>of("d1", List.<Object[]>of(g("f1", "2026-01-01T00:00:00", "2027-01-01T00:00:00"))));
        Map<String, Object> out = svc(forecast, inv).compute(t -> true, NOW);
        assertThat(window(out, 12)).containsEntry("renewals", 0).containsEntry("lapses", 0);
        assertThat(window(out, 12).get("on_time_pct")).isNull();

        when(forecast.sharedRenewalGroups()).thenThrow(new RuntimeException("db"));
        assertThatThrownBy(() -> svc(forecast, inv).compute(t -> true, NOW)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("bellek: aynı kapsam anahtarı ikinci çağrıda hesaplamaz")
    void memoised() {
        RenewalForecastService forecast = mock(RenewalForecastService.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        when(inv.findActiveDomainTeams()).thenReturn(List.of());
        when(forecast.sharedRenewalGroups()).thenReturn(Map.of());
        RenewalTimelinessStatsService s = svc(forecast, inv);
        s.cacheMs = 60_000L;
        s.build(t -> true, "ALL");
        s.build(t -> true, "ALL");
        verify(inv, times(1)).findActiveDomainTeams();
    }

    @Test
    @DisplayName("veri saklama sınırı: saklama 90 gün → 3 / 6 / 12 ay kırpılır (etkin gün 90), oran YALNIZ kapsanan pencereden; 120 gün → 3 ay tam")
    void windowsClampedToRetention() {
        RenewalForecastService forecast = mock(RenewalForecastService.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        when(inv.findActiveDomainTeams()).thenReturn(List.of(
                new Object[]{"d1", 1L, null}, new Object[]{"d2", 1L, null}, new Object[]{"d3", 1L, null}, new Object[]{"d5", 1L, null}));
        when(forecast.sharedRenewalGroups()).thenReturn(Map.<String, List<Object[]>>of(
                // Şubat yenilemesi: saklama penceresinin (Ağustos ortası) dışında — 12 ay kırpılınca SAYILMAZ
                "d1", List.<Object[]>of(g("f1", "2025-12-01T10:00:00", "2026-03-01T00:00:00"), g("f2", "2026-02-20T00:00:00", "2027-03-01T00:00:00")),
                "d2", List.<Object[]>of(g("f1", "2025-09-01T10:00:00", "2026-09-01T00:00:00"), g("f2", "2026-09-05T00:00:00", "2027-09-01T00:00:00")),
                "d3", List.<Object[]>of(g("f1", "2025-10-15T10:00:00", "2026-10-15T00:00:00")),
                "d5", List.<Object[]>of(g("f1", "2025-10-20T10:00:00", "2026-10-20T00:00:00"), g("f2", "2026-10-17T00:00:00", "2027-10-20T00:00:00"))));
        Map<String, Object> out = svc(forecast, inv).compute(t -> true, NOW, 90);
        assertThat(out).containsEntry("retention_days", 90);
        for (int m : RenewalTimelinessStatsService.WINDOWS) {
            assertThat(window(out, m)).as(m + " ay").containsEntry("clamped", true).containsEntry("effective_days", 90L)
                    .containsEntry("from", "2026-08-15T09:00:00");
        }
        // 12 ay kırpılmadan d1'in Şubat yenilemesi oranı %50'ye çıkarırdı; kapsanan 90 günde 2 yenileme (1 dolmadan) + 1 dolum
        assertThat(window(out, 12)).containsEntry("renewals", 2).containsEntry("before_expiry", 1).containsEntry("lapses", 1)
                .containsEntry("on_time_pct", 33.3).containsEntry("nominal_from", "2025-11-13T09:00:00");

        Map<String, Object> wide = svc(forecast, inv).compute(t -> true, NOW, 120);
        assertThat(window(wide, 3)).containsEntry("clamped", false).containsEntry("effective_days", 92L);
        assertThat(window(wide, 6)).containsEntry("clamped", true).containsEntry("effective_days", 120L);
    }

    @Test
    @DisplayName("saklama günü CANLI okunur (gece temizliğinin değeri) ve bellek anahtarında durur — değişince yeniden hesaplanır")
    void retentionReadLiveAndKeyed() {
        RenewalForecastService forecast = mock(RenewalForecastService.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        when(inv.findActiveDomainTeams()).thenReturn(List.of());
        when(forecast.sharedRenewalGroups()).thenReturn(Map.of());
        RetentionService retention = mock(RetentionService.class);
        when(retention.effectiveDays(any(RetentionPolicy.class))).thenReturn(90, 90, 365);
        RenewalTimelinessStatsService s = svc(forecast, inv);
        s.cacheMs = 60_000L;
        s.retentionService = retention;
        assertThat(s.build(t -> true, "ALL")).containsEntry("retention_days", 90);
        assertThat(s.build(t -> true, "ALL")).containsEntry("retention_days", 90);
        verify(inv, times(1)).findActiveDomainTeams();
        assertThat(s.build(t -> true, "ALL")).containsEntry("retention_days", 365);
        verify(inv, times(2)).findActiveDomainTeams();
        // Hizmet yok → politikanın varsayılanı (180)
        assertThat(svc(forecast, inv).retentionDays()).isEqualTo(180);
    }
}
