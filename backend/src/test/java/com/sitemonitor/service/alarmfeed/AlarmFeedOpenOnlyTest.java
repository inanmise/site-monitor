package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.controller.AlarmFeedController;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Yalnız açık alarmlar" kipi (2026-11-05, kullanıcı kararı; {@code resolved-mode=none}, VARSAYILAN) — GERÇEK SQL (H2,
 * PostgreSQL kipi), işlemsiz (üretimdeki gibi).
 *
 * <p>Merkezi 7/24 sistemi durum tabanlıdır: akışta AÇIK görmediği alarmı kendisi kapatır. Bu kipte yanıtta yalnız STATUS=OPEN
 * satırı vardır; çözülen, kapsamdan çıkan (SCOPE), anahtarı değişen ve hiç bildirilmemiş çözülmüş alarm satır üretmez. Durum
 * tablosu aynen işler ve bekleyen kapanışlar hemen "teslim edildi" sayılır — kip sonradan {@code status}'a çevrilince eski
 * kapanışlar akmaz. Okunamazlık hâlâ istisnadır (uç 503) — boş dizi "okunamadı" anlamında asla dönmez.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedOpenOnlyTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long T0 = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;
    @Autowired TeamRepository teams;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    AlarmFeedService svc;
    long now = T0;
    Long teamA;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        svc = service(null);
        Team t = new Team();
        t.setName("Dijital Kanallar");
        t.setEmail("dijital@example.com");
        teamA = teams.save(t).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM alarm_feed_events");
        alerts.deleteAll();
        inventory.deleteAll();
        teams.deleteAll();
    }

    AlarmFeedService service(AppSettingsService settings) {
        AlarmFeedService s = new AlarmFeedService(alerts, inventory, teams, jdbc, settings);
        s.clock = () -> now;
        s.cacheMs = 0;
        return s;
    }

    static String iso(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    AlertEvent alarm(String domain, String type, String level) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setTeamId(teamA);
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

    static AlarmFeedSettings.Config cfg(String mode, String minLevel, int maxRows) {
        return new AlarmFeedSettings.Config(true, minLevel, Set.of(), Set.of(), 15, mode, "Clear", "Critical", "Major", "Warning",
                "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, maxRows, true);
    }

    static final AlarmFeedSettings.Config OPEN_ONLY = cfg("none", "WARNING", 2000);
    static final AlarmFeedSettings.Config STATUS = cfg("status", "WARNING", 2000);

    static List<String> summary(AlarmFeedService.Feed f) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : f.rows()) out.add(r.get("ALERTID") + ":" + r.get("STATUS") + ":" + r.get("SEVERITY"));
        return out;
    }

    long pendingClosures() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NOT NULL AND closure_sent_at IS NULL",
                Long.class);
    }

    @Test
    @DisplayName("Yalnız açık: çözülen, kapsamdan çıkan, anahtarı değişen ve hiç bildirilmemiş çözülmüş alarm satır üretmez")
    void onlyOpenRows() {
        AlertEvent keep = alarm("host-keep", "PING_DOWN", "CRITICAL");
        AlertEvent res = alarm("host-res", "PING_DOWN", "HIGH");
        AlertEvent out = alarm("host-scope", "PING_DOWN", "WARNING");
        AlertEvent ren = alarm("old.example.com", "PING_DOWN", "HIGH");
        AlarmFeedService.Feed first = svc.feed(OPEN_ONLY);
        assertThat(first.rows()).hasSize(4);
        assertThat(first.open()).isEqualTo(4);
        assertThat(first.resolved()).isZero();

        now += 60_000;
        resolve(res);
        ren.setDomain("new.example.com");
        alerts.save(ren);
        AlertEvent never = alarm("host-never", "PING_DOWN", "CRITICAL");   // hiç bildirilmeden pencerede çözülür
        now += 30_000;
        resolve(never);
        // En düşük seviye HIGH → WARNING alarm kapsam dışı kalır (SCOPE kapanışı satır üretmez, adı akışta görünmez)
        AlarmFeedService.Feed f = svc.feed(cfg("none", "HIGH", 2000));
        assertThat(summary(f)).containsExactly(keep.getId() + ":OPEN:Critical", ren.getId() + ":OPEN:Major");
        assertThat(f.open()).isEqualTo(2);
        assertThat(f.resolved()).isZero();
        for (Map<String, Object> r : f.rows()) {
            assertThat(r.get("STATUS")).isEqualTo("OPEN");
            assertThat(r.get("RESOLVEDTIME")).isNull();
            assertThat(String.valueOf(r.get("RCI"))).doesNotContain("host-scope").doesNotContain("old.example.com");
        }
        assertThat(f.rows().get(1).get("RCI")).asString().startsWith("new.example.com");
        // Kolon şeması değişmez (merkezi sistemin kolonları aynı kalır)
        assertThat(f.rows().get(0).keySet()).containsExactlyElementsOf(AlarmFeedRows.COLUMNS);
        // Durum tablosu aynen işledi: kapanışlar oluştu ve hepsi teslim edildi sayıldı
        assertThat(jdbc.queryForList("SELECT closed_reason FROM alarm_feed_events WHERE closed_at IS NOT NULL", String.class))
                .containsExactlyInAnyOrder(AlarmFeedRows.REASON_RESOLVED, AlarmFeedRows.REASON_SCOPE, AlarmFeedRows.REASON_KEY_CHANGE);
        assertThat(pendingClosures()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NULL", Long.class))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("Seviye değişimi: yalnız yeni seviye OPEN; eski seviyenin kapanışı gönderilmez, teslim sayılır")
    void levelChangeOnlyNewSeverity() {
        AlertEvent e = alarm("api.example.com", "EXPIRY", "WARNING");
        svc.feed(OPEN_ONLY);
        now += 60_000;
        e.setAlertLevel("CRITICAL");
        alerts.save(e);
        AlarmFeedService.Feed f = svc.feed(OPEN_ONLY);
        assertThat(summary(f)).containsExactly(e.getId() + ":OPEN:Critical");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM alarm_feed_events WHERE closed_at IS NOT NULL", String.class))
                .isEqualTo(AlarmFeedRows.REASON_LEVEL_CHANGE);
        assertThat(pendingClosures()).isZero();
    }

    @Test
    @DisplayName("Kip status'a çevrilince yalnız-açık kipte teslim sayılan eski kapanışlar topluca akmaz")
    void switchToStatusDoesNotFloodOldClosures() {
        AlertEvent a = alarm("host-a", "PING_DOWN", "HIGH");
        AlertEvent b = alarm("host-b", "PING_DOWN", "CRITICAL");
        // Önce durum kipinde OPEN gönderildi; sonra çözüldü ve kimse çağırmadı (kapanış teslim edilmemiş bekliyor)
        svc.feed(STATUS);
        now += 60_000;
        resolve(a);
        resolve(b);
        now += 3 * 3600_000L;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NOT NULL", Long.class))
                .isZero();   // henüz fark edilmedi: kapanışlar bu çağrıda oluşacak
        assertThat(svc.feed(OPEN_ONLY).rows()).isEmpty();
        assertThat(pendingClosures()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closure_sent_at IS NOT NULL", Long.class))
                .isEqualTo(2L);
        // Pencere geçti → status kipine dönülünce eski kapanışlar RESOLVED olarak akmaz
        now += 16 * 60_000;
        assertThat(svc.feed(STATUS).rows()).isEmpty();
        assertThat(svc.feed(cfg("clear", "WARNING", 2000)).rows()).isEmpty();
    }

    @Test
    @DisplayName("Durum kipinde bekleyen (teslim edilmemiş) kapanış yalnız-açık kipe geçişte gönderilmez, teslim sayılır")
    void pendingFromStatusModeIsMarkedDelivered() {
        AlertEvent a = alarm("host-a", "PING_DOWN", "HIGH");
        svc.feed(STATUS);
        now += 60_000;
        resolve(a);
        svc.preview(STATUS);   // önizleme kapanışı oluşturmaz / işaretlemez
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NOT NULL", Long.class)).isZero();
        jdbc.update("UPDATE alarm_feed_events SET closed_at = ?, closed_reason = ?, closed_noted_at = ?", iso(now),
                AlarmFeedRows.REASON_RESOLVED, iso(now));   // kapanış durum kipinde oluşmuş ama yanıtı hiç gitmemiş gibi
        assertThat(pendingClosures()).isEqualTo(1L);
        assertThat(svc.feed(OPEN_ONLY).rows()).isEmpty();
        assertThat(pendingClosures()).isZero();
    }

    @Test
    @DisplayName("Satır sınırı: en önemli açık alarmlar kalır, truncated işaretlenir, RESOLVED yok")
    void capKeepsMostImportant() {
        AlertEvent crit = alarm("crit", "PING_DOWN", "CRITICAL");
        for (int i = 0; i < 120; i++) {
            now += 1000;
            alarm("w" + i, "PING_DOWN", "WARNING");
        }
        AlarmFeedService.Feed f = svc.feed(cfg("none", "WARNING", 100));
        assertThat(f.truncated()).isTrue();
        assertThat(f.rows()).hasSize(100);
        assertThat(f.open()).isEqualTo(100);
        assertThat(f.resolved()).isZero();
        assertThat(f.rows().get(0).get("ALERTID")).isEqualTo(crit.getId());   // seviye sırası: kritik en başta
        assertThat(f.rows()).allSatisfy(r -> assertThat(r.get("STATUS")).isEqualTo("OPEN"));
        // Sınırın altında kesilmez
        AlarmFeedService.Feed all = svc.feed(cfg("none", "WARNING", 200));
        assertThat(all.truncated()).isFalse();
        assertThat(all.rows()).hasSize(121);
    }

    @Test
    @DisplayName("Önizleme yalnız-açık kipte de hiçbir şey yazmaz (durum satırı eklemez, kapanışı teslim işaretlemez)")
    void previewWritesNothing() {
        AlertEvent a = alarm("host-a", "PING_DOWN", "HIGH");
        svc.feed(STATUS);
        now += 60_000;
        resolve(a);
        svc.feed(STATUS);   // kapanış oluştu ve durum kipinde gönderildi
        jdbc.update("UPDATE alarm_feed_events SET closure_sent_at = NULL");
        AlertEvent b = alarm("host-b", "PING_DOWN", "CRITICAL");
        AlarmFeedService.Feed f = svc.preview(OPEN_ONLY);
        assertThat(summary(f)).containsExactly(b.getId() + ":OPEN:Critical");
        assertThat(pendingClosures()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Okunamazlık yalnız-açık kipte de istisna (uç 503) — asla boş dizi; gerçekten alarm yoksa boş dizi")
    void readFailureStillThrows() {
        AlertEventRepository broken = mock(AlertEventRepository.class);
        when(broken.findOpenOrResolvedSince(anyString(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        AlarmFeedService s = new AlarmFeedService(broken, inventory, teams, jdbc, null);
        assertThatThrownBy(() -> s.feed(OPEN_ONLY)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> s.preview(OPEN_ONLY)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Takım listesi okunamazsa da istisna
        TeamRepository brokenTeams = mock(TeamRepository.class);
        when(brokenTeams.findAll()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        AlarmFeedService t = new AlarmFeedService(alerts, inventory, brokenTeams, jdbc, null);
        assertThatThrownBy(() -> t.feed(OPEN_ONLY)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Okunabilir ve alarm yok → gerçek boş dizi
        assertThat(svc.feed(OPEN_ONLY).rows()).isEmpty();
    }

    /** Canlı ayarlar: kaydedilmemiş (varsayılan) çözülen alarm kipi; akış açık, 7/24 süzgeci kapalı (dizin bu testte yok). */
    static AppSettingsService liveSettings(String storedMode) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(s.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(s.getBoolean(eq(AlarmFeedSettings.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(s.getBoolean(eq(AlarmFeedSettings.KEY_NOC_ONLY), anyBoolean())).thenReturn(false);
        if (storedMode != null) when(s.getString(eq(AlarmFeedSettings.KEY_RESOLVED_MODE), any())).thenReturn(storedMode);
        return s;
    }

    static MockHttpServletRequest req(String format) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", AlarmFeedController.PATH);
        r.setRemoteAddr("10.0.0.1");
        if (format != null) r.setParameter("format", format);
        return r;
    }

    @Test
    @DisplayName("Uç (varsayılan kip = yalnız açık): X-Feed-Resolved 0, X-Feed-Open = satır sayısı; CSV'de de RESOLVED yok")
    void endpointHeadersInDefaultMode() {
        AlarmFeedService live = service(liveSettings(null));
        assertThat(live.config().resolvedMode()).isEqualTo("none");
        AlarmFeedRequestLog log = mock(AlarmFeedRequestLog.class);
        AlarmFeedController controller = new AlarmFeedController(live, log, mock(ClientIpResolver.class));
        AlertEvent a = alarm("host-a", "PING_DOWN", "HIGH");
        AlertEvent b = alarm("host-b", "PING_DOWN", "CRITICAL");
        controller.alarms(null, req(null));
        now += 60_000;
        resolve(a);
        ResponseEntity<String> r = controller.alarms(null, req(null));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("X-Feed-Rows")).isEqualTo("1");
        assertThat(r.getHeaders().getFirst("X-Feed-Open")).isEqualTo("1");
        assertThat(r.getHeaders().getFirst("X-Feed-Resolved")).isEqualTo("0");
        assertThat(r.getHeaders().getFirst("X-Feed-Truncated")).isEqualTo("false");
        assertThat(r.getBody()).contains("\"ALERTID\":" + b.getId()).doesNotContain("\"STATUS\":\"RESOLVED\"")
                .doesNotContain("\"ALERTID\":" + a.getId() + ",").contains("\"RESOLVEDTIME\":null");
        ResponseEntity<String> csv = controller.alarms("csv", req("csv"));
        assertThat(csv.getBody()).startsWith("CREATEDTIME,SEVERITY,RCI,RCITYPE,CATEGORY,FILTERSOURCE,TEXT,BODY,MAILS,STATUS,RESOLVEDTIME")
                .doesNotContain(",RESOLVED,").contains(",OPEN,");
        assertThat(csv.getHeaders().getFirst("X-Feed-Resolved")).isEqualTo("0");

        // Kaydedilmiş "status" korunur (yalnız varsayılan değişti): çözülen alarm RESOLVED satırıyla döner
        jdbc.update("DELETE FROM alarm_feed_events");
        alerts.deleteAll();
        AlarmFeedService statusLive = service(liveSettings("status"));
        assertThat(statusLive.config().resolvedMode()).isEqualTo("status");
        AlertEvent c = alarm("host-c", "PING_DOWN", "HIGH");
        statusLive.feed(statusLive.config());
        now += 60_000;
        resolve(c);
        ResponseEntity<String> s = new AlarmFeedController(statusLive, log, mock(ClientIpResolver.class)).alarms(null, req(null));
        assertThat(s.getHeaders().getFirst("X-Feed-Resolved")).isEqualTo("1");
        assertThat(s.getBody()).contains("\"STATUS\":\"RESOLVED\"");
    }

    @Test
    @DisplayName("Uç: veri okunamazsa yalnız-açık kipte de 503 ALARM_FEED_UNAVAILABLE (boş dizi değil)")
    void endpointUnavailableInOpenOnly() {
        AlertEventRepository broken = mock(AlertEventRepository.class);
        when(broken.findOpenOrResolvedSince(anyString(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        AlarmFeedService s = new AlarmFeedService(broken, inventory, teams, jdbc, liveSettings(null));
        s.cacheMs = 0;
        ResponseEntity<String> r = new AlarmFeedController(s, mock(AlarmFeedRequestLog.class), mock(ClientIpResolver.class))
                .alarms(null, req(null));
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("ALARM_FEED_UNAVAILABLE").doesNotStartWith("[");
    }
}
