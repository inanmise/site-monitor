package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Alarm akışı servisi — GERÇEK SQL (H2, PostgreSQL kipi), işlemsiz (üretimdeki gibi; durum tablosu otomatik commit).
 *
 * <p>Senaryolar: kapsam süzgeçleri, seviye değişiminde eski seviyeye "çözüldü", çözülme penceresi, kapsamdan çıkan alarm,
 * hiç bildirilmemiş çözülen alarm, önizleme yazmaz, Clear kipi, satır tavanı kapanışları korur, iki pod aynı anda.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedServiceTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long T0 = Instant.parse("2026-10-30T10:00:00Z").toEpochMilli();

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;
    @Autowired TeamRepository teams;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    AlarmFeedService svc;
    long now = T0;
    Long teamA, teamB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        svc = service();
        teamA = team("Dijital Kanallar", "dijital@example.com; ops@example.com");
        teamB = team("Mobil Ekip", "mobil@example.com");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM alarm_feed_events");
        alerts.deleteAll();
        inventory.deleteAll();
        teams.deleteAll();
    }

    AlarmFeedService service() {
        AlarmFeedService s = new AlarmFeedService(alerts, inventory, teams, jdbc, null);
        s.clock = () -> now;
        s.cacheMs = 0;
        return s;
    }

    Long team(String name, String email) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(email);
        return teams.save(t).getId();
    }

    static String iso(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    AlertEvent alarm(String domain, String type, String level, Long teamId) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setTeamId(teamId);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(iso(now));
        e.setMessage("ileti");
        return alerts.save(e);
    }

    void resolve(AlertEvent e) {
        e.setResolved(true);
        e.setResolvedAt(iso(now));
        e.setResolvedBy("system");
        alerts.save(e);
    }

    void level(AlertEvent e, String level) {
        e.setAlertLevel(level);
        alerts.save(e);
    }

    static AlarmFeedSettings.Config cfg(String mode, String minLevel, Set<Long> teamIds, Set<String> types, int maxRows) {
        return new AlarmFeedSettings.Config(true, minLevel, teamIds, types, 15, mode, "Clear", "Critical", "Major", "Warning",
                "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, maxRows, true);
    }

    static final AlarmFeedSettings.Config DEF = cfg("status", "WARNING", Set.of(), Set.of(), 2000);

    static List<String> summary(AlarmFeedService.Feed f) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : f.rows()) out.add(r.get("ALERTID") + ":" + r.get("STATUS") + ":" + r.get("SEVERITY"));
        return out;
    }

    @Test
    @DisplayName("Açık alarmlar seviyeye göre sıralı döner; bağımsız alarm takımı damgadan, e-postalar takım adresinden")
    void openAlarms() {
        AlertEvent w = alarm("host-a", "PING_DOWN", "WARNING", teamA);
        now += 1000;
        AlertEvent c = alarm("https://shop.example.com", "HTTP_DOWN", "CRITICAL", teamB);
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(c.getId() + ":OPEN:Critical", w.getId() + ":OPEN:Warning");
        assertThat(f.open()).isEqualTo(2);
        assertThat(f.resolved()).isZero();
        Map<String, Object> first = f.rows().get(0);
        assertThat(first.get("TEAM")).isEqualTo("Mobil Ekip");
        assertThat(first.get("MAILS")).isEqualTo("mobil@example.com");
        assertThat(f.rows().get(1).get("MAILS")).isEqualTo("dijital@example.com; ops@example.com");
    }

    @Test
    @DisplayName("Envanter gibi yönlenen alarm (sertifika) SY + UG takımını envanterden alır; takım süzgeci UG'yi de sayar")
    void inventoryRoutedTeams() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("api.example.com");
        inv.setTeamId(teamA);
        inv.setUgTeamId(teamB);
        inventory.save(inv);
        AlertEvent e = alarm("api.example.com", "EXPIRY", "HIGH", null);
        Map<String, Object> r = svc.feed(DEF).rows().get(0);
        assertThat(r.get("TEAM")).isEqualTo("Dijital Kanallar");
        assertThat(r.get("UGTEAM")).isEqualTo("Mobil Ekip");
        assertThat(r.get("MAILS")).isEqualTo("dijital@example.com; ops@example.com; mobil@example.com");
        // Süzgeç sonucunu durumdan bağımsız görmek için önizleme (durum yazmaz; önceki "bildirilmiş" satır yok sayılmaz —
        // aşağıda kapsam dışı kapanışı ayrıca sınanır)
        jdbc.update("DELETE FROM alarm_feed_events");
        assertThat(svc.preview(cfg("status", "WARNING", Set.of(teamB), Set.of(), 2000)).rows()).hasSize(1);
        assertThat(svc.preview(cfg("status", "WARNING", Set.of(999L), Set.of(), 2000)).rows()).isEmpty();
        assertThat(e.getId()).isNotNull();
    }

    @Test
    @DisplayName("Süzgeçler: en düşük seviye, izleme türü, takım")
    void filters() {
        alarm("host-a", "PING_DOWN", "WARNING", teamA);
        alarm("host-b", "PING_DOWN", "CRITICAL", teamB);
        alarm("https://x.example.com", "HTTP_DOWN", "HIGH", teamA);
        // Önizleme: durum yazılmaz → her çağrı yalnız süzgecin sonucunu gösterir
        assertThat(svc.preview(cfg("status", "HIGH", Set.of(), Set.of(), 2000)).rows()).hasSize(2);
        assertThat(svc.preview(cfg("status", "CRITICAL", Set.of(), Set.of(), 2000)).rows()).hasSize(1);
        assertThat(svc.preview(cfg("status", "WARNING", Set.of(), Set.of("ping"), 2000)).rows()).hasSize(2);
        assertThat(svc.preview(cfg("status", "WARNING", Set.of(teamA), Set.of(), 2000)).rows()).hasSize(2);
        // Akışta: bildirilmiş alarm sonradan kapsam dışı kalırsa "çözüldü (kapsam dışı)" satırıyla döner
        svc.feed(DEF);
        AlarmFeedService.Feed narrowed = svc.feed(cfg("status", "CRITICAL", Set.of(), Set.of(), 2000));
        assertThat(narrowed.open()).isEqualTo(1);
        assertThat(narrowed.resolved()).isEqualTo(2);
    }

    @Test
    @DisplayName("Seviye yükselince: yeni seviye OPEN, ESKİ seviye pencere boyunca RESOLVED (merkezi olay açık kalmaz)")
    void levelChangeClosesOldSeverity() {
        AlertEvent e = alarm("api.example.com", "EXPIRY", "WARNING", teamA);
        assertThat(summary(svc.feed(DEF))).containsExactly(e.getId() + ":OPEN:Warning");
        now += 60_000;
        level(e, "CRITICAL");
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(e.getId() + ":OPEN:Critical", e.getId() + ":RESOLVED:Warning");
        Map<String, Object> old = f.rows().get(1);
        assertThat(old.get("TEXT")).asString().contains("Seviye değişti (yeni seviye Critical)");
        assertThat(old.get("RESOLVEDTIME")).isEqualTo("2026-10-30 13:01:00");
        assertThat(old.get("RCI")).isEqualTo(f.rows().get(0).get("RCI"));
        // Pencere geçince eski seviye satırı düşer
        now += 16 * 60_000;
        assertThat(summary(svc.feed(DEF))).containsExactly(e.getId() + ":OPEN:Critical");
        // Clear kipinde seviye değişiminin kapanışı gönderilmez
        level(e, "HIGH");
        now += 60_000;
        assertThat(summary(svc.feed(cfg("clear", "WARNING", Set.of(), Set.of(), 2000))))
                .containsExactly(e.getId() + ":OPEN:Major");
    }

    @Test
    @DisplayName("Çözülünce aynı SEVERITY + RESOLVED, Clear kipinde 'Clear'; pencere bitince düşer")
    void resolvedWithinWindow() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        svc.feed(DEF);
        now += 120_000;
        resolve(e);
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(e.getId() + ":RESOLVED:Major");
        assertThat(f.rows().get(0).get("RESOLVEDTIME")).isEqualTo("2026-10-30 13:02:00");
        assertThat(summary(svc.feed(cfg("clear", "WARNING", Set.of(), Set.of(), 2000))))
                .containsExactly(e.getId() + ":RESOLVED:Clear");
        now += 16 * 60_000;
        assertThat(svc.feed(DEF).rows()).isEmpty();
    }

    @Test
    @DisplayName("Hiç bildirilmemiş ama pencerede çözülmüş alarm da RESOLVED döner (bilgi)")
    void resolvedNeverSent() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "CRITICAL", teamA);
        now += 30_000;
        resolve(e);
        assertThat(summary(svc.feed(DEF))).containsExactly(e.getId() + ":RESOLVED:Critical");
    }

    @Test
    @DisplayName("Kapsam daraltılınca bildirilmiş açık alarm 'kapsam dışı' RESOLVED olur; kapsam geri gelince yeniden açılır")
    void scopeChange() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "WARNING", teamA);
        svc.feed(DEF);
        now += 60_000;
        AlarmFeedService.Feed f = svc.feed(cfg("status", "HIGH", Set.of(), Set.of(), 2000));
        assertThat(summary(f)).containsExactly(e.getId() + ":RESOLVED:Warning");
        assertThat(f.rows().get(0).get("TEXT")).asString().contains("Akış kapsamından çıktı");
        now += 60_000;
        assertThat(summary(svc.feed(DEF))).containsExactly(e.getId() + ":OPEN:Warning");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NULL", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Akış kapalıyken çözülmüş, bildirilmiş alarm: geç de olsa GERÇEK çözülme anıyla bir kez döner, sonra düşer")
    void lateResolution() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        svc.feed(DEF);
        now += 60_000;
        resolve(e);
        now += 3 * 3600_000L;   // pencerenin çok dışında, kimse çağırmadı
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(e.getId() + ":RESOLVED:Major");
        assertThat(f.rows().get(0).get("RESOLVEDTIME")).isEqualTo("2026-10-30 13:01:00");   // gerçek çözülme anı
        now += 60_000;
        assertThat(svc.feed(DEF).rows()).isEmpty();   // teslim edildi + pencere dışında
    }

    @Test
    @DisplayName("Önizleme teslim işareti koymaz: kapanış asıl çağrıda yine gider")
    void previewDoesNotMarkDelivered() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        svc.feed(DEF);
        now += 60_000;
        resolve(e);
        now += 3 * 3600_000L;
        assertThat(summary(svc.preview(DEF))).containsExactly(e.getId() + ":RESOLVED:Major");
        assertThat(summary(svc.feed(DEF))).containsExactly(e.getId() + ":RESOLVED:Major");
    }

    @Test
    @DisplayName("Alan adı yeniden adlandırılınca: yeni RCI OPEN + ESKİ RCI'ya (gönderilmiş kopyadan) RESOLVED")
    void renameClosesOldKey() {
        AlertEvent e = alarm("old.example.com", "PING_DOWN", "HIGH", teamA);
        Map<String, Object> first = svc.feed(DEF).rows().get(0);
        now += 60_000;
        e.setDomain("new.example.com");
        alerts.save(e);
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(e.getId() + ":OPEN:Major", e.getId() + ":RESOLVED:Major");
        assertThat(f.rows().get(0).get("RCI")).asString().startsWith("new.example.com");
        assertThat(f.rows().get(1).get("RCI")).isEqualTo(first.get("RCI"));
        assertThat(f.rows().get(1).get("TEXT")).asString().contains("Kayıt yeni anahtarla sürüyor");
    }

    @Test
    @DisplayName("Seviye eşlemesi değişince: yeni metinle OPEN + ESKİ metinle RESOLVED")
    void severityMappingChangeClosesOldKey() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        svc.feed(DEF);
        now += 60_000;
        AlarmFeedSettings.Config renamed = new AlarmFeedSettings.Config(true, "WARNING", Set.of(), Set.of(), 15, "status",
                "Clear", "Critical", "High", "Warning", "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, 2000, true);
        assertThat(summary(svc.feed(renamed))).containsExactly(e.getId() + ":OPEN:High", e.getId() + ":RESOLVED:Major");
    }

    @Test
    @DisplayName("Aynı anahtar aynı yanıtta hem AÇIK hem ÇÖZÜLDÜ olmaz (çözülen alarmın yerine aynı hedefte yenisi açıldı)")
    void neverOpenAndResolvedWithSameKey() {
        AlertEvent old = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        svc.feed(DEF);
        now += 60_000;
        resolve(old);
        AlertEvent fresh = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(fresh.getId() + ":OPEN:Major");
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Map<String, Object> r : f.rows()) assertThat(keys.add(AlarmFeedRows.eventKey(r))).isTrue();
    }

    @Test
    @DisplayName("Aynı hostta Ping ve Port yavaşlığı ayrı RCI alır; biri kapanınca öteki açık kalır")
    void slowAlarmsOnSameHostStayDistinct() {
        AlertEvent ping = alarm("host-a", "PING_SLOW", "WARNING", teamA);
        AlertEvent port = alarm("host-a", "PORT_SLOW", "WARNING", teamA);
        svc.feed(DEF);
        now += 60_000;
        resolve(ping);
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactlyInAnyOrder(port.getId() + ":OPEN:Warning", ping.getId() + ":RESOLVED:Warning");
        assertThat(f.rows().get(0).get("RCI")).isNotEqualTo(f.rows().get(1).get("RCI"));
    }

    @Test
    @DisplayName("Silinmiş açık alarm: gönderilmiş kopyadan bir kez 'kayıt silindi' RESOLVED döner")
    void deletedAlarm() {
        AlertEvent e = alarm("host-a", "PING_DOWN", "HIGH", teamA);
        Map<String, Object> sent = svc.feed(DEF).rows().get(0);
        alerts.delete(e);
        now += 60_000;
        AlarmFeedService.Feed f = svc.feed(DEF);
        assertThat(summary(f)).containsExactly(e.getId() + ":RESOLVED:Major");
        assertThat(f.rows().get(0).get("RCI")).isEqualTo(sent.get("RCI"));
        assertThat(f.rows().get(0).get("TEXT")).asString().contains("Alarm kaydı silindi");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM alarm_feed_events", String.class)).isEqualTo("GONE");
    }

    @Test
    @DisplayName("Önizleme durumu YAZMAZ")
    void previewWritesNothing() {
        alarm("host-a", "PING_DOWN", "HIGH", teamA);
        AlarmFeedService.Feed f = svc.preview(DEF);
        assertThat(f.rows()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events", Long.class)).isZero();
    }

    @Test
    @DisplayName("Satır tavanı: kapanışlar korunur, en düşük öncelikli açıklar düşer, truncated işaretlenir")
    void capKeepsResolvedRows() {
        List<AlertEvent> ws = new ArrayList<>();
        for (int i = 0; i < 120; i++) ws.add(alarm("w" + i, "PING_DOWN", "WARNING", teamA));
        AlertEvent r = alarm("r", "PING_DOWN", "CRITICAL", teamA);
        svc.feed(DEF);
        now += 60_000;
        resolve(r);
        AlarmFeedService.Feed f = svc.feed(cfg("status", "WARNING", Set.of(), Set.of(), 100));
        assertThat(f.truncated()).isTrue();
        assertThat(f.rows()).hasSize(100);
        assertThat(f.resolved()).isEqualTo(1);
        assertThat(f.rows().get(99).get("STATUS")).isEqualTo("RESOLVED");
        assertThat(ws).hasSize(120);
    }

    @Test
    @DisplayName("Alarm okunamazsa istisna (uç 503 döner) — asla boş liste")
    void readFailureThrows() {
        AlertEventRepository broken = mock(AlertEventRepository.class);
        when(broken.findOpenOrResolvedSince(anyString(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        AlarmFeedService s = new AlarmFeedService(broken, inventory, teams, jdbc, null);
        assertThatThrownBy(() -> s.feed(DEF)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("İki pod aynı anda: durum satırı tekil, ikisi de aynı satırları döndürür")
    void twoPodsConcurrently() throws Exception {
        AlertEvent e = alarm("host-a", "PING_DOWN", "CRITICAL", teamA);
        AlarmFeedService a = service();
        AlarmFeedService b = service();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                jdbc.update("DELETE FROM alarm_feed_events");
                CountDownLatch go = new CountDownLatch(1);
                Future<AlarmFeedService.Feed> fa = ex.submit(() -> { go.await(); return a.feed(DEF); });
                Future<AlarmFeedService.Feed> fb = ex.submit(() -> { go.await(); return b.feed(DEF); });
                go.countDown();
                assertThat(summary(fa.get(30, TimeUnit.SECONDS))).containsExactly(e.getId() + ":OPEN:Critical");
                assertThat(summary(fb.get(30, TimeUnit.SECONDS))).containsExactly(e.getId() + ":OPEN:Critical");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events", Long.class)).isEqualTo(1L);
            }
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("Aynı alarm durumu = aynı gövde (zaman geçse de) — yanıtlar özetle tekilleşebilir")
    void deterministicBody() {
        alarm("host-a", "PING_DOWN", "CRITICAL", teamA);
        String one = svc.toJson(svc.feed(DEF));
        now += 60_000;
        String two = svc.toJson(svc.feed(DEF));
        assertThat(two).isEqualTo(one);
        assertThat(one).startsWith("[{\"CREATEDTIME\":\"2026-10-30 13:00:00\",\"SEVERITY\":\"Critical\"");
        assertThat(AlarmFeedService.toCsv(svc.feed(DEF))).startsWith("CREATEDTIME,SEVERITY,RCI,RCITYPE,CATEGORY,FILTERSOURCE,TEXT,BODY,MAILS,STATUS");
    }
}
