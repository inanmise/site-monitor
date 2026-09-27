package com.sitemonitor.repository;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standalone DNS/Port izlemede SİLİNMİŞ ≠ DURAKLATILMIŞ (2026-09-27, kullanıcı kararı) — gerçek sorgular (H2) ve
 * tek seferlik geri doldurma. Eskiden silme ile duraklatma aynı durumu ({@code active=false}) yazıyordu; liste
 * sorgusu duraklatılanı da gizliyordu.
 *
 * <p>Testler {@code NOT_SUPPORTED} ile koşar: geri doldurma servisinin KENDİ transaction'ı (nişan + UPDATE aynı
 * işlemde) üretimdeki gibi commit olur.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:softdelete;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Import(StandaloneMonitorDeletionBackfill.class)
class StandaloneMonitorSoftDeleteTest {

    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired StandaloneMonitorDeletionBackfill backfill;

    private static final String NOW = "2026-09-27T10:00:00";   // yalnız saklanan damga; pencereyle karşılaştırılmaz

    private PortMonitor port(String host, boolean standalone, boolean active, String deletedAt, String group) {
        PortMonitor m = new PortMonitor();
        m.setName(host); m.setHost(host); m.setPort(443); m.setTeamId(3L);
        m.setStandalone(standalone); m.setActive(active); m.setDeletedAt(deletedAt); m.setGroupName(group);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return portRepo.save(m);
    }

    private DnsMonitor dns(String domain, boolean standalone, boolean active, String deletedAt) {
        DnsMonitor m = new DnsMonitor();
        m.setName(domain); m.setDomain(domain); m.setRecordType("A"); m.setTeamId(3L);
        m.setStandalone(standalone); m.setActive(active); m.setDeletedAt(deletedAt);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return dnsRepo.save(m);
    }

    @AfterEach
    void cleanUp() {
        portRepo.deleteAll();
        dnsRepo.deleteAll();
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Liste sorgusu: DURAKLATILMIŞ standalone GELİR, SİLİNMİŞ ve envanter-türevi GELMEZ (Port + DNS)")
    void listQuery_includesPaused_excludesDeleted() {
        port("aktif.example.com", true, true, null, null);
        port("durdu.example.com", true, false, null, null);
        port("silindi.example.com", true, false, NOW, null);
        port("turev.example.com", false, true, null, null);
        dns("aktif.example.com", true, true, null);
        dns("durdu.example.com", true, false, null);
        dns("silindi.example.com", true, false, NOW);

        assertThat(portRepo.findByStandaloneTrueAndDeletedAtIsNull()).extracting(PortMonitor::getHost)
                .containsExactlyInAnyOrder("aktif.example.com", "durdu.example.com");
        assertThat(dnsRepo.findByStandaloneTrueAndDeletedAtIsNull()).extracting(DnsMonitor::getDomain)
                .containsExactlyInAnyOrder("aktif.example.com", "durdu.example.com");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Mükerrer/canlandırma sorguları: duraklatılmış engel, silinmiş engel DEĞİL; DNS'te en son silinmiş canlandırılır")
    void duplicateAndReviveQueries() {
        port("durdu.example.com", true, false, null, null);
        port("silindi.example.com", true, false, NOW, null);
        assertThat(portRepo.existsByHostAndPortAndStandaloneTrueAndActiveFalseAndDeletedAtIsNull("durdu.example.com", 443)).isTrue();
        assertThat(portRepo.existsByHostAndPortAndStandaloneTrueAndActiveFalseAndDeletedAtIsNull("silindi.example.com", 443)).isFalse();
        assertThat(portRepo.existsByHostAndPortAndActiveTrue("silindi.example.com", 443)).isFalse();

        dns("iki.example.com", true, false, "2026-09-01T00:00:00");
        DnsMonitor newer = dns("iki.example.com", true, false, "2026-09-02T00:00:00");
        assertThat(dnsRepo.findFirstByDomainAndRecordTypeAndStandaloneTrueAndDeletedAtIsNull("iki.example.com", "A")).isEmpty();
        assertThat(dnsRepo.findFirstByDomainAndRecordTypeAndStandaloneTrueAndDeletedAtIsNotNullOrderByIdDesc("iki.example.com", "A"))
                .get().extracting(DnsMonitor::getId).isEqualTo(newer.getId());
        DnsMonitor paused = dns("uc.example.com", true, false, null);
        assertThat(dnsRepo.findFirstByDomainAndRecordTypeAndStandaloneTrueAndDeletedAtIsNull("uc.example.com", "A"))
                .get().extracting(DnsMonitor::getId).isEqualTo(paused.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Grup sayımı silinmiş standalone satırı saymaz (görünmeyen izleme grubu 'dolu' göstermez)")
    void groupCounts_excludeDeleted() {
        port("a.example.com", true, true, null, "Grup A");
        port("b.example.com", true, false, null, "Grup A");      // duraklatılmış SAYILIR
        port("c.example.com", true, false, NOW, "Grup A");       // silinmiş sayılmaz
        List<Object[]> rows = portRepo.groupCountsByTeam();
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("GERİ DOLDURMA TEK SEFER: ilk koşu eski pasif standalone'ları silinmiş işaretler; ikinci koşu SONRADAN duraklatılana dokunmaz")
    void backfill_runsExactlyOnce() {
        PortMonitor legacyInactive = port("eski-pasif.example.com", true, false, null, null);  // yükseltme öncesi: silinmiş mi duraklatılmış mı bilinmez
        PortMonitor running = port("calisan.example.com", true, true, null, null);
        PortMonitor derivedInactive = port("turev.example.com", false, false, null, null);
        DnsMonitor legacyDns = dns("eski-pasif.example.com", true, false, null);

        int first = backfill.applyOnce();

        assertThat(first).isEqualTo(2);   // 1 Port + 1 DNS
        assertThat(portRepo.findById(legacyInactive.getId()).orElseThrow().getDeletedAt()).as("güvenli seçim: silinmiş").isNotNull();
        assertThat(dnsRepo.findById(legacyDns.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(portRepo.findById(running.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(portRepo.findById(derivedInactive.getId()).orElseThrow().getDeletedAt()).as("envanter-türevi dokunulmaz").isNull();

        // Yükseltmeden SONRA kullanıcı bir izlemeyi duraklatır; uygulama yeniden başlar → yama yeniden çağrılır.
        PortMonitor pausedLater = portRepo.findById(running.getId()).orElseThrow();
        pausedLater.setActive(false);
        portRepo.save(pausedLater);
        DnsMonitor pausedLaterDns = dns("sonra-durdu.example.com", true, false, null);

        int second = backfill.applyOnce();

        assertThat(second).as("nişan var → hiçbir şey yapılmaz").isEqualTo(-1);
        assertThat(portRepo.findById(running.getId()).orElseThrow().getDeletedAt())
                .as("sonradan duraklatılan izleme SİLİNMİŞ işaretlenmemeli").isNull();
        assertThat(dnsRepo.findById(pausedLaterDns.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(portRepo.findByStandaloneTrueAndDeletedAtIsNull()).extracting(PortMonitor::getHost)
                .containsExactly("calisan.example.com");
    }
}
