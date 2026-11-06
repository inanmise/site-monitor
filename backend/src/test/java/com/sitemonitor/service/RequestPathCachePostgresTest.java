package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.it.SwallowedSqlErrors;
import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performans 2026-11-06 (paket B1) — istek yolu belleklerinin yeni DAR sorguları GERÇEK PostgreSQL'de: yerel SQL'in tür
 * dönüşümü (UNION ALL metin sabiti = varchar kolon), JPQL {@code MEMBER OF}, {@code LENGTH(TRIM(text))}, tek satırlık
 * {@code SUM(CASE …)} ve dar okuma yolu; sonuçlar eski sorgularla (kâhin) aynı. Servis girişleri yutulan SQL hatası üretmez.
 */
@PostgresIntegration
class RequestPathCachePostgresTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static <T> T bean(Class<T> type) { return PostgresIt.app().bean(type); }

    @Test
    @DisplayName("lastCheckPerMonitor (UNION ALL + ilişkili MAX) PostgreSQL'de eski gruplu sorguyla aynı sonucu verir")
    void lastCheckPerMonitorOnPostgres() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        HttpMonitor h = new HttpMonitor(); h.setName("rp-http-" + tag); h.setUrl("https://rp-" + tag + ".invalid/");
        long hid = bean(HttpMonitorRepository.class).save(h).getId();
        PortMonitor p = new PortMonitor(); p.setName("rp-port-" + tag); p.setHost("rp-" + tag + ".invalid"); p.setPort(443);
        long pid = bean(PortMonitorRepository.class).save(p).getId();
        Instant now = Instant.now();
        ActivityLogRepository repo = bean(ActivityLogRepository.class);
        for (Object[] a : List.<Object[]>of(new Object[]{"HTTP", hid, 30, "SCHEDULED_CHECK"}, new Object[]{"HTTP", hid, 10, "MANUAL_CHECK"},
                new Object[]{"HTTP", hid, 5, "CONFIG_CHANGED"}, new Object[]{"PORT", pid, 60, "SCHEDULED_CHECK"})) {
            ActivityLog l = new ActivityLog();
            l.setMonitorType((String) a[0]); l.setMonitorId((Long) a[1]); l.setAction((String) a[3]); l.setResultStatus("SUCCESS");
            l.setActivityTime(ISO.format(now.minus((Integer) a[2], ChronoUnit.MINUTES)));
            repo.save(l);
        }
        String since = ISO.format(now.minus(7, ChronoUnit.DAYS));
        Map<String, String> old = new HashMap<>();
        for (Object[] r : repo.lastCheckByMonitor(since)) old.put(r[0] + ":" + ((Number) r[1]).longValue(), (String) r[2]);
        Map<String, String> fresh = new HashMap<>();
        for (Object[] r : repo.lastCheckPerMonitor(since)) if (r[2] != null) fresh.put(r[0] + ":" + ((Number) r[1]).longValue(), (String) r[2]);
        // Paylaşılan postgres-it veritabanında başka bir test aynı izleme kimliğine (tablolar arası çakışan id) daha yeni bir
        // satır bırakmış olabilir (CI 2026-11-06) — asıl kanıt yeni sorgu = eski sorgu; mutlak zaman en az bizim son kontrolümüz
        assertThat(fresh.get("HTTP:" + hid)).isEqualTo(old.get("HTTP:" + hid))
                .isGreaterThanOrEqualTo(ISO.format(now.minus(10, ChronoUnit.MINUTES)));
        assertThat(fresh.get("PORT:" + pid)).isEqualTo(old.get("PORT:" + pid));
        for (Map.Entry<String, String> e : fresh.entrySet()) assertThat(e.getValue()).as(e.getKey()).isEqualTo(old.get(e.getKey()));
    }

    @Test
    @DisplayName("bildirim günlüğü gövdesiz satırları + tek geçişli SMTP dönemleri PostgreSQL'de eski sorgularla aynı")
    void notificationProjectionsOnPostgres() {
        NotificationLogRepository repo = bean(NotificationLogRepository.class);
        Instant now = Instant.now();
        String[][] rows = {{"SENT", "SKIPPED"}, {"FAILED: 550", "SENT"}, {"SKIPPED_DISABLED", "FAILED"}, {"QUEUED_RETRY 1/3", null}};
        int i = 0;
        for (String[] r : rows) {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L); n.setEmailStatus(r[0]); n.setWebhookStatus(r[1]); n.setRecipientEmail("rp@example.com");
            n.setSentAt(ISO.format(now.minus(++i * 2L, ChronoUnit.DAYS))); n.setSubject("rp"); n.setMessage("<p>" + "x".repeat(3000) + "</p>");
            repo.save(n);
        }
        String c1 = ISO.format(now.minus(1, ChronoUnit.DAYS)), c2 = ISO.format(now.minus(3, ChronoUnit.DAYS)),
               c3 = ISO.format(now.minus(5, ChronoUnit.DAYS)), c4 = ISO.format(now.minus(30, ChronoUnit.DAYS));
        List<NotificationLog> full = repo.findAllSince(c4);
        List<Object[]> narrow = repo.findStatusRowsSince(c4);
        assertThat(narrow).hasSameSizeAs(full);
        Object[] r = repo.smtpPeriodCounts(c1, c2, c3, c4).get(0);
        String[] cuts = {c1, c2, c3, c4};
        for (int k = 0; k < 4; k++) {
            assertThat(((Number) r[3 * k]).longValue()).isEqualTo(repo.countAttemptedSince(cuts[k]));
            assertThat(((Number) r[3 * k + 1]).longValue()).isEqualTo(repo.countSentSince(cuts[k]));
            assertThat(((Number) r[3 * k + 2]).longValue()).isEqualTo(repo.countAllSince(cuts[k]));
        }
    }

    @Test
    @DisplayName("takım üyeleri dar satırları (MEMBER OF, LENGTH(TRIM(text))) PostgreSQL'de doğru; ad satırları kimlikle")
    void teamMemberRowsOnPostgres() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long team = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L) + 5_000_000L;   // çakışmayan takım kimliği
        AppUserRepository repo = bean(AppUserRepository.class);
        AppUser a = user("rpa-" + tag, team, Set.of(team), "QUFBQQ==");
        AppUser b = user("rpb-" + tag, team + 1, Set.of(team + 1, team), "");
        user("rpc-" + tag, team + 1, Set.of(team + 1), null);
        List<Object[]> rows = repo.findTeamMemberDirectoryRows(team);
        assertThat(rows).extracting(x -> x[0]).containsExactly(a.getId(), b.getId());
        assertThat(rows).extracting(x -> x[14]).containsExactly(true, false);
        assertThat(repo.findNameRowsByIds(List.of(a.getId()))).singleElement().satisfies(x -> assertThat(x[1]).isEqualTo("rpa-" + tag));
    }

    private static AppUser user(String username, long teamId, Set<Long> teams, String photo) {
        AppUser u = new AppUser();
        u.setUsername(username); u.setTeamId(teamId); u.setTeamIds(new LinkedHashSet<>(teams)); u.setPhotoBase64(photo);
        u.setActive(true); u.setSystemRole("USER");
        return bean(AppUserRepository.class).save(u);
    }

    @Test
    @DisplayName("bildirim kutusu (dar izleme referansı dahil), 'bugün' anlık görüntüsü ve SMTP özeti yutulan SQL hatası üretmez")
    void servicesHaveNoSwallowedSqlErrors() {
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            AlertEvent e = new AlertEvent(); e.setId(-1L); e.setAlertType("PORT_DOWN"); e.setDomain("yok.invalid");
            bean(MonitorRefResolver.class).resolve(List.of(e, alert("DNS_FAILURE"), alert("SCRIPTED_FAIL"), alert("DBQUERY_FAIL"),
                    alert("HTTP_DOWN"), alert("KEYWORD_DOWN"), alert("PING_DOWN"), alert("PAGE_DOWN"), alert("PAGESPEED_DOWN"),
                    alert("DOMAINMON_EXPIRY")));
            bean(InboxService.class).build(t -> true, List.of());
            bean(TodayMonitorInsightsService.class).snapshot(Instant.now());
            Map<String, Object> smtp = bean(ExtendedHealthService.class).getSmtpStats();
            bean(ExtendedHealthService.class).findDomainsWithConsecutiveMailFailures(3, 7);
            assertThat(sql.errors()).as("yutulan SQL hataları").isEmpty();
            assertThat(smtp).doesNotContainKey("error").containsKey("periods");
        }
    }

    private static long seq = -100;

    private static AlertEvent alert(String type) {
        AlertEvent e = new AlertEvent(); e.setId(seq--); e.setAlertType(type); e.setDomain("yok-" + type + ".invalid");
        return e;
    }
}
