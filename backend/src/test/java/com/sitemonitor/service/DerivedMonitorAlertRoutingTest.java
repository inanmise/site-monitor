package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O2 (2026-09-28): ≤20.89.0'da açılmış envanter TÜREVİ Port/DNS alarmları bağlamda kopya {@code team_id} taşıdığı için
 * "bağımsız" sayılıyor, UG'ye ve UG'nin kişilerine gitmiyor, kopya bayatsa eski takıma gidiyordu. Geçiş yalnız türev
 * satırın açık olaylarını envanter yönlendirmesine alır; bağımsız / belirsiz / kapalı olaya dokunmaz; idempotenttir.
 */
class DerivedMonitorAlertRoutingTest {

    private final AlertEventRepository alertRepo = mock(AlertEventRepository.class);
    private final PortMonitorRepository portRepo = mock(PortMonitorRepository.class);
    private final DnsMonitorRepository dnsRepo = mock(DnsMonitorRepository.class);
    private final CertificateInventoryRepository invRepo = mock(CertificateInventoryRepository.class);
    private final DerivedMonitorAlertRouting routing =
            new DerivedMonitorAlertRouting(alertRepo, portRepo, dnsRepo, invRepo, new ObjectMapper());

    private static AlertEvent ev(long id, String type, String domain, Long team, String ctx) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setAlertType(type); e.setDomain(domain); e.setTeamId(team); e.setResolved(false); e.setContextJson(ctx);
        return e;
    }

    @Test
    @DisplayName("O2: türev Port/DNS açık alarmı → bağlamdan team_id çıkar, damga canlı SY; D4: işaretsiz eski bağımsız → işaret; doğru/satırsız/sertifika olaya dokunulmaz; ikinci koşu no-op")
    void migratesOnlyDerivedOpenPortDnsAlerts() {
        PortMonitor derivedPort = new PortMonitor(); derivedPort.setId(1L); derivedPort.setStandalone(false);
        PortMonitor standalonePort = new PortMonitor(); standalonePort.setId(2L); standalonePort.setStandalone(true);
        DnsMonitor derivedDns = new DnsMonitor(); derivedDns.setId(3L); derivedDns.setStandalone(null);   // eski türev satır (NULL)
        when(portRepo.findById(1L)).thenReturn(Optional.of(derivedPort));
        when(portRepo.findById(2L)).thenReturn(Optional.of(standalonePort));
        when(dnsRepo.findById(3L)).thenReturn(Optional.of(derivedDns));
        when(portRepo.findById(9L)).thenReturn(Optional.empty());
        CertificateInventory inv = new CertificateInventory(); inv.setTeamId(5L); inv.setUgTeamId(6L);
        when(invRepo.findByDomain(any())).thenReturn(Optional.of(inv));

        AlertEvent oldDerivedPort = ev(10, "PORT_DOWN", "a.example.com", 4L, "{\"port\":443,\"monitor_id\":1,\"team_id\":4}");
        AlertEvent standaloneEv = ev(11, "PORT_DOWN", "b.example.com", 7L, "{\"port\":8443,\"monitor_id\":2,\"team_id\":7}");
        AlertEvent markedEv = ev(12, "PORT_SLOW", "c.example.com", 7L, "{\"monitor_id\":1,\"team_id\":7,\"standalone\":true}");
        AlertEvent derivedDnsEv = ev(13, "DNS_CHANGED", "d.example.com", 4L, "{\"record_type\":\"A\",\"monitor_id\":3,\"team_id\":4}");
        AlertEvent noRow = ev(14, "PORT_DOWN", "e.example.com", 4L, "{\"monitor_id\":9,\"team_id\":4}");
        AlertEvent cert = ev(15, "EXPIRY", "f.example.com", 4L, "{\"team_id\":4,\"monitor_id\":1}");
        AlertEvent noMonitorId = ev(16, "DNS_FAILURE", "g.example.com", 4L, "{\"team_id\":4}");
        // D4: işaretsiz ESKİ bağımsız olay (team_id anlık görüntüde yok) → işaret + satırın takımı
        standalonePort.setTeamId(7L);
        AlertEvent legacyStandalone = ev(17, "PORT_DOWN", "h.example.com", null, "{\"port\":8443,\"monitor_id\":2}");
        AlertEvent derivedClean = ev(18, "PORT_DOWN", "i.example.com", 5L, "{\"port\":443,\"monitor_id\":1}");   // zaten doğru
        when(alertRepo.findAllOpenOrderBySeverity()).thenReturn(
                List.of(oldDerivedPort, standaloneEv, markedEv, derivedDnsEv, noRow, cert, noMonitorId, legacyStandalone, derivedClean));

        assertThat(routing.migrateOpenDerivedAlerts()).isEqualTo(3);
        assertThat(legacyStandalone.getContextJson()).contains("\"standalone\":true").contains("\"team_id\":7");
        assertThat(legacyStandalone.getTeamId()).isEqualTo(7L);
        assertThat(EscalationService.isStandaloneEvent(legacyStandalone)).isTrue();
        assertThat(derivedClean.getContextJson()).isEqualTo("{\"port\":443,\"monitor_id\":1}");
        assertThat(oldDerivedPort.getTeamId()).isEqualTo(5L);
        assertThat(oldDerivedPort.getContextJson()).doesNotContain("team_id").contains("\"monitor_id\":1");
        assertThat(EscalationService.isStandaloneEvent(oldDerivedPort)).isFalse();   // artık envanter yönlendirmesi
        assertThat(derivedDnsEv.getTeamId()).isEqualTo(5L);
        assertThat(standaloneEv.getContextJson()).contains("team_id");
        assertThat(markedEv.getContextJson()).contains("team_id");
        assertThat(noRow.getContextJson()).contains("team_id");
        assertThat(cert.getContextJson()).contains("team_id");
        assertThat(noMonitorId.getContextJson()).contains("team_id");
        verify(alertRepo).save(oldDerivedPort);
        verify(alertRepo).save(derivedDnsEv);
        verify(alertRepo).save(legacyStandalone);
        verify(alertRepo, times(3)).save(any());

        clearInvocations(alertRepo);
        assertThat(routing.migrateOpenDerivedAlerts()).isZero();   // idempotent
        verify(alertRepo, never()).save(any());
    }
}
