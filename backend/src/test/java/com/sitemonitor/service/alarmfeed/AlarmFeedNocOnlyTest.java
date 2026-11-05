package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NocSettings;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocSettingsRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.noc.NocConfigService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Alarm akışı — yalnız 7/24'e bildirilen alarmlar ({@code noc-only}, 2026-11-05 kullanıcı isteği). GERÇEK SQL (H2), gerçek
 * {@link NocMonitorDirectory} ve {@link NocConfigService}; işlemsiz (üretimdeki gibi).
 *
 * <p>Kural = 7/24 e-postasının alarm-bazlı kararı: tür 7/24'te açık, en düşük seviye (erişim kesintisi ailesi = KRİTİK),
 * izlemede "7/24 ekibine bildir" açık ve izleme aktif. Okunamayan karar ASLA "7/24 dışı" sayılmaz (sahte "çözüldü" olurdu):
 * istisna (uç 503), durum tablosuna kapanış yazılmaz. Kapalıyken bugünkü çıktı birebir.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedNocOnlyTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long T0 = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;
    @Autowired TeamRepository teams;
    @Autowired PingMonitorRepository pings;
    @Autowired NocSettingsRepository nocSettings;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    AlarmFeedService svc;
    long now = T0;
    Long teamA;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        svc = service(new NocMonitorDirectory(jdbc), new NocConfigService(nocSettings));
        Team t = new Team();
        t.setName("Dijital Kanallar");
        t.setEmail("dijital@example.com");
        teamA = teams.save(t).getId();
        noc(null, "WARNING");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM alarm_feed_events");
        alerts.deleteAll();
        inventory.deleteAll();
        pings.deleteAll();
        nocSettings.deleteAll();
        teams.deleteAll();
    }

    AlarmFeedService service(NocMonitorDirectory directory, NocConfigService config) {
        AlarmFeedService s = new AlarmFeedService(alerts, inventory, teams, jdbc, null);
        if (directory != null) s.setDirectory(directory);
        if (config != null) s.setNocConfig(config);
        s.clock = () -> now;
        s.cacheMs = 0;
        return s;
    }

    /** 7/24 ayarı: kapalı türler (virgüllü) + en düşük seviye. */
    void noc(String disabledTypes, String minLevel) {
        NocSettings s = nocSettings.findById(NocSettings.SINGLETON_ID).orElseGet(NocSettings::new);
        s.setId(NocSettings.SINGLETON_ID);
        s.setDisabledTypes(disabledTypes);
        s.setMinLevel(minLevel);
        nocSettings.save(s);
    }

    PingMonitor ping(String host, boolean nocNotify, boolean active) {
        PingMonitor m = new PingMonitor();
        m.setName("Ping " + host);
        m.setHost(host);
        m.setTeamId(teamA);
        m.setNocNotify(nocNotify);
        m.setActive(active);
        return pings.save(m);
    }

    CertificateInventory inv(String domain, boolean nocNotify, boolean active) {
        CertificateInventory r = new CertificateInventory();
        r.setDomain(domain);
        r.setTeamId(teamA);
        r.setNocNotify(nocNotify);
        r.setActive(active);
        return inventory.save(r);
    }

    AlertEvent alarm(String domain, String type, String level) {
        return alarm(domain, type, level, null);
    }

    AlertEvent alarm(String domain, String type, String level, String contextJson) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setTeamId(teamA);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(ISO.format(Instant.ofEpochMilli(now)));
        e.setMessage("ileti");
        e.setContextJson(contextJson);
        return alerts.save(e);
    }

    void resolve(AlertEvent e) {
        e.setResolved(true);
        e.setResolvedAt(ISO.format(Instant.ofEpochMilli(now)));
        e.setResolvedBy("system");
        alerts.save(e);
    }

    /** 7/24 süzgeci açık / kapalı; geri kalan ayarlar AlarmFeedServiceTest.DEF ile aynı. */
    static AlarmFeedSettings.Config cfg(boolean nocOnly, String minLevel) {
        return new AlarmFeedSettings.Config(true, minLevel, Set.of(), Set.of(), 15, "status", "Clear", "Critical", "Major",
                "Warning", "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, 2000, true, true, 120, 15, true,
                List.of(), nocOnly);
    }

    static final AlarmFeedSettings.Config ON = cfg(true, "WARNING");
    static final AlarmFeedSettings.Config OFF = cfg(false, "WARNING");

    static List<String> summary(AlarmFeedService.Feed f) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : f.rows()) out.add(r.get("ALERTID") + ":" + r.get("STATUS") + ":" + r.get("SEVERITY"));
        return out;
    }

    long openStates() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NULL", Long.class);
    }

    long closedStates() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_events WHERE closed_at IS NOT NULL", Long.class);
    }

    /** Her tablo okumasında istisna atan JdbcTemplate (tür tablosu okunamıyor). */
    JdbcTemplate brokenJdbc(AtomicInteger calls) {
        return new JdbcTemplate(dataSource) {
            @Override
            public void query(String sql, RowCallbackHandler rch, Object... args) {
                calls.incrementAndGet();
                throw new DataAccessResourceFailureException("tablo okunamadı");
            }

            @Override
            public void query(String sql, RowCallbackHandler rch) {
                calls.incrementAndGet();
                throw new DataAccessResourceFailureException("tablo okunamadı");
            }
        };
    }

    @Test
    @DisplayName("Açıkken yalnız 7/24'e işaretli ve aktif izlemenin alarmı girer; işaretsiz, pasif, izlemesiz girmez; kapalıyken hepsi")
    void onlyMarkedActiveMonitors() {
        ping("host-a", true, true);
        ping("host-b", false, true);
        ping("host-c", true, false);
        AlertEvent a = alarm("host-a", "PING_DOWN", "CRITICAL");
        AlertEvent b = alarm("host-b", "PING_DOWN", "CRITICAL");
        AlertEvent c = alarm("host-c", "PING_DOWN", "CRITICAL");
        AlertEvent z = alarm("host-z", "PING_DOWN", "CRITICAL");
        assertThat(summary(svc.preview(ON))).containsExactly(a.getId() + ":OPEN:Critical");
        assertThat(svc.preview(OFF).rows()).hasSize(4);
        assertThat(b.getId() + c.getId() + z.getId()).isPositive();
    }

    @Test
    @DisplayName("Bağlamdaki monitor_id ile çözülür (hedef adı başka olsa da) — 7/24 e-postasıyla aynı eşleme")
    void resolvedByMonitorId() {
        PingMonitor m = ping("10.0.0.5", true, true);
        PingMonitor other = ping("10.0.0.6", false, true);
        AlertEvent byId = alarm("eski-ad.example.com", "PING_DOWN", "CRITICAL", "{\"monitor_id\": " + m.getId() + "}");
        alarm("10.0.0.5", "PING_DOWN", "CRITICAL", "{\"monitor_id\": " + other.getId() + "}");   // id işaretsiz izlemeyi gösterir
        assertThat(summary(svc.preview(ON))).containsExactly(byId.getId() + ":OPEN:Critical");
    }

    @Test
    @DisplayName("7/24 ayarında türü kapalı → girmez; en düşük seviyenin altı girmez; erişim kesintisi ailesi WARNING bile olsa KRİTİK sayılır")
    void typeSwitchAndMinLevel() {
        ping("host-a", true, true);
        AlertEvent down = alarm("host-a", "PING_DOWN", "WARNING");
        AlertEvent slowWarn = alarm("host-a", "PING_SLOW", "WARNING");
        noc(null, "CRITICAL");
        assertThat(summary(svc.preview(ON))).containsExactly(down.getId() + ":OPEN:Warning");
        noc(null, "WARNING");
        assertThat(summary(svc.preview(ON))).containsExactlyInAnyOrder(down.getId() + ":OPEN:Warning",
                slowWarn.getId() + ":OPEN:Warning");
        AlertEvent slowHigh = alarm("host-a", "PING_SLOW", "HIGH");
        noc(null, "HIGH");
        assertThat(summary(svc.preview(ON))).containsExactlyInAnyOrder(down.getId() + ":OPEN:Warning",
                slowHigh.getId() + ":OPEN:Major");
        noc("PING", "WARNING");
        assertThat(svc.preview(ON).rows()).isEmpty();
        // Akışın kendi süzgeçleri de AYNEN uygulanır (7/24 kararına EK olarak)
        noc(null, "WARNING");
        assertThat(summary(svc.preview(cfg(true, "HIGH")))).containsExactly(slowHigh.getId() + ":OPEN:Major");
    }

    @Test
    @DisplayName("Sertifika ve ERİŞİLEBİLİRLİK alarmı envanter satırının noc_notify + aktif alanıyla karar verir")
    void certificateAndAccessibilityFromInventory() {
        inv("api.example.com", true, true);
        inv("plain.example.com", false, true);
        inv("paused.example.com", true, false);
        AlertEvent expiry = alarm("api.example.com", "EXPIRY", "CRITICAL");
        AlertEvent access = alarm("api.example.com", "ACCESSIBILITY", "WARNING");   // kesinti ailesi → KRİTİK
        alarm("plain.example.com", "EXPIRY", "CRITICAL");
        alarm("paused.example.com", "ACCESSIBILITY", "CRITICAL");
        noc(null, "CRITICAL");
        assertThat(summary(svc.preview(ON))).containsExactly(expiry.getId() + ":OPEN:Critical",
                access.getId() + ":OPEN:Warning");
    }

    @Test
    @DisplayName("Gönderilmiş alarmın izlemesinde 7/24 anahtarı kapatılınca TEK kapsam dışı (SCOPE) kapanışı; açılınca yeniden OPEN")
    void switchingNocOffClosesOnceWithScope() {
        PingMonitor m = ping("host-a", true, true);
        AlertEvent e = alarm("host-a", "PING_DOWN", "CRITICAL");
        assertThat(summary(svc.feed(ON))).containsExactly(e.getId() + ":OPEN:Critical");
        now += 60_000;
        m.setNocNotify(false);
        pings.save(m);
        AlarmFeedService.Feed f = svc.feed(ON);
        assertThat(summary(f)).containsExactly(e.getId() + ":RESOLVED:Critical");
        assertThat(f.rows().get(0).get("TEXT")).asString().contains("Akış kapsamından çıktı");
        assertThat(jdbc.queryForList("SELECT closed_reason FROM alarm_feed_events", String.class)).containsExactly("SCOPE");
        // Kapanış ilk gönderimden sonra bir pencere tekrarlanır, sonra düşer — yeni kapanış satırı YAZILMAZ
        now += 60_000;
        assertThat(summary(svc.feed(ON))).containsExactly(e.getId() + ":RESOLVED:Critical");
        now += 16 * 60_000;
        assertThat(svc.feed(ON).rows()).isEmpty();
        assertThat(closedStates()).isEqualTo(1L);
        // Anahtar yeniden açılınca aynı olay yeniden OPEN
        m.setNocNotify(true);
        pings.save(m);
        now += 60_000;
        assertThat(summary(svc.feed(ON))).containsExactly(e.getId() + ":OPEN:Critical");
        assertThat(openStates()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Süzgeç açılınca daha önce gönderilmiş 7/24 dışı açık alarm bir kez SCOPE ile kapanır; 7/24'e işaretli olan açık kalır")
    void enablingFilterClosesNonNocOnce() {
        ping("host-a", true, true);
        ping("host-b", false, true);
        AlertEvent a = alarm("host-a", "PING_DOWN", "CRITICAL");
        AlertEvent b = alarm("host-b", "PING_DOWN", "CRITICAL");
        assertThat(svc.feed(OFF).rows()).hasSize(2);
        now += 60_000;
        assertThat(summary(svc.feed(ON))).containsExactly(a.getId() + ":OPEN:Critical", b.getId() + ":RESOLVED:Critical");
        assertThat(jdbc.queryForList("SELECT closed_reason FROM alarm_feed_events WHERE closed_at IS NOT NULL", String.class))
                .containsExactly("SCOPE");
    }

    @Test
    @DisplayName("Hiç gönderilmemiş, pencerede çözülmüş alarmın bilgi satırına da süzgeç uygulanır")
    void resolvedInfoRowsAreFiltered() {
        ping("host-a", true, true);
        ping("host-b", false, true);
        AlertEvent a = alarm("host-a", "PING_DOWN", "CRITICAL");
        AlertEvent b = alarm("host-b", "PING_DOWN", "CRITICAL");
        now += 30_000;
        resolve(a);
        resolve(b);
        assertThat(summary(svc.feed(ON))).containsExactly(a.getId() + ":RESOLVED:Critical");
    }

    @Test
    @DisplayName("Tür tablosu okunamazsa istisna (uç 503) — durum tablosuna kapanış YAZILMAZ, önizleme de kurulmaz")
    void directoryUnreadableThrowsAndWritesNothing() {
        ping("host-a", true, true);
        AlertEvent e = alarm("host-a", "PING_DOWN", "CRITICAL");
        assertThat(summary(svc.feed(ON))).containsExactly(e.getId() + ":OPEN:Critical");
        AtomicInteger reads = new AtomicInteger();
        AlarmFeedService broken = service(new NocMonitorDirectory(brokenJdbc(reads)), new NocConfigService(nocSettings));
        now += 60_000;
        assertThatThrownBy(() -> broken.feed(ON)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("izleme dizini okunamadı");
        assertThatThrownBy(() -> broken.preview(ON)).isInstanceOf(IllegalStateException.class);
        assertThat(reads.get()).isPositive();
        assertThat(closedStates()).isZero();
        assertThat(openStates()).isEqualTo(1L);
        // Süzgeç KAPALIYKEN bugünkü davranış: dizin okunamaması yalnız ad / takım kaybıdır, alarm döner
        assertThat(summary(broken.preview(OFF))).containsExactly(e.getId() + ":OPEN:Critical");
    }

    @Test
    @DisplayName("7/24 ayarı okunamazsa ya da bileşen yoksa istisna (uç 503) — kapanış yazılmaz")
    void configUnreadableOrMissingThrows() {
        ping("host-a", true, true);
        AlertEvent e = alarm("host-a", "PING_DOWN", "CRITICAL");
        assertThat(summary(svc.feed(ON))).containsExactly(e.getId() + ":OPEN:Critical");
        NocSettingsRepository brokenRepo = mock(NocSettingsRepository.class);
        when(brokenRepo.findById(any())).thenThrow(new DataAccessResourceFailureException("db"));
        now += 60_000;
        AlarmFeedService unreadable = service(new NocMonitorDirectory(jdbc), new NocConfigService(brokenRepo));
        assertThatThrownBy(() -> unreadable.feed(ON)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7/24 ayarı okunamadı");
        assertThatThrownBy(() -> service(new NocMonitorDirectory(jdbc), null).feed(ON))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(null, new NocConfigService(nocSettings)).feed(ON))
                .isInstanceOf(IllegalStateException.class);
        assertThat(closedStates()).isZero();
        assertThat(openStates()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Kapalıyken bugünkü çıktı birebir: 7/24 ayarı hiç okunmaz, yanıt 7/24 bileşenleri olmayan servisle aynı")
    void offIsByteIdenticalAndReadsNoNocConfig() {
        ping("host-a", true, true);
        ping("host-b", false, false);
        alarm("host-a", "PING_DOWN", "CRITICAL");
        alarm("host-b", "PING_SLOW", "WARNING");
        inv("api.example.com", false, true);
        alarm("api.example.com", "EXPIRY", "HIGH");
        noc("PING,SSL", "CRITICAL");
        NocSettingsRepository throwing = mock(NocSettingsRepository.class);
        when(throwing.findById(any())).thenThrow(new DataAccessResourceFailureException("okunmamalıydı"));
        AlarmFeedService withNoc = service(new NocMonitorDirectory(jdbc), new NocConfigService(throwing));
        AlarmFeedService legacy = service(new NocMonitorDirectory(jdbc), null);
        String a = withNoc.toJson(withNoc.preview(OFF));
        String b = legacy.toJson(legacy.preview(OFF));
        assertThat(a).isEqualTo(b);
        assertThat(withNoc.preview(OFF).rows()).hasSize(3);
    }

    @Test
    @DisplayName("7/24 kararı saf kural: tür yok / kapalı, seviye, satır yok / işaretsiz / pasif")
    void nocEligibleRule() {
        NocConfigService.Config all = new NocConfigService.Config(Set.of(), "HIGH", true, "", null, null);
        NocMonitorDirectory.Row on = new NocMonitorDirectory.Row(com.sitemonitor.service.noc.NocType.HTTP, 1, "x", "u", 1L,
                null, true, true, null, false);
        NocMonitorDirectory.Row unmarked = new NocMonitorDirectory.Row(com.sitemonitor.service.noc.NocType.HTTP, 1, "x", "u",
                1L, null, true, false, null, false);
        NocMonitorDirectory.Row paused = new NocMonitorDirectory.Row(com.sitemonitor.service.noc.NocType.HTTP, 1, "x", "u",
                1L, null, false, true, null, false);
        var http = com.sitemonitor.service.noc.NocType.HTTP;
        assertThat(AlarmFeedService.nocEligible("HTTP_DOWN", "WARNING", http, on, all)).isTrue();    // kesinti → KRİTİK
        assertThat(AlarmFeedService.nocEligible("HTTP_SLOW", "WARNING", http, on, all)).isFalse();   // seviye altı
        assertThat(AlarmFeedService.nocEligible("HTTP_SLOW", "HIGH", http, on, all)).isTrue();
        assertThat(AlarmFeedService.nocEligible("HTTP_DOWN", "CRITICAL", http, unmarked, all)).isFalse();
        assertThat(AlarmFeedService.nocEligible("HTTP_DOWN", "CRITICAL", http, paused, all)).isFalse();
        assertThat(AlarmFeedService.nocEligible("HTTP_DOWN", "CRITICAL", http, null, all)).isFalse();
        assertThat(AlarmFeedService.nocEligible("WHATEVER", "CRITICAL", null, on, all)).isFalse();
        NocConfigService.Config httpOff = new NocConfigService.Config(Set.of(http), "WARNING", true, "", null, null);
        assertThat(AlarmFeedService.nocEligible("HTTP_DOWN", "CRITICAL", http, on, httpOff)).isFalse();
    }
}
