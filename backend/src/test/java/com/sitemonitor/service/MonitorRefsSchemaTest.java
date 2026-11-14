package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link MonitorSparklineService#monitorRefs} GERÇEK şema üstünde (Hibernate'in varlıklardan kurduğu tablolar; 2026-11-14,
 * Kişisel Panom "7 günlük erişilebilirlik"): sorgu dokuz izleme tablosunun HEPSİNDE {@code id, team_id, name, active}
 * sütunlarını, Port'ta ayrıca {@code standalone, host}, DNS'te {@code standalone, domain} sütunlarını okur — biri bir tabloda
 * yoksa kutu üretimde sürekli 503 verirdi; elle kurulan tek tabloluk birim testi bunu göremez. Bu test varlıklardan birine
 * sütun adı değişikliği gelirse kırmızıya döner. Envanter TÜREVİ Port / DNS satırı envanterin takımını taşır (inceleme
 * 2026-11-14) — "türev mi" sorusunu {@code standalone} cevaplar, takım değil.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MonitorRefsSchemaTest {

    @Autowired DataSource dataSource;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;

    @Test
    @DisplayName("dokuz izleme tablosunun hepsinde sorgu geçerli (id, team_id, name, active; Port / DNS: standalone + host / alan adı); türev satır takımını taşır")
    void everyMonitorTableHasTheColumns() {
        MonitorSparklineService svc = new MonitorSparklineService(new JdbcTemplate(dataSource));
        assertThat(MonitorSparklineService.KINDS).hasSize(9);
        for (String type : MonitorSparklineService.KINDS.keySet()) {
            assertThatCode(() -> svc.monitorRefs(type)).as(type).doesNotThrowAnyException();
        }

        HttpMonitor paused = new HttpMonitor();
        paused.setName("Ödeme API");
        paused.setUrl("https://odeme.example.com/health");
        paused.setTeamId(5L);
        paused.setActive(false);
        long httpId = httpRepo.save(paused).getId();

        PortMonitor derived = new PortMonitor();   // envanter türevi satır: envanterin takımını taşır (MonitoringController)
        derived.setName("odeme.example.com:443");
        derived.setHost("odeme.example.com");
        derived.setPort(443);
        derived.setTeamId(5L);
        long portId = portRepo.save(derived).getId();

        DnsMonitor own = new DnsMonitor();         // kullanıcının eklediği bağımsız DNS satırı
        own.setName("Ödeme DNS");
        own.setDomain("odeme.example.com");
        own.setRecordType("A");
        own.setStandalone(true);
        own.setTeamId(9L);
        long dnsId = dnsRepo.save(own).getId();
        try {
            Map<Long, MonitorSparklineService.MonitorRef> http = svc.monitorRefs("http");
            assertThat(http.get(httpId)).isEqualTo(new MonitorSparklineService.MonitorRef(5L, "Ödeme API", false));
            assertThat(http.get(httpId).standalone()).isNull();   // Port / DNS dışında envanter kuralı yok
            Map<Long, MonitorSparklineService.MonitorRef> port = svc.monitorRefs("port");
            assertThat(port.get(portId)).isEqualTo(new MonitorSparklineService.MonitorRef(5L, "odeme.example.com:443", true, false, "odeme.example.com"));
            Map<Long, MonitorSparklineService.MonitorRef> dns = svc.monitorRefs("dns");
            assertThat(dns.get(dnsId)).isEqualTo(new MonitorSparklineService.MonitorRef(9L, "Ödeme DNS", true, true, "odeme.example.com"));
        } finally {
            httpRepo.deleteById(httpId);
            portRepo.deleteById(portId);
            dnsRepo.deleteById(dnsId);
        }
    }
}
