package com.sitemonitor.service.noc;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.NocSettingsRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 7/24 KAPSAMI + şema yaması + grup silme — GERÇEK sorgular (H2). Envanterden türeyen DNS/Port satırlarının takımı
 * ENVANTERDEN gelir (saklı {@code team_id} bayat kopya), UG takımı da görür; silinmiş standalone ve envanteri canlı
 * olmayan türev satır listelenmez. Testler {@code NOT_SUPPORTED}: servislerin kendi işlemleri üretimdeki gibi commit olur.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:nocpersist;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Import({NocSchemaPatches.class, NocMonitorDirectory.class, NocCoverageService.class, NocConfigService.class, NocGroupService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NocPersistenceTest {

    private static final long A = 1L, B = 2L, C = 3L;
    private static final String NOW = "2026-01-10T10:00:00";   // yalnız saklanan damga; pencereyle karşılaştırılmaz

    @Autowired JdbcTemplate jdbc;
    @Autowired NocSchemaPatches patches;
    @Autowired NocMonitorDirectory directory;
    @Autowired NocCoverageService coverage;
    @Autowired NocConfigService config;
    @Autowired NocGroupService groups;
    @Autowired CertificateInventoryRepository invRepo;
    @Autowired PingMonitorRepository pingRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired NocNotificationGroupRepository groupRepo;
    @Autowired NocSettingsRepository settingsRepo;

    @AfterEach
    void clean() {
        for (String t : List.of("certificate_inventory", "ping_monitors", "port_monitors", "dns_monitors",
                "noc_notification_groups", "noc_settings", "noc_deliveries")) jdbc.update("DELETE FROM " + t);
    }

    // ── Tohum ────────────────────────────────────────────────────────────────

    private CertificateInventory inv(String domain, Long team, Long ug, boolean active, String deletedAt, boolean noc) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setTeamId(team); i.setUgTeamId(ug); i.setActive(active); i.setDeletedAt(deletedAt);
        i.setNocNotify(noc); i.setCreatedAt(NOW); i.setUpdatedAt(NOW);
        return invRepo.save(i);
    }

    private PingMonitor ping(String host, long team, boolean active, boolean noc, String groupsCsv) {
        PingMonitor m = new PingMonitor();
        m.setName(host); m.setHost(host); m.setTeamId(team); m.setActive(active); m.setNocNotify(noc); m.setNocGroupIds(groupsCsv);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return pingRepo.save(m);
    }

    private PortMonitor port(String host, int port, Long storedTeam, boolean standalone, String deletedAt, boolean noc, String groupsCsv) {
        PortMonitor m = new PortMonitor();
        m.setName(host); m.setHost(host); m.setPort(port); m.setTeamId(storedTeam); m.setStandalone(standalone);
        m.setActive(true); m.setDeletedAt(deletedAt); m.setNocNotify(noc); m.setNocGroupIds(groupsCsv);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return portRepo.save(m);
    }

    private DnsMonitor dns(String domain, Long storedTeam, boolean standalone, boolean noc) {
        DnsMonitor m = new DnsMonitor();
        m.setName(domain); m.setDomain(domain); m.setRecordType("A"); m.setTeamId(storedTeam); m.setStandalone(standalone);
        m.setActive(true); m.setNocNotify(noc); m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return dnsRepo.save(m);
    }

    private NocNotificationGroup group(String name, boolean active, boolean def) {
        NocNotificationGroup g = new NocNotificationGroup();
        g.setName(name); g.setEmails("noc@example.com"); g.setActive(active); g.setIsDefault(def);
        return groupRepo.save(g);
    }

    private Map<String, Object> cov(List<Long> viewTeams, Long teamFilter) {
        Map<Long, String> names = Map.of(A, "Takım A", B, "Takım B", C, "Takım C");
        return coverage.compute(teamFilter, null,
                r -> viewTeams == null || (r.teamId() != null && viewTeams.contains(r.teamId()))
                        || (r.ugTeamId() != null && viewTeams.contains(r.ugTeamId())),
                r -> true, names);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> cov, String type, String name) {
        for (Map<String, Object> i : (List<Map<String, Object>>) cov.get("items"))
            if (type.equals(i.get("type")) && name.equals(i.get("name"))) return i;
        return null;
    }

    // ── Şema yaması ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("şema yaması İDEMPOTENT: eksik kolonu ekler, ikinci koşu hiçbir şey yapmaz; tekil teslim anahtarı DB'de")
    void schemaPatchIsIdempotent() {
        jdbc.execute("ALTER TABLE ping_monitors DROP COLUMN noc_notify");
        jdbc.execute("ALTER TABLE dns_monitors DROP COLUMN noc_group_ids");
        assertThat(patches.columnExists("ping_monitors", "noc_notify")).isFalse();

        assertThat(patches.apply()).isEqualTo(2);
        assertThat(patches.columnExists("ping_monitors", "noc_notify")).isTrue();
        assertThat(patches.columnExists("dns_monitors", "noc_group_ids")).isTrue();
        assertThat(patches.apply()).isZero();
        for (String t : NocSchemaPatches.MONITOR_TABLES) {
            assertThat(patches.columnExists(t, "noc_notify")).as(t).isTrue();
            assertThat(patches.columnExists(t, "noc_group_ids")).as(t).isTrue();
        }
        jdbc.update("INSERT INTO noc_deliveries (dedupe_key, phase, created_at) VALUES ('alert:1:OPEN', 'OPEN', ?)", NOW);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO noc_deliveries (dedupe_key, phase, created_at) VALUES ('alert:1:OPEN', 'OPEN', ?)", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Kapsam ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kapsam: nedenler (PAUSED/MONITOR_OFF/TYPE_DISABLED/NO_ACTIVE_GROUP), özet ve tür kırılımı")
    void reasonsAndSummary() {
        group("NOC Ana", true, true);
        ping("p-on.example.com", A, true, true, null);
        ping("p-off.example.com", A, true, false, null);
        ping("p-paused.example.com", A, false, true, null);
        inv("www.a.example.com", A, null, true, null, true);

        Map<String, Object> c = cov(null, null);
        assertThat(item(c, "PING", "p-on.example.com")).containsEntry("covered", true).containsEntry("reason", null)
                .containsEntry("group_names", List.of("NOC Ana"));
        assertThat(item(c, "PING", "p-off.example.com")).containsEntry("reason", NocCoverageService.MONITOR_OFF);
        assertThat(item(c, "PING", "p-paused.example.com")).containsEntry("reason", NocCoverageService.PAUSED);
        assertThat(item(c, "SSL", "www.a.example.com")).containsEntry("covered", true);
        @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) c.get("summary");
        assertThat(s).containsEntry("total", 4).containsEntry("covered", 2).containsEntry("paused", 1)
                .containsEntry("not_covered", 1).containsEntry("active_groups", 1);
        @SuppressWarnings("unchecked") Map<String, Map<String, Integer>> byType = (Map<String, Map<String, Integer>>) s.get("by_type");
        assertThat(byType).hasSize(10);
        assertThat(byType.get("PING")).containsEntry("total", 3).containsEntry("covered", 1);

        config.save(Map.of("enabledTypes", Map.of("PING", false)), "admin", "Yönetici");
        assertThat(item(cov(null, null), "PING", "p-on.example.com")).containsEntry("reason", NocCoverageService.TYPE_DISABLED);
        assertThat(((Map<?, ?>) cov(null, null).get("summary")).get("disabled_types")).isEqualTo(List.of("PING"));

        jdbc.update("UPDATE noc_notification_groups SET active = FALSE");
        assertThat(item(cov(null, null), "SSL", "www.a.example.com")).containsEntry("reason", NocCoverageService.NO_ACTIVE_GROUP);
    }

    @Test
    @DisplayName("kapsam: envanter kökenli DNS/Port — takım ENVANTERDEN, UG takımı görür; silinmiş/öksüz satır yok")
    void dualSourcedDnsPort() {
        group("NOC Ana", true, true);
        inv("www.b.example.com", B, A, true, null, false);            // SY = B, UG = A
        inv("www.a.example.com", A, null, true, null, false);
        port("www.b.example.com", 443, C, false, null, true, null);   // türev; saklı takım C BAYAT
        port("www.b.example.com", 8443, C, false, null, true, null);  // türev ama envanter portu değil → yok
        port("orphan.example.com", 443, A, false, null, true, null);  // türev ama envanter yok → yok
        port("solo.example.com", 22, A, true, null, true, null);      // standalone
        port("gone.example.com", 22, A, true, NOW, true, null);       // silinmiş standalone → yok
        dns("www.a.example.com", null, false, true);                  // türev; saklı takım boş
        dns("solo-dns.example.com", B, true, false);                  // standalone, B

        Map<String, Object> all = cov(null, null);
        Map<String, Object> derivedPort = item(all, "PORT", "www.b.example.com");
        assertThat(derivedPort).containsEntry("team_id", B).containsEntry("derived", true).containsEntry("covered", true)
                .containsEntry("target", "www.b.example.com:443");
        assertThat(item(all, "PORT", "orphan.example.com")).isNull();
        assertThat(item(all, "PORT", "gone.example.com")).isNull();
        assertThat(item(all, "PORT", "solo.example.com")).containsEntry("team_id", A).containsEntry("derived", false);
        assertThat(item(all, "DNS", "www.a.example.com")).containsEntry("team_id", A).containsEntry("derived", true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> ports = ((List<Map<String, Object>>) all.get("items"))
                .stream().filter(i -> "PORT".equals(i.get("type"))).toList();
        assertThat(ports).hasSize(2);

        // Takım A görüşü: kendi satırları + UG takımı olduğu türev Port; B'nin standalone DNS'i görünmez.
        Map<String, Object> viewA = cov(List.of(A), null);
        assertThat(item(viewA, "PORT", "www.b.example.com")).isNotNull();
        assertThat(item(viewA, "DNS", "www.a.example.com")).isNotNull();
        assertThat(item(viewA, "DNS", "solo-dns.example.com")).isNull();
        // Takım süzgeci ETKİN takıma göre: B süzgecinde türev Port (envanter takımı B) çıkar, A süzgecinde çıkmaz.
        assertThat(item(cov(null, B), "PORT", "www.b.example.com")).isNotNull();
        assertThat(item(cov(null, A), "PORT", "www.b.example.com")).isNull();
    }

    @Test
    @DisplayName("alarm → izleme eşlemesi: kimlik yoksa hedef anahtarıyla (Port host:port, SSL alan adı)")
    void forAlertByKey() {
        inv("www.b.example.com", B, null, true, null, true);
        PortMonitor p = port("www.b.example.com", 443, C, false, null, true, null);
        assertThat(directory.forAlert(NocType.PORT, "www.b.example.com", null).id()).isEqualTo(p.getId());
        assertThat(directory.forAlert(NocType.PORT, "WWW.B.example.com", p.getId()).teamId()).isEqualTo(B);
        assertThat(directory.forAlert(NocType.SSL, "www.b.example.com", null).teamId()).isEqualTo(B);
        assertThat(directory.forAlert(NocType.PING, "yok.example.com", null)).isNull();
    }

    // ── Grup silme ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("grup silinince onu SEÇEN izlemelerin listesinden çıkar (boşalan varsayılana düşer); sayı döner")
    void deleteDetachesMonitors() {
        NocNotificationGroup g = group("Silinecek", true, false);
        NocNotificationGroup keep = group("Kalan", true, true);
        long id = g.getId();
        PingMonitor p = ping("p.example.com", A, true, true, id + "," + keep.getId());
        PortMonitor po = port("solo.example.com", 22, A, true, null, true, String.valueOf(id));
        PingMonitor other = ping("q.example.com", A, true, true, String.valueOf(id * 10 + 1));   // "11" ⊃ "1" tuzağı

        int affected = groups.deleteAndDetach(g);

        assertThat(affected).isEqualTo(2);
        assertThat(groupRepo.findById(id)).isEmpty();
        assertThat(pingRepo.findById(p.getId()).orElseThrow().getNocGroupIds()).isEqualTo(String.valueOf(keep.getId()));
        assertThat(portRepo.findById(po.getId()).orElseThrow().getNocGroupIds()).isNull();
        assertThat(pingRepo.findById(other.getId()).orElseThrow().getNocGroupIds()).isEqualTo(String.valueOf(id * 10 + 1));
    }

    @Test
    @DisplayName("grup adı harf duyarsız TEKİL")
    void uniqueNameCaseInsensitive() {
        groups.create(new NocGroupService.GroupInput("NOC Ana", null, List.of("a@example.com"), true, false), "admin", "Yönetici");
        assertThatThrownBy(() -> groups.create(new NocGroupService.GroupInput("noc ana", null, List.of("b@example.com"), true, false),
                "admin", "Yönetici")).hasMessageContaining("zaten var");
    }

    // ── Yayın öncesi inceleme (2026-09-27) ──────────────────────────────────

    @Autowired com.sitemonitor.repository.NotificationLogRepository logRepo;

    /** SQL sayan JdbcTemplate — fırtına değerlendirmesinde tablo başına TEK okuma iddiasını ölçer. */
    static final class CountingJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        CountingJdbc(javax.sql.DataSource ds) { super(ds); }
        @Override public void query(String sql, org.springframework.jdbc.core.RowCallbackHandler rch) {
            sqls.add(sql); super.query(sql, rch);
        }
        @Override public void query(String sql, org.springframework.jdbc.core.RowCallbackHandler rch, Object... args) {
            sqls.add(sql); super.query(sql, rch, args);
        }
        long count(String fragment) { return sqls.stream().filter(s -> s.contains(fragment)).count(); }
    }

    @Test
    @DisplayName("anlık görüntü: çok üyeli değerlendirmede envanter ve her tablo EN ÇOK bir kez okunur")
    void snapshotReadsEachTableOnce() {
        inv("www.b.example.com", B, null, true, null, true);
        List<PortMonitor> ports = new ArrayList<>();
        for (int i = 0; i < 5; i++) ports.add(port("solo" + i + ".example.com", 22, A, true, null, true, null));
        port("www.b.example.com", 443, C, false, null, true, null);
        PingMonitor p = ping("p.example.com", A, true, true, null);
        CountingJdbc cj = new CountingJdbc(jdbc.getDataSource());
        NocMonitorDirectory dir = new NocMonitorDirectory(cj);

        NocMonitorDirectory.Snapshot snap = dir.snapshot();
        for (PortMonitor pm : ports) assertThat(snap.forAlert(NocType.PORT, pm.getHost(), pm.getId())).isNotNull();
        assertThat(snap.forAlert(NocType.PORT, "www.b.example.com", null).teamId()).isEqualTo(B);
        assertThat(snap.forAlert(NocType.SSL, "www.b.example.com", null)).isNotNull();
        assertThat(snap.forAlert(NocType.PING, "p.example.com", p.getId())).isNotNull();

        assertThat(cj.count("FROM port_monitors")).isEqualTo(1);
        assertThat(cj.count("FROM ping_monitors")).isEqualTo(1);
        assertThat(cj.count("SELECT domain, team_id, ug_team_id, port FROM certificate_inventory")).as("tam envanter taraması").isEqualTo(1);
        assertThat(cj.count("SELECT id, domain, port, team_id")).as("SSL satırları").isEqualTo(1);
    }

    @Test
    @DisplayName("tekil arama tabloyu TARAMAZ: DNS/Port dışında envanter hiç okunmaz; kimliksiz SSL yalnız o alan adı")
    void singleLookupsDoNotScan() {
        inv("www.b.example.com", B, null, true, null, true);
        PingMonitor p = ping("p.example.com", A, true, true, null);
        PortMonitor derived = port("www.b.example.com", 443, C, false, null, true, null);
        CountingJdbc cj = new CountingJdbc(jdbc.getDataSource());
        NocMonitorDirectory dir = new NocMonitorDirectory(cj);

        assertThat(dir.forAlert(NocType.PING, "p.example.com", p.getId())).isNotNull();
        assertThat(cj.count("certificate_inventory")).isZero();
        assertThat(dir.forAlert(NocType.SSL, "www.b.example.com", null).teamId()).isEqualTo(B);
        assertThat(dir.forAlert(NocType.PORT, "www.b.example.com", derived.getId()).teamId()).isEqualTo(B);
        assertThat(cj.count("SELECT domain, team_id, ug_team_id, port FROM certificate_inventory")).as("tam tarama yok").isZero();
    }

    @Test
    @DisplayName("alarm listesi e-posta sayısı 7/24 satırlarını SAYMAZ (takım bildirimi değildir)")
    void teamMailCountExcludesNocRows() {
        for (String role : List.of("COMBINED", "NOC")) {
            com.sitemonitor.model.NotificationLog n = new com.sitemonitor.model.NotificationLog();
            n.setAlertEventId(4242L); n.setSentAt(NOW); n.setEmailStatus("SENT"); n.setRecipientRole(role);
            logRepo.save(n);
        }
        com.sitemonitor.model.NotificationLog legacy = new com.sitemonitor.model.NotificationLog();
        legacy.setAlertEventId(4242L); legacy.setSentAt(NOW); legacy.setEmailStatus("FAILED: 550");   // rol yok (eski satır)
        logRepo.save(legacy);
        List<Object[]> rows = logRepo.countByAlertIds(List.of(4242L));
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[1]).longValue()).as("sent").isEqualTo(1);
        assertThat(((Number) rows.get(0)[2]).longValue()).as("failed").isEqualTo(1);
        jdbc.update("DELETE FROM notification_logs");
    }

    @Test
    @DisplayName("saklama: AÇIK alarmın ve sürmekte olan fırtınanın teslim izi silinmez; çözülmüşünki silinir")
    void retentionKeepsOpenAlertRows() {
        String old = "2020-01-01T00:00:00";
        jdbc.update("INSERT INTO alert_events (id, domain, alert_level, alert_type, resolved, acknowledged, created_at) "
                + "VALUES (9001, 'a.example.com', 'CRITICAL', 'PING_DOWN', FALSE, FALSE, ?)", old);
        jdbc.update("INSERT INTO alert_events (id, domain, alert_level, alert_type, resolved, acknowledged, created_at) "
                + "VALUES (9002, 'b.example.com', 'CRITICAL', 'PING_DOWN', TRUE, FALSE, ?)", old);
        jdbc.update("INSERT INTO noc_deliveries (dedupe_key, alert_event_id, phase, status, created_at) VALUES ('alert:9001:OPEN', 9001, 'OPEN', 'SENT', ?)", old);
        jdbc.update("INSERT INTO noc_deliveries (dedupe_key, alert_event_id, phase, status, created_at) VALUES ('alert:9002:OPEN', 9002, 'OPEN', 'SENT', ?)", old);
        jdbc.update("INSERT INTO alert_storms (id, scope_key, resolved, created_at) VALUES (9101, 'GLOBAL', FALSE, ?)", old);
        jdbc.update("INSERT INTO alert_storms (id, scope_key, resolved, created_at) VALUES (9102, 'GLOBAL', TRUE, ?)", old);
        jdbc.update("INSERT INTO noc_deliveries (dedupe_key, storm_id, phase, status, created_at) VALUES ('storm:9101:OPEN', 9101, 'OPEN', 'SENT', ?)", old);
        jdbc.update("INSERT INTO noc_deliveries (dedupe_key, storm_id, phase, status, created_at) VALUES ('storm:9102:OPEN', 9102, 'OPEN', 'SENT', ?)", old);
        var policy = com.sitemonitor.service.retention.RetentionCatalog.ALL.stream()
                .filter(x -> x.id().equals("noc-deliveries")).findFirst().orElseThrow();
        int deleted = jdbc.update(policy.deleteSql(), "2025-01-01T00:00:00");
        assertThat(deleted).as("yalnız çözülmüş alarmın ve bitmiş fırtınanın izi").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM noc_deliveries WHERE alert_event_id = 9001", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM noc_deliveries WHERE storm_id = 9101", Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM noc_deliveries");
        jdbc.update("DELETE FROM alert_storms WHERE id IN (9101, 9102)");
        jdbc.update("DELETE FROM alert_events WHERE id IN (9001, 9002)");
    }
}
