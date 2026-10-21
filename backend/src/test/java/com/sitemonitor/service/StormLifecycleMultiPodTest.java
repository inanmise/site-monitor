package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına yaşam döngüsü İKİ POD'da (2026-10-21, çok pod — MP-04). İki {@link StormService} örneği AYNI H2 veritabanını
 * paylaşır ({@code alert_storms} + {@code scheduler_lock} gerçek tablolar; depolar tabloyu okur, her okuma YENİ varlık — bayat
 * kopya gerçekteki gibi), kilit sahibi kimlikleri farklı. Kanıtlanan: kilidi süresi dolup başka pod'a geçse de kapanış
 * (kurtarma postası / günlük satırı) TEK kez; kilit tazelenemezse tur durur.
 */
class StormLifecycleMultiPodTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private MultiPodTestSupport mp;
    private JdbcTemplate jdbc;
    private AlertStormRepository stormRepo;
    private AlertEventRepository alertEventRepo;
    private EmailNotificationService email;
    private NotificationLogRepository logRepo;
    private AppSettingsService settings;
    private TeamRepository teamRepo;
    private final Map<Long, List<AlertEvent>> members = new ConcurrentHashMap<>();
    private final List<NotificationLog> logs = new CopyOnWriteArrayList<>();
    private final AtomicInteger recoveryMails = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        jdbc = mp.jdbc();
        jdbc.execute("CREATE TABLE alert_storms (id BIGINT PRIMARY KEY, scope_key VARCHAR(200) NOT NULL, scope_type VARCHAR(30), "
                + "resolved BOOLEAN NOT NULL, member_count INTEGER, root_cause VARCHAR(60), created_at VARCHAR(30), "
                + "resolved_at VARCHAR(30), last_re_alert_at VARCHAR(30), last_member_at VARCHAR(30), team_id BIGINT, "
                + "peak_targets INTEGER, resolve_reason VARCHAR(30))");
        stormRepo = mock(AlertStormRepository.class);
        alertEventRepo = mock(AlertEventRepository.class);
        email = mock(EmailNotificationService.class);
        logRepo = mock(NotificationLogRepository.class);
        settings = mock(AppSettingsService.class, withSettings().defaultAnswer(inv -> {
            Object[] a = inv.getArguments();
            if (inv.getMethod().getName().startsWith("get") && a.length == 2 && a[0] instanceof String) return a[1];
            return RETURNS_DEFAULTS.answer(inv);
        }));
        teamRepo = mock(TeamRepository.class);
        Team t = new Team();
        t.setId(14L); t.setName("Takım A"); t.setEmail("takim@example.com");
        when(teamRepo.findById(14L)).thenReturn(Optional.of(t));
        when(stormRepo.findByResolvedFalse()).thenAnswer(i ->
                jdbc.query("SELECT * FROM alert_storms WHERE resolved = false ORDER BY id", STORM));
        when(stormRepo.findById(anyLong())).thenAnswer(i ->
                jdbc.query("SELECT * FROM alert_storms WHERE id = ?", STORM, (Long) i.getArgument(0)).stream().findFirst());
        when(alertEventRepo.findByStormId(anyLong())).thenAnswer(i -> members.getOrDefault((Long) i.getArgument(0), List.of()));
        when(alertEventRepo.findByStormIdAndResolvedFalse(anyLong())).thenAnswer(i -> members
                .getOrDefault((Long) i.getArgument(0), List.of()).stream().filter(e -> !Boolean.TRUE.equals(e.getResolved())).toList());
        when(email.sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any())).thenAnswer(i -> {
            if (String.valueOf((Object) i.getArgument(2)).contains("ÇÖZÜLDÜ")) recoveryMails.incrementAndGet();
            return "SENT";
        });
        when(logRepo.save(any())).thenAnswer(i -> {
            logs.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    private static final RowMapper<AlertStorm> STORM = (rs, n) -> {
        AlertStorm s = new AlertStorm();
        s.setId(rs.getLong("id"));
        s.setScopeKey(rs.getString("scope_key"));
        s.setScopeType(rs.getString("scope_type"));
        s.setResolved(rs.getBoolean("resolved"));
        s.setMemberCount((Integer) rs.getObject("member_count"));
        s.setCreatedAt(rs.getString("created_at"));
        s.setResolvedAt(rs.getString("resolved_at"));
        s.setLastReAlertAt(rs.getString("last_re_alert_at"));
        s.setLastMemberAt(rs.getString("last_member_at"));
        s.setPeakTargets((Integer) rs.getObject("peak_targets"));
        s.setResolveReason(rs.getString("resolve_reason"));
        return s;
    };

    private StormService pod(String owner) {
        StormService s = new StormService(stormRepo, alertEventRepo, settings, email, mock(WebhookService.class), teamRepo,
                mock(EscalationContactRepository.class), mock(CertificateInventoryRepository.class), jdbc,
                mock(HttpMonitorRepository.class), mock(PortMonitorRepository.class), mock(KeywordMonitorRepository.class),
                mock(PingMonitorRepository.class), mock(DnsMonitorRepository.class), mock(DomainMonitorRepository.class),
                mock(NotificationGroupService.class));
        s.lockOwner = owner;
        ReflectionTestUtils.setField(s, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(s, "notificationLogRepo", logRepo);
        return s;
    }

    private static String ago(long minutes) {
        return ISO.format(Instant.now().minusSeconds(minutes * 60));
    }

    /** Takım 14'ün fırtınası; iki üyesi de kurtulmuş → histerezis tabanının altında, kapanır. */
    private void stormWithRecoveredMembers(long id) {
        jdbc.update("INSERT INTO alert_storms (id, scope_key, scope_type, resolved, created_at, last_member_at, last_re_alert_at, "
                + "team_id) VALUES (?, 'TEAM:14', 'TEAM', false, ?, ?, ?, 14)", id, ago(30), ago(1), ago(20));
        List<AlertEvent> ms = new ArrayList<>();
        for (long e = id * 10 + 1; e <= id * 10 + 2; e++) {
            AlertEvent ev = new AlertEvent();
            ev.setId(e);
            ev.setDomain("https://h" + e + ".example.com");
            ev.setAlertType(EscalationService.TYPE_HTTP_DOWN);
            ev.setAlertLevel("CRITICAL");
            ev.setTeamId(14L);
            ev.setStormId(id);
            ev.setCreatedAt(ago(30));
            ev.setResolved(true);
            ev.setResolvedAt(ago(2));
            ms.add(ev);
        }
        members.put(id, ms);
    }

    private boolean resolved(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT resolved FROM alert_storms WHERE id = ?", Boolean.class, id));
    }

    private long resolveLogs() {
        return logs.stream().filter(l -> StormService.TRIGGER_STORM_RESOLVE.equals(l.getTrigger())).count();
    }

    @Test
    @DisplayName("A'nın kilidi tur ortasında doldu, B kilidi alıp aynı fırtınayı kapattı: A'nın bayat kopyası KAPATAMAZ → TEK kurtarma postası, üye başına TEK STORM_RESOLVE satırı")
    void lockExpiredMidSweep_singleRecovery() {
        stormWithRecoveredMembers(1);
        StormService a = pod("podA-storm");
        StormService b = pod("podB-storm");
        AtomicBoolean interleaved = new AtomicBoolean();
        List<AlertEvent> ms = members.get(1L);
        when(alertEventRepo.findByStormId(1L)).thenAnswer(i -> {
            if (interleaved.compareAndSet(false, true)) {
                // A üye listesini okurken kilidinin süresi doldu: B süresi dolan satırı sildi, kilidi aldı ve tam turunu koştu
                jdbc.update("DELETE FROM scheduler_lock WHERE name = 'storm-sweep'");
                b.lifecycleSweep();
            }
            return ms;
        });

        a.lifecycleSweep();

        assertThat(interleaved).isTrue();
        assertThat(resolved(1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT resolve_reason FROM alert_storms WHERE id = 1", String.class))
                .isEqualTo(StormService.RESOLVE_FLOOR);
        assertThat(recoveryMails.get()).as("kurtarma postası tek").isEqualTo(1);
        assertThat(resolveLogs()).as("üye başına tek STORM_RESOLVE günlük satırı").isEqualTo(2);
    }

    @Test
    @DisplayName("Kilit tazelenemezse (başka pod aldı) tur DURUR: ikinci fırtınaya dokunulmaz; kilidin sahibi sonra onu kapatır — her fırtına tek kurtarma")
    void lockLost_loopStops() {
        stormWithRecoveredMembers(1);
        stormWithRecoveredMembers(2);
        StormService a = pod("podA-storm");
        a.lockRenewEveryMs = 0;   // her fırtınadan önce tazele
        when(email.sendHtml(any(String[].class), any(), anyString(), any(), any(), anyList(), anyBoolean(), any())).thenAnswer(i -> {
            if (String.valueOf((Object) i.getArgument(2)).contains("ÇÖZÜLDÜ") && recoveryMails.incrementAndGet() == 1) {
                // 1. fırtınanın postası yavaş: bu sırada A'nın kilidi doldu ve B aldı
                jdbc.update("UPDATE scheduler_lock SET locked_by = 'podB-storm' WHERE name = 'storm-sweep'");
            }
            return "SENT";
        });

        a.lifecycleSweep();

        assertThat(resolved(1)).isTrue();
        assertThat(resolved(2)).as("kilidini kaybeden pod 2. fırtınaya dokunmaz").isFalse();
        assertThat(recoveryMails.get()).isEqualTo(1);
        assertThat(mp.schedulerLockHolder("storm-sweep")).as("A başkasının kilidini silmez").isEqualTo("podB-storm");

        jdbc.update("DELETE FROM scheduler_lock WHERE name = 'storm-sweep'");   // B'nin turu bitti
        pod("podB-storm").lifecycleSweep();
        assertThat(resolved(2)).isTrue();
        assertThat(recoveryMails.get()).isEqualTo(2);
        assertThat(resolveLogs()).isEqualTo(4);
    }

    @Test
    @DisplayName("İki pod aynı anda süpürür (kilit iki pod'da da kayıtsız kalsa bile — tek pod geri düşüşü yok): kapanış koşullu → TEK kurtarma")
    void concurrentSweeps_singleRecovery() {
        stormWithRecoveredMembers(1);
        StormService a = pod("podA-storm");
        StormService b = pod("podB-storm");

        for (int round = 0; round < 3; round++) {
            mp.race(() -> { a.lifecycleSweep(); return true; }, () -> { b.lifecycleSweep(); return true; });
        }

        assertThat(resolved(1)).isTrue();
        assertThat(recoveryMails.get()).isEqualTo(1);
        assertThat(resolveLogs()).isEqualTo(2);
        assertThat(mp.schedulerLockHolder("storm-sweep")).as("kilit bırakıldı").isNull();
    }
}
