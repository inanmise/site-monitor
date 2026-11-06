package com.sitemonitor.repository;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.MonitorRefResolver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performans 2026-11-06 (paket B1, istek yolu önbellekleri) — yeni DAR sorguların H2 (MODE=PostgreSQL) üstünde SORGU-DÜZEYİ
 * kanıtı: JPQL / yerel SQL geçerli, sütun sırası servislerin okuduğu sırayla aynı ve eski tam varlık yollarıyla (kâhin) AYNI
 * sonucu verir.
 * <ul>
 *   <li>{@code AppUserRepository.findTeamMemberDirectoryRows} / {@code findNameRowsByIds} — takım üyeleri penceresi</li>
 *   <li>{@code NotificationLogRepository.findStatusRowsSince} / {@code smtpPeriodCounts} — "bugün" bildirimleri, tekrarlayan
 *       posta hatası, Sistem Sağlığı SMTP kutusu</li>
 *   <li>{@code ActivityLogRepository.lastCheckPerMonitor} — "bugün" bayat izleme kartı</li>
 *   <li>{@code MonitorRefResolver} dar okuma yolu — bildirim kutusunun "izlemeye git" bağlantısı</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestPathCacheQueriesTest {

    @Autowired AppUserRepository userRepo;
    @Autowired NotificationLogRepository logRepo;
    @Autowired ActivityLogRepository activityRepo;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired KeywordMonitorRepository keywordRepo;
    @Autowired PingMonitorRepository pingRepo;
    @Autowired DomainMonitorRepository domainRepo;
    @Autowired PageMonitorRepository pageRepo;
    @Autowired ScriptedMonitorRepository scriptedRepo;
    @Autowired PageSpeedMonitorRepository pageSpeedRepo;
    @Autowired EntityManager em;

    // ── Takım üyeleri ───────────────────────────────────────────────────────────────────────────

    private AppUser user(String username, String display, Long teamId, Set<Long> extra, String photo, boolean active, Long managerId) {
        AppUser u = new AppUser();
        u.setUsername(username); u.setDisplayName(display); u.setTeamId(teamId);
        u.setTeamIds(new LinkedHashSet<>(extra)); u.setPhotoBase64(photo); u.setActive(active); u.setManagerId(managerId);
        u.setEmail(username + "@example.com"); u.setTitle("Uzman"); u.setDepartment("Dijital"); u.setMudurlukName("Kanallar");
        u.setOrgRole("TECH"); u.setCompanyLevel("7"); u.setSystemRole("USER"); u.setFirstName("Ad" + username);
        u.setLastName("Soyad"); u.setPasswordHash("x");
        return userRepo.save(u);
    }

    @Test
    @DisplayName("findTeamMemberDirectoryRows: birincil VEYA çoklu üyelik (tekrar satır yok), kimliğe göre; has_photo = boş değil; alanlar eski varlıkla aynı")
    void teamMemberRowsEqualTheOldEntityFilter() {
        AppUser mgr = user("mgr", "Müdür", 9L, Set.of(9L), null, true, null);
        AppUser primary = user("primary", "Birincil", 1L, Set.of(1L), "AAAA", true, mgr.getId());
        AppUser extra = user("extra", null, 2L, Set.of(2L, 1L), "", true, null);          // ek üyelik; boş foto
        AppUser passive = user("passive", "Pasif", 1L, Set.of(), null, false, null);    // birincil, üyelik satırı yok
        user("other", "Başka", 2L, Set.of(2L), "BBBB", true, null);
        em.flush(); em.clear();

        List<Object[]> rows = userRepo.findTeamMemberDirectoryRows(1L);

        // Kâhin: eski yol — tüm varlıklar, Java'da birincil ya da çoklu üyelik
        List<AppUser> expected = new ArrayList<>();
        for (AppUser u : userRepo.findAll())
            if (Long.valueOf(1L).equals(u.getTeamId()) || (u.getTeamIds() != null && u.getTeamIds().contains(1L))) expected.add(u);
        expected.sort(java.util.Comparator.comparing(AppUser::getId));
        assertThat(rows).hasSize(expected.size()).hasSize(3);
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            AppUser u = expected.get(i);
            assertThat(r).containsExactly(u.getId(), u.getUsername(), u.getDisplayName(), u.getFirstName(), u.getLastName(),
                    u.getOrgRole(), u.getTitle(), u.getDepartment(), u.getMudurlukName(), u.getCompanyLevel(), u.getEmail(),
                    u.getManagerId(), u.getSystemRole(), u.getActive(),
                    u.getPhotoBase64() != null && !u.getPhotoBase64().isBlank());
        }
        assertThat(rows).extracting(r -> r[0]).containsExactly(primary.getId(), extra.getId(), passive.getId());

        List<Object[]> names = userRepo.findNameRowsByIds(List.of(mgr.getId(), passive.getId(), 999_999L));
        Map<Object, Object[]> byId = new HashMap<>();
        for (Object[] n : names) byId.put(n[0], n);
        assertThat(byId).containsOnlyKeys(mgr.getId(), passive.getId());
        assertThat(byId.get(mgr.getId())).containsExactly(mgr.getId(), "mgr", "Müdür", "Admgr", "Soyad", true);
        assertThat(byId.get(passive.getId())[5]).isEqualTo(false);
    }

    // ── Bildirim günlüğü ─────────────────────────────────────────────────────────────────────

    private NotificationLog log(long eventId, String sentAt, String email, String hook, String to) {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(eventId); n.setSentAt(sentAt); n.setEmailStatus(email); n.setWebhookStatus(hook);
        n.setRecipientEmail(to); n.setSubject("[Site Monitor] " + eventId); n.setMessage("<html>" + "x".repeat(5000) + "</html>");
        return logRepo.save(n);
    }

    @Test
    @DisplayName("findStatusRowsSince: findAllSince'in GÖVDESİZ sütunları, aynı satırlar ve sıra; smtpPeriodCounts = dönem başına üç eski sayım")
    void notificationProjectionsEqualTheOldQueries() {
        log(1, "2026-11-05T10:00:00", "SENT", "SKIPPED", "a@example.com");
        log(2, "2026-11-04T10:00:00", "FAILED: 550", "SENT", "b@example.com");
        log(3, "2026-11-01T10:00:00", "SKIPPED_DISABLED", "FAILED: timeout", "hook");
        log(0, "2026-10-20T10:00:00", "SENT", null, "c@example.com");
        log(4, "2026-10-01T10:00:00", "FAILED", null, "d@example.com");    // en eski kesimin de dışında
        log(5, "2026-10-12T10:00:00", "QUEUED_RETRY 1/3", null, "e@example.com");
        em.flush(); em.clear();

        String cutoff = "2026-10-10T00:00:00";
        List<NotificationLog> full = logRepo.findAllSince(cutoff);
        List<Object[]> narrow = logRepo.findStatusRowsSince(cutoff);
        assertThat(narrow).hasSize(full.size()).hasSize(5);
        for (int i = 0; i < full.size(); i++) {
            NotificationLog n = full.get(i);
            assertThat(narrow.get(i)).containsExactly(n.getAlertEventId(), n.getSentAt(), n.getRecipientEmail(), n.getSubject(),
                    n.getEmailStatus(), n.getWebhookStatus());
        }

        String c1 = "2026-11-05T00:00:00", c2 = "2026-11-02T00:00:00", c3 = "2026-10-15T00:00:00", c4 = "2026-10-10T00:00:00";
        List<Object[]> rows = logRepo.smtpPeriodCounts(c1, c2, c3, c4);
        assertThat(rows).hasSize(1);
        Object[] r = rows.get(0);
        String[] cuts = {c1, c2, c3, c4};
        for (int i = 0; i < 4; i++) {
            assertThat(((Number) r[3 * i]).longValue()).as("denenen %s", cuts[i]).isEqualTo(logRepo.countAttemptedSince(cuts[i]));
            assertThat(((Number) r[3 * i + 1]).longValue()).as("gönderilen %s", cuts[i]).isEqualTo(logRepo.countSentSince(cuts[i]));
            assertThat(((Number) r[3 * i + 2]).longValue()).as("toplam %s", cuts[i]).isEqualTo(logRepo.countAllSince(cuts[i]));
        }
        // Boş pencere: tek satır, SUM null / COUNT 0 (okuyan 0 sayar)
        List<Object[]> empty = logRepo.smtpPeriodCounts("2099-01-01T00:00:00", "2099-01-01T00:00:00", "2099-01-01T00:00:00", "2099-01-01T00:00:00");
        assertThat(empty).hasSize(1);
        assertThat(((Number) empty.get(0)[11]).longValue()).isZero();
    }

    // ── Aktivite: izleme başına son kontrol ──────────────────────────────────────────────────

    private void activity(String type, Long monitorId, String time, String action) {
        ActivityLog a = new ActivityLog();
        a.setMonitorType(type); a.setMonitorId(monitorId); a.setActivityTime(time); a.setAction(action); a.setResultStatus("SUCCESS");
        activityRepo.save(a);
    }

    @Test
    @DisplayName("lastCheckPerMonitor: mevcut her izleme için eski gruplu sorgunun MAX'ı (kontrol eylemleri, pencere içi); kontrolsüz izleme null")
    void lastCheckPerMonitorEqualsTheGroupedQuery() {
        HttpMonitor h = new HttpMonitor(); h.setName("h"); h.setUrl("https://h.example.com"); h = httpRepo.save(h);
        HttpMonitor quiet = new HttpMonitor(); quiet.setName("q"); quiet.setUrl("https://q.example.com"); quiet = httpRepo.save(quiet);
        PortMonitor p = new PortMonitor(); p.setName("p"); p.setHost("p.example.com"); p.setPort(443); p = portRepo.save(p);
        DnsMonitor d = new DnsMonitor(); d.setName("d"); d.setDomain("d.example.com"); d.setRecordType("A"); d = dnsRepo.save(d);
        activity("HTTP", h.getId(), "2026-11-05T10:00:00", "SCHEDULED_CHECK");
        activity("HTTP", h.getId(), "2026-11-05T11:00:00", "MANUAL_CHECK");
        activity("HTTP", h.getId(), "2026-11-05T12:00:00", "CONFIG_CHANGED");     // kontrol değil
        activity("HTTP", quiet.getId(), "2026-10-01T10:00:00", "SCHEDULED_CHECK"); // pencere dışı
        activity("PORT", p.getId(), "2026-11-04T09:00:00", "SCHEDULED_CHECK");
        activity("PORT", h.getId(), "2026-11-05T13:00:00", "SCHEDULED_CHECK");    // aynı kimlik, başka tür
        activity("DNS", d.getId(), "2026-11-03T08:00:00", "SCHEDULED_CHECK");
        activity("HTTP", 987_654L, "2026-11-05T09:00:00", "SCHEDULED_CHECK");      // silinmiş izleme
        em.flush(); em.clear();

        String since = "2026-10-29T00:00:00";
        Map<String, String> old = new HashMap<>();
        for (Object[] r : activityRepo.lastCheckByMonitor(since)) old.put(r[0] + ":" + ((Number) r[1]).longValue(), (String) r[2]);
        Map<String, String> fresh = new HashMap<>();
        Set<String> keys = new java.util.HashSet<>();
        for (Object[] r : activityRepo.lastCheckPerMonitor(since)) {
            String k = r[0] + ":" + ((Number) r[1]).longValue();
            keys.add(k);
            if (r[2] != null) fresh.put(k, (String) r[2]);
        }
        assertThat(keys).contains("HTTP:" + h.getId(), "HTTP:" + quiet.getId(), "PORT:" + p.getId(), "DNS:" + d.getId());
        for (String k : keys) assertThat(fresh.get(k)).as(k).isEqualTo(old.get(k));
        assertThat(fresh).containsEntry("HTTP:" + h.getId(), "2026-11-05T11:00:00")
                .containsEntry("PORT:" + p.getId(), "2026-11-04T09:00:00")
                .doesNotContainKey("HTTP:" + quiet.getId())
                .doesNotContainKey("HTTP:987654");
    }

    // ── İzleme referansı (bildirim kutusu) ───────────────────────────────────────────────────

    @Test
    @DisplayName("MonitorRefResolver dar okuma yolu = tam liste yolu (anahtar başına küçük id; silinmiş Port/DNS yok; ad anahtarlı aileler)")
    void monitorRefNarrowPathEqualsFindAllPath() {
        PortMonitor deleted = new PortMonitor(); deleted.setName("eski"); deleted.setHost("svc.example.com"); deleted.setPort(443);
        deleted.setDeletedAt("2026-09-01T00:00:00"); portRepo.save(deleted);
        PortMonitor live = new PortMonitor(); live.setName("canlı"); live.setHost("svc.example.com"); live.setPort(443); portRepo.save(live);
        PortMonitor live2 = new PortMonitor(); live2.setName("ikinci"); live2.setHost("svc.example.com"); live2.setPort(8443); portRepo.save(live2);
        HttpMonitor h = new HttpMonitor(); h.setName("Site"); h.setUrl("https://site.example.com"); httpRepo.save(h);
        DnsMonitor dns = new DnsMonitor(); dns.setName("ns"); dns.setDomain("ns.example.com"); dns.setRecordType("A");
        dns.setDeletedAt("2026-09-01T00:00:00"); dnsRepo.save(dns);
        em.flush(); em.clear();

        List<AlertEvent> events = List.of(event(10L, "PORT_DOWN", "svc.example.com"), event(11L, "DNS_FAILURE", "ns.example.com"),
                event(12L, "HTTP_DOWN", "https://site.example.com"), event(13L, "HTTP_DOWN", "https://yok.example.com"),
                event(14L, "SCRIPTED_FAIL", "k6 adı"), event(15L, null, "cert.example.com"));
        MonitorRefResolver resolver = new MonitorRefResolver(httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo, pageRepo,
                scriptedRepo, pageSpeedRepo);
        Map<Long, MonitorRefResolver.Ref> viaFindAll = resolver.resolve(events);
        ReflectionTestUtils.setField(resolver, "em", em);
        Map<Long, MonitorRefResolver.Ref> narrow = resolver.resolve(events);

        assertThat(narrow).isEqualTo(viaFindAll);
        assertThat(narrow.get(10L).monitorId()).isEqualTo(live.getId());   // silinmiş eski satır gölgelemez; küçük id kazanır
        assertThat(narrow.get(11L).monitorId()).isNull();
        assertThat(narrow.get(12L).name()).isEqualTo("Site");
        assertThat(narrow.get(13L).monitorId()).isNull();
    }

    private static AlertEvent event(long id, String type, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setAlertType(type); e.setDomain(domain);
        return e;
    }
}
