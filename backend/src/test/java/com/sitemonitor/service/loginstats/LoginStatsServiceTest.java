package com.sitemonitor.service.loginstats;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giriş istatistikleri GERÇEK sorgularla (H2, ORTAM İŞLEMİ YOK — {@code NOT_SUPPORTED}: satırlar gerçekten yazılır,
 * sorgular üretimdeki gibi işlemsiz koşar). Kurgu (İstanbul 2026-10-03 12:30, 7 gün): yerel / LDAP / pasif hesap, her kanaldan
 * başarılı giriş, eski (yöntemsiz) satır, bilinmeyen kullanıcı denemeleri, kod hunisi, teslim hatası ve önceki dönem.
 * Örnek IP'ler RFC 5737 belgeleme blokları; kişi / takım adları yer tutucu.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "sitemonitor.test.ctx=login-stats"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginStatsServiceTest {

    static final Instant NOW = Instant.parse("2026-10-03T09:30:00Z");   // İstanbul 12:30

    @Autowired AuditLogRepository auditRepo;
    @Autowired AppUserRepository userRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired JdbcTemplate jdbc;

    LoginStatsService svc;
    MutableClock clock;

    static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private long seq = 1;

    private void ev(String type, String time, String actor, String outcome, String reason, String detail, String ip) {
        AuditLog a = new AuditLog();
        a.setSeq(seq++);
        a.setEventType(type);
        a.setEventTime(time);
        a.setActor(actor);
        a.setOutcome(outcome);
        a.setFailureReason(reason);
        a.setDetail(detail);
        a.setIpAddress(ip);
        a.setIpCity("Doc City");
        a.setIpCountry("Docland");
        a.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0 Safari/537.36");
        auditRepo.save(a);
    }

    private static String m(String method) {
        return "{\"method\":\"" + method + "\"}";
    }

    @BeforeEach
    void seed() {
        cleanup();
        Team team = new Team();
        team.setName("Takim A");
        team = teamRepo.save(team);
        userRepo.save(user("USER-A", "Kullanici A", team.getId(), "LOCAL", true));
        userRepo.save(user("USER-B", "Kullanici B", team.getId(), "LDAP", true));
        userRepo.save(user("USER-C", "Kullanici C", null, "LDAP", false));

        // ── bu dönem (2026-09-26T21:00Z = İstanbul 27 Eylül 00:00 → şimdi) ──
        ev("LOGIN", "2026-10-03T08:00:00", "USER-A", "SUCCESS", null, m("LOCAL"), "192.0.2.10");
        ev("LOGIN", "2026-10-02T10:00:00", "USER-A", "SUCCESS", null, m("LOCAL"), "192.0.2.10");
        ev("LOGIN", "2026-10-02T11:00:00", "USER-A", "SUCCESS", null, m("REMEMBER_ME"), "192.0.2.10");
        ev("LOGIN", "2026-10-03T07:10:00", "USER-A", "SUCCESS", null, m("OTP_PUSH"), "192.0.2.11");
        for (String t : List.of("2026-09-28T06:00:00", "2026-09-29T06:00:00", "2026-09-30T06:00:00")) {
            ev("LOGIN", t, "USER-B", "SUCCESS", null, m("LDAP"), "198.51.100.20");
        }
        ev("LOGIN", "2026-09-27T05:00:00", "USER-B", "SUCCESS", null, null, "198.51.100.20");            // eski: yöntemsiz
        ev("LOGIN_FAILED", "2026-10-01T09:00:00", "USER-B", "FAILURE", "BAD_PASSWORD: attempt #1/5 for x", m("LDAP"), "198.51.100.21");
        ev("LOGIN_FAILED", "2026-10-01T09:01:00", "USER-B", "FAILURE", "BAD_PASSWORD: attempt #2/5 for x", m("LDAP"), "198.51.100.21");
        ev("LOGIN_OTP_REQUESTED", "2026-10-03T07:00:00", "USER-A", "SUCCESS", null, "{\"channel\":\"PUSH\",\"result\":\"SENT\"}", "192.0.2.11");
        ev("LOGIN_OTP_REQUESTED", "2026-10-03T07:05:00", "USER-A", "SUCCESS", null, "{\"channel\":\"PUSH\",\"result\":\"SENT\"}", "192.0.2.11");
        ev("LOGIN_OTP_REQUESTED", "2026-10-03T07:06:00", "USER-A", "BLOCKED", "SUPPRESSED: COOLDOWN",
                "{\"channel\":\"PUSH\",\"result\":\"SUPPRESSED\",\"reason\":\"COOLDOWN\"}", "192.0.2.11");
        ev("LOGIN_OTP_VERIFY_FAILED", "2026-10-03T07:08:00", "USER-A", "FAILURE", "OTP_INVALID: yanlış kod",
                "{\"channel\":\"PUSH\",\"attempts_left\":2}", "192.0.2.11");
        ev("LOGIN_FAILED", "2026-10-02T01:00:00", "GHOST", "FAILURE", "UNKNOWN_USER: attempt #1/5 for x", m("LDAP"), "203.0.113.66");
        ev("LOGIN_FAILED", "2026-10-02T01:01:00", "GHOST", "FAILURE", "UNKNOWN_USER: attempt #2/5 for x", m("LDAP"), "203.0.113.66");
        ev("LOGIN_OTP_REQUESTED", "2026-10-02T01:02:00", "GHOST", "BLOCKED", "SUPPRESSED: UNKNOWN_USER",
                "{\"channel\":\"PUSH\",\"result\":\"SUPPRESSED\",\"reason\":\"UNKNOWN_USER\"}", "203.0.113.66");
        ev("LOGIN_OTP_DELIVERY_FAILED", "2026-10-02T12:00:00", "USER-A", "FAILURE", "DELIVERY_FAILED: SMTP",
                "{\"channel\":\"EMAIL\",\"reason\":\"SMTP\"}", "192.0.2.10");
        ev("LOGIN_FAILED", "2026-09-30T12:00:00", "USER-C", "BLOCKED", "ACCOUNT_INACTIVE: pasif hesap (LDAP)", null, "198.51.100.30");
        ev("USER_UPDATE", "2026-10-02T12:00:00", "USER-A", "SUCCESS", null, null, null);                  // giriş olayı değil
        // ── önceki dönem (aynı uzunluk, hemen önce) ve pencere dışı ──
        ev("LOGIN", "2026-09-25T10:00:00", "USER-A", "SUCCESS", null, m("LOCAL"), "192.0.2.10");
        ev("LOGIN_FAILED", "2026-09-25T10:05:00", "USER-A", "FAILURE", "BAD_PASSWORD: attempt #1/5 for x", m("LOCAL"), "192.0.2.10");
        ev("LOGIN", "2026-08-01T10:00:00", "USER-A", "SUCCESS", null, m("LOCAL"), "192.0.2.10");

        clock = new MutableClock();
        svc = new LoginStatsService(auditRepo, userRepo, teamRepo);
        svc.setClock(clock);
    }

    private static AppUser user(String name, String display, Long teamId, String source, boolean active) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setDisplayName(display);
        u.setTeamId(teamId);
        u.setAuthSource(source);
        u.setActive(active);
        return u;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM audit_log");
        userRepo.deleteAll();
        teamRepo.deleteAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channel(Map<String, Object> s, String ch) {
        return ((List<Map<String, Object>>) s.get("channels")).stream().filter(c -> ch.equals(c.get("channel"))).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("özet: toplamlar, önceki dönem, kanal kırılımı (tahmin + bilinmeyen kullanıcı), kod hunisi, nedenler, günlük seri")
    void summary() {
        Map<String, Object> s = svc.summary(7, false);
        assertThat(s).containsEntry("days", 7).containsEntry("granularity", "day").containsEntry("truncated", false)
                .containsEntry("from", "2026-09-26T21:00:00").containsEntry("estimated", 1L);
        Map<String, Object> t = (Map<String, Object>) s.get("totals");
        assertThat(t).containsEntry("success", 8L).containsEntry("failed", 6L).containsEntry("attempts", 14L)
                .containsEntry("unique_users", 2L).containsEntry("unknown_user_failures", 2L)
                .containsEntry("delivery_failures", 1L).containsEntry("success_rate", 0.5714);
        Map<String, Object> p = (Map<String, Object>) s.get("previous");
        assertThat(p).containsEntry("success", 1L).containsEntry("failed", 1L).containsEntry("unique_users", 1L);

        assertThat(channel(s, "LDAP")).containsEntry("success", 4L).containsEntry("failed", 3L).containsEntry("estimated", 1L)
                .containsEntry("unique_users", 1).containsEntry("share", 0.5);
        assertThat(channel(s, "LOCAL")).containsEntry("success", 2L).containsEntry("failed", 0L).containsEntry("success_rate", 1.0);
        assertThat(channel(s, "REMEMBER_ME")).containsEntry("success", 1L);
        Map<String, Object> push = channel(s, "OTP_PUSH");
        assertThat(push).containsEntry("success", 1L).containsEntry("failed", 1L);
        Map<String, Object> f = (Map<String, Object>) push.get("otp");
        assertThat(f).containsEntry("requested", 4L).containsEntry("sent", 2L).containsEntry("verified", 1L)
                .containsEntry("suppressed", 2L).containsEntry("wrong_code", 1L).containsEntry("conversion", 0.5);
        assertThat((List<Map<String, Object>>) f.get("suppressed_reasons")).extracting(r -> r.get("reason"))
                .containsExactlyInAnyOrder("COOLDOWN", "UNKNOWN_USER");
        assertThat((Map<String, Object>) channel(s, "OTP_EMAIL").get("otp")).containsEntry("delivery_failed", 1L)
                .containsEntry("conversion", null);

        List<Map<String, Object>> reasons = (List<Map<String, Object>>) s.get("failure_reasons");
        assertThat(reasons).extracting(r -> r.get("reason")).containsExactly("BAD_PASSWORD", "UNKNOWN_USER", "ACCOUNT_INACTIVE", "OTP_INVALID");
        assertThat((Map<String, Object>) reasons.get(0).get("channels")).containsEntry("LDAP", 2L);
        assertThat((Map<String, Object>) reasons.get(1).get("channels")).containsEntry("UNKNOWN", 2L);

        List<Map<String, Object>> series = (List<Map<String, Object>>) s.get("series");
        assertThat(series).hasSize(7);
        assertThat(series.get(0)).containsEntry("ts", "2026-09-26T21:00:00").containsEntry("LDAP", 1L);   // eski satır (tahmin)
        Map<String, Object> today = series.get(6);
        assertThat(today).containsEntry("LOCAL", 1L).containsEntry("OTP_PUSH", 1L).containsEntry("failed", 1L);
        long seriesSuccess = series.stream().mapToLong(b -> List.of("LDAP", "LOCAL", "OTP_PUSH", "OTP_EMAIL", "REMEMBER_ME", "OTHER")
                .stream().mapToLong(k -> (Long) b.get(k)).sum()).sum();
        assertThat(seriesSuccess).isEqualTo(8L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("24 saat: saatlik 24 kova; önceki dönem aynı uzunlukta")
    void hourly() {
        Map<String, Object> s = svc.summary(1, false);
        assertThat(s).containsEntry("granularity", "hour").containsEntry("from", "2026-10-02T10:00:00");
        assertThat((List<?>) s.get("series")).hasSize(24);
        // 10-02T10:00Z (pencere başı, dahil) … şimdi: USER-A'nın 4 girişi + 1 yanlış kod; önceki 24 saat: GHOST denemeleri
        Map<String, Object> t = (Map<String, Object>) s.get("totals");
        assertThat(t).containsEntry("success", 4L).containsEntry("failed", 1L);
        assertThat((Map<String, Object>) s.get("previous")).containsEntry("failed", 2L).containsEntry("success", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("kullanıcı tablosu: yalnız var olan hesaplar (bilinmeyen yok); arama, kanal süzgeci, sıralama, sayfa")
    void users() {
        Map<String, Object> r = svc.users(7, null, null, null, 1, 25, false);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertThat(items).extracting(i -> i.get("username")).containsExactly("USER-A", "USER-B", "USER-C");
        assertThat(r).containsEntry("total", 3);
        Map<String, Object> a = items.get(0);
        assertThat((Map<String, Object>) a.get("success")).containsEntry("LOCAL", 2L).containsEntry("REMEMBER_ME", 1L)
                .containsEntry("OTP_PUSH", 1L);
        assertThat(a).containsEntry("failed", 1L).containsEntry("team_name", "Takim A").containsEntry("source", "LOCAL")
                .containsEntry("active", true);
        assertThat((Map<String, Object>) a.get("last_success")).containsEntry("at", "2026-10-03T08:00:00").containsEntry("channel", "LOCAL");
        assertThat((Map<String, Object>) a.get("last_failure")).containsEntry("reason", "OTP_INVALID");
        Map<String, Object> c = items.get(2);
        assertThat(c).containsEntry("success_total", 0L).containsEntry("failed", 1L).containsEntry("active", false)
                .containsEntry("last_success", null);

        assertThat((List<Map<String, Object>>) svc.users(7, null, "LDAP", null, 1, 25, false).get("items"))
                .extracting(i -> i.get("username")).containsExactly("USER-B", "USER-C");
        assertThat((List<Map<String, Object>>) svc.users(7, "kullanici b", null, null, 1, 25, false).get("items"))
                .extracting(i -> i.get("username")).containsExactly("USER-B");
        assertThat((List<Map<String, Object>>) svc.users(7, null, null, "failures", 1, 25, false).get("items"))
                .extracting(i -> i.get("username")).containsExactly("USER-B", "USER-A", "USER-C");
        Map<String, Object> page2 = svc.users(7, null, null, "name", 2, 2, false);
        assertThat(page2).containsEntry("page", 2).containsEntry("total_pages", 2);
        assertThat((List<Map<String, Object>>) page2.get("items")).extracting(i -> i.get("username")).containsExactly("USER-C");
        // sayfa boyu tavanı 100; CSV dışa aktarımı süzülmüş TÜM satırları tek sayfada verir
        assertThat(svc.users(7, null, null, null, 1, 5000, false)).containsEntry("size", 100);
        Map<String, Object> export = svc.users(7, null, "LDAP", null, 3, 1, false, true);
        assertThat((List<Map<String, Object>>) export.get("items")).extracting(i -> i.get("username")).containsExactly("USER-B", "USER-C");
        assertThat(export).containsEntry("page", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("kullanıcı ayrıntısı: kanal kırılımı, nedenler, seri, son olaylar (cihaz etiketi); IdentityMask — global değilse iz düşer, kişinin kendisi görür")
    void userDetail_andIdentityMask() throws Exception {
        Map<String, Object> d = svc.user("user-a", 7, true, "ADMIN");
        assertThat(d).containsEntry("found", true).containsEntry("identity_masked", false);
        assertThat((Map<String, Object>) d.get("user")).containsEntry("username", "USER-A").containsEntry("team_name", "Takim A");
        assertThat((Map<String, Object>) d.get("totals")).containsEntry("success", 4L).containsEntry("failed", 1L);
        List<Map<String, Object>> recent = (List<Map<String, Object>>) d.get("recent");
        assertThat(recent).hasSize(9);   // USER_UPDATE ve pencere dışı satırlar yok
        assertThat(recent.get(0)).containsEntry("event", "LOGIN").containsEntry("channel", "LOCAL").containsEntry("ip", "192.0.2.10");
        assertThat(String.valueOf(recent.get(0).get("ua_summary"))).contains("Chrome");
        assertThat(recent).anySatisfy(e -> assertThat(e).containsEntry("event", "LOGIN_OTP_VERIFY_FAILED")
                .containsEntry("channel", "OTP_PUSH").containsEntry("reason", "OTP_INVALID"));
        assertThat((List<?>) d.get("series")).hasSize(7);

        String masked = new ObjectMapper().writeValueAsString(svc.user("user-a", 7, false, "OTHER-AUDITOR"));
        assertThat(masked).doesNotContain("192.0.2.10").doesNotContain("192.0.2.11").doesNotContain("Doc City")
                .doesNotContain("Docland").doesNotContain("Chrome").contains("\"identity_masked\":true");
        String self = new ObjectMapper().writeValueAsString(svc.user("user-a", 7, false, "USER-A"));
        assertThat(self).contains("192.0.2.10");

        Map<String, Object> none = svc.user("NOBODY", 7, true, "ADMIN");
        assertThat(none).containsEntry("found", false);
        assertThat((List<?>) none.get("recent")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("önbellek: 30 sn içinde yeni satır görünmez; fresh 5 sn içinde önbelleği atlamaz, sonrasında yeniden hesaplar")
    void memoization() {
        assertThat(((Map<String, Object>) svc.summary(7, false).get("totals"))).containsEntry("success", 8L);
        ev("LOGIN", "2026-10-03T09:00:00", "USER-A", "SUCCESS", null, m("LOCAL"), "192.0.2.10");
        clock.now = NOW.plusSeconds(3);
        assertThat(((Map<String, Object>) svc.summary(7, false).get("totals"))).containsEntry("success", 8L);
        assertThat(((Map<String, Object>) svc.summary(7, true).get("totals"))).containsEntry("success", 8L);
        clock.now = NOW.plusSeconds(6);
        assertThat(((Map<String, Object>) svc.summary(7, true).get("totals"))).containsEntry("success", 9L);
        clock.now = NOW.plusSeconds(20);
        assertThat(((Map<String, Object>) svc.summary(7, false).get("totals"))).containsEntry("success", 9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tavan: izdüşüm tavanı aşılınca truncated=true; toplamlar KESİN gruplu sayımdan gelir")
    void truncated_totalsStayExact() {
        svc.rowCap = 5;
        Map<String, Object> s = svc.summary(7, false);
        assertThat(s).containsEntry("truncated", true).containsEntry("row_count", 5L).containsEntry("row_cap", 5);
        assertThat((Map<String, Object>) s.get("totals")).containsEntry("success", 8L).containsEntry("failed", 6L)
                .containsEntry("unique_users", 2L);
    }
}
