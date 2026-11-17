package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.MonitorSchedule;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yoklanan uçların dar sorguları (2026-11-17 perf) — gerçek SQL (H2). Tam varlık okuyup Java'da sayan / süzen ESKİ yolların
 * sonucuyla birebir aynı olduklarını sabitler: takım sayaçları (kullanıcı, envanter, on izleme türü, açık alarm, kişi, grup),
 * nöbetçi / kullanıcı etkinliği satırları (fotoğraf yerine VAR MI bayrağı), alan adı rozet satırları (sıra), aynı URL satırları.
 * {@code NOT_SUPPORTED}: üretimdeki gibi çağıransız okuma; satırlar her testte temizlenir.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PolledProjectionQueriesH2Test {

    @Autowired TeamRepository teams;
    @Autowired AppUserRepository users;
    @Autowired CertificateInventoryRepository inventory;
    @Autowired EscalationContactRepository contacts;
    @Autowired NotificationGroupRepository groups;
    @Autowired AlertEventRepository alerts;
    @Autowired HttpMonitorRepository http;
    @Autowired PortMonitorRepository port;
    @Autowired PingMonitorRepository ping;
    @Autowired DnsMonitorRepository dns;
    @Autowired KeywordMonitorRepository keyword;
    @Autowired PageMonitorRepository page;
    @Autowired PageSpeedMonitorRepository pageSpeed;
    @Autowired ScriptedMonitorRepository scripted;
    @Autowired DomainMonitorRepository domain;
    @Autowired DbQueryMonitorRepository dbQuery;

    @BeforeEach
    @AfterEach
    void clean() {
        alerts.deleteAll();
        http.deleteAll();
        port.deleteAll();
        keyword.deleteAll();
        domain.deleteAll();
        contacts.deleteAll();
        groups.deleteAll();
        inventory.deleteAll();
        users.deleteAll();
        teams.deleteAll();
    }

    private Team team(String name) {
        Team t = new Team();
        t.setName(name);
        t.setActive(true);
        return teams.save(t);
    }

    private AppUser user(String name, Long primary, Boolean active, String photo, Long... extra) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setDisplayName("Ad " + name);
        u.setEmail(name.toLowerCase() + "@example.com");
        u.setTeamId(primary);
        u.setActive(active);
        u.setPhotoBase64(photo);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (primary != null) ids.add(primary);
        ids.addAll(List.of(extra));
        u.setTeamIds(ids);
        u.setLastLoginAt("2026-11-01T10:00:00");
        u.setTourState("{\"status\":\"completed\"}");
        u.setFailedSinceLogin(2);
        return users.save(u);
    }

    private CertificateInventory inv(String d, Long team, Long ug, boolean active) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(d);
        i.setPort(443);
        i.setTeamId(team);
        i.setUgTeamId(ug);
        i.setActive(active);
        return inventory.save(i);
    }

    private HttpMonitor httpMon(String name, Long team, boolean active) {
        HttpMonitor m = new HttpMonitor();
        m.setName(name); m.setUrl("https://" + name + ".example.test/"); m.setTeamId(team); m.setActive(active);
        return http.save(m);
    }

    private PortMonitor portMon(String host, Long team, boolean active) {
        PortMonitor m = new PortMonitor();
        m.setName(host); m.setHost(host); m.setPort(443); m.setTeamId(team); m.setActive(active);
        return port.save(m);
    }

    private DomainMonitor domainMon(String d, Long team, boolean active) {
        DomainMonitor m = new DomainMonitor();
        m.setName(d); m.setDomain(d); m.setTeamId(team); m.setActive(active);
        return domain.save(m);
    }

    private KeywordMonitor kw(String name, String url, Long team, boolean active) {
        KeywordMonitor m = new KeywordMonitor();
        m.setName(name); m.setUrl(url); m.setKeyword("Ödeme " + name); m.setTeamId(team); m.setActive(active);
        return keyword.save(m);
    }

    private AlertEvent alert(String d, String type, String level, Long team, boolean resolved, boolean ack, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(d); e.setAlertType(type); e.setAlertLevel(level); e.setTeamId(team); e.setResolved(resolved);
        e.setAcknowledged(ack); e.setCreatedAt(createdAt);
        return alerts.save(e);
    }

    private EscalationContact contact(String name, Long team, boolean active) {
        EscalationContact c = new EscalationContact();
        c.setName(name); c.setRole("PO"); c.setTeamId(team); c.setActive(active); c.setEmail(name + "@example.com");
        return contacts.save(c);
    }

    private NotificationGroup group(String name, Long team, boolean active) {
        NotificationGroup g = new NotificationGroup();
        g.setName(name); g.setTeamId(team); g.setActive(active); g.setIsDefault(false); g.setEmails("g@example.com");
        return groups.save(g);
    }

    private static Map<Long, Long> asMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) m.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return m;
    }

    private static Map<Long, Long> grouped(List<Long> teamIds) {
        Map<Long, Long> m = new HashMap<>();
        for (Long t : teamIds) if (t != null) m.merge(t, 1L, Long::sum);
        return m;
    }

    @Test
    @DisplayName("gruplu sayımlar = eski findAll + Java süzgeci (on izleme türü, açık alarm, etkin kişi, etkin grup)")
    void groupedCountsEqualOldJavaCounts() {
        Team a = team("A-perf"), b = team("B-perf");
        httpMon("h1", a.getId(), true); httpMon("h2", a.getId(), true); httpMon("h3", b.getId(), false); httpMon("h4", null, true);
        portMon("p1.example.test", b.getId(), true); portMon("p2.example.test", null, true);
        domainMon("d1.example.test", a.getId(), true); domainMon("d2.example.test", b.getId(), false);
        kw("k1", "https://k.example.test/", b.getId(), true);
        alert("x.example.test", "PING_DOWN", "CRITICAL", a.getId(), false, false, "2026-11-01T10:00:00");
        alert("y.example.test", "PING_DOWN", "HIGH", a.getId(), true, false, "2026-11-01T10:00:00");
        alert("z.example.test", "PING_DOWN", "HIGH", null, false, false, "2026-11-01T10:00:00");
        contact("c1", a.getId(), true); contact("c2", a.getId(), false); contact("c3", b.getId(), true);
        group("g1", a.getId(), true); group("g2", b.getId(), false); group("g3", b.getId(), true);

        Map<String, Supplier<List<? extends MonitorSchedule>>> all = new LinkedHashMap<>();
        Map<String, Supplier<List<Object[]>>> counts = new LinkedHashMap<>();
        all.put("http", http::findAll); counts.put("http", http::countActiveByTeam);
        all.put("port", port::findAll); counts.put("port", port::countActiveByTeam);
        all.put("ping", ping::findAll); counts.put("ping", ping::countActiveByTeam);
        all.put("dns", dns::findAll); counts.put("dns", dns::countActiveByTeam);
        all.put("keyword", keyword::findAll); counts.put("keyword", keyword::countActiveByTeam);
        all.put("page", page::findAll); counts.put("page", page::countActiveByTeam);
        all.put("pagespeed", pageSpeed::findAll); counts.put("pagespeed", pageSpeed::countActiveByTeam);
        all.put("scripted", scripted::findAll); counts.put("scripted", scripted::countActiveByTeam);
        all.put("domain", domain::findAll); counts.put("domain", domain::countActiveByTeam);
        all.put("dbquery", dbQuery::findAll); counts.put("dbquery", dbQuery::countActiveByTeam);
        for (String type : all.keySet()) {
            List<Long> old = all.get(type).get().stream().filter(m -> Boolean.TRUE.equals(m.getActive()))
                    .map(MonitorSchedule::getTeamId).toList();
            assertThat(asMap(counts.get(type).get())).as(type).isEqualTo(grouped(old));
        }
        assertThat(asMap(http.countActiveByTeam())).isEqualTo(Map.of(a.getId(), 2L));
        assertThat(asMap(alerts.countOpenByTeam())).isEqualTo(grouped(alerts.findAllOpenOrderBySeverity().stream().map(AlertEvent::getTeamId).toList()))
                .isEqualTo(Map.of(a.getId(), 1L));
        assertThat(asMap(contacts.countActiveByTeam())).isEqualTo(grouped(contacts.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive())).map(EscalationContact::getTeamId).toList()));
        assertThat(asMap(groups.countActiveByTeam())).isEqualTo(grouped(groups.findAll().stream()
                .filter(g -> !Boolean.FALSE.equals(g.getActive())).map(NotificationGroup::getTeamId).toList()))
                .isEqualTo(Map.of(a.getId(), 1L, b.getId(), 1L));
    }

    @Test
    @DisplayName("takım sayaçları (TeamAdminService.stats) gerçek SQL ile eski tam varlık yolunun birebir aynısı")
    void teamStatsEqualOldEntityWay() {
        Team a = team("A-stats"), b = team("B-stats"), c = team("C-stats");
        user("U1", a.getId(), true, null);
        user("U2", b.getId(), true, "iVBOR", a.getId());
        user("U3", a.getId(), false, null, b.getId(), c.getId());
        user("U4", null, true, null);
        inv("i1.example.test", a.getId(), b.getId(), true);
        inv("i2.example.test", b.getId(), b.getId(), true);
        inv("i3.example.test", c.getId(), a.getId(), false);
        httpMon("s1", a.getId(), true); portMon("s2.example.test", c.getId(), true); domainMon("s3.example.test", c.getId(), false);
        alert("o1.example.test", "PING_DOWN", "HIGH", c.getId(), false, false, "2026-11-01T10:00:00");
        contact("sc1", b.getId(), true); group("sg1", c.getId(), true);

        TeamAdminService svc = new TeamAdminService(teams, users, inventory, contacts, groups, alerts, http, port, ping, dns,
                keyword, page, pageSpeed, scripted, domain);
        svc.setDbQueryRepos(dbQuery, null, null);
        Map<Long, Map<String, Object>> got = svc.stats();

        // Eski yol (2026-11-17 öncesi stats gövdesi): tam varlıklar + Java sayımı
        Map<Long, int[]> exp = new LinkedHashMap<>();
        for (Team t : teams.findAll()) exp.put(t.getId(), new int[8]);
        java.util.function.BiConsumer<Long, Integer> bump = (id, idx) -> { if (id != null && exp.containsKey(id)) exp.get(id)[idx]++; };
        for (AppUser u : users.findAll()) {
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            if (u.getTeamId() != null) ids.add(u.getTeamId());
            if (u.getTeamIds() != null) ids.addAll(u.getTeamIds());
            boolean inactive = !Boolean.TRUE.equals(u.getActive());
            for (Long id : ids) { bump.accept(id, 0); if (inactive) bump.accept(id, 6); }
        }
        for (CertificateInventory i : inventory.findByActiveTrueOrderByDomainAsc()) {
            bump.accept(i.getTeamId(), 1);
            if (i.getUgTeamId() != null && !i.getUgTeamId().equals(i.getTeamId())) bump.accept(i.getUgTeamId(), 7);
        }
        List<List<? extends MonitorSchedule>> monitorLists = List.of(http.findAll(), port.findAll(), ping.findAll(), dns.findAll(),
                keyword.findAll(), page.findAll(), pageSpeed.findAll(), scripted.findAll(), domain.findAll(), dbQuery.findAll());
        for (List<? extends MonitorSchedule> list : monitorLists)
            for (MonitorSchedule m : list) if (Boolean.TRUE.equals(m.getActive())) bump.accept(m.getTeamId(), 2);
        for (AlertEvent e : alerts.findAllOpenOrderBySeverity()) bump.accept(e.getTeamId(), 3);
        for (EscalationContact ec : contacts.findAll()) if (Boolean.TRUE.equals(ec.getActive())) bump.accept(ec.getTeamId(), 4);
        for (NotificationGroup g : groups.findAll()) if (!Boolean.FALSE.equals(g.getActive())) bump.accept(g.getTeamId(), 5);

        assertThat(got.keySet()).containsExactlyElementsOf(exp.keySet());
        String[] keys = {"members", "domains", "monitors", "open_alerts", "contacts", "groups", "members_inactive", "ug_domains"};
        for (var e : exp.entrySet()) {
            for (int i = 0; i < keys.length; i++) {
                assertThat(got.get(e.getKey())).as(e.getKey() + " " + keys[i]).containsEntry(keys[i], e.getValue()[i]);
            }
        }
        assertThat(got.get(a.getId())).containsEntry("members", 3).containsEntry("members_inactive", 1).containsEntry("domains", 1);
        assertThat(got.get(b.getId())).containsEntry("ug_domains", 1);
    }

    @Test
    @DisplayName("nöbetçi / kullanıcı etkinliği / sayaç satırları: değerler varlıkla aynı; fotoğraf okunmaz, yalnız VAR MI bayrağı")
    void userRowsMatchEntities() {
        Team a = team("A-users");
        AppUser withPhoto = user("P1", a.getId(), true, "iVBORw0KGgo=");
        AppUser blank = user("P2", null, false, "   ");
        AppUser empty = user("P3", a.getId(), true, "");
        AppUser none = user("P4", a.getId(), true, null);
        List<Long> ids = List.of(withPhoto.getId(), blank.getId(), empty.getId(), none.getId());

        Map<Long, Object[]> oncall = new HashMap<>();
        for (Object[] r : users.findOnCallPersonRows(ids)) oncall.put(((Number) r[0]).longValue(), r);
        assertThat(oncall).hasSize(4);
        assertThat(oncall.get(withPhoto.getId())).containsExactly(withPhoto.getId(), "P1", "Ad P1", "p1@example.com", true);
        assertThat(oncall.get(blank.getId())[4]).isEqualTo(false);

        Map<Long, Object[]> act = new HashMap<>();
        for (Object[] r : users.findActivityRows()) act.put(((Number) r[0]).longValue(), r);
        assertThat(act.get(withPhoto.getId())).hasSize(30);
        assertThat(act.get(withPhoto.getId())[29]).isEqualTo(true);
        assertThat(act.get(blank.getId())[29]).isEqualTo(false);
        assertThat(act.get(empty.getId())[29]).isEqualTo(false);
        assertThat(act.get(none.getId())[29]).isEqualTo(false);
        AppUser rebuilt = UserActivityService.activityUser(act.get(withPhoto.getId()), Map.of(withPhoto.getId(), Set.of(a.getId())));
        AppUser stored = users.findById(withPhoto.getId()).orElseThrow();
        assertThat(rebuilt.getUsername()).isEqualTo(stored.getUsername());
        assertThat(rebuilt.getLastLoginAt()).isEqualTo(stored.getLastLoginAt());
        assertThat(rebuilt.getTourState()).isEqualTo(stored.getTourState());
        assertThat(rebuilt.getFailedSinceLogin()).isEqualTo(stored.getFailedSinceLogin());
        assertThat(rebuilt.getSystemRole()).isEqualTo(stored.getSystemRole());

        Map<Long, Object[]> counter = new HashMap<>();
        for (Object[] r : users.findCounterRows()) counter.put(((Number) r[0]).longValue(), r);
        assertThat(counter.get(blank.getId())).containsExactly(blank.getId(), null, false, "USER", false, "2026-11-01T10:00:00");
        Set<String> pairs = new java.util.HashSet<>();
        for (Object[] p : users.findAllTeamMembershipPairs()) pairs.add(p[0] + ":" + p[1]);
        assertThat(pairs).contains(withPhoto.getId() + ":" + a.getId());
    }

    @Test
    @DisplayName("alan adı rozet satırları: yalnız istenen alan adları + türler, sıra findAllOpenOrderBySeverity ile aynı")
    void domainBadgeRowsKeepSeverityOrder() {
        alert("a.example.test", "DOMAINMON_EXPIRY", "WARNING", null, false, false, "2026-11-03T10:00:00");
        alert("a.example.test", "DOMAINMON_STATUS", "CRITICAL", null, false, true, "2026-11-01T10:00:00");
        alert("a.example.test", "DOMAINMON_UNKNOWN", "HIGH", null, false, false, "2026-11-02T10:00:00");
        alert("a.example.test", "DOMAINMON_BLACKLIST", "CRITICAL", null, true, false, "2026-11-04T10:00:00");   // kapalı
        alert("a.example.test", "PING_DOWN", "CRITICAL", null, false, false, "2026-11-05T10:00:00");           // başka tür
        alert("b.example.test", "DOMAINMON_EXPIRY", "WARNING", null, false, false, "2026-11-01T10:00:00");
        Set<String> types = Set.of("DOMAINMON_EXPIRY", "DOMAINMON_UNKNOWN", "DOMAINMON_STATUS", "DOMAINMON_CHANGED",
                "DOMAINMON_TRANSFER_LOCK", "DOMAINMON_BLACKLIST");

        List<Object[]> rows = alerts.findOpenBadgeRowsByDomainIn(types, List.of("a.example.test"));
        List<String> old = new ArrayList<>();
        for (AlertEvent e : alerts.findAllOpenOrderBySeverity()) {
            if (types.contains(e.getAlertType()) && Objects.equals(e.getDomain(), "a.example.test")) old.add(e.getAlertLevel() + "/" + e.getAcknowledged());
        }
        assertThat(rows.stream().map(r -> r[1] + "/" + r[2]).toList()).containsExactlyElementsOf(old)
                .containsExactly("CRITICAL/true", "HIGH/false", "WARNING/false");
        assertThat(rows).allSatisfy(r -> assertThat(r[0]).isEqualTo("a.example.test"));
    }

    @Test
    @DisplayName("aynı URL satırları = findAllByOrderByNameAsc ile aynı sıra ve değerler (dar)")
    void keywordSameUrlRowsEqualEntities() {
        Team a = team("A-kw");
        kw("zeta", "https://shop.example.test/", a.getId(), true);
        kw("alfa", "HTTPS://Shop.Example.test/", null, false);
        kw("beta", "https://baska.example.test/", a.getId(), true);
        List<String> old = keyword.findAllByOrderByNameAsc().stream()
                .map(k -> k.getId() + "|" + k.getName() + "|" + k.getTeamId() + "|" + k.getKeyword() + "|" + k.getActive() + "|" + k.getUrl()).toList();
        List<String> now = keyword.findSameUrlRows().stream()
                .map(r -> r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4] + "|" + r[5]).toList();
        assertThat(now).containsExactlyElementsOf(old).hasSize(3);
    }
}
