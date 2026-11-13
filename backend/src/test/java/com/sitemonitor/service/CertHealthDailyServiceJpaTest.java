package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sertifika sağlığı günlük görüntüsü (2026-11-13, İstatistikler #12) — GERÇEK SQL (H2, PostgreSQL kipi): gün × kova tek
 * satır (ikinci yazım no-op), kurum her sertifikayı bir kez, takım SY ∪ UG, 120 günden eski satır silinir; seri kapsamı
 * (kurum yalnız tüm izlemeyi görene, kapsamlıya ilk görünür takım). Aynı bağlamda yönetici özetinin yeni dar JPQL'leri.
 */
@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
        replace = org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)   // MODE=PostgreSQL (ON CONFLICT)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CertHealthDailyServiceJpaTest {

    @Autowired DataSource dataSource;
    @Autowired TeamRepository teamRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired LatestCheckRepository latestRepo;
    @Autowired AlertEventRepository alertRepo;

    CertHealthDailyService svc;
    JdbcTemplate jdbc;
    Long teamA, teamB;

    private CertificateInventory inv(String d, Long sy, Long ug) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(d); i.setTeamId(sy); i.setUgTeamId(ug); i.setActive(true);
        return inventoryRepo.save(i);
    }

    private void lc(String d, Integer days, String status) {
        LatestCheck c = new LatestCheck(); c.setDomain(d); c.setDaysRemaining(days); c.setStatus(status);
        latestRepo.save(c);
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM cert_health_daily");
        alertRepo.deleteAll(); latestRepo.deleteAll(); inventoryRepo.deleteAll(); teamRepo.deleteAll();
        Team a = new Team(); a.setName("Takım A"); a.setActive(true); teamA = teamRepo.save(a).getId();
        Team b = new Team(); b.setName("Takım B"); b.setActive(true); teamB = teamRepo.save(b).getId();
        inv("d1.example.com", teamA, null);
        inv("d2.example.com", teamA, teamB);
        inv("d3.example.com", teamB, teamB);
        inv("d4.example.com", null, null);
        CertificateInventory paused = inv("paused.example.com", teamA, null);
        paused.setActive(false); inventoryRepo.save(paused);
        lc("d1.example.com", 100, "valid");
        lc("d2.example.com", 5, "valid");
        lc("d3.example.com", null, "error");
        lc("paused.example.com", -3, "valid");
        svc = new CertHealthDailyService(jdbc, inventoryRepo, latestRepo, teamRepo);
    }

    @Test
    @DisplayName("yazım: kurum + iki takım kovası; ikinci yazım no-op; snapshotWritten; pasif kayıt sayılmaz")
    void writeOncePerDay() {
        Instant now = Instant.now();
        assertThat(svc.snapshotWritten(now)).isFalse();
        svc.writeDailySnapshot(now);
        svc.writeDailySnapshot(now);
        assertThat(svc.snapshotWritten(now)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_health_daily", Integer.class)).isEqualTo(3);
        Map<String, Object> org = jdbc.queryForMap("SELECT * FROM cert_health_daily WHERE team_key = 0");
        assertThat(((Number) org.get("TOTAL")).intValue()).isEqualTo(4);
        assertThat(((Number) org.get("EVALUATED")).intValue()).isEqualTo(3);
        assertThat(((Number) org.get("OK")).intValue()).isEqualTo(1);
        assertThat(((Number) org.get("UNDER7")).intValue()).isEqualTo(1);
        assertThat(((Number) org.get("ERROR_COUNT")).intValue()).isEqualTo(1);
        Map<String, Object> b = jdbc.queryForMap("SELECT * FROM cert_health_daily WHERE team_key = ?", teamB);
        assertThat(((Number) b.get("TOTAL")).intValue()).isEqualTo(2);   // d2 (UG) + d3 (SY = UG, bir kez)
    }

    @Test
    @DisplayName("budama: 120 günden eski satır her yazımda silinir")
    void prunesOldRows() {
        String old = LocalDate.now(ZoneId.of("Europe/Istanbul")).minusDays(CertHealthDailyService.KEEP_DAYS + 5).toString();
        jdbc.update("INSERT INTO cert_health_daily(snap_day, team_key, total, evaluated, ok, under30, under7, expired, error_count, created_at) "
                + "VALUES (?, 0, 1, 1, 1, 0, 0, 0, 0, 'x')", old);
        svc.writeDailySnapshot(Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_health_daily WHERE snap_day = ?", Integer.class, old)).isZero();
    }

    @Test
    @DisplayName("seri: tüm izlemeyi gören → kurum; kapsamlı (yalnız B) → B, seçenekler yalnız B; sağlık %; toplanmaz")
    void seriesScope() {
        svc.writeDailySnapshot(Instant.now());
        Map<String, Object> org = svc.series(90, null, true, t -> true);
        assertThat(org).containsEntry("scope", "org").containsEntry("org_allowed", true);
        List<Map<String, Object>> pts = pts(org);
        assertThat(pts).hasSize(1);
        assertThat((Double) pts.get(0).get("health_pct")).isEqualTo(33.3);
        assertThat(org.get("collecting_since")).isNotNull();

        Map<String, Object> scoped = svc.series(90, null, false, t -> teamB.equals(t));
        assertThat(scoped).containsEntry("scope", "team").containsEntry("team_id", teamB).containsEntry("org_allowed", false);
        assertThat(teams(scoped)).extracting(x -> x.get("id")).containsExactly(teamB);
        assertThat(pts(scoped).get(0)).containsEntry("total", 2).containsEntry("ok", 0);
        assertThat((Double) pts(scoped).get(0).get("health_pct")).isEqualTo(0.0);

        Map<String, Object> none = svc.series(90, null, false, t -> false);
        assertThat(none).containsEntry("scope", "none");
        assertThat(pts(none)).isEmpty();
    }

    @Test
    @DisplayName("yönetici özetinin dar JPQL'leri: açık alarm [takım, alan, seviye]; pencerede açılan [takım, alan, zaman]")
    void executiveNarrowQueries() {
        AlertEvent open = new AlertEvent(); open.setDomain("d1.example.com"); open.setAlertLevel("CRITICAL"); open.setAlertType("EXPIRY");
        open.setTeamId(teamA); open.setCreatedAt("2026-11-10T10:00:00"); open.setResolved(false); open.setAcknowledged(false);
        AlertEvent closed = new AlertEvent(); closed.setDomain("d2.example.com"); closed.setAlertLevel("WARNING"); closed.setAlertType("EXPIRY");
        closed.setCreatedAt("2026-09-01T10:00:00"); closed.setResolved(true); closed.setAcknowledged(false);
        alertRepo.saveAll(List.of(open, closed));
        List<Object[]> o = alertRepo.findOpenStatRows();
        assertThat(o).hasSize(1);
        assertThat(o.get(0)[0]).isEqualTo(teamA);
        assertThat(o.get(0)[1]).isEqualTo("d1.example.com");
        assertThat(o.get(0)[2]).isEqualTo("CRITICAL");
        assertThat(alertRepo.findCreatedStatRowsSince("2026-10-01T00:00:00")).hasSize(1);
        assertThat(alertRepo.findCreatedStatRowsSince("2026-08-01T00:00:00")).hasSize(2);
    }

    @Test
    @DisplayName("hata taraması 2026-11-13: sertifikası olmayan takım — kapsamlıya varsayılan VERİSİ OLAN takım; seçilirse no_certificates (görüntü başlamadıysa false)")
    void teamWithoutCertificates() {
        Team empty = new Team(); empty.setName("AAA Sertifikasız Takım"); empty.setActive(true);
        Long emptyId = teamRepo.save(empty).getId();   // adı ilk sırada — eskiden varsayılan buydu
        // Görüntü henüz yok → "toplanmaya başlıyor" (no_certificates false)
        assertThat(svc.series(90, emptyId, false, t -> true)).containsEntry("no_certificates", false);
        svc.writeDailySnapshot(Instant.now());
        Map<String, Object> def = svc.series(90, null, false, t -> emptyId.equals(t) || teamB.equals(t));
        assertThat(def).containsEntry("scope", "team").containsEntry("team_id", teamB).containsEntry("no_certificates", false);
        assertThat(pts(def)).hasSize(1);
        Map<String, Object> chosen = svc.series(90, emptyId, false, t -> emptyId.equals(t) || teamB.equals(t));
        assertThat(chosen).containsEntry("team_id", emptyId).containsEntry("no_certificates", true);
        assertThat(pts(chosen)).isEmpty();
        assertThat(chosen.get("collecting_since")).isNull();
        // Hiçbir görünür takımın verisi yoksa ilk görünür takım (eski davranış)
        assertThat(svc.series(90, null, false, emptyId::equals)).containsEntry("team_id", emptyId).containsEntry("no_certificates", true);
        // Kurum serisi bu alanı hiç işaretlemez
        assertThat(svc.series(90, null, true, t -> true)).containsEntry("no_certificates", false);
    }

    @Test
    @DisplayName("hata taraması 2026-11-13: seri yüzdesi aşağı kesilir (sayaçla aynı kural)")
    void seriesHealthIsFloored() {
        jdbc.update("INSERT INTO cert_health_daily(snap_day, team_key, total, evaluated, ok, under30, under7, expired, error_count, created_at) "
                + "VALUES (?, 0, 2000, 2000, 1999, 0, 0, 1, 0, 'x')", LocalDate.now(ZoneId.of("Europe/Istanbul")).toString());
        assertThat((Double) pts(svc.series(90, null, true, t -> true)).get(0).get("health_pct")).isEqualTo(99.9);
    }

    @Test
    @DisplayName("hata taraması 2026-11-13: takım alarm kırılımının dar JPQL'leri — açık [id, takım, alan, çözüldü, açılış] ve pencerede açılan")
    void teamStatNarrowQueries() {
        AlertEvent open = new AlertEvent(); open.setDomain("d1.example.com"); open.setAlertLevel("CRITICAL"); open.setAlertType("EXPIRY");
        open.setTeamId(teamA); open.setCreatedAt("2026-09-10T10:00:00"); open.setResolved(false); open.setAcknowledged(false);
        AlertEvent closed = new AlertEvent(); closed.setDomain("d2.example.com"); closed.setAlertLevel("WARNING"); closed.setAlertType("EXPIRY");
        closed.setCreatedAt("2026-11-01T10:00:00"); closed.setResolved(true); closed.setAcknowledged(false);
        alertRepo.saveAll(List.of(open, closed));
        List<Object[]> o = alertRepo.findOpenTeamStatRows();
        assertThat(o).hasSize(1);
        assertThat(o.get(0)).hasSize(5);
        assertThat(o.get(0)[1]).isEqualTo(teamA);
        assertThat(o.get(0)[2]).isEqualTo("d1.example.com");
        assertThat(o.get(0)[3]).isEqualTo(false);
        assertThat(o.get(0)[4]).isEqualTo("2026-09-10T10:00:00");
        List<Object[]> since = alertRepo.findTeamStatRowsSince("2026-10-01T00:00:00");
        assertThat(since).hasSize(1);
        assertThat(since.get(0)[2]).isEqualTo("d2.example.com");
        assertThat(since.get(0)[3]).isEqualTo(true);
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> pts(Map<String, Object> m) { return (List<Map<String, Object>>) m.get("points"); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> teams(Map<String, Object> m) { return (List<Map<String, Object>>) m.get("teams"); }
}
