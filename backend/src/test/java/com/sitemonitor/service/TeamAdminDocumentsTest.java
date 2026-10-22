package com.sitemonitor.service;

import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADMIN-1 / ADMIN-5 (2026-10-22): takım silme / taşıma takım BELGELERİNİ görür. Kanal teknik hata raporu kanalları (raporları
 * yetim kuralıyla kalıcı silinirdi), şablon kütüphanesi ve bitmemiş bakım pencereleri etkide sayılır ({@code empty=false} →
 * silme 409); "Taşı" hepsini taşır (rapor / görsel / geçmiş satırlarındaki takım kopyası dâhil); hedefte aynı adlı kanal ya da
 * şablon varsa 409, hiçbir şey taşınmaz. Gerçek tablolar (H2, ddl create-drop) — kimliksiz mock'ların göremeyeceği SQL.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TeamAdminDocumentsTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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
    @Autowired TechErrorChannelRepository channelRepo;
    @Autowired TechErrorTeamTemplateRepository templateRepo;
    @Autowired TechErrorReportRepository reportRepo;
    @Autowired MaintenanceWindowRepository windowRepo;

    TeamAdminService service;
    JdbcTemplate jdbc;
    Long a, b;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        service = new TeamAdminService(teamRepo, userRepo, inventoryRepo, contactRepo, groupRepo, alertRepo, httpRepo, portRepo,
                pingRepo, dnsRepo, keywordRepo, pageRepo, pageSpeedRepo, scriptedRepo, domainRepo);
        service.setJdbc(jdbc);
        service.setMaintenanceRepo(windowRepo);
        a = team("Kaynak Ekip");
        b = team("Hedef Ekip");
    }

    private Long team(String name) {
        Team t = new Team();
        t.setName(name);
        t.setActive(true);
        return teamRepo.saveAndFlush(t).getId();
    }

    private TechErrorChannel channel(Long teamId, String name, Integer order) {
        TechErrorChannel c = new TechErrorChannel();
        c.setTeamId(teamId);
        c.setName(name);
        c.setNameKey(name.trim().toLowerCase(java.util.Locale.forLanguageTag("tr")));
        c.setTemplateJson("{\"sections\":[]}");
        c.setSortOrder(order);
        c.setCreatedAt(Instant.now());
        return channelRepo.saveAndFlush(c);
    }

    private TechErrorReport report(TechErrorChannel ch, int month) {
        TechErrorReport r = new TechErrorReport();
        r.setChannelId(ch.getId());
        r.setTeamId(ch.getTeamId());
        r.setReportYear(2026);
        r.setReportMonth(month);
        r.setTemplateJson("{\"sections\":[]}");
        r.setValuesJson("{}");
        return reportRepo.saveAndFlush(r);
    }

    private TechErrorTeamTemplate template(Long teamId, String name, boolean isDefault) {
        TechErrorTeamTemplate t = new TechErrorTeamTemplate();
        t.setTeamId(teamId);
        t.setName(name);
        t.setNameKey(name.trim().toLowerCase(java.util.Locale.forLanguageTag("tr")));
        t.setTemplateJson("{\"sections\":[]}");
        t.setDefaultEntry(isDefault);
        t.setCreatedAt(Instant.now());
        return templateRepo.saveAndFlush(t);
    }

    private MaintenanceWindow window(Long teamId, String name, Instant start, int minutes, String recurrence) {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setName(name);
        w.setTeamId(teamId);
        w.setStartAt(ISO.format(start));
        w.setDurationMinutes(minutes);
        w.setRecurrence(recurrence);
        w.setActive(true);
        return windowRepo.saveAndFlush(w);
    }

    @Test
    @DisplayName("etki: kanal (+ rapor sayısı), şablon ve bitmemiş bakım penceresi takımı 'boş değil' yapar; bitmiş pencere ve yönetici özeti yalnız bilgi")
    @SuppressWarnings("unchecked")
    void impactCountsTeamDocuments() {
        assertThat(service.impact(a).get("empty")).isEqualTo(true);

        TechErrorChannel ch = channel(a, "Mobil Kanal", 1);
        report(ch, 8);
        report(ch, 9);
        Map<String, Object> r = service.impact(a);
        assertThat(r.get("empty")).as("kanalı olan takım silinemez").isEqualTo(false);
        Map<String, Object> te = (Map<String, Object>) r.get("te_channels");
        assertThat(te.get("count")).isEqualTo(1);
        assertThat((List<String>) te.get("items")).containsExactly("Mobil Kanal");
        assertThat(te.get("reports")).isEqualTo(2L);
        assertThat((Map<String, Object>) r.get("executive_summary")).containsEntry("settings", false);

        // Yalnız şablon / yalnız bitmemiş bakım penceresi de engeldir
        template(b, "Varsayılan TR", true);
        assertThat(service.impact(b).get("empty")).isEqualTo(false);

        Long c = team("Pencere Ekip");
        window(c, "Eski bakım", Instant.now().minusSeconds(7200), 30, "NONE");
        assertThat(service.impact(c).get("empty")).as("bitmiş tek seferlik pencere engel değil").isEqualTo(true);
        window(c, "Gece bakımı", Instant.now().minusSeconds(86_400), 30, "DAILY");
        Map<String, Object> rc = service.impact(c);
        assertThat(rc.get("empty")).as("tekrarlayan pencere bitmez → engel").isEqualTo(false);
        assertThat((List<String>) ((Map<String, Object>) rc.get("maintenance_windows")).get("items")).containsExactly("Gece bakımı");
    }

    @Test
    @DisplayName("moveAll: kanallar sona eklenerek, raporların / şablon kütüphanesinin / bakım pencerelerinin takımı hedefe geçer; sonra etki boş")
    void moveAllMovesDocuments() {
        channel(b, "Mevcut Kanal", 1);
        TechErrorChannel ch1 = channel(a, "Mobil", 1);
        TechErrorChannel ch2 = channel(a, "Web", 2);
        TechErrorReport rep = report(ch1, 9);
        template(a, "Kaynak Şablon", true);
        template(b, "Hedef Varsayılan", true);
        MaintenanceWindow w = window(a, "Aylık bakım", Instant.now().plusSeconds(3600), 60, "NONE");

        Map<String, Integer> moved = service.moveAll(a, b);
        windowRepo.flush();

        assertThat(moved).containsEntry("te_channels", 2).containsEntry("te_reports", 1)
                .containsEntry("te_templates", 1).containsEntry("maintenance_windows", 1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, team_id, sort_order FROM tech_error_channels WHERE team_id = ? ORDER BY sort_order", b);
        assertThat(rows).extracting(m -> m.get("NAME")).containsExactly("Mevcut Kanal", "Mobil", "Web");
        assertThat(rows).extracting(m -> ((Number) m.get("SORT_ORDER")).intValue()).containsExactly(1, 2, 3);
        assertThat(jdbc.queryForObject("SELECT team_id FROM tech_error_reports WHERE id = ?", Long.class, rep.getId())).isEqualTo(b);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tech_error_team_templates WHERE team_id = ? AND is_default = TRUE",
                Long.class, b)).as("hedefte tek varsayılan kalır").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tech_error_team_templates WHERE team_id = ?", Long.class, b)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT team_id FROM maintenance_windows WHERE id = ?", Long.class, w.getId())).isEqualTo(b);
        assertThat(service.impact(a).get("empty")).isEqualTo(true);
    }

    @Test
    @DisplayName("Son inceleme 2026-10-22 · kilit sırası: moveAll önce RAPOR satırlarını, sonra KANAL satırlarını kimlik sırasıyla kilitler — "
            + "ilk kanal UPDATE'inden ÖNCE (kanal silme: raporlar → kanal ile aynı yön)")
    void moveAllLocksReportsThenChannelsFirst() {
        TechErrorChannel ch1 = channel(a, "Mobil", 1);
        report(ch1, 9);
        List<String> sql = new java.util.ArrayList<>();
        JdbcTemplate recording = new JdbcTemplate(dataSource) {
            @Override
            public <T> List<T> queryForList(String q, Class<T> type, Object... args) {
                sql.add(q);
                return super.queryForList(q, type, args);
            }

            @Override
            public int update(String q, Object... args) {
                sql.add(q);
                return super.update(q, args);
            }
        };
        service.setJdbc(recording);

        service.moveAll(a, b);

        int lockReports = indexOf(sql, "SELECT id FROM tech_error_reports WHERE team_id = ? ORDER BY id FOR UPDATE");
        int lockChannels = indexOf(sql, "SELECT id FROM tech_error_channels WHERE team_id = ? ORDER BY id FOR UPDATE");
        int firstChannelUpdate = indexOf(sql, "UPDATE tech_error_channels");
        int firstReportUpdate = indexOf(sql, "UPDATE tech_error_reports");
        assertThat(lockReports).as("rapor kilidi var").isGreaterThanOrEqualTo(0);
        assertThat(lockReports).isLessThan(lockChannels);
        assertThat(lockChannels).isLessThan(firstChannelUpdate);
        assertThat(lockChannels).isLessThan(firstReportUpdate);
    }

    private static int indexOf(List<String> sql, String prefix) {
        for (int i = 0; i < sql.size(); i++) if (sql.get(i).startsWith(prefix)) return i;
        return -1;
    }

    @Test
    @DisplayName("moveAll: hedefte aynı adlı kanal (harf duyarsız) ya da şablon → 409 TEAM_MOVE_*_CLASH, HİÇBİR şey taşınmaz")
    void moveAllRefusesNameClash() {
        channel(a, "Mobil", 1);
        channel(b, "mobil", 1);
        assertThatThrownBy(() -> service.moveAll(a, b))
                .isInstanceOf(TeamAdminService.MoveConflictException.class)
                .hasMessageContaining("Mobil")
                .satisfies(e -> assertThat(((TeamAdminService.MoveConflictException) e).getCode()).isEqualTo("TEAM_MOVE_CHANNEL_CLASH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tech_error_channels WHERE team_id = ?", Long.class, a)).isEqualTo(1L);

        Long c = team("Üçüncü");
        Long d = team("Dördüncü");
        template(c, "Ortak", false);
        template(d, "ORTAK", false);
        assertThatThrownBy(() -> service.moveAll(c, d))
                .isInstanceOf(TeamAdminService.MoveConflictException.class)
                .satisfies(e -> assertThat(((TeamAdminService.MoveConflictException) e).getCode()).isEqualTo("TEAM_MOVE_TEMPLATE_CLASH"));
    }
}
