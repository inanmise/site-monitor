package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KALICI silme (2026-10-07, kullanıcı kararı: "silme işlemi her şekilde kalıcı olsun") — GERÇEK tablolar (H2, PostgreSQL
 * kipi). Mock'lanmış bir depo "hangi tablodan ne silindi"yi kanıtlayamaz; burada her çocuk tablo satırla doldurulur, silme
 * koşar, kalan satırlar sayılır. Testler {@code NOT_SUPPORTED} ile koşar: servisin KENDİ işlemi üretimdeki gibi commit olur
 * (RepositoryWriteTransactionGuardTest'in uyardığı "test işlemi tx eksikliğini gizler" tuzağı yok).
 *
 * <p>Ne GİDER: kontrol geçmişi, latest_checks, notlar + revizyonlar, manuel sürümler, envanter TÜREVİ Port/DNS izlemeleri
 * ve serileri / özetleri / tanılamaları, uptime serisi + UPTIME özeti, zayıf algoritma istisnası, sertifika tanılamaları,
 * öksüz kalan rehber/not. Ne KALIR: alarm geçmişi, ürün geçmişi, BAĞIMSIZ Port/DNS izlemeleri, alan adı tanılaması
 * (DOMAIN_EXPIRY — alan adı izlemesiyle paylaşılır), hâlâ izlenen hedefin rehberi, başka kayıtların verisi.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:permdelete;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,DAY",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Import(PermanentDeletionService.class)
class PermanentDeletionServiceTest {

    @Autowired PermanentDeletionService service;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired PortCheckRepository portCheckRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired DnsRecordRepository dnsRecordRepo;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean EscalationService escalationService;

    private static final String NOW = "2026-10-07T10:00:00";
    private static final String GONE = "gone.example.com";
    private static final String KEEP = "keep.example.com";

    /** Ham DDL tablolar (SchedulerService.applySchemaPatches) — entity'leri yok, ddl-auto kurmaz. */
    @BeforeEach
    void rawTables() {
        for (String t : List.of("monitor_check_daily", "monitor_check_hourly")) {
            String bucket = t.endsWith("daily") ? "day VARCHAR(10)" : "hour_bucket VARCHAR(13)";
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + t + " (monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                    + bucket + " NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, avg_response_ms INTEGER, "
                    + "max_response_ms INTEGER)");
        }
    }

    @AfterEach
    void cleanUp() {
        for (String t : List.of("certificate_checks", "latest_checks", "certificate_note_revisions", "certificate_notes",
                "manual_certificate_versions", "port_checks", "port_monitors", "dns_records", "dns_monitors", "uptime_checks",
                "weak_algo_exception", "diagnostic_runs", "monitor_notes", "monitor_guide", "monitor_check_daily",
                "monitor_check_hourly", "alert_events", "certificate_inventory")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ── kurulum yardımcıları ───────────────────────────────────────────────────────────────────────────────

    private CertificateInventory inventory(String domain, int port, Long team, String deletedAt) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(port); i.setTeamId(team); i.setActive(deletedAt == null); i.setDeletedAt(deletedAt);
        i.setCreatedAt(NOW); i.setUpdatedAt(NOW);
        return inventoryRepo.save(i);
    }

    private PortMonitor port(String host, int port, boolean standalone) {
        PortMonitor m = new PortMonitor();
        m.setName(host); m.setHost(host); m.setPort(port); m.setTeamId(5L); m.setStandalone(standalone); m.setActive(true);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        m = portRepo.save(m);
        PortCheck c = new PortCheck(); c.setMonitorId(m.getId()); c.setOpen(true); c.setCheckedAt(NOW);
        portCheckRepo.save(c);
        jdbc.update("INSERT INTO monitor_check_daily (monitor_type, monitor_key, day, total_checks, up_checks) VALUES ('PORT', ?, '2026-10-06', 10, 10)",
                String.valueOf(m.getId()));
        jdbc.update("INSERT INTO monitor_check_hourly (monitor_type, monitor_key, hour_bucket, total_checks, up_checks) VALUES ('PORT', ?, '2026-10-06T10', 1, 1)",
                String.valueOf(m.getId()));
        diag("port-monitor:" + m.getId(), "PORT_DIAG");
        return m;
    }

    private DnsMonitor dns(String domain, String type, boolean standalone) {
        DnsMonitor m = new DnsMonitor();
        m.setName(domain); m.setDomain(domain); m.setRecordType(type); m.setTeamId(5L); m.setStandalone(standalone); m.setActive(true);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        m = dnsRepo.save(m);
        DnsRecord r = new DnsRecord(); r.setMonitorId(m.getId()); r.setRecordType(type); r.setValue("192.0.2.1"); r.setCheckedAt(NOW);
        dnsRecordRepo.save(r);
        diag("dns-monitor:" + m.getId(), "DNS_DIAG");
        return m;
    }

    private void diag(String key, String type) {
        jdbc.update("INSERT INTO diagnostic_runs (domain, run_type, executed_at, success) VALUES (?, ?, ?, TRUE)", key, type, NOW);
    }

    /** Bir envanter alan adının sertifika tarafı verisi (her tabloya bir+ satır). */
    private void certData(String domain, int port) {
        jdbc.update("INSERT INTO certificate_checks (domain, checked_at, status) VALUES (?, ?, 'valid')", domain, NOW);
        jdbc.update("INSERT INTO certificate_checks (domain, checked_at, status) VALUES (?, ?, 'error')", domain, NOW);
        jdbc.update("INSERT INTO latest_checks (domain, status) VALUES (?, 'valid')", domain);
        jdbc.update("INSERT INTO certificate_notes (domain, note) VALUES (?, 'not')", domain);
        Long noteId = jdbc.queryForObject("SELECT MAX(id) FROM certificate_notes WHERE domain = ?", Long.class, domain);
        jdbc.update("INSERT INTO certificate_note_revisions (note_id, sequence_no, event_type, edited_at, edited_by) VALUES (?, 1, 'CREATE', ?, 'u')",
                noteId, NOW);
        jdbc.update("INSERT INTO uptime_checks (domain, port, status, checked_at) VALUES (?, ?, 'up', ?)", domain, port, NOW);
        jdbc.update("INSERT INTO monitor_check_daily (monitor_type, monitor_key, day, total_checks, up_checks) VALUES ('UPTIME', ?, '2026-10-06', 5, 5)", domain);
        jdbc.update("INSERT INTO monitor_check_hourly (monitor_type, monitor_key, hour_bucket, total_checks, up_checks) VALUES ('UPTIME', ?, '2026-10-06T10', 1, 1)", domain);
        jdbc.update("INSERT INTO weak_algo_exception (domain, reason) VALUES (?, 'r')", domain);
        diag(domain, "CONNECTION");
        diag(domain, "HSTS");
        diag(domain, "DOMAIN_EXPIRY");   // alan adı izlemesiyle paylaşılır — KALIR
        jdbc.update("INSERT INTO alert_events (domain, alert_level, alert_type, acknowledged, resolved, created_at) VALUES (?, 'HIGH', 'EXPIRY', FALSE, TRUE, ?)",
                domain, NOW);
    }

    private int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where), Integer.class, args);
        return n == null ? 0 : n;
    }

    // ── testler ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("envanter silme: kayıt + sahip olduğu her tablo KALICI gider; bağımsız izleme, alarm geçmişi ve başka kayıt kalır")
    void deleteInventory_removesOwnedData_keepsTheRest() {
        CertificateInventory gone = inventory(GONE, 8443, 5L, null);
        inventory(KEEP, 443, 5L, null);
        certData(GONE, 8443);
        certData(KEEP, 443);
        PortMonitor derivedPort = port(GONE, 8443, false);
        PortMonitor standalonePort = port(GONE, 9000, true);          // başka takımın bağımsız izlemesi olabilir — KALIR
        DnsMonitor derivedDns = dns(GONE, "A", false);
        DnsMonitor standaloneDns = dns(GONE, "MX", true);             // KALIR
        PortMonitor keepPort = port(KEEP, 443, false);
        // Rehber / not: türev port hedefi öksüz kalır → gider; DNS hedefi bağımsız MX izlemesiyle sürer → kalır.
        jdbc.update("INSERT INTO monitor_notes (monitor_type, target, problem) VALUES ('PORT', ?, 'p')", GONE + ":8443");
        jdbc.update("INSERT INTO monitor_guide (monitor_type, target, guide) VALUES ('PORT', ?, 'g')", GONE + ":8443");
        jdbc.update("INSERT INTO monitor_notes (monitor_type, target, problem) VALUES ('DNS', ?, 'p')", GONE);
        jdbc.update("INSERT INTO monitor_guide (monitor_type, target, guide) VALUES ('DNS', ?, 'g')", GONE);
        when(escalationService.closeAlertsOnInventoryDelete(GONE)).thenReturn(2);

        var r = service.deleteInventory(gone);

        assertThat(r.alertsClosed()).isEqualTo(2);
        assertThat(r.derivedMonitors()).isEqualTo(2);
        assertThat(r.checksDeleted()).isEqualTo(2);
        verify(escalationService).closeAlertsOnInventoryDelete(GONE);   // alarmlar silmeden ÖNCE, aynı işlemde (sahip anahtarı kuralı serviste)

        assertThat(count("certificate_inventory", "domain = ?", GONE)).isZero();
        assertThat(count("certificate_checks", "domain = ?", GONE)).isZero();
        assertThat(count("latest_checks", "domain = ?", GONE)).isZero();
        assertThat(count("certificate_notes", "domain = ?", GONE)).isZero();
        assertThat(count("certificate_note_revisions", null)).isEqualTo(1);   // yalnız KEEP'in revizyonu
        assertThat(count("uptime_checks", "domain = ?", GONE)).isZero();
        assertThat(count("monitor_check_daily", "monitor_type = 'UPTIME' AND monitor_key = ?", GONE)).isZero();
        assertThat(count("monitor_check_hourly", "monitor_type = 'UPTIME' AND monitor_key = ?", GONE)).isZero();
        assertThat(count("weak_algo_exception", "domain = ?", GONE)).isZero();
        assertThat(count("diagnostic_runs", "domain = ? AND run_type IN ('CONNECTION','HSTS')", GONE)).isZero();
        assertThat(count("diagnostic_runs", "domain = ? AND run_type = 'DOMAIN_EXPIRY'", GONE)).as("alan adı tanılaması paylaşılır").isEqualTo(1);
        // Türev izlemeler + serileri / özetleri / tanılamaları
        assertThat(portRepo.findById(derivedPort.getId())).isEmpty();
        assertThat(count("port_checks", "monitor_id = ?", derivedPort.getId())).isZero();
        assertThat(count("monitor_check_daily", "monitor_type = 'PORT' AND monitor_key = ?", String.valueOf(derivedPort.getId()))).isZero();
        assertThat(count("diagnostic_runs", "domain = ?", "port-monitor:" + derivedPort.getId())).isZero();
        assertThat(dnsRepo.findById(derivedDns.getId())).isEmpty();
        assertThat(count("dns_records", "monitor_id = ?", derivedDns.getId())).isZero();
        assertThat(count("diagnostic_runs", "domain = ?", "dns-monitor:" + derivedDns.getId())).isZero();
        assertThat(count("monitor_notes", "monitor_type = 'PORT' AND target = ?", GONE + ":8443")).isZero();
        assertThat(count("monitor_guide", "monitor_type = 'PORT' AND target = ?", GONE + ":8443")).isZero();
        // KALANLAR
        assertThat(portRepo.findById(standalonePort.getId())).as("bağımsız Port izlemesi envantere ait değil").isPresent();
        assertThat(count("port_checks", "monitor_id = ?", standalonePort.getId())).isEqualTo(1);
        assertThat(dnsRepo.findById(standaloneDns.getId())).isPresent();
        assertThat(count("dns_records", "monitor_id = ?", standaloneDns.getId())).isEqualTo(1);
        assertThat(count("monitor_notes", "monitor_type = 'DNS' AND target = ?", GONE)).as("hedef hâlâ izleniyor").isEqualTo(1);
        assertThat(count("monitor_guide", "monitor_type = 'DNS' AND target = ?", GONE)).isEqualTo(1);
        assertThat(count("alert_events", "domain = ?", GONE)).as("kapanmış alarm geçmişi kalır").isEqualTo(1);
        assertThat(count("certificate_inventory", "domain = ?", KEEP)).isEqualTo(1);
        assertThat(count("certificate_checks", "domain = ?", KEEP)).isEqualTo(2);
        assertThat(count("latest_checks", "domain = ?", KEEP)).isEqualTo(1);
        assertThat(count("uptime_checks", "domain = ?", KEEP)).isEqualTo(1);
        assertThat(portRepo.findById(keepPort.getId())).isPresent();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("silinen ad YENİDEN eklenebilir — DB'deki UNIQUE(domain) eski satıra çarpmaz; eski veri yeni kayda taşınmaz")
    void readdSameDomain_afterDelete() {
        CertificateInventory gone = inventory(GONE, 443, 5L, null);
        certData(GONE, 443);
        service.deleteInventory(gone);

        CertificateInventory again = inventory(GONE, 443, 9L, null);   // başka takım, aynı ad
        assertThat(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(GONE)).get()
                .extracting(CertificateInventory::getId).isEqualTo(again.getId());
        assertThat(count("certificate_checks", "domain = ?", GONE)).as("eski kontrol geçmişi diriltilmez").isZero();
        assertThat(count("certificate_notes", "domain = ?", GONE)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("manuel kayıt: sürümler de gider; başka kaydın sürümleri kalır")
    void deleteInventory_manualRow_deletesVersions() {
        CertificateInventory manual = new CertificateInventory();
        manual.setDomain("api-takip"); manual.setPort(443); manual.setTeamId(5L); manual.setActive(true);
        manual.setCertSource(CertificateInventory.SOURCE_MANUAL); manual.setCreatedAt(NOW); manual.setUpdatedAt(NOW);
        manual = inventoryRepo.save(manual);
        CertificateInventory other = inventory(KEEP, 443, 5L, null);
        int v = 0;
        for (Long inv : List.of(manual.getId(), manual.getId(), other.getId())) {
            jdbc.update("INSERT INTO manual_certificate_versions (inventory_id, version, is_current, fingerprint, chain_pem) "
                    + "VALUES (?, ?, FALSE, 'ab', 'pem')", inv, ++v);
        }

        var r = service.deleteInventory(manual);

        assertThat(r.rows()).containsEntry("manual_certificate_versions", 2).containsEntry("certificate_inventory", 1);
        assertThat(count("manual_certificate_versions", "inventory_id = ?", manual.getId())).isZero();
        assertThat(count("manual_certificate_versions", "inventory_id = ?", other.getId())).isEqualTo(1);
        assertThat(inventoryRepo.findById(manual.getId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("eski çöp satırı temizliği: harf duyarsız, YALNIZ yumuşak silinmiş satırlar; canlı kayıt dokunulmaz; sonra ad serbest")
    void purgeLegacyBinRows_onlySoftDeleted_caseInsensitive() {
        CertificateInventory bin = inventory("Old.Example.com", 443, 9L, "2026-09-01T00:00:00");
        certData("Old.Example.com", 443);
        CertificateInventory live = inventory("live.example.com", 443, 5L, null);

        assertThat(service.purgeLegacyBinRows("old.example.com")).isEqualTo(1);
        assertThat(service.purgeLegacyBinRows("live.example.com")).as("canlı kayıt çöp değildir").isZero();
        assertThat(service.purgeLegacyBinRows("  ")).isZero();

        assertThat(inventoryRepo.findById(bin.getId())).isEmpty();
        assertThat(count("certificate_checks", "domain = ?", "Old.Example.com")).isZero();
        assertThat(inventoryRepo.findById(live.getId())).isPresent();
        verify(escalationService).closeAlertsOnInventoryDelete("Old.Example.com");
        verify(escalationService, never()).closeAlertsOnInventoryDelete("live.example.com");
        inventory("old.example.com", 443, 5L, null);   // ad artık serbest
        assertThat(inventoryRepo.findByDomainIgnoreCaseAndDeletedAtIsNotNull("old.example.com")).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("bağımsız Port silme: YALNIZ bu izlemenin açık alarmları (sahiplik bağlamı) kapanır; seri, özet, tanılama, satır, öksüz rehber gider")
    void deleteStandalonePort_permanent() {
        PortMonitor m = port("svc.example.com", 8080, true);
        PortMonitor other = port("svc.example.com", 9090, true);
        jdbc.update("INSERT INTO monitor_guide (monitor_type, target, guide) VALUES ('PORT', ?, 'g')", "svc.example.com:8080");
        jdbc.update("INSERT INTO monitor_guide (monitor_type, target, guide) VALUES ('PORT', ?, 'g')", "svc.example.com:9090");

        service.deleteStandalonePort(m);

        Long id = m.getId();
        verify(escalationService).resolveOpenAlertsSilently(eq("svc.example.com"),
                eq(MonitorTypeCatalog.ALERT_TYPES.get("port")), eq("Sistem (izleme silindi)"),
                argThat((Map<String, Object> ctx) -> id.equals(ctx.get("monitor_id")) && Long.valueOf(5L).equals(ctx.get("team_id"))
                        && Boolean.TRUE.equals(ctx.get("standalone"))));
        assertThat(portRepo.findById(id)).isEmpty();
        assertThat(count("port_checks", "monitor_id = ?", id)).isZero();
        assertThat(count("monitor_check_daily", "monitor_type = 'PORT' AND monitor_key = ?", String.valueOf(id))).isZero();
        assertThat(count("monitor_check_hourly", "monitor_type = 'PORT' AND monitor_key = ?", String.valueOf(id))).isZero();
        assertThat(count("diagnostic_runs", "domain = ?", "port-monitor:" + id)).isZero();
        assertThat(count("monitor_guide", "target = ?", "svc.example.com:8080")).isZero();
        assertThat(portRepo.findById(other.getId())).isPresent();
        assertThat(count("port_checks", "monitor_id = ?", other.getId())).isEqualTo(1);
        assertThat(count("monitor_guide", "target = ?", "svc.example.com:9090")).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("bağımsız DNS silme: alarmlar (sahiplik bağlamı) + kayıt serisi + satır gider; aynı alan adının başka izlemesi ve rehberi kalır")
    void deleteStandaloneDns_permanent() {
        DnsMonitor a = dns("zone.example.com", "A", true);
        DnsMonitor mx = dns("zone.example.com", "MX", true);
        jdbc.update("INSERT INTO monitor_guide (monitor_type, target, guide) VALUES ('DNS', ?, 'g')", "zone.example.com");

        service.deleteStandaloneDns(a);

        verify(escalationService).resolveOpenAlertsSilently(eq("zone.example.com"),
                eq(MonitorTypeCatalog.ALERT_TYPES.get("dns")), eq("Sistem (izleme silindi)"), any());
        assertThat(dnsRepo.findById(a.getId())).isEmpty();
        assertThat(count("dns_records", "monitor_id = ?", a.getId())).isZero();
        assertThat(dnsRepo.findById(mx.getId())).isPresent();
        assertThat(count("monitor_guide", "target = ?", "zone.example.com")).as("MX izlemesi hedefi hâlâ izliyor").isEqualTo(1);

        service.deleteStandaloneDns(mx);
        assertThat(count("monitor_guide", "target = ?", "zone.example.com")).as("son izleme de gidince rehber öksüz — gider").isZero();
        verify(escalationService, never()).closeAlertsOnInventoryDelete(anyString());
    }
}
