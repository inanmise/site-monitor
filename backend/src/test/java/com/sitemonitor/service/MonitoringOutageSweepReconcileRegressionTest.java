package com.sitemonitor.service;

import com.sitemonitor.model.NetworkOutageEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression: ISSUE-001 — süpürme (DNS_FAILURE) kaynaklı ağ kesintisi kaydı, kesintiden SONRA uygulama yeniden
 * başlayınca hiç RESOLVED'a çekilmiyordu: bellek-içi {@code suppressionActive} boş geldiği için
 * {@code clearSuppression} "zaten kapalıydı" diye erken dönüyor, DB'deki ONGOING satır sonsuza dek açık kalıyordu
 * (canlıda 44+ saat "Still ongoing", DNS 11/11 Healthy).
 * Found by /qa on 2026-09-21
 * Report: .gstack/qa-reports/qa-report-localhost-2026-09-21-full.md
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringOutageSweepReconcileRegressionTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock EscalationService escalationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock AppSettingsService appSettings;
    @Mock NetworkOutageEventRepository networkOutageRepo;

    private MonitoringOutageService service;

    @BeforeEach
    void setUp() {
        service = newServiceAsIfFreshlyStarted();
    }

    /** Yeni örnek = yeniden başlatma: bellek-içi bastırma haritası boş. */
    private MonitoringOutageService newServiceAsIfFreshlyStarted() {
        MonitoringOutageService s = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate,
                dnsRecordRepo, dnsMonitorRepo, appSettings, networkOutageRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(s, "dnsAlertEnabled", true);
        ReflectionTestUtils.setField(s, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(s, "bulkMinErrors", 3);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        return s;
    }

    private static MonitoringOutageService.SweepItem healthy(String domain) {
        return new MonitoringOutageService.SweepItem(EscalationService.TYPE_DNS_FAILURE, domain, "A", true,
                null, null, null);
    }

    private static NetworkOutageEvent ongoing(long id) {
        NetworkOutageEvent ev = new NetworkOutageEvent();
        ev.setId(id);
        ev.setSource(EscalationService.TYPE_DNS_FAILURE);
        ev.setStatus("ONGOING");
        ev.setDetectedAt("2026-09-20T02:16:44");   // servisin ISO deseni: yyyy-MM-dd'T'HH:mm:ss (UTC, sonek yok)
        return ev;
    }

    @Test
    @DisplayName("Yeniden başlatma sonrası ilk sağlıklı DNS süpürmesi DB'de açık kalan ONGOING kaydı RESOLVED'a çeker")
    void staleOngoingOutage_isResolvedOnFirstHealthySweepAfterRestart() {
        when(networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(EscalationService.TYPE_DNS_FAILURE, "ONGOING"))
                .thenReturn(Optional.of(ongoing(42L)));

        service.handleSweepResults(EscalationService.TYPE_DNS_FAILURE, List.of(healthy("a.example.com"), healthy("b.example.com")));

        ArgumentCaptor<NetworkOutageEvent> saved = ArgumentCaptor.forClass(NetworkOutageEvent.class);
        verify(networkOutageRepo).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(42L);
        assertThat(saved.getValue().getStatus()).isEqualTo("RESOLVED");
        assertThat(saved.getValue().getResolvedAt()).isNotBlank();
        assertThat(saved.getValue().getDurationMs()).isNotNull().isPositive();
    }

    @Test
    @DisplayName("Uzlaştırma BİR kez: açık kayıt yoksa sonraki sağlıklı turlar DB'ye sorgu atmaz")
    void reconcileRunsOnce_noRepeatedQueriesWhenNothingOpen() {
        when(networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(anyString(), eq("ONGOING")))
                .thenReturn(Optional.empty());

        for (int i = 0; i < 5; i++) {
            service.handleSweepResults(EscalationService.TYPE_DNS_FAILURE, List.of(healthy("a.example.com")));
        }

        verify(networkOutageRepo, times(1)).findFirstBySourceAndStatusOrderByIdDesc(EscalationService.TYPE_DNS_FAILURE, "ONGOING");
        verify(networkOutageRepo, never()).save(any());
    }

    @Test
    @DisplayName("Aynı süreçte kesinti → toparlanma: kayıt yazılır ve sonraki sağlıklı turda kapatılır (mevcut davranış korunur)")
    void outageThenRecovery_sameProcess_stillOpensAndCloses() {
        // 3/3 DNS-sınıfı hata ⇒ bastırma + ONGOING kaydı
        MonitoringOutageService.SweepItem d1 = new MonitoringOutageService.SweepItem(EscalationService.TYPE_DNS_FAILURE,
                "a.example.com", "A", false, "a.example.com", null, null);   // çıplak host = UnknownHost
        MonitoringOutageService.SweepItem d2 = new MonitoringOutageService.SweepItem(EscalationService.TYPE_DNS_FAILURE,
                "b.example.com", "A", false, "b.example.com", null, null);
        MonitoringOutageService.SweepItem d3 = new MonitoringOutageService.SweepItem(EscalationService.TYPE_DNS_FAILURE,
                "c.example.com", "A", false, "c.example.com", null, null);
        service.handleSweepResults(EscalationService.TYPE_DNS_FAILURE, List.of(d1, d2, d3));

        ArgumentCaptor<NetworkOutageEvent> opened = ArgumentCaptor.forClass(NetworkOutageEvent.class);
        verify(networkOutageRepo).save(opened.capture());
        assertThat(opened.getValue().getStatus()).isEqualTo("ONGOING");
        assertThat(opened.getValue().getSource()).isEqualTo(EscalationService.TYPE_DNS_FAILURE);

        NetworkOutageEvent stored = opened.getValue();
        stored.setId(7L);
        when(networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(EscalationService.TYPE_DNS_FAILURE, "ONGOING"))
                .thenReturn(Optional.of(stored));

        service.handleSweepResults(EscalationService.TYPE_DNS_FAILURE, List.of(healthy("a.example.com"), healthy("b.example.com"), healthy("c.example.com")));

        verify(networkOutageRepo, times(2)).save(any());
        assertThat(stored.getStatus()).isEqualTo("RESOLVED");
        assertThat(stored.getResolvedAt()).isNotBlank();
    }
}
