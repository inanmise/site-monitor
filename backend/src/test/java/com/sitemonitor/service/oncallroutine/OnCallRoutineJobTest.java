package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.NoticeRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.A;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.B;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.MON_0900;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.TEAM;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.THU_0930;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nöbet rutini işi + bildirimleri — GERÇEK SQL (H2, PostgreSQL kipi): iki pod tek tamamlama bildirimi, yarıda kalan
 * sahiplenme UNKNOWN + tek iz (yeniden gönderilmez), hatırlatma yalnız sorumluya ve yalnız bekleyen varken, gün kapanışı,
 * devir bir kez, e-posta yeniden denemesi push'u tekrarlamaz. İki {@link OnCallRoutineNotifier} örneği = iki pod.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OnCallRoutineJobTest {

    static final LocalDate THU = LocalDate.parse("2026-11-26");
    static final String DONE_TRIGGER = OnCallRoutineCodes.TRIGGER_DONE;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired NotificationLogRepository logs;

    OnCallRoutineTestRig rig;
    final EscalationService escalation = mock(EscalationService.class);
    final EmailNotificationService email = mock(EmailNotificationService.class);
    final UserPushService push = mock(UserPushService.class);
    final SystemMaintenanceService maintenance = mock(SystemMaintenanceService.class);
    final AtomicInteger mails = new AtomicInteger();
    volatile String mailResult = "SENT";

    @BeforeEach
    void setUp() {
        rig = new OnCallRoutineTestRig(jdbc, txm);
        clean();
        when(escalation.teamEmailsForMonitor(eq(TEAM), any())).thenReturn(List.of("odeme@example.com"));
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), eq(false), any())).thenAnswer(i -> {
            mails.incrementAndGet();
            return mailResult;
        });
        when(push.messageCharLimit()).thenReturn(200);
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 3));
        when(push.enqueueDirectLocalized(anyList(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Map.of("queued", 1));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        rig.clean();
        List<NotificationLog> ours = new ArrayList<>();
        for (NotificationLog l : logs.findAll()) {
            if (l.getTrigger() != null && l.getTrigger().startsWith("ONCALL_ROUTINE")) ours.add(l);
        }
        logs.deleteAll(ours);
    }

    OnCallRoutineNotifier notifier(Instant now) {
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(rig.store, rig.svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = Clock.fixed(now, ZoneOffset.UTC);
        return n;
    }

    List<NotificationLog> ourLogs(String trigger) {
        return logs.findAll().stream().filter(l -> trigger.equals(l.getTrigger())).toList();
    }

    /** Pazartesi kaydedilmiş şablon; bugün (Perşembe) zorunlular işaretli ve gün tamamlanmış. */
    TeamCtx completedThursday() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        rig.svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, A, true);
        rig.svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "DONE", null, null, A, true);
        rig.svc.completeDay(c, THU, rig.store.day(TEAM, "2026-11-26").version(), null, null, A);
        return c;
    }

    @Test
    @DisplayName("iki pod aynı anda: tek tamamlama e-postası + tek push (tamamlayan hariç); yeniden tamamlama 'güncellendi' ile ikinci bildirim")
    void twoPodsOneCompletionNotice() throws Exception {
        TeamCtx c = completedThursday();
        Instant now = THU_0930.plusSeconds(60);
        OnCallRoutineNotifier pod1 = notifier(now), pod2 = notifier(now);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier go = new CyclicBarrier(2);
            List<Future<Integer>> fs = new ArrayList<>();
            for (OnCallRoutineNotifier n : List.of(pod1, pod2)) {
                fs.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return n.sendPendingCompletions(now, () -> false);
                }));
            }
            for (Future<Integer> f : fs) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(mails.get()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> exclude = ArgumentCaptor.forClass(Set.class);
        verify(push, times(1)).enqueueTeamNoticeLocalized(eq(TEAM), eq(DONE_TRIGGER), eq("WARNING"),
                eq(OnCallRoutineCodes.PUSH_TYPE), eq("Ödeme Sistemleri"), any(), eq("or-done:7:2026-11-26:1"), exclude.capture());
        assertThat(exclude.getValue()).containsExactly("N10");
        NoticeRow notice = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(notice.status()).isEqualTo("SENT");
        assertThat(notice.pushCount()).isEqualTo(3);
        assertThat(rig.store.day(TEAM, "2026-11-26").notifiedSeq()).isEqualTo(1);
        List<NotificationLog> trace = ourLogs(DONE_TRIGGER);
        assertThat(trace).hasSize(1);
        assertThat(trace.get(0).getSubject()).isEqualTo("[Site Monitor] Nöbet rutini tamamlandı: Ödeme Sistemleri · 26.11.2026");
        assertThat(trace.get(0).getRecipientEmail()).isEqualTo("odeme@example.com");
        assertThat(trace.get(0).getAlertEventId()).isZero();

        // tekrar tur: aday yok
        pod1.sendPendingCompletions(now, () -> false);
        assertThat(mails.get()).isEqualTo(1);

        // yeniden aç + yeniden tamamla → seq 2, ayrı bildirim
        DayRow day = rig.store.day(TEAM, "2026-11-26");
        rig.svc.reopenDay(c, THU, day.version(), B);
        rig.svc.completeDay(c, THU, rig.store.day(TEAM, "2026-11-26").version(), null, null, B);
        pod2.sendPendingCompletions(now, () -> false);
        assertThat(mails.get()).isEqualTo(2);
        assertThat(ourLogs(DONE_TRIGGER)).extracting(NotificationLog::getSubject)
                .contains("[Site Monitor] Nöbet rutini tamamlandı: Ödeme Sistemleri · 26.11.2026 (güncellendi)");
        assertThat(rig.store.day(TEAM, "2026-11-26").notifiedSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("SENDING'de kalan sahiplenme 15 dk sonra UNKNOWN + TEK 'sonuç bilinmiyor' izi; asla yeniden gönderilmez")
    void staleSendingBecomesUnknownOnce() {
        completedThursday();
        String claimedAt = OnCallRoutineStore.ts(THU_0930.plusSeconds(5));
        Long id = rig.store.claim(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1", OnCallRoutineCodes.N_SENDING, null, null,
                claimedAt, "pod-x");
        assertThat(id).isNotNull();
        Instant early = THU_0930.plus(Duration.ofMinutes(10));
        assertThat(notifier(early).sweepStale(early)).isZero();   // 15 dk dolmadı

        Instant later = THU_0930.plus(Duration.ofMinutes(25));
        OnCallRoutineNotifier n = notifier(later);
        assertThat(n.sweepStale(later)).isEqualTo(1);
        NoticeRow row = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(row.status()).isEqualTo("UNKNOWN");
        assertThat(row.reason()).isEqualTo("INTERRUPTED");
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo(OnCallRoutineNotifier.FAILED_UNKNOWN));
        assertThat(n.sweepStale(later)).isZero();
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1);

        // tamamlama turu: karar zaten var → yalnız işaret, e-posta yok
        n.sendPendingCompletions(later, () -> false);
        assertThat(mails.get()).isZero();
        verify(push, never()).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), any());
        assertThat(rig.store.day(TEAM, "2026-11-26").notifiedSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("yarıda kalan sahiplenme: gönderen pod günlüğü yazmışsa ikinci iz yazılmaz")
    void staleSendingAlreadyTraced() {
        completedThursday();
        String claimedAt = OnCallRoutineStore.ts(THU_0930.plusSeconds(5));
        rig.store.claim(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1", OnCallRoutineCodes.N_SENDING, null, null, claimedAt,
                "pod-x");
        Instant later = THU_0930.plus(Duration.ofMinutes(25));
        OnCallRoutineNotifier n = notifier(later);
        String subject = n.subjectOf(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1"));
        NotificationLog l = new NotificationLog();
        l.setAlertEventId(0L);
        l.setTrigger(DONE_TRIGGER);
        l.setSubject(subject);
        l.setSentAt(OnCallRoutineStore.ts(THU_0930.plusSeconds(20)));
        l.setEmailStatus("SENT");
        logs.save(l);
        assertThat(n.sweepStale(later)).isEqualTo(1);
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1);
    }

    @Test
    @DisplayName("yarıda kalan sahiplenme: gönderilen konu güncel gün satırından başka biçimdeyse (geç / eksiklerle) de ikinci iz yazılmaz")
    void staleSendingTracedWithOtherSubjectVariant() {
        completedThursday();
        String claimedAt = OnCallRoutineStore.ts(THU_0930.plusSeconds(5));
        rig.store.claim(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1", OnCallRoutineCodes.N_SENDING, null, null, claimedAt,
                "pod-x");
        Instant later = THU_0930.plus(Duration.ofMinutes(25));
        OnCallRoutineNotifier n = notifier(later);
        NoticeRow row = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        String current = n.subjectOf(row);
        List<String> variants = n.subjectVariants(row, current);
        assertThat(variants).hasSizeGreaterThan(1).first().isEqualTo(current);
        String other = variants.get(variants.size() - 1);
        assertThat(other).isNotEqualTo(current);
        NotificationLog l = new NotificationLog();
        l.setAlertEventId(0L);
        l.setTrigger(DONE_TRIGGER);
        l.setSubject(other);
        l.setSentAt(OnCallRoutineStore.ts(THU_0930.plusSeconds(20)));
        l.setEmailStatus("SENT");
        logs.save(l);
        assertThat(n.sweepStale(later)).isEqualTo(1);
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1);
    }

    @Test
    @DisplayName("e-postası düşen bildirim (PARTIAL): 10 dk dolmadan denenmez; sonra YALNIZ e-posta yeniden — push tekrarlanmaz")
    void mailOnlyRetry() {
        completedThursday();
        mailResult = "FAILED: bağlantı reddedildi";
        Instant t0 = THU_0930.plusSeconds(60);
        notifier(t0).sendPendingCompletions(t0, () -> false);
        NoticeRow r = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(r.status()).isEqualTo("PARTIAL");
        assertThat(mails.get()).isEqualTo(1);

        mailResult = "SENT";
        Instant t5 = t0.plus(Duration.ofMinutes(5));
        notifier(t5).sendPendingCompletions(t5, () -> false);
        assertThat(mails.get()).isEqualTo(1);

        Instant t11 = t0.plus(Duration.ofMinutes(11));
        notifier(t11).sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(2);
        NoticeRow after = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(after.status()).isEqualTo("SENT");
        assertThat(after.attempts()).isEqualTo(2);
        verify(push, times(1)).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), any());
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(2);
    }

    @Test
    @DisplayName("sistem bakımı susturması: pencere içinde sahiplenme yok, bakım bitince gönderilir")
    void maintenanceMuteWaits() {
        completedThursday();
        when(maintenance.notificationsMuted()).thenReturn(true);
        Instant t = THU_0930.plusSeconds(60);
        notifier(t).sendPendingCompletions(t, () -> false);
        assertThat(mails.get()).isZero();
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1")).isNull();
        assertThat(ourLogs(DONE_TRIGGER)).isEmpty();
        when(maintenance.notificationsMuted()).thenReturn(false);
        notifier(t).sendPendingCompletions(t, () -> false);
        assertThat(mails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("takım tamamlama bildirimini kapattıysa: SKIPPED kararı + tek iz satırı, e-posta / push yok")
    void teamSwitchOffLeavesDecisionTrace() {
        completedThursday();
        rig.svc.saveSettings(TEAM, 0, Map.of("completion_notice", Map.of("enabled", false)), A);
        Instant t = THU_0930.plusSeconds(60);
        notifier(t).sendPendingCompletions(t, () -> false);
        assertThat(mails.get()).isZero();
        NoticeRow r = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.reason()).isEqualTo("DISABLED");
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo(OnCallRoutineNotifier.SKIP_TEAM_OFF));
        assertThat(rig.store.day(TEAM, "2026-11-26").notifiedSeq()).isEqualTo(1);
    }

    // ── Hatırlatmalar ────────────────────────────────────────────────────────────────────────

    TeamCtx withResponsible() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(THU_0930);
        rig.person(21L, "N21", "Zeynep Kaya", true, true);
        TeamCtx c = rig.ctx();
        rig.svc.assign(c, "DAY", "2026-11-26", THU, 21L, "Zeynep Kaya", null, A);
        return c;
    }

    @Test
    @DisplayName("hatırlatma YALNIZ sorumluya (tek alıcı), yuva başına bir kez; ikinci yuva 'Hatırlatma:' önekiyle")
    void remindersOnlyToResponsible() {
        TeamCtx c = withResponsible();
        Instant t1631 = Instant.parse("2026-11-26T13:31:00Z");
        assertThat(notifier(t1631).reminders(c, t1631)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPushService.DirectRecipient>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(push, times(1)).enqueueDirectLocalized(to.capture(), eq(TEAM), eq(OnCallRoutineCodes.TRIGGER_REMINDER), eq("WARNING"),
                eq(OnCallRoutineCodes.PUSH_TYPE), eq("Ödeme Sistemleri"), text.capture(), key.capture());
        assertThat(to.getValue()).extracting(UserPushService.DirectRecipient::username).containsExactly("N21");
        assertThat(key.getValue()).isEqualTo("or-rem:7:2026-11-26:1630");
        assertThat(text.getValue().tr()).isEqualTo("Ödeme Sistemleri - nöbet rutininde 2 görev bekliyor");
        NoticeRow r = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_REMINDER, "2026-11-26|16:30");
        assertThat(r.status()).isEqualTo("SENT");
        assertThat(r.recipientUserId()).isEqualTo(21L);

        Instant t1640 = Instant.parse("2026-11-26T13:40:00Z");
        assertThat(notifier(t1640).reminders(c, t1640)).isZero();   // aynı yuva bir kez

        Instant t1731 = Instant.parse("2026-11-26T14:31:00Z");
        assertThat(notifier(t1731).reminders(c, t1731)).isEqualTo(1);
        verify(push, times(2)).enqueueDirectLocalized(to.capture(), eq(TEAM), anyString(), anyString(), anyString(), anyString(),
                text.capture(), key.capture());
        assertThat(text.getValue().tr()).startsWith("Hatırlatma: ");
        assertThat(text.getValue().en()).startsWith("Reminder: ");
        // e-posta yok, günlük satırı yok
        assertThat(mails.get()).isZero();
        assertThat(ourLogs(OnCallRoutineCodes.TRIGGER_REMINDER)).isEmpty();
    }

    @Test
    @DisplayName("bekleyen zorunlu kalmadıysa ikinci hatırlatma gönderilmez ve yuva satırı yazılmaz")
    void secondReminderOnlyIfPending() {
        TeamCtx c = withResponsible();
        Instant t1631 = Instant.parse("2026-11-26T13:31:00Z");
        notifier(t1631).reminders(c, t1631);
        rig.svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, B, true);
        rig.svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "DONE", null, null, B, true);
        Instant t1731 = Instant.parse("2026-11-26T14:31:00Z");
        assertThat(notifier(t1731).reminders(c, t1731)).isZero();
        verify(push, times(1)).enqueueDirectLocalized(anyList(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString());
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_REMINDER, "2026-11-26|17:30")).isNull();
    }

    @Test
    @DisplayName("sorumlu yoksa push yok, karar satırı NO_RESPONSIBLE; tamamlanmış günde hatırlatma yok")
    void reminderWithoutResponsible() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        Instant t1631 = Instant.parse("2026-11-26T13:31:00Z");
        assertThat(notifier(t1631).reminders(c, t1631)).isZero();
        NoticeRow r = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_REMINDER, "2026-11-26|16:30");
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.reason()).isEqualTo("NO_RESPONSIBLE");
        verify(push, never()).enqueueDirectLocalized(anyList(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString());

        rig.svc.completeDay(c, THU, rig.store.day(TEAM, "2026-11-26") == null ? 0 : rig.store.day(TEAM, "2026-11-26").version(),
                "Bugün nöbetçi yoktu", null, A);
        Instant t1731 = Instant.parse("2026-11-26T14:31:00Z");
        assertThat(notifier(t1731).reminders(c, t1731)).isZero();
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_REMINDER, "2026-11-26|17:30")).isNull();
    }

    @Test
    @DisplayName("pasif takıma hatırlatma yok: push da yuva satırı da yazılmaz")
    void noReminderForPassiveTeam() {
        withResponsible();
        rig.team(TEAM, OnCallRoutineTestRig.TEAM_NAME, false);
        TeamCtx c = rig.ctx();
        Instant t1631 = Instant.parse("2026-11-26T13:31:00Z");
        assertThat(notifier(t1631).reminders(c, t1631)).isZero();
        verify(push, never()).enqueueDirectLocalized(anyList(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString());
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_REMINDER, "2026-11-26|16:30")).isNull();
    }

    // ── Kapanış ve devir ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gün kapanışı: etkinleşmeden önceki gün kapanmaz; bütçe sınırlar; ikinci tur dokunmaz")
    void closeDays() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        OnCallRoutineJob job = new OnCallRoutineJob(rig.store, rig.svc, notifier(THU_0930));
        int[] small = {2};
        assertThat(job.closeDays(c, THU_0930, small)).isEqualTo(2);
        assertThat(small[0]).isZero();
        int[] budget = {50};
        assertThat(job.closeDays(c, THU_0930, budget)).isEqualTo(1);
        assertThat(job.closeDays(c, THU_0930, budget)).isZero();
        assertThat(rig.store.day(TEAM, "2026-11-23").status()).isEqualTo("MISSED");
        assertThat(rig.store.day(TEAM, "2026-11-25").status()).isEqualTo("MISSED");
        assertThat(rig.store.day(TEAM, "2026-11-20")).isNull();   // rutin 23'ünde başladı
        assertThat(rig.store.day(TEAM, "2026-11-26")).isNull();   // bugün kapanmaz
    }

    @Test
    @DisplayName("devir: önceki dönemin günleri kapanır, özet saklanır, e-posta + push BİR kez")
    void handoverOnce() {
        Instant mon16 = Instant.parse("2026-11-16T06:00:00Z");
        rig.at(mon16);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        Instant mon23 = Instant.parse("2026-11-23T07:00:00Z");   // 10:00 İstanbul, devirden 1 saat sonra
        rig.at(mon23);
        TeamCtx c = rig.ctx();
        OnCallRoutineJob job1 = new OnCallRoutineJob(rig.store, rig.svc, notifier(mon23));
        OnCallRoutineJob job2 = new OnCallRoutineJob(rig.store, rig.svc, notifier(mon23));
        assertThat(job1.handover(c, mon23, new int[] {50})).isTrue();
        assertThat(job2.handover(c, mon23, new int[] {50})).isFalse();
        assertThat(mails.get()).isEqualTo(1);
        verify(push, times(1)).enqueueTeamNoticeLocalized(eq(TEAM), eq(OnCallRoutineCodes.TRIGGER_HANDOVER), anyString(), anyString(),
                anyString(), any(), eq("or-hand:7:2026-11-16"), any());
        for (String d : List.of("2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20")) {
            assertThat(rig.store.day(TEAM, d).status()).as(d).isEqualTo("MISSED");
        }
        assertThat(rig.store.day(TEAM, "2026-11-21")).isNull();
        assertThat(rig.store.handover(TEAM, "2026-11-16").summaryJson()).contains("\"workdays\":5");
        List<NotificationLog> trace = ourLogs(OnCallRoutineCodes.TRIGGER_HANDOVER);
        assertThat(trace).hasSize(1);
        assertThat(trace.get(0).getSubject()).isEqualTo("[Site Monitor] Nöbet devri: Ödeme Sistemleri · 16.11 – 22.11.2026");
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_HANDOVER, "2026-11-16").status()).isEqualTo("SENT");

        // pencere dışında (devirden 25 saat sonra) bildirim yok
        rig.clean();
        Instant late = Instant.parse("2026-11-24T07:00:00Z");
        rig.at(mon16);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        OnCallRoutineJob job3 = new OnCallRoutineJob(rig.store, rig.svc, notifier(late));
        assertThat(job3.handover(rig.ctx(), late, new int[] {50})).isFalse();
    }

    @Test
    @DisplayName("ucuz kapı: etkin takım ya da bildirilmemiş tamamlama yoksa iş yok")
    void anyWorkGate() {
        OnCallRoutineJob job = new OnCallRoutineJob(rig.store, rig.svc, notifier(THU_0930));
        assertThat(job.anyWork(THU_0930)).isFalse();
        rig.saveTemplate(OnCallRoutineTestRig.template());
        assertThat(job.anyWork(THU_0930)).isTrue();
    }

    // ── Hata avı (2026-11-26) ─────────────────────────────────────────────────────────────────

    List<NotificationLog> retryTraces() {
        return ourLogs(DONE_TRIGGER).stream()
                .filter(l -> l.getEmailStatus() != null && l.getEmailStatus().startsWith(OnCallRoutineNotifier.SKIP_RETRY_PREFIX))
                .toList();
    }

    /** E-postası düşen (PARTIAL) tamamlama bildirimi; 10 dk sonrası ilk yeniden deneme anı döner. */
    Instant partialNotice() {
        completedThursday();
        mailResult = "FAILED: bağlantı reddedildi";
        Instant t0 = THU_0930.plusSeconds(60);
        notifier(t0).sendPendingCompletions(t0, () -> false);
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status()).isEqualTo("PARTIAL");
        assertThat(mails.get()).isEqualTo(1);
        mailResult = "SENT";
        return t0.plus(Duration.ofMinutes(11));
    }

    @Test
    @DisplayName("yeniden deneme: takım tamamlama bildirimini SONRADAN kapattıysa yeniden gönderilmez — hak biter, TEK iz satırı")
    void retryStopsWhenTeamSwitchedOff() {
        Instant t11 = partialNotice();
        rig.svc.saveSettings(TEAM, 0, Map.of("completion_notice", Map.of("enabled", false)), A);
        notifier(t11).sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        NoticeRow r = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(r.status()).isEqualTo("PARTIAL");   // gerçek teslim sonucu aynen kalır
        assertThat(r.attempts()).isEqualTo(OnCallRoutineCodes.MAX_ATTEMPTS);
        assertThat(retryTraces()).hasSize(1).allSatisfy(l -> assertThat(l.getEmailStatus())
                .isEqualTo(OnCallRoutineNotifier.SKIP_RETRY_PREFIX + "takım ayarı kapalı"));

        Instant t40 = t11.plus(Duration.ofMinutes(30));
        notifier(t40).sendPendingCompletions(t40, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(retryTraces()).hasSize(1);
    }

    @Test
    @DisplayName("yeniden deneme: e-posta kanalı kapandıysa (push açık) PARTIAL'ın e-posta denemesi yapılmaz")
    void retryStopsWhenMailChannelOff() {
        Instant t11 = partialNotice();
        rig.svc.saveSettings(TEAM, 0, Map.of("completion_notice", Map.of("enabled", true, "mail", false, "push", true)), A);
        notifier(t11).sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(retryTraces()).hasSize(1);
    }

    @Test
    @DisplayName("yeniden deneme: modül takım için kapandıysa yeniden gönderilmez")
    void retryStopsWhenModuleHidden() {
        Instant t11 = partialNotice();
        OnCallRoutineNotifier n = notifier(t11);
        n.visibility = mock(OnCallRoutineVisibility.class);
        when(n.visibility.teamVisible(TEAM)).thenReturn(false);
        n.sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(retryTraces()).hasSize(1).allSatisfy(l -> assertThat(l.getEmailStatus())
                .isEqualTo(OnCallRoutineNotifier.SKIP_RETRY_PREFIX + "modül bu takım için kapalı"));
    }

    @Test
    @DisplayName("yeniden deneme: gün yeniden açıldıysa eski bildirim denenmez; yeniden tamamlanınca YENİ bildirim normal gider")
    void retryStopsWhenDayReopened() {
        Instant t11 = partialNotice();
        TeamCtx c = rig.ctx();
        rig.svc.reopenDay(c, THU, rig.store.day(TEAM, "2026-11-26").version(), A);
        notifier(t11).sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(retryTraces()).hasSize(1).allSatisfy(l -> assertThat(l.getEmailStatus())
                .isEqualTo(OnCallRoutineNotifier.SKIP_RETRY_PREFIX + "gün yeniden açıldı ya da yeniden tamamlandı"));
        rig.svc.completeDay(c, THU, rig.store.day(TEAM, "2026-11-26").version(), null, null, B);
        notifier(t11).sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(2);
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#2").status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("yeniden deneme: hakkı bitmiş 60 eski satır yenisinin önünü TIKAMAZ (hak SQL'de süzülür, sayfa sayfa okunur)")
    void exhaustedRowsDoNotStarveRetries() {
        String at = OnCallRoutineStore.ts(THU_0930);
        for (int i = 1; i <= 60; i++) {
            Long id = rig.store.claim(99L, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#" + i, OnCallRoutineCodes.N_FAILED,
                    "ERROR", null, at, "pod-x");
            jdbc.update("UPDATE oncall_routine_notices SET attempts = ? WHERE id = ?", OnCallRoutineCodes.MAX_ATTEMPTS, id);
        }
        Instant t11 = partialNotice();
        notifier(t11).sendPendingCompletions(t11, () -> false);
        assertThat(mails.get()).isEqualTo(2);
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("yeniden denenebilir satır sorgusu: FAILED + e-postası ulaşmamış PARTIAL; e-postası ulaşan / kapalı PARTIAL ve hakkı biten gelmez")
    void retryableQueryRule() {
        String at = OnCallRoutineStore.ts(THU_0930);
        java.util.function.BiFunction<String, String, Long> row = (status, mail) -> {
            Long id = rig.store.claim(99L, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#" + status + "|" + mail, status, null, null,
                    at, "pod-x");
            jdbc.update("UPDATE oncall_routine_notices SET mail_status = ? WHERE id = ?", mail, id);
            return id;
        };
        Long failed = row.apply("FAILED", null);
        Long mailFailed = row.apply("PARTIAL", "FAILED: bağlantı reddedildi");
        row.apply("PARTIAL", "SENT");
        row.apply("PARTIAL", "QUEUED_RETRY (1/3)");
        row.apply("PARTIAL", OnCallRoutineNotifier.MAIL_OFF);
        row.apply("PARTIAL", null);
        row.apply("SENT", "SENT");
        Long exhausted = row.apply("FAILED", "FAILED: x");
        jdbc.update("UPDATE oncall_routine_notices SET attempts = ? WHERE id = ?", OnCallRoutineCodes.MAX_ATTEMPTS, exhausted);
        String since = OnCallRoutineStore.ts(THU_0930.minus(Duration.ofHours(1)));
        assertThat(rig.store.retryable(since, 0L, OnCallRoutineCodes.MAX_ATTEMPTS, 50)).extracting(NoticeRow::id)
                .containsExactly(failed, mailFailed);
        assertThat(rig.store.retryable(since, failed, OnCallRoutineCodes.MAX_ATTEMPTS, 50)).extracting(NoticeRow::id)
                .containsExactly(mailFailed);
        // vazgeçme koşullu: okunan durum / deneme değiştiyse 0
        assertThat(rig.store.giveUpRetry(mailFailed, "FAILED", 1, OnCallRoutineCodes.MAX_ATTEMPTS)).isZero();
        assertThat(rig.store.giveUpRetry(mailFailed, "PARTIAL", 1, OnCallRoutineCodes.MAX_ATTEMPTS)).isEqualTo(1);
        assertThat(rig.store.giveUpRetry(mailFailed, "PARTIAL", 1, OnCallRoutineCodes.MAX_ATTEMPTS)).isZero();
    }

    @Test
    @DisplayName("yeniden deneme sayfaları: 120 yeniden denenebilir satırın hepsi tek turda değerlendirilir (sınırlı sayfa)")
    void retryPagesCoverMoreThanOnePage() {
        String at = OnCallRoutineStore.ts(THU_0930);
        // takımı olmayan 120 FAILED satır → her biri vazgeçilir (takım yok) — tek iz, ikinci turda yok
        for (int i = 1; i <= 120; i++) {
            rig.store.claim(99L, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#" + i, OnCallRoutineCodes.N_FAILED, "ERROR", null,
                    at, "pod-x");
        }
        Instant t = THU_0930.plus(Duration.ofMinutes(15));
        notifier(t).retryFailed(t, () -> false);
        assertThat(retryTraces()).hasSize(120);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_notices WHERE attempts < ?", Integer.class,
                OnCallRoutineCodes.MAX_ATTEMPTS)).isZero();
        notifier(t).retryFailed(t, () -> false);
        assertThat(retryTraces()).hasSize(120);
    }

    @Test
    @DisplayName("yeniden açılan GEÇMİŞ gün düzenleme penceresinde kapatılmaz; penceresinden çıktığı gün kapanır; devir de kapatmaz")
    void reopenedPastDayStaysOpenInsideEditWindow() {
        LocalDate mon = LocalDate.parse("2026-11-23");
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        TeamCtx c = rig.ctx();
        rig.svc.applyState(c, mon, "d1", "DAY", "2026-11-23", "DONE", null, null, A, true);
        rig.svc.applyState(c, mon, "d2", "DAY", "2026-11-23", "DONE", null, null, A, true);
        rig.svc.completeDay(c, mon, rig.store.day(TEAM, "2026-11-23").version(), null, null, A);
        rig.at(THU_0930);
        c = rig.ctx();
        rig.svc.reopenDay(c, mon, rig.store.day(TEAM, "2026-11-23").version(), A);
        OnCallRoutineJob job = new OnCallRoutineJob(rig.store, rig.svc, notifier(THU_0930));
        job.closeDays(c, THU_0930, new int[] {50});
        assertThat(rig.store.day(TEAM, "2026-11-23").status()).isEqualTo("OPEN");
        assertThat(rig.store.day(TEAM, "2026-11-24").status()).isEqualTo("MISSED");   // sıradan gün kapanır

        // 1 Aralık 00:31 İstanbul: 23 Kasım artık düzenlenemez → kapanır. Zorunluların hepsi (d1, d2) yapılmış: §1.6'da
        // tanımsız "hepsi çözülmüş ama tamamlanmamış" durum kendiliğinden tamamlanır (inceleme #2b) — yeniden açılmış günün
        // pencere sonu kapanışı GEÇ sayılır; tamamlama bildirimi gönderilmez.
        Instant dec1 = Instant.parse("2026-11-30T21:31:00Z");
        rig.at(dec1);
        TeamCtx later = rig.ctx();
        OnCallRoutineJob job2 = new OnCallRoutineJob(rig.store, rig.svc, notifier(dec1));
        job2.closeDays(later, dec1, new int[] {50});
        DayRow closed = rig.store.day(TEAM, "2026-11-23");
        assertThat(closed.status()).isEqualTo("COMPLETED");
        assertThat(closed.completedLate()).isTrue();
        assertThat(closed.completedByName()).isEqualTo("SiteMonitor");
        assertThat(closed.completionSeq()).isEqualTo(2);
        assertThat(closed.notifiedSeq()).isEqualTo(closed.completionSeq());
    }

    @Test
    @DisplayName("kapanış kuralı: yeniden açılmış gün yalnız düzenleme penceresinden çıkınca; sıradan açık gün ertesi 00:30'dan sonra")
    void closeDueRule() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        TeamCtx c = rig.ctx();
        LocalDate tue = LocalDate.parse("2026-11-24");
        DayRow reopened = new DayRow(1L, TEAM, "2026-11-24", null, null, "OPEN", null, null, null, null, null, null, null, null, null,
                null, null, false, 1, 1, null, 0, null, null, null, null, 3);
        DayRow fresh = new DayRow(2L, TEAM, "2026-11-24", null, null, "OPEN", null, null, null, null, null, null, null, null, null,
                null, null, false, 0, 0, null, 0, null, null, null, null, 0);
        Instant wed0031 = Instant.parse("2026-11-24T21:31:00Z");
        Instant wed0029 = Instant.parse("2026-11-24T21:29:00Z");
        assertThat(OnCallRoutineJob.closeDue(c, tue, "2026-11-23", wed0029, fresh)).isFalse();
        assertThat(OnCallRoutineJob.closeDue(c, tue, "2026-11-23", wed0031, fresh)).isTrue();
        assertThat(OnCallRoutineJob.closeDue(c, tue, "2026-11-23", wed0031, null)).isTrue();
        assertThat(OnCallRoutineJob.closeDue(c, tue, "2026-11-23", wed0031, reopened)).isFalse();
        // 24 Kasım + 7 = 1 Aralık hâlâ düzenlenebilir; 2 Aralık değil
        assertThat(OnCallRoutineJob.closeDue(c, tue, "2026-11-23", Instant.parse("2026-12-01T09:00:00Z"), reopened)).isFalse();
        assertThat(OnCallRoutineJob.closeDue(c, tue, "2026-11-23", Instant.parse("2026-12-01T21:31:00Z"), reopened)).isTrue();
    }

    @Test
    @DisplayName("#12 e-posta GİTTİ ama satır sonucu yazılamadı: ikinci, çelişen FAILED izi yok, satır FAILED olmaz (yeniden gönderilmez); 15 dk sonra UNKNOWN, ek iz yok")
    void sentButFinishFailedWritesNoSecondTrace() {
        completedThursday();
        OnCallRoutineStore spyStore = org.mockito.Mockito.spy(rig.store);
        // yalnız SONUÇ yazımı düşer; sonraki yazımlar çalışır — gönderildiği hâlde hata yoluna düşen kod FAILED yazabilir ve
        // bu test onu yakalar (her yazım düşseydi FAILED de yazılamaz, "ulaştı" kararı sınanmazdı)
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db"))
                .doCallRealMethod()
                .when(spyStore).finishNotice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Instant t = THU_0930.plusSeconds(60);
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(spyStore, rig.svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = java.time.Clock.fixed(t, ZoneOffset.UTC);
        n.sendPendingCompletions(t, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(ourLogs(DONE_TRIGGER)).as("tek iz: gerçek gönderim").hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).startsWith("SENT").doesNotContain("hazırlanamadı"));
        NoticeRow row = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(row.status()).as("FAILED değil — yeniden deneme ikinci e-posta gönderirdi").isEqualTo("SENDING");

        // yeniden deneme turu bu satırı göndermez; 15 dk sonra yarıda kalan kuralı UNKNOWN yapar, iz zaten var
        Instant t30 = t.plus(Duration.ofMinutes(30));
        notifier(t30).retryFailed(t30, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(notifier(t30).sweepStale(t30)).isEqualTo(1);
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status()).isEqualTo("UNKNOWN");
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1);
    }

    @Test
    @DisplayName("#12 yalnız e-posta GİTTİ (push düştü), satır sonucu yazılamadı: satır FAILED olmaz — e-posta tek başına 'ulaştı' sayılır")
    void mailOnlySentButFinishFailedIsNotFailed() {
        completedThursday();
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("kuyruk yok"));
        OnCallRoutineStore spyStore = org.mockito.Mockito.spy(rig.store);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db"))
                .doCallRealMethod()
                .when(spyStore).finishNotice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Instant t = THU_0930.plusSeconds(60);
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(spyStore, rig.svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = java.time.Clock.fixed(t, ZoneOffset.UTC);
        n.sendPendingCompletions(t, () -> false);
        assertThat(mails.get()).isEqualTo(1);
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).startsWith("SENT").contains("push: FAILED"));
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status())
                .as("FAILED değil — yeniden deneme e-postayı ikinci kez gönderirdi").isEqualTo("SENDING");
    }

    @Test
    @DisplayName("#12 yalnız push GİTTİ (e-posta düştü), satır sonucu yazılamadı: satır FAILED olmaz — push tek başına 'ulaştı' sayılır")
    void pushOnlySentButFinishFailedIsNotFailed() {
        completedThursday();
        mailResult = "FAILED: bağlantı reddedildi";
        OnCallRoutineStore spyStore = org.mockito.Mockito.spy(rig.store);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db"))
                .doCallRealMethod()
                .when(spyStore).finishNotice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Instant t = THU_0930.plusSeconds(60);
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(spyStore, rig.svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = java.time.Clock.fixed(t, ZoneOffset.UTC);
        n.sendPendingCompletions(t, () -> false);
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).startsWith("FAILED: bağlantı").contains("push "));
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status())
                .as("FAILED değil — yeniden deneme push'u ikinci kez gönderirdi").isEqualTo("SENDING");
    }

    @Test
    @DisplayName("#12 hiçbir kanal ulaşmadı, denemenin izi yazıldı, satır yazımı düştü: ikinci iz yok, istisna kaçmaz; 15 dk sonra UNKNOWN, ek iz yok")
    void failureAfterTraceWritesNoSecondTrace() {
        completedThursday();
        mailResult = "FAILED: bağlantı reddedildi";
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("kuyruk yok"));
        OnCallRoutineStore spyStore = org.mockito.Mockito.spy(rig.store);
        // ilk finishNotice (sonuç) düşer → hata yolu; hata yolunun FAILED yazımı da düşer
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db"))
                .when(spyStore).finishNotice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Instant t = THU_0930.plusSeconds(60);
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(spyStore, rig.svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = java.time.Clock.fixed(t, ZoneOffset.UTC);
        n.sendPendingCompletions(t, () -> false);   // istisna kaçmaz
        List<NotificationLog> trace = ourLogs(DONE_TRIGGER);
        assertThat(trace).as("tek iz: denemenin kendi izi (her karar TEK satır)").hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).startsWith("FAILED: bağlantı").doesNotContain("hazırlanamadı"));
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status()).isEqualTo("SENDING");

        Instant t30 = t.plus(Duration.ofMinutes(30));
        assertThat(notifier(t30).sweepStale(t30)).isEqualTo(1);
        assertThat(rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1").status()).isEqualTo("UNKNOWN");
        assertThat(ourLogs(DONE_TRIGGER)).as("iz zaten var — FAILED_UNKNOWN eklenmez").hasSize(1);
    }

    @Test
    @DisplayName("#12 gönderimden ÖNCE hata (içerik okunamadı): TEK 'hazırlanamadı' izi + satır FAILED (yeniden denenebilir)")
    void failureBeforeAnySendIsFailedOnce() {
        completedThursday();
        OnCallRoutineStore spyStore = org.mockito.Mockito.spy(rig.store);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db"))
                .when(spyStore).items(any(), any());
        Instant t = THU_0930.plusSeconds(60);
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(spyStore, rig.svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = java.time.Clock.fixed(t, ZoneOffset.UTC);
        n.sendPendingCompletions(t, () -> false);
        assertThat(mails.get()).isZero();
        assertThat(ourLogs(DONE_TRIGGER)).hasSize(1)
                .allSatisfy(l -> assertThat(l.getEmailStatus()).contains("hazırlanamadı").contains("DataAccessResourceFailureException"));
        NoticeRow row = rig.store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26#1");
        assertThat(row.status()).isEqualTo("FAILED");
        org.mockito.Mockito.verify(push, org.mockito.Mockito.never())
                .enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("dönem kapanışı tur bütçesiyle sınırlı: bütçe bitince kalan dönem işaretlenmez, sonraki turda kapanır")
    void periodCloseBudget() {
        Instant early = Instant.parse("2026-10-05T06:00:00Z");
        rig.at(early);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        Instant now = Instant.parse("2026-11-26T06:30:00Z");
        rig.at(now);
        TeamCtx c = rig.ctx();
        OnCallRoutineJob job = new OnCallRoutineJob(rig.store, rig.svc, notifier(now));
        int[] budget = {1};
        job.closePeriods(c, now, budget);
        assertThat(budget[0]).isZero();
        int[] more = {50};
        job.closePeriods(c, now, more);
        assertThat(50 - more[0]).as("kalan dönemler sonraki turda").isEqualTo(5);   // 5 hafta + 1 ay − ilk tur
        int[] none = {50};
        job.closePeriods(c, now, none);
        assertThat(none[0]).isEqualTo(50);   // bu pod'da kapananlar yeniden koşmaz
    }
}
