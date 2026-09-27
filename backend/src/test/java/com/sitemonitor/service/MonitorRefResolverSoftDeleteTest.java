package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-09-27 (silinmiş ≠ duraklatılmış): alarm → izleme referansı çözümünde SİLİNMİŞ standalone Port/DNS satırı
 * yoktur. İndeks anahtar başına İLK satırı alır; eski (küçük id'li) silinmiş satır aynı host'u izleyen canlı
 * izlemeyi gölgeleyip "İzlemeye git" bağlantısını açılamayan (404) bir izlemeye götürüyordu.
 */
class MonitorRefResolverSoftDeleteTest {

    private static AlertEvent event(long id, String type, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setAlertType(type); e.setDomain(domain);
        return e;
    }

    @Test
    @DisplayName("Silinmiş (küçük id'li) satır canlı izlemeyi gölgelemez; yalnız silinmiş varsa bağlantı kurulmaz")
    void deletedRowsDoNotResolve() {
        PortMonitorRepository portRepo = mock(PortMonitorRepository.class);
        DnsMonitorRepository dnsRepo = mock(DnsMonitorRepository.class);
        PortMonitor deleted = new PortMonitor(); deleted.setId(1L); deleted.setName("eski"); deleted.setHost("svc.example.com");
        deleted.setDeletedAt("2026-09-01T00:00:00");
        PortMonitor live = new PortMonitor(); live.setId(2L); live.setName("canlı"); live.setHost("svc.example.com");
        when(portRepo.findAll()).thenReturn(List.of(deleted, live));
        DnsMonitor deletedDns = new DnsMonitor(); deletedDns.setId(3L); deletedDns.setName("eski dns"); deletedDns.setDomain("ns.example.com");
        deletedDns.setDeletedAt("2026-09-01T00:00:00");
        when(dnsRepo.findAll()).thenReturn(List.of(deletedDns));
        MonitorRefResolver r = new MonitorRefResolver(mock(HttpMonitorRepository.class), portRepo,
                mock(KeywordMonitorRepository.class), mock(PingMonitorRepository.class), dnsRepo,
                mock(DomainMonitorRepository.class), mock(PageMonitorRepository.class),
                mock(ScriptedMonitorRepository.class), mock(PageSpeedMonitorRepository.class));

        Map<Long, MonitorRefResolver.Ref> refs = r.resolve(List.of(
                event(10L, "PORT_DOWN", "svc.example.com"), event(11L, "DNS_FAILURE", "ns.example.com")));

        assertThat(refs.get(10L).monitorId()).as("canlı izleme").isEqualTo(2L);
        assertThat(refs.get(11L).monitorId()).as("yalnız silinmiş satır → bağlantı yok").isNull();
    }
}
