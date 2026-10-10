package com.sitemonitor.service.quality;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateCheck;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.noc.NocConfigService;
import com.sitemonitor.service.noc.NocGroupService;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.report.InventoryHygieneService;
import com.sitemonitor.service.quality.DataQualitySource.Facts;
import com.sitemonitor.service.quality.DataQualitySource.MonitorFact;
import com.sitemonitor.service.quality.DataQualitySource.TeamFact;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Veri kalitesi girdilerinin GERÇEK SQL'i (H2, PostgreSQL kipi) + sabit sorgu bütçesi + günlük görüntü yazımı.
 */
@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
        replace = org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)   // MODE=PostgreSQL (ON CONFLICT)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class DataQualityQueriesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory emf;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired TeamRepository teamRepo;
    @Autowired AppUserRepository userRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired EscalationContactRepository contactRepo;
    @Autowired NotificationGroupRepository groupRepo;
    @Autowired MonitorChangeLogRepository changeLogRepo;
    @Autowired CertificateCheckRepository checkRepo;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired PingMonitorRepository pingRepo;

    JdbcTemplate jdbc;
    NocConfigService nocConfig;
    NocGroupService nocGroups;
    InventoryHygieneService hygiene;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.spy(new JdbcTemplate(dataSource));
        nocConfig = mock(NocConfigService.class);
        when(nocConfig.get()).thenReturn(new NocConfigService.Config(Set.of(), "HIGH", true, null, null, null));
        nocGroups = mock(NocGroupService.class);
        when(nocGroups.list()).thenReturn(List.of());
        hygiene = mock(InventoryHygieneService.class);
        when(hygiene.analyze(any(), anyInt())).thenReturn(new InventoryHygieneService.Result(List.of(
                new InventoryHygieneService.Group("stale", "x", 1, List.of(
                        new InventoryHygieneService.Finding("Pay.Example.com", "son kontrol …", List.of("stale")))),
                new InventoryHygieneService.Group("error", "x", 1, List.of(
                        new InventoryHygieneService.Finding("pay.example.com", "Connection refused", List.of("error"))))), 2));
    }

    /** JdbcTemplate sorgusu Hibernate'ı flush'lamaz — kurulan satırlar (üyelik koleksiyonu dahil) önce yazılsın. */
    private DataQualitySource source() {
        em.flush();
        return new DataQualitySource(jdbc, inventoryRepo, contactRepo, groupRepo, changeLogRepo, hygiene, nocConfig, nocGroups);
    }

    private Team team(String name, String email, Long leader, Long manager, boolean active) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(email);
        t.setLeaderId(leader);
        t.setManagerId(manager);
        t.setActive(active);
        return teamRepo.save(t);
    }

    private AppUser user(String name, boolean active, Long primary, Long... extra) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setSystemRole("USER");
        u.setActive(active);
        u.setTeamId(primary);
        if (primary != null) u.getTeamIds().add(primary);
        for (Long e : extra) u.getTeamIds().add(e);
        return userRepo.save(u);
    }

    private CertificateInventory inv(String domain, Long team, boolean active, Integer port) {
        CertificateInventory r = new CertificateInventory();
        r.setDomain(domain);
        r.setPort(port);
        r.setTeamId(team);
        r.setActive(active);
        return inventoryRepo.save(r);
    }

    @Test
    @DisplayName("takımlar: lider/müdür hesabının aktifliği LEFT JOIN ile; e-posta boşluğu adres sayılmaz")
    void teams() {
        AppUser lead = user("lead", true, null);
        AppUser gone = user("gone", false, null);
        Team a = team("A", "a@example.com", lead.getId(), null, true);
        Team b = team("B", "  ", gone.getId(), gone.getId(), true);
        team("C", null, null, null, false);
        Map<Long, TeamFact> byId = new java.util.HashMap<>();
        for (TeamFact t : source().teams()) byId.put(t.id(), t);
        assertThat(byId.get(a.getId())).isEqualTo(new TeamFact(a.getId(), "A", true, true, true, true, false, false));
        assertThat(byId.get(b.getId())).isEqualTo(new TeamFact(b.getId(), "B", true, false, true, false, true, false));
        assertThat(byId).hasSize(3);
    }

    @Test
    @DisplayName("aktif üye sayısı: birincil ∪ çoklu üyelik, kullanıcı başına bir; pasif hesap sayılmaz")
    void members() {
        Team a = team("A", null, null, null, true);
        Team b = team("B", null, null, null, true);
        user("u1", true, a.getId(), b.getId());
        user("u2", true, a.getId());
        user("u3", false, b.getId());
        AppUser u4 = new AppUser();   // birincil takımı var ama app_user_teams satırı YOK (eski kayıt)
        u4.setUsername("u4");
        u4.setSystemRole("USER");
        u4.setActive(true);
        u4.setTeamId(b.getId());
        userRepo.save(u4);
        Map<Long, Integer> m = source().activeMembers();
        assertThat(m).containsEntry(a.getId(), 2).containsEntry(b.getId(), 2);
    }

    @Test
    @DisplayName("eskalasyon: YÜKSEK = UYARI/YÜKSEK eşikli kişi, KRİTİK = her aktif kişi; adressiz kişi sayılmaz")
    void escalation() {
        Team a = team("A", null, null, null, true);
        Team b = team("B", null, null, null, true);
        Team c = team("C", null, null, null, true);
        contactRepo.save(contact(a.getId(), "CRITICAL", "m@example.com", true));
        contactRepo.save(contact(b.getId(), "HIGH", "x@example.com", true));
        contactRepo.save(contact(c.getId(), "WARNING", null, true));   // adres/webhook yok
        contactRepo.save(contact(c.getId(), "HIGH", "y@example.com", false));   // pasif
        Map<Long, DataQualitySource.Escalation> e = source().escalation(List.of(a.getId(), b.getId(), c.getId()));
        assertThat(e.get(a.getId())).isEqualTo(new DataQualitySource.Escalation(false, true));
        assertThat(e.get(b.getId())).isEqualTo(new DataQualitySource.Escalation(true, true));
        assertThat(e).doesNotContainKey(c.getId());
    }

    private static EscalationContact contact(Long team, String level, String email, boolean active) {
        EscalationContact c = new EscalationContact();
        c.setTeamId(team);
        c.setName("k");
        c.setRole("MANAGER");
        c.setMinAlertLevel(level);
        c.setEmail(email);
        c.setActive(active);
        return c;
    }

    @Test
    @DisplayName("bildirim grubu: yalnız aktif VE adresli grup")
    void groups() {
        Team a = team("A", null, null, null, true);
        Team b = team("B", null, null, null, true);
        groupRepo.save(group(a.getId(), "n@example.com", true));
        groupRepo.save(group(b.getId(), "n@example.com", false));
        assertThat(source().groupAddress(List.of(a.getId(), b.getId()))).containsExactly(a.getId());
    }

    private static NotificationGroup group(Long team, String emails, boolean active) {
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(team);
        g.setName("g" + team + active);
        g.setEmails(emails);
        g.setActive(active);
        return g;
    }

    @Test
    @DisplayName("tarama hata sayısı: son 7 gün, alan adı küçük harf; pencere dışı ve hatasız sayılmaz")
    void errorCounts() {
        scan("Pay.example.com", "error", "2026-10-09T10:00:00");
        scan("pay.example.com", "error", "2026-10-04T10:00:00");
        scan("pay.example.com", "error", "2026-10-02T10:00:00");   // pencere dışı (7 gün = 10-03T09:00)
        scan("pay.example.com", "valid", "2026-10-09T11:00:00");
        assertThat(source().errorCounts(NOW)).containsExactly(Map.entry("pay.example.com", 2));
    }

    private void scan(String domain, String status, String at) {
        CertificateCheck c = new CertificateCheck();
        c.setDomain(domain);
        c.setStatus(status);
        c.setCheckedAt(at);
        checkRepo.save(c);
    }

    @Test
    @DisplayName("izlemeler: hafif kolonlar; türev Port yalnız envanterin KENDİ portunda, türev DNS yalnız envanter AKTİFken; takım envanterden")
    void monitors() {
        Team a = team("A", null, null, null, true);
        Team b = team("B", null, null, null, true);
        inv("svc.example.com", b.getId(), true, 8443);
        inv("off.example.com", b.getId(), false, 443);

        HttpMonitor h = new HttpMonitor();
        h.setName("API");
        h.setUrl("HTTPS://Api.Example.com/");
        h.setMethod("get");
        h.setTeamId(a.getId());
        h.setGroupName("Çekirdek");
        httpRepo.save(h);

        port("svc.example.com", 8443, a.getId(), false);   // türev, envanterin portu → takım B
        port("svc.example.com", 22, a.getId(), false);     // türev ama başka port → yok
        port("db.example.com", 5432, a.getId(), true);     // bağımsız

        dns("svc.example.com", "A", a.getId(), false);     // türev, envanter aktif → takım B
        dns("off.example.com", "A", a.getId(), false);     // türev, envanter pasif → yok

        PingMonitor p = new PingMonitor();
        p.setName("gw");
        p.setHost(" GW.example.com ");
        p.setTeamId(a.getId());
        p.setActive(false);
        pingRepo.save(p);

        List<MonitorFact> list = source().monitors(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc());
        assertThat(list).extracting(m -> m.type() + ":" + m.target() + ":" + m.teamId().equals(b.getId()) + ":" + m.standalone())
                .containsExactlyInAnyOrder(
                        "HTTP:HTTPS://Api.Example.com/:false:true",
                        "PORT:svc.example.com:8443:true:false",
                        "PORT:db.example.com:5432:false:true",
                        "DNS:svc.example.com (A):true:false",
                        "PING: GW.example.com :false:true");
        MonitorFact http = list.stream().filter(m -> m.type() == NocType.HTTP).findFirst().orElseThrow();
        assertThat(http.dupKey()).isEqualTo("GET https://api.example.com");
        assertThat(http.groupName()).isEqualTo("Çekirdek");
        MonitorFact ping = list.stream().filter(m -> m.type() == NocType.PING).findFirst().orElseThrow();
        assertThat(ping.dupKey()).isEqualTo("gw.example.com");
        assertThat(ping.active()).isFalse();
    }

    @Test
    @DisplayName("dokuz türün HER sorgusu gerçek şemada hatasız koşar (kolon adı yanlışsa yükleme sessizce o türü atlardı)")
    void everyMonitorQueryRuns() {
        for (NocType t : NocType.values()) {
            if (t == NocType.SSL) continue;
            java.util.List<MonitorFact> out = new java.util.ArrayList<>();
            org.assertj.core.api.Assertions.assertThatCode(() -> source().load(t, Map.of(), out))
                    .as("%s sorgusu", t).doesNotThrowAnyException();
        }
    }

    private void port(String host, int port, Long team, boolean standalone) {
        PortMonitor m = new PortMonitor();
        m.setName(host + ":" + port);
        m.setHost(host);
        m.setPort(port);
        m.setTeamId(team);
        m.setStandalone(standalone);
        portRepo.save(m);
    }

    private void dns(String domain, String rt, Long team, boolean standalone) {
        DnsMonitor m = new DnsMonitor();
        m.setName(domain);
        m.setDomain(domain);
        m.setRecordType(rt);
        m.setTeamId(team);
        m.setStandalone(standalone);
        dnsRepo.save(m);
    }

    @Test
    @DisplayName("URL normalizasyonu: şema + ana makine küçük harf, yol korunur, tek '/' yok sayılır")
    void normUrl() {
        assertThat(DataQualitySource.normUrl("HTTPS://Api.Example.com/")).isEqualTo("https://api.example.com");
        assertThat(DataQualitySource.normUrl("https://api.example.com/Path?q=1")).isEqualTo("https://api.example.com/Path?q=1");
        assertThat(DataQualitySource.normUrl(" api.example.com ")).isEqualTo("api.example.com");
        assertThat(DataQualitySource.normUrl(null)).isNull();
    }

    @Test
    @DisplayName("duraklatma geçmişi: 'TÜR:kimlik' → active'in son değiştiği an (değişiklik geçmişinden)")
    void pausedSince() {
        Team a = team("A", null, null, null, true);
        CertificateInventory off = inv("off.example.com", a.getId(), false, 443);
        changeLogRepo.save(change("INVENTORY", off.getId(), "2026-08-01T10:00:00", "{\"active\":{\"from\":true,\"to\":false}}"));
        changeLogRepo.save(change("INVENTORY", off.getId(), "2026-07-01T10:00:00", "{\"active\":{\"from\":false,\"to\":true}}"));
        changeLogRepo.save(change("INVENTORY", off.getId(), "2026-09-01T10:00:00", "{\"owner\":{\"from\":\"a\",\"to\":\"b\"}}"));
        Map<String, String> m = source().pausedSince(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc(), List.of());
        assertThat(m).containsEntry("INVENTORY:" + off.getId(), "2026-08-01T10:00:00");
    }

    private static MonitorChangeLog change(String kind, Long id, String at, String changes) {
        MonitorChangeLog c = new MonitorChangeLog();
        c.setResourceKind(kind);
        c.setResourceId(id);
        c.setResourceName("x");
        c.setEventType("UPDATE");
        c.setCreatedAt(at);
        c.setChanges(changes);
        c.setSeq(1);
        return c;
    }

    @Test
    @DisplayName("tam yükleme uçtan uca: hijyen kodları alan adı küçük harfle eşleşir; 7/24 grubu yoksa NO_ACTIVE_GROUP")
    void loadEndToEnd() {
        Team a = team("A", "a@example.com", null, null, true);
        inv("pay.example.com", a.getId(), true, 443);
        scan("pay.example.com", "error", "2026-10-09T10:00:00");
        Facts f = source().load(NOW);
        assertThat(f.hygieneCodes().get("pay.example.com")).containsExactlyInAnyOrder("stale", "error");
        assertThat(f.hygieneErrors()).containsEntry("pay.example.com", "Connection refused");
        assertThat(f.errorCounts()).containsEntry("pay.example.com", 1);
        assertThat(f.noc().usable()).isFalse();
        assertThat(f.noc().reason()).isEqualTo("NO_ACTIVE_GROUP");
        DataQualityEvaluator.Evaluation e = DataQualityEvaluator.evaluate(f, NOW);
        assertThat(e.team(a.getId()).counts().get(DataQualityRule.INV_STALE_CHECK).failing()).isEqualTo(1);
    }

    @Test
    @DisplayName("SABİT SORGU BÜTÇESİ: 2 takım / 1 kayıt ile 12 takım / 30 kayıt / 20 izleme aynı sayıda sorgu")
    void fixedQueryCount() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        Team a = team("A", null, null, null, true);
        team("B", null, null, null, true);
        inv("a0.example.com", a.getId(), true, 443);
        inv("a1.example.com", a.getId(), false, 443);   // duraklatılmış (geçmiş sorgusu iki durumda da koşsun)
        long[] small = measure(stats);

        for (int i = 0; i < 10; i++) {
            Team t = team("T" + i, null, null, null, true);
            user("u" + i, true, t.getId());
            contactRepo.save(contact(t.getId(), "HIGH", "k" + i + "@example.com", true));
            groupRepo.save(group(t.getId(), "g" + i + "@example.com", true));
            for (int j = 0; j < 3; j++) inv("t" + i + "-" + j + ".example.com", t.getId(), j != 0, 443);
            port("p" + i + ".example.com", 22, t.getId(), true);
            PingMonitor p = new PingMonitor();
            p.setName("p" + i);
            p.setHost("h" + i);
            p.setTeamId(t.getId());
            p.setActive(false);
            pingRepo.save(p);
        }
        long[] large = measure(stats);
        assertThat(large[0]).as("JdbcTemplate sorgu sayısı").isEqualTo(small[0]);
        assertThat(large[1]).as("JPA hazırlanan cümle sayısı").isEqualTo(small[1]);
        // takımlar + üyeler + hata sayısı + 9 izleme türü (JDBC) · envanter + kişiler + gruplar + duraklatma geçmişi (JPA)
        assertThat(large[0]).as("JdbcTemplate sorguları").isEqualTo(12);
        assertThat(large[1]).as("JPA cümleleri").isEqualTo(4);
    }

    /** [JdbcTemplate sorgu çağrısı, Hibernate hazırlanan cümle] — bir tam yükleme. */
    private long[] measure(Statistics stats) {
        em.flush();   // önceki kayıtların yazımı sayılmasın
        em.clear();
        DataQualitySource src = source();
        Mockito.clearInvocations(jdbc);
        stats.clear();
        src.load(NOW);
        // Yalnız DataQualitySource'un ÇAĞIRDIĞI sorgular — casusun JdbcTemplate içi kendi-kendine çağrıları sayılmaz
        long jdbcCalls = Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("query"))
                .filter(i -> String.valueOf(i.getLocation()).contains("DataQualitySource"))
                .count();
        return new long[]{jdbcCalls, stats.getPrepareStatementCount()};
    }

    @Test
    @DisplayName("günlük görüntü: gün × kova tek satır (ikinci yazım no-op), eskiler budanır, eğilim okunur")
    void snapshotWriteAndTrend() {
        DataQualityFixtures fx = new DataQualityFixtures().team(7, "Ödeme");
        fx.inv(10, "pay.example.com", 7L).setTier(null);
        DataQualitySource src = mock(DataQualitySource.class);
        when(src.load(any())).thenReturn(fx.facts());
        JdbcTemplate plain = new JdbcTemplate(dataSource);
        plain.update(DataQualityService.SQL_INSERT, "2026-05-01", 0L, 50, 1, 1, "2026-05-01T01:00:00");   // budanacak
        plain.update(DataQualityService.SQL_INSERT, "2026-10-03", 0L, 60, 1, 1, "2026-10-03T01:00:00");   // 7 gün önce
        plain.update(DataQualityService.SQL_INSERT, "2026-10-03", 7L, 40, 1, 1, "2026-10-03T01:00:00");

        DataQualityService svc = new DataQualityService(src, plain, 60_000);
        svc.clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        assertThat(svc.snapshotWritten(NOW)).isFalse();
        assertThat(svc.writeDailySnapshot(NOW)).isEqualTo(3);   // kurum + Sahipsiz + 1 takım
        assertThat(svc.writeDailySnapshot(NOW)).isEqualTo(3);
        assertThat(plain.queryForObject("SELECT COUNT(*) FROM data_quality_daily WHERE snap_day = '2026-10-10'", Integer.class))
                .isEqualTo(3);
        assertThat(plain.queryForObject("SELECT COUNT(*) FROM data_quality_daily WHERE snap_day = '2026-05-01'", Integer.class))
                .as("120 günden eski budanır").isZero();
        assertThat(svc.snapshotWritten(NOW)).isTrue();

        DataQualityService.Viewer all = new DataQualityService.Viewer(true, true, id -> true, id -> true, id -> true, id -> true);
        Map<String, Object> s = svc.summary(all, false);
        @SuppressWarnings("unchecked") Map<String, Object> org = (Map<String, Object>) s.get("org");
        assertThat((List<?>) org.get("trend")).hasSize(2);
        assertThat(org.get("delta_7d")).isEqualTo((Integer) org.get("score") - 60);
        @SuppressWarnings("unchecked") Map<String, Object> team = ((List<Map<String, Object>>) s.get("teams")).get(0);
        assertThat(team.get("delta_7d")).isEqualTo((Integer) team.get("score") - 40);
    }
}
