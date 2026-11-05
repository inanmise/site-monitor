package com.sitemonitor.service.activity;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static com.sitemonitor.service.activity.ActivityRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivityTimelineService} GERÇEK H2 üstünde (2026-11-05, aktivite zenginleştirmesi D): izleme künyesi tablodan
 * (Port envanter türevinde envanterin takımı), kapı (görülemeyen / bilinmeyen → 404), silinmiş izleme en yeni satırdan,
 * satır kapsamı (başka takım döneminin satırları sızmaz), sertifikanın kimliksiz eski satırları hedefle, tavan ve aralık
 * doğrulaması.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityTimelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    @Autowired DataSource dataSource;
    @Autowired TeamRepository teams;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;

    JdbcTemplate jdbc;
    ActivityTimelineService svc;
    long teamA, teamB, httpId, portId, invId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        ActivityRows.extendSchema(jdbc);
        jdbc.update("DELETE FROM activity_log");
        httpRepo.deleteAll();
        portRepo.deleteAll();
        inventoryRepo.deleteAll();
        teams.deleteAll();
        teamA = teams.save(team("Ödeme Kanalları Takımı")).getId();
        teamB = teams.save(team("Altyapı")).getId();
        HttpMonitor h = new HttpMonitor();
        h.setName("Ana sayfa");
        h.setUrl("https://a.example.com/");
        h.setTeamId(teamA);
        httpId = httpRepo.save(h).getId();
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("b.example.com");
        inv.setTeamId(teamB);
        invId = inventoryRepo.save(inv).getId();
        PortMonitor pm = new PortMonitor();
        pm.setName("b 443");
        pm.setHost("b.example.com");
        pm.setPort(443);
        pm.setTeamId(teamA);            // saklanan takım A — envanter türevi: etkin takım envanterinki (B)
        pm.setStandalone(false);
        portId = portRepo.save(pm).getId();
        svc = new ActivityTimelineService(jdbc);
    }

    private static Team team(String name) {
        Team t = new Team();
        t.setName(name);
        return t;
    }

    private Map<String, Object> timeline(String monitor, Set<Long> visible, boolean scoped) {
        return svc.timeline(monitor, "2026-11-05T00:00:00Z", "2026-11-05T12:00:00Z", scoped, List.copyOf(visible),
                visible::contains, NOW);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mon(Map<String, Object> m) {
        return (Map<String, Object>) m.get("monitor");
    }

    @Test
    @DisplayName("künye tablodan (ad, hedef, takım adı); satırlar kapsamla süzülür (başka takım dönemindeki satır sızmaz)")
    void resolvesAndScopesRows() {
        row("2026-11-05T01:00:00", "HTTP", httpId, "SUCCESS").team(teamA).save(jdbc);
        row("2026-11-05T01:05:00", "HTTP", httpId, "ERROR").team(teamA).reason("DNS_NXDOMAIN").save(jdbc);
        row("2026-11-05T01:10:00", "HTTP", httpId, "SUCCESS").team(teamB).save(jdbc);   // taşınmadan önceki takım
        Map<String, Object> m = timeline("HTTP:" + httpId, Set.of(teamA), true);
        assertThat(mon(m)).containsEntry("name", "Ana sayfa").containsEntry("target", "https://a.example.com/")
                .containsEntry("team_id", teamA).containsEntry("team_name", "Ödeme Kanalları Takımı").containsEntry("exists", true);
        assertThat(m.get("checks")).isEqualTo(2);
        assertThat(m.get("outage_count")).isEqualTo(1);
        Map<String, Object> global = svc.timeline("HTTP:" + httpId, null, null, false, List.of(), id -> true, NOW);
        assertThat(global.get("checks")).isEqualTo(3);
        assertThat(global.get("from")).isEqualTo("2026-10-29T12:00:00Z");      // varsayılan son 7 gün
    }

    @Test
    @DisplayName("kapı: izlemenin takımı görülmüyorsa 404 — satırlar görünen takımda olsa bile")
    void gateOnMonitorTeam() {
        row("2026-11-05T01:00:00", "HTTP", httpId, "SUCCESS").team(teamB).save(jdbc);
        assertThatThrownBy(() -> timeline("HTTP:" + httpId, Set.of(teamB), true)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> timeline("HTTP:999999", Set.of(teamA, teamB), true)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("Port envanter türevi: etkin takım envanterinki (B); A kullanıcısı göremez, B görür")
    void portEffectiveTeam() {
        assertThatThrownBy(() -> timeline("PORT:" + portId, Set.of(teamA), true)).isInstanceOf(NoSuchElementException.class);
        Map<String, Object> m = timeline("PORT:" + portId, Set.of(teamB), true);
        assertThat(mon(m)).containsEntry("team_id", teamB).containsEntry("target", "b.example.com:443");
    }

    @Test
    @DisplayName("silinmiş izleme: künye en yeni aktivite satırından (exists=false), kapı o satırın takımı")
    void deletedMonitorFromNewestRow() {
        row("2026-11-05T01:00:00", "PING", 4242L, "SUCCESS").team(teamA).name("Eski ping").target("10.0.0.9").save(jdbc);
        Map<String, Object> m = timeline("PING:4242", Set.of(teamA), true);
        assertThat(mon(m)).containsEntry("name", "Eski ping").containsEntry("exists", false).containsEntry("target", "10.0.0.9");
        assertThatThrownBy(() -> timeline("PING:4242", Set.of(teamB), true)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("sertifika: envanter kimlikli yeni satırlar + kimliksiz eski satırlar (aynı hedef) birlikte; başka hedef girmez")
    void certLegacyRowsByTarget() {
        row("2026-11-05T01:00:00", "CERT", null, "SUCCESS").team(teamB).target("b.example.com").save(jdbc);
        row("2026-11-05T02:00:00", "CERT", invId, "ERROR").team(teamB).target("b.example.com").save(jdbc);
        row("2026-11-05T03:00:00", "CERT", null, "ERROR").team(teamB).target("c.example.com").save(jdbc);
        Map<String, Object> m = timeline("CERT:" + invId, Set.of(teamB), true);
        assertThat(m.get("checks")).isEqualTo(2);
        assertThat(mon(m)).containsEntry("name", "b.example.com");
        Map<String, Object> up = timeline("UPTIME:" + invId, Set.of(teamB), true);
        assertThat(mon(up)).containsEntry("target", "b.example.com:443");
    }

    @Test
    @DisplayName("tavan: en YENİ satırlar alınır, truncated + covered_from; doğrulama: bilinmeyen tür / 90 günü aşan aralık / izlemesiz → 400")
    void truncationAndValidation() {
        for (int i = 0; i < 5; i++) row(String.format("2026-11-05T0%d:00:00", i + 1), "HTTP", httpId, "SUCCESS").team(teamA).save(jdbc);
        svc.maxRows = 3;
        Map<String, Object> m = timeline("HTTP:" + httpId, Set.of(teamA), true);
        assertThat(m.get("truncated")).isEqualTo(true);
        assertThat(m.get("checks")).isEqualTo(3);
        assertThat(m.get("covered_from")).isEqualTo("2026-11-05T03:00:00Z");
        assertThatThrownBy(() -> svc.timeline("FOO:1", null, null, false, List.of(), x -> true, NOW))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> svc.timeline("HTTP:" + httpId, "2026-01-01", "2026-11-05", false, List.of(), x -> true, NOW))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> svc.timeline("  ", null, null, false, List.of(), x -> true, NOW))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> svc.timeline("HTTP:" + httpId, "2026-11-05T12:00:00Z", "2026-11-05T01:00:00Z", false, List.of(),
                x -> true, NOW)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("alarm açılış satırı (ALERT_TRIGGERED) kesintiye bağlanır, kontrol sayılmaz")
    void alertRowLinked() {
        row("2026-11-05T01:00:00", "HTTP", httpId, "ERROR").team(teamA).reason("CONNECT_REFUSED").save(jdbc);
        row("2026-11-05T01:02:00", "HTTP", httpId, "ERROR").team(teamA).action("ALERT_TRIGGERED").alert(555L).save(jdbc);
        row("2026-11-05T01:05:00", "HTTP", httpId, "SUCCESS").team(teamA).save(jdbc);
        Map<String, Object> m = timeline("HTTP:" + httpId, Set.of(teamA), true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outages = (List<Map<String, Object>>) m.get("outages");
        assertThat(outages).hasSize(1);
        assertThat(outages.get(0)).containsEntry("alert_event_id", 555L).containsEntry("reason", "CONNECT_REFUSED");
        assertThat(m.get("checks")).isEqualTo(2);
    }
}
