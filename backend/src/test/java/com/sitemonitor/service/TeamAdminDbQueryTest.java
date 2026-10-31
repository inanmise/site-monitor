package com.sitemonitor.service;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.DbQueryTemplate;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Veritabanı sorgu izlemesi (2026-10-31) ve takım yönetimi: kayıtlı bağlantılar ve sorgu şablonları TAKIM BELGESİDİR — etki
 * önizlemesi sayar (takım silme 409 "önce taşı"), "Taşı" izlemelerle BİRLİKTE taşır (izleme ve bağlantısı aynı takımda
 * kalır); hedefte aynı adlı izleme / bağlantı / şablon ya da şablon sınırı aşımı 409 — hiçbir şey taşınmaz. Gerçek tablolar (H2).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TeamAdminDbQueryTest {

    @Autowired DataSource dataSource;
    @Autowired TeamRepository teamRepo;
    @Autowired AppUserRepository userRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired EscalationContactRepository contactRepo;
    @Autowired NotificationGroupRepository groupRepo;
    @Autowired AlertEventRepository alertRepo;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired PingMonitorRepository pingRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired KeywordMonitorRepository keywordRepo;
    @Autowired PageMonitorRepository pageRepo;
    @Autowired PageSpeedMonitorRepository pageSpeedRepo;
    @Autowired ScriptedMonitorRepository scriptedRepo;
    @Autowired DomainMonitorRepository domainRepo;
    @Autowired DbQueryMonitorRepository dbQueryRepo;
    @Autowired DbConnectionProfileRepository connectionRepo;
    @Autowired DbQueryTemplateRepository templateRepo;

    TeamAdminService service;
    JdbcTemplate jdbc;
    Long a, b;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        service = new TeamAdminService(teamRepo, userRepo, inventoryRepo, contactRepo, groupRepo, alertRepo, httpRepo, portRepo,
                pingRepo, dnsRepo, keywordRepo, pageRepo, pageSpeedRepo, scriptedRepo, domainRepo);
        service.setJdbc(jdbc);
        service.setDbQueryRepos(dbQueryRepo, connectionRepo, templateRepo);
        a = team("Kaynak Ekip");
        b = team("Hedef Ekip");
    }

    private Long team(String name) {
        Team t = new Team();
        t.setName(name);
        t.setActive(true);
        return teamRepo.saveAndFlush(t).getId();
    }

    private DbConnectionProfile conn(Long team, String name) {
        DbConnectionProfile p = new DbConnectionProfile();
        p.setTeamId(team); p.setName(name); p.setDbType("POSTGRESQL");
        p.setHost("db.internal"); p.setPort(5432); p.setDatabaseName("orders"); p.setUsername("ro");
        return connectionRepo.saveAndFlush(p);
    }

    private DbQueryMonitor mon(Long team, String name, Long profile) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setTeamId(team); m.setName(name); m.setActive(true); m.setProfileId(profile); m.setSqlText("SELECT 1");
        return dbQueryRepo.saveAndFlush(m);
    }

    private DbQueryTemplate tpl(Long team, String name) {
        DbQueryTemplate t = new DbQueryTemplate();
        t.setTeamId(team); t.setName(name); t.setSqlText("SELECT 1");
        return templateRepo.saveAndFlush(t);
    }

    @Test
    @DisplayName("etki: bağlantı ve şablon sayılır, izleme DBQUERY türünde görünür; takım boş sayılmaz")
    void impactCountsConnectionsAndTemplates() {
        assertThat(service.impact(a).get("empty")).isEqualTo(true);
        Long p = conn(a, "Sipariş DB").getId();
        tpl(a, "Bekleyen siparişler");
        Map<String, Object> r = service.impact(a);
        assertThat(r.get("empty")).isEqualTo(false);
        assertThat(String.valueOf(r.get("dbq_connections"))).contains("Sipariş DB");
        assertThat(String.valueOf(r.get("dbq_templates"))).contains("Bekleyen siparişler");

        mon(a, "Sipariş birikmesi", p);
        assertThat(String.valueOf(service.impact(a))).contains("DBQUERY");
    }

    @Test
    @DisplayName("taşı: izlemeler + bağlantılar + şablonlar hedefe geçer (izleme bağlantısıyla aynı takımda); sonra kaynak boş")
    void moveAllMovesMonitorsConnectionsTemplates() {
        Long p = conn(a, "Sipariş DB").getId();
        DbQueryMonitor m = mon(a, "Sipariş birikmesi", p);
        tpl(a, "Bekleyen siparişler");

        Map<String, Integer> moved = service.moveAll(a, b);
        dbQueryRepo.flush();   // izleme varlık kaydıyla taşınır (öteki türler gibi) — JDBC okumasından önce yazılsın
        assertThat(moved).containsEntry("dbq_connections", 1).containsEntry("dbq_templates", 1);
        assertThat(moved.get("monitors")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT team_id FROM db_connection_profiles WHERE id = ?", Long.class, p)).isEqualTo(b);
        assertThat(jdbc.queryForObject("SELECT team_id FROM dbquery_monitors WHERE id = ?", Long.class, m.getId())).isEqualTo(b);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbquery_templates WHERE team_id = ?", Long.class, b)).isEqualTo(1L);
        assertThat(service.impact(a).get("empty")).isEqualTo(true);
    }

    @Test
    @DisplayName("taşı reddi: hedefte aynı adlı izleme / bağlantı / şablon ya da şablon sınırı → 409, hiçbir şey taşınmaz")
    void moveAllRefusesClashes() {
        Long p = conn(a, "Sipariş DB").getId();
        mon(a, "Sipariş birikmesi", p);
        mon(b, "sipariş birikmesi", null);
        assertThatThrownBy(() -> service.moveAll(a, b))
                .isInstanceOfSatisfying(TeamAdminService.MoveConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TEAM_MOVE_DBQUERY_NAME_CLASH"));
        assertThat(jdbc.queryForObject("SELECT team_id FROM db_connection_profiles WHERE id = ?", Long.class, p)).isEqualTo(a);

        Long c = team("C"), d = team("D");
        conn(c, "Ortak DB");
        conn(d, "ortak db");
        assertThatThrownBy(() -> service.moveAll(c, d))
                .isInstanceOfSatisfying(TeamAdminService.MoveConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TEAM_MOVE_DBQ_CONNECTION_CLASH"));

        Long e1 = team("E"), f = team("F");
        tpl(e1, "Ortak şablon");
        tpl(f, "Ortak şablon");
        assertThatThrownBy(() -> service.moveAll(e1, f))
                .isInstanceOfSatisfying(TeamAdminService.MoveConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TEAM_MOVE_DBQ_TEMPLATE_CLASH"));

        Long g = team("G"), h = team("H");
        tpl(g, "Taşınacak");
        for (int i = 0; i < com.sitemonitor.service.dbquery.DbQueryTemplateService.MAX_PER_TEAM; i++) tpl(h, "Şablon " + i);
        assertThatThrownBy(() -> service.moveAll(g, h))
                .isInstanceOfSatisfying(TeamAdminService.MoveConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TEAM_MOVE_DBQ_TEMPLATE_LIMIT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbquery_templates WHERE team_id = ?", Long.class, g)).isEqualTo(1L);
    }

    @Test
    @DisplayName("depolar yokken (eski elle kurulum) davranış bugünküyle aynı: dbq anahtarları 0, hata yok")
    void withoutReposIsUnchanged() {
        TeamAdminService plain = new TeamAdminService(teamRepo, userRepo, inventoryRepo, contactRepo, groupRepo, alertRepo, httpRepo,
                portRepo, pingRepo, dnsRepo, keywordRepo, pageRepo, pageSpeedRepo, scriptedRepo, domainRepo);
        plain.setJdbc(jdbc);
        Map<String, Integer> moved = plain.moveAll(a, b);
        assertThat(moved).containsEntry("dbq_connections", 0).containsEntry("dbq_templates", 0);
        assertThat(plain.impact(a).get("empty")).isEqualTo(true);
        List<String> keys = List.copyOf(plain.impact(a).keySet());
        assertThat(keys).contains("dbq_connections", "dbq_templates");
    }
}
