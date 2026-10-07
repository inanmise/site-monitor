package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Eski çöp kutusunun TEK SEFERLİK kalıcı temizliği (2026-10-07) — gerçek tablolar (H2). Yumuşak silinmiş envanter
 * (silinmiş takımınki dâhil) ve bağımsız Port/DNS satırları gider, açık alarmları kapanır; canlı / duraklatılmış satırlar
 * kalır; nişan yazılır ve ikinci koşu (ya da ikinci pod) hiçbir şey yapmaz.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:binpurge;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,DAY",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Import({PermanentDeletionService.class, DeletedRecordsPurge.class})
class DeletedRecordsPurgeTest {

    @Autowired DeletedRecordsPurge purge;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean EscalationService escalationService;

    private static final String NOW = "2026-10-07T10:00:00";

    @BeforeEach
    void rawTables() {
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.execute("CREATE TABLE IF NOT EXISTS monitor_check_daily (monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                + "day VARCHAR(10) NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, avg_response_ms INTEGER, max_response_ms INTEGER)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS monitor_check_hourly (monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                + "hour_bucket VARCHAR(13) NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, avg_response_ms INTEGER, max_response_ms INTEGER)");
    }

    @AfterEach
    void cleanUp() {
        for (String t : List.of("schema_patch_markers", "certificate_checks", "latest_checks", "port_monitors", "dns_monitors",
                "certificate_inventory", "monitor_check_daily", "monitor_check_hourly")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    private CertificateInventory inv(String domain, Long team, String deletedAt) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setTeamId(team); i.setActive(deletedAt == null); i.setDeletedAt(deletedAt);
        i.setCreatedAt(NOW); i.setUpdatedAt(NOW);
        i = inventoryRepo.save(i);
        jdbc.update("INSERT INTO certificate_checks (domain, checked_at, status) VALUES (?, ?, 'valid')", domain, NOW);
        jdbc.update("INSERT INTO latest_checks (domain, status) VALUES (?, 'valid')", domain);
        return i;
    }

    private PortMonitor port(String host, boolean standalone, boolean active, String deletedAt) {
        PortMonitor m = new PortMonitor();
        m.setName(host); m.setHost(host); m.setPort(8443); m.setTeamId(3L); m.setStandalone(standalone);
        m.setActive(active); m.setDeletedAt(deletedAt); m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return portRepo.save(m);
    }

    private DnsMonitor dns(String domain, boolean active, String deletedAt) {
        DnsMonitor m = new DnsMonitor();
        m.setName(domain); m.setDomain(domain); m.setRecordType("A"); m.setTeamId(3L); m.setStandalone(true);
        m.setActive(active); m.setDeletedAt(deletedAt); m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return dnsRepo.save(m);
    }

    private int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("TEK SEFER: çöp kutusu (envanter + bağımsız Port/DNS, silinmiş takımınki dâhil) kalıcı gider, alarmlar kapanır; canlı/duraklatılmış kalır; ikinci koşu no-op")
    void purgesBinOnce() {
        CertificateInventory gone = inv("gone.example.com", 5L, "2026-09-01T00:00:00");
        CertificateInventory orphan = inv("orphan.example.com", 404L, "2026-08-01T00:00:00");   // takımı silinmiş
        CertificateInventory live = inv("live.example.com", 5L, null);
        PortMonitor goneP = port("p.example.com", true, false, "2026-09-02T00:00:00");
        PortMonitor pausedP = port("paused.example.com", true, false, null);
        DnsMonitor goneD = dns("d.example.com", false, "2026-09-03T00:00:00");
        DnsMonitor liveD = dns("live-d.example.com", true, null);
        when(escalationService.closeAlertsOnInventoryDelete("gone.example.com")).thenReturn(2);
        when(escalationService.closeAlertsOnInventoryDelete("orphan.example.com")).thenReturn(1);

        DeletedRecordsPurge.Result r = purge.applyOnce();

        assertThat(r).isNotNull();
        assertThat(r.inventory()).extracting(DeletedRecordsPurge.Purged::name)
                .containsExactlyInAnyOrder("gone.example.com", "orphan.example.com");
        assertThat(r.ports()).extracting(DeletedRecordsPurge.Purged::id).containsExactly(goneP.getId());
        assertThat(r.dns()).extracting(DeletedRecordsPurge.Purged::id).containsExactly(goneD.getId());
        assertThat(r.alertsClosed()).isEqualTo(3);
        assertThat(inventoryRepo.findById(gone.getId())).isEmpty();
        assertThat(inventoryRepo.findById(orphan.getId())).isEmpty();
        assertThat(count("certificate_checks", "domain IN (?, ?)", "gone.example.com", "orphan.example.com")).isZero();
        assertThat(count("latest_checks", "domain IN (?, ?)", "gone.example.com", "orphan.example.com")).isZero();
        assertThat(inventoryRepo.findById(live.getId())).isPresent();
        assertThat(count("certificate_checks", "domain = ?", "live.example.com")).isEqualTo(1);
        assertThat(portRepo.findById(goneP.getId())).isEmpty();
        assertThat(portRepo.findById(pausedP.getId())).as("duraklatılmış izleme silinmiş DEĞİLDİR").isPresent();
        assertThat(dnsRepo.findById(goneD.getId())).isEmpty();
        assertThat(dnsRepo.findById(liveD.getId())).isPresent();
        verify(escalationService).closeAlertsOnInventoryDelete("gone.example.com");
        verify(escalationService).closeAlertsOnInventoryDelete("orphan.example.com");
        verify(escalationService, never()).closeAlertsOnInventoryDelete("live.example.com");
        verify(escalationService).resolveOpenAlertsSilently(eq("p.example.com"), any(), eq("Sistem (izleme silindi)"), any());
        verify(escalationService).resolveOpenAlertsSilently(eq("d.example.com"), any(), eq("Sistem (izleme silindi)"), any());
        assertThat(count("schema_patch_markers", "patch_key = ?", DeletedRecordsPurge.KEY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                DeletedRecordsPurge.KEY)).isEqualTo(4);
        // Denetim ayrıntısı: forensics — her silinen kaydın kimliği + adı + takımı (çağıran tek sistem olayına yazar)
        assertThat(r.auditDetail()).contains("\"permanent\":true", "\"inventoryPurged\":2", "\"portMonitorsPurged\":1",
                "\"dnsMonitorsPurged\":1", "\"name\":\"gone.example.com\"", "\"id\":" + goneP.getId(), "\"teamId\":404");

        // İkinci açılış (ya da ilk koşu commit olduktan sonra açılan ikinci pod): nişan var → hiçbir şey yapılmaz.
        CertificateInventory laterLegacy = inv("later.example.com", 5L, "2026-10-01T00:00:00");
        assertThat(purge.applyOnce()).isNull();
        assertThat(inventoryRepo.findById(laterLegacy.getId())).as("nişan sonrası koşu hiçbir şeye dokunmaz").isPresent();
        verify(escalationService, times(2)).closeAlertsOnInventoryDelete(anyString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("çöp kutusu boşsa da nişan yazılır (0 satır) — sonraki açılışlar sorgu bile koşmaz")
    void emptyBin_writesMarker() {
        inv("live.example.com", 5L, null);
        DeletedRecordsPurge.Result r = purge.applyOnce();
        assertThat(r.total()).isZero();
        assertThat(count("schema_patch_markers", "patch_key = ?", DeletedRecordsPurge.KEY)).isEqualTo(1);
        assertThat(purge.applyOnce()).isNull();
        verifyNoInteractions(escalationService);
    }

    @Test
    @DisplayName("çoklu pod: nişan INSERT'i çakışırsa (başka pod aynı anda aldı) iş YAPILMAZ ve hata çağırana gider (işlem geri alınır)")
    void markerConflict_otherPodWins_noWork() {
        JdbcTemplate j = mock(JdbcTemplate.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        PortMonitorRepository ports = mock(PortMonitorRepository.class);
        DnsMonitorRepository dnsRepository = mock(DnsMonitorRepository.class);
        PermanentDeletionService deletion = mock(PermanentDeletionService.class);
        when(j.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(j.update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO schema_patch_markers"), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("uq"));
        DeletedRecordsPurge p = new DeletedRecordsPurge(j, inv, ports, dnsRepository, deletion);

        assertThatThrownBy(p::applyOnce).isInstanceOf(DuplicateKeyException.class);
        verifyNoInteractions(inv, ports, dnsRepository, deletion);
    }
}
