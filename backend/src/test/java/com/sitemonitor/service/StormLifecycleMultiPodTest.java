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
import com.sitemonitor.service.schedule.ClusterLockService;
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
                + "peak_targets INTEGER, resolve_reason VARCHAR(30), close_state VARCHAR(16), close_claimed_at VARCHAR(30), "
                + "close_owner VARCHAR(120), close_attempts INTEGER, recovery_state VARCHAR(16))");
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
        s.setCloseState(rs.getString("close_state"));
        s.setCloseClaimedAt(rs.getString("close_claimed_at"));
        s.setCloseOwner(rs.getString("close_owner"));
        s.setCloseAttempts((Integer) rs.getObject("close_attempts"));
        s.setRecoveryState(rs.getString("recovery_state"));
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

    // ── İki aşamalı kapanış (2026-10-21, inceleme S-1 / F1) ──────────────────────────────────────────────────────────

    /** Kurtulmuş iki üye + duyurulmamış hâlâ-down bir üye (bağı koparılmalı). */
    private void stormWithOneStillDown(long id) {
        stormWithRecoveredMembers(id);
        AlertEvent down = new AlertEvent();
        down.setId(id * 10 + 9);
        down.setDomain("https://down" + id + ".example.com");
        down.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        down.setAlertLevel("CRITICAL");
        down.setTeamId(14L);
        down.setStormId(id);
        down.setCreatedAt(ago(5));   // son toplu postadan (20 dk önce) SONRA katıldı → duyurulmamış
        down.setResolved(false);
        List<AlertEvent> ms = new ArrayList<>(members.get(id));
        ms.add(down);
        members.put(id, ms);
    }

    private String col(long id, String c) {
        return jdbc.queryForObject("SELECT " + c + " FROM alert_storms WHERE id = ?", String.class, id);
    }

    /** Sahiplenmeyi {@code minutes} dk geriye çeker (pod öldü, zaman geçti). */
    private void ageClaim(long id, long minutes) {
        jdbc.update("UPDATE alert_storms SET close_claimed_at = ? WHERE id = ?", ago(minutes), id);
    }

    private long logsWith(String trigger, String statusPart) {
        return logs.stream().filter(l -> trigger.equals(l.getTrigger()))
                .filter(l -> l.getEmailStatus() != null && l.getEmailStatus().contains(statusPart)).count();
    }

    @Test
    @DisplayName("Sahiplenmeden SONRA hata (üye okuması düştü): fırtına CLOSING kalır, kurtarma GİTMEZ; bayatlayınca başka pod bitirir — kurtarma TEK kez, sonra DONE")
    void exceptionAfterClaim_staleFinisherCompletesOnce() {
        stormWithRecoveredMembers(1);
        StormService a = pod("podA-storm");
        AtomicInteger reads = new AtomicInteger();
        List<AlertEvent> ms = members.get(1L);
        when(alertEventRepo.findByStormId(1L)).thenAnswer(i -> {
            if (reads.incrementAndGet() == 2) throw new IllegalStateException("bağlantı koptu");   // sahiplenmeden sonraki okuma
            return ms;
        });

        a.lifecycleSweep();

        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_CLOSING);
        assertThat(resolved(1)).as("sahiplenme kapsamı hemen boşaltır — takılı kapanış yeni fırtınayı engellemez").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_storms WHERE scope_key = 'TEAM:14' AND resolved = false",
                Integer.class)).as("ux_alert_storms_active (resolved=false) kapsamı serbest").isZero();
        assertThat(col(1, "recovery_state")).isEqualTo(StormService.RECOVERY_PENDING);
        assertThat(recoveryMails.get()).isZero();

        StormService b = pod("podB-storm");
        b.lifecycleSweep();   // sahiplenme taze: sahibi hâlâ çalışıyor olabilir — dokunulmaz
        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_CLOSING);
        assertThat(recoveryMails.get()).isZero();

        ageClaim(1, StormService.CLOSE_STALE_MS / 60_000 + 1);
        b.lifecycleSweep();
        assertThat(resolved(1)).isTrue();
        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_DONE);
        assertThat(col(1, "recovery_state")).isEqualTo(StormService.RECOVERY_SENT);
        assertThat(col(1, "close_owner")).isEqualTo("podB-storm");
        assertThat(recoveryMails.get()).as("kurtarma tek").isEqualTo(1);

        ageClaim(1, 60);
        a.lifecycleSweep();
        b.lifecycleSweep();
        assertThat(recoveryMails.get()).as("DONE fırtına yeniden kapanmaz").isEqualTo(1);
        assertThat(resolveLogs()).isEqualTo(2);
    }

    @Test
    @DisplayName("Üye döngüsünün ORTASINDA hata (bağ koparma düştü): kurtarma bir kez gider, kapanış CLOSING kalır; bitiren tur üyeyi bırakır, kurtarmayı YENİDEN GÖNDERMEZ")
    void exceptionMidMemberLoop_recoveryNotResent() {
        stormWithOneStillDown(1);
        StormService a = pod("podA-storm");
        AtomicInteger unlinks = new AtomicInteger();
        when(alertEventRepo.unlinkFromStorm(anyLong(), anyLong())).thenAnswer(i -> {
            if (unlinks.incrementAndGet() == 1) throw new IllegalStateException("kilit çakışması");
            return 1;
        });

        a.lifecycleSweep();

        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_CLOSING);
        assertThat(col(1, "recovery_state")).isEqualTo(StormService.RECOVERY_SENT);
        assertThat(recoveryMails.get()).isEqualTo(1);

        ageClaim(1, StormService.CLOSE_STALE_MS / 60_000 + 1);
        StormService b = pod("podB-storm");
        b.lifecycleSweep();

        assertThat(resolved(1)).isTrue();
        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_DONE);
        assertThat(unlinks.get()).as("bağ koparma en az bir kez tekrarlandı").isEqualTo(2);
        verify(alertEventRepo, times(2)).unlinkFromStorm(19L, 1L);
        assertThat(recoveryMails.get()).as("kurtarma tam olarak bir kez").isEqualTo(1);
    }

    @Test
    @DisplayName("İki pod yarıda kalan kapanışı AYNI ANDA bitirmeye çalışır: sahiplenme koşullu (close_claimed_at CAS) → kurtarma TEK kez")
    void twoPodsRaceTheFinish_singleRecovery() {
        stormWithRecoveredMembers(1);
        jdbc.update("UPDATE alert_storms SET close_state = 'CLOSING', close_claimed_at = ?, close_owner = 'oldPod', "
                + "close_attempts = 1, recovery_state = 'PENDING', resolved_at = ?, resolve_reason = ? WHERE id = 1",
                ago(30), ago(1), StormService.RESOLVE_FLOOR);
        StormService a = pod("podA-storm");
        StormService b = pod("podB-storm");
        AlertStorm sa = stormRepo.findById(1L).orElseThrow();
        AlertStorm sb = stormRepo.findById(1L).orElseThrow();

        mp.race(() -> a.finishStaleClose(sa), () -> b.finishStaleClose(sb));

        assertThat(resolved(1)).isTrue();
        assertThat(recoveryMails.get()).isEqualTo(1);
        assertThat(resolveLogs()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT close_attempts FROM alert_storms WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("Pod kurtarma postasını GÖNDERİRKEN öldü (SENDING kaldı): bitiren pod yeniden GÖNDERMEZ — UNKNOWN + notification_logs izi, kapanış DONE")
    void diedWhileSending_unknownNotResent() {
        stormWithRecoveredMembers(1);
        jdbc.update("UPDATE alert_storms SET close_state = 'CLOSING', close_claimed_at = ?, close_owner = 'deadPod', "
                + "close_attempts = 1, recovery_state = 'SENDING', resolved_at = ?, resolve_reason = ? WHERE id = 1",
                ago(30), ago(1), StormService.RESOLVE_SEALED);

        pod("podB-storm").lifecycleSweep();

        assertThat(resolved(1)).isTrue();
        assertThat(col(1, "recovery_state")).isEqualTo(StormService.RECOVERY_UNKNOWN);
        assertThat(recoveryMails.get()).as("çift posta yok").isZero();
        assertThat(logsWith(StormService.TRIGGER_STORM_RESOLVE, "sonuç bilinmiyor")).as("kurtulan üye başına iz").isEqualTo(2);
    }

    @Test
    @DisplayName("Kapanış deneme tavanında (5) da bitmezse: yine DONE, bağı koparılamayan üye için STORM izi, kurtarma bir kez")
    void giveUpAfterMaxAttempts_tracesStrandedMembers() {
        stormWithOneStillDown(1);
        when(alertEventRepo.unlinkFromStorm(anyLong(), anyLong())).thenThrow(new IllegalStateException("kalıcı hata"));
        StormService a = pod("podA-storm");

        a.lifecycleSweep();   // deneme 1
        for (int i = 2; i <= StormService.CLOSE_MAX_ATTEMPTS; i++) {
            assertThat(col(1, "close_state")).as("deneme %d öncesi hâlâ CLOSING", i).isEqualTo(StormService.CLOSE_CLOSING);
            ageClaim(1, StormService.CLOSE_STALE_MS / 60_000 + 1);
            pod("pod" + i + "-storm").lifecycleSweep();
        }

        assertThat(resolved(1)).isTrue();
        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_DONE);
        assertThat(jdbc.queryForObject("SELECT close_attempts FROM alert_storms WHERE id = 1", Integer.class))
                .isEqualTo(StormService.CLOSE_MAX_ATTEMPTS);
        assertThat(recoveryMails.get()).isEqualTo(1);
        assertThat(logs.stream().filter(l -> EscalationService.TRIGGER_STORM_SUPPRESSED.equals(l.getTrigger()))
                .filter(l -> Long.valueOf(19L).equals(l.getAlertEventId()))
                .filter(l -> l.getEmailStatus().contains("tamamlanamadı")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Pod kapanıyor (ContextClosedEvent): süpürme hiçbir şey yapmaz — kilit alınmaz, fırtına kapatılmaz")
    void shutdownGate_noWork() {
        stormWithRecoveredMembers(1);
        StormService a = pod("podA-storm");
        a.onContextClosed();

        a.lifecycleSweep();

        assertThat(resolved(1)).isFalse();
        assertThat(col(1, "close_state")).isNull();
        assertThat(mp.schedulerLockHolder("storm-sweep")).isNull();
        assertThat(recoveryMails.get()).isZero();
    }

    @Test
    @DisplayName("Küme kilidi servisi (R1-A) varsa kilit oradan: sahip = servisin benzersiz kimliği; yeni kilit vermeyi durdurunca süpürme iş yapmaz")
    void clusterLockService_ownerAndStopAcquiring() {
        stormWithRecoveredMembers(1);
        stormWithRecoveredMembers(2);
        ClusterLockService locks = new ClusterLockService(jdbc, "podA-uniq", System::currentTimeMillis, false);
        StormService a = pod("ignored");
        a.lockService = locks;
        locks.stopAcquiring();
        a.lifecycleSweep();
        assertThat(resolved(1)).isFalse();
        assertThat(mp.schedulerLockHolder("storm-sweep")).isNull();

        ClusterLockService fresh = new ClusterLockService(jdbc, "podA-uniq2", System::currentTimeMillis, false);
        a.lockService = fresh;
        a.lifecycleSweep();
        assertThat(resolved(1)).isTrue();
        assertThat(resolved(2)).isTrue();
        assertThat(col(1, "close_owner")).isEqualTo("podA-uniq2");
        assertThat(mp.schedulerLockHolder("storm-sweep")).as("kilit servisten bırakıldı").isNull();
        assertThat(recoveryMails.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Küme kilidi tur ortasında kaybedilirse (held=false) tur durur: ikinci fırtınaya dokunulmaz")
    void clusterLockLost_loopStops() {
        stormWithRecoveredMembers(1);
        stormWithRecoveredMembers(2);
        ClusterLockService locks = spy(new ClusterLockService(jdbc, "podA-uniq", System::currentTimeMillis, false));
        doReturn(false).when(locks).held("storm-sweep");
        StormService a = pod("ignored");
        a.lockService = locks;

        a.lifecycleSweep();

        assertThat(resolved(1)).isTrue();
        assertThat(resolved(2)).isFalse();
        assertThat(recoveryMails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kilit sahibi kimliği pod başına BENZERSİZ (ana makine adı + rastgele son ek) — aynı adlı iki konteyner çakışmaz")
    void lockOwnerIsUnique() {
        assertThat(StormService.INSTANCE_ID).startsWith(SchedulerService.instanceId()).endsWith("-storm");
    }

    @Test
    @DisplayName("CLOSING fırtına üye almaz: değerlendirme bireysel gönderime düşer, sayaç tazelemesi CLOSING satıra yazmaz")
    void closingStormTakesNoMembers() {
        stormWithRecoveredMembers(1);
        jdbc.update("UPDATE alert_storms SET close_state = 'CLOSING', close_claimed_at = ? WHERE id = 1", ago(0));
        AlertStorm s = stormRepo.findById(1L).orElseThrow();
        assertThat(StormService.openForMembers(s)).isFalse();
        s.setCloseState(null);
        assertThat(StormService.openForMembers(s)).isTrue();
        s.setResolved(true);
        assertThat(StormService.openForMembers(s)).isFalse();
    }

    @Test
    @DisplayName("Kapanış SÜRERKEN bireysel bildirilen üyenin damgası korunur (ikinci inceleme D1): bitiren tur bağı koparır ama damgayı sıfırlamaz (ek INITIAL yok), fırtına postasına geri sarmaz")
    void memberNotifiedWhileClosing_keepsItsStamp() {
        stormWithOneStillDown(1);
        AlertEvent down = members.get(1L).stream().filter(e -> e.getId() == 19L).findFirst().orElseThrow();
        AtomicInteger unlinks = new AtomicInteger();
        when(alertEventRepo.unlinkFromStorm(anyLong(), anyLong())).thenAnswer(i -> {
            if (unlinks.incrementAndGet() == 1) throw new IllegalStateException("kilit çakışması");
            return 1;
        });
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), anyLong(), anyString())).thenReturn(1);
        pod("podA-storm").lifecycleSweep();
        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_CLOSING);

        // Fırtına üyeler için kapalı: üye bu arada bireysel tırmandı / tekrar aldı → kendi damgası kapanış anından yeni
        String escalatedAt = ago(-1);
        down.setLastReAlertAt(escalatedAt);
        ageClaim(1, StormService.CLOSE_STALE_MS / 60_000 + 1);
        pod("podB-storm").lifecycleSweep();

        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_DONE);
        assertThat(unlinks.get()).as("damga sıfırlayan bağ koparma yalnız ilk (düşen) denemede").isEqualTo(1);
        verify(alertEventRepo).releaseFromStormAsNotified(19L, 1L, escalatedAt);
        assertThat(recoveryMails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bu sürümden önceki CLOSING satırı (resolved=false) da yaşam döngüsünce bulunur ve bitirilir — sonra resolved=true, DONE")
    void preReleaseClosingRowIsFinished() {
        stormWithRecoveredMembers(1);
        jdbc.update("UPDATE alert_storms SET close_state = 'CLOSING', close_claimed_at = ?, close_owner = 'eski', close_attempts = 1, "
                + "recovery_state = 'PENDING', resolved_at = ?, resolve_reason = ? WHERE id = 1", ago(30), ago(1), StormService.RESOLVE_FLOOR);
        assertThat(resolved(1)).isFalse();

        pod("podA-storm").lifecycleSweep();

        assertThat(resolved(1)).isTrue();
        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_DONE);
        assertThat(recoveryMails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Yalnız yarıda kalmış kapanış varsa (açık fırtına yok) süpürme yine kilidi alıp onu bitirir")
    void onlyClosingStormStillSwept() {
        stormWithRecoveredMembers(1);
        jdbc.update("UPDATE alert_storms SET resolved = true, close_state = 'CLOSING', close_claimed_at = ?, close_owner = 'ölü', "
                + "close_attempts = 1, recovery_state = 'PENDING', resolved_at = ?, resolve_reason = ? WHERE id = 1",
                ago(30), ago(1), StormService.RESOLVE_SEALED);

        pod("podA-storm").lifecycleSweep();

        assertThat(col(1, "close_state")).isEqualTo(StormService.CLOSE_DONE);
        assertThat(recoveryMails.get()).isEqualTo(1);
    }
}
