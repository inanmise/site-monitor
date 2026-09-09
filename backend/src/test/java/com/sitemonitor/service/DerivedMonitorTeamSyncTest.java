package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Envanter takım değişiminde TÜREV izlemelerin takımı da tazelenir.
 *
 * <p>Yetki kapısı ({@code MonitoringController.effectiveTeam}) ekranı ve istek yolunu anında
 * doğru yapar, ama zamanlayıcı oturumsuzdur ve alarm yönlendirmesini doğrudan sütundan okur.
 * Sütun tazelenmezse kesinti bildirimi ESKİ takıma gitmeye devam eder ve bunu kimse ekranda
 * göremez — bu yüzden iki katman da gerekli.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class DerivedMonitorTeamSyncTest {

    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock PortMonitorRepository portMonitorRepo;
    @InjectMocks DerivedMonitorTeamSync sync;

    private static DnsMonitor dns(long id, String domain, Long teamId, boolean standalone) {
        DnsMonitor m = new DnsMonitor();
        m.setId(id); m.setDomain(domain); m.setTeamId(teamId); m.setStandalone(standalone);
        return m;
    }

    private static PortMonitor port(long id, String host, Long teamId, boolean standalone) {
        PortMonitor m = new PortMonitor();
        m.setId(id); m.setHost(host); m.setTeamId(teamId); m.setStandalone(standalone);
        return m;
    }

    @Test
    @DisplayName("türev DNS ve Port satırları yeni takıma taşınır")
    void syncsDerivedRowsOfBothTypes() {
        when(dnsMonitorRepo.findAll()).thenReturn(List.of(dns(1, "a.example.com", 5L, false)));
        when(portMonitorRepo.findAll()).thenReturn(List.of(port(2, "a.example.com", 5L, false)));

        int n = sync.syncTeam("a.example.com", 9L);

        assertThat(n).isEqualTo(2);
        verify(dnsMonitorRepo).save(any());
        verify(portMonitorRepo).save(any());
    }

    @Test
    @DisplayName("STANDALONE satıra DOKUNULMAZ — takımı kullanıcının kendi seçimidir")
    void leavesStandaloneRowsAlone() {
        when(dnsMonitorRepo.findAll()).thenReturn(List.of(dns(1, "a.example.com", 5L, true)));
        when(portMonitorRepo.findAll()).thenReturn(List.of(port(2, "a.example.com", 5L, true)));

        assertThat(sync.syncTeam("a.example.com", 9L)).isZero();

        verify(dnsMonitorRepo, never()).save(any());
        verify(portMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("BAŞKA domain'in satırına dokunulmaz")
    void onlyTouchesTheGivenDomain() {
        when(dnsMonitorRepo.findAll()).thenReturn(List.of(dns(1, "other.example.com", 5L, false)));
        when(portMonitorRepo.findAll()).thenReturn(List.of());

        assertThat(sync.syncTeam("a.example.com", 9L)).isZero();
        verify(dnsMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("takım zaten doğruysa gereksiz yazma yapılmaz")
    void skipsRowsAlreadyOnTheTargetTeam() {
        when(dnsMonitorRepo.findAll()).thenReturn(List.of(dns(1, "a.example.com", 9L, false)));
        when(portMonitorRepo.findAll()).thenReturn(List.of());

        assertThat(sync.syncTeam("a.example.com", 9L)).isZero();
        verify(dnsMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("domain boşsa hiçbir repo okunmaz (savunmacı kısa devre)")
    void blankDomainIsANoOp() {
        assertThat(sync.syncTeam(null, 9L)).isZero();
        assertThat(sync.syncTeam("  ", 9L)).isZero();
        verify(dnsMonitorRepo, never()).findAll();
        verify(portMonitorRepo, never()).findAll();
    }
}
