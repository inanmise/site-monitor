package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.QuietDigestItem;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.QuietDigestItemRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sessiz saat özeti (2026-10-01, onaylı öneri 15): pencere bitince takım başına TEK e-posta; açık / pencerede çözülmüş
 * ayrımı; tam bir kez (sahiplenme belirteci — ikinci tur/pod göndermez); kilit; hâlâ açık alarmların yeniden uyarı damgası;
 * geçersizleşmiş ve e-postası kapalı kayıtlar özete girmez; farklı bildirim grubu damgası ayrı alıcı kümesi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuietDigestServiceTest {

    @Mock QuietDigestItemRepository itemRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock EmailNotificationService emailService;
    @Mock EscalationService escalationService;
    @Mock SchedulerService schedulerService;

    static final long TEAM = 42L;
    /** 2026-10-02 07:01 Istanbul — pencere (22:00–07:00) bitti. */
    static final Instant AFTER = QuietHoursTest.ist("2026-10-02T07:01:00");

    QuietDigestService service;
    final Map<Long, QuietDigestItem> db = new LinkedHashMap<>();
    final Map<Long, AlertEvent> events = new HashMap<>();
    final List<NotificationLog> logs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new QuietDigestService(itemRepo, alertEventRepo, teamRepo, notificationLogRepo, emailService,
                escalationService, schedulerService);
        service.clock = Clock.fixed(AFTER, ZoneOffset.UTC);
        Team t = new Team();
        t.setId(TEAM);
        t.setName("Ödeme");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t));
        when(alertEventRepo.findAllById(any())).thenAnswer(i -> {
            List<AlertEvent> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) if (events.containsKey(id)) out.add(events.get(id));
            return out;
        });
        when(escalationService.teamEmailsForMonitor(TEAM, null)).thenReturn(List.of("team@x.com"));
        when(emailService.buildQuietDigestHtml(anyString(), anyString(), anyList())).thenReturn("<html>özet</html>");
        when(emailService.sendHtml(any(String[].class), any(), anyString(), anyString(), any())).thenReturn("SENT");
        // Depo taklidi: findDue → bekleyen + vakti gelmiş; claim → yalnız hâlâ bekleyenlere belirteç; findByDigestStatus.
        when(itemRepo.findDue(anyString())).thenAnswer(i -> db.values().stream()
                .filter(x -> x.getDigestSentAt() == null && x.getWindowEnd().compareTo(i.getArgument(0)) <= 0).toList());
        when(itemRepo.claim(anyCollection(), anyString(), anyString())).thenAnswer(i -> {
            int n = 0;
            for (Object id : (Collection<?>) i.getArgument(0)) {
                QuietDigestItem x = db.get(id);
                if (x != null && x.getDigestSentAt() == null) {
                    x.setDigestSentAt(i.getArgument(1));
                    x.setDigestStatus(i.getArgument(2));
                    n++;
                }
            }
            return n;
        });
        when(itemRepo.findByDigestStatus(anyString())).thenAnswer(i -> db.values().stream()
                .filter(x -> i.getArgument(0).equals(x.getDigestStatus())).toList());
        when(itemRepo.markStatus(anyCollection(), anyString())).thenAnswer(i -> {
            for (Object id : (Collection<?>) i.getArgument(0)) db.get(id).setDigestStatus(i.getArgument(1));
            return 1;
        });
        when(notificationLogRepo.saveAll(anyList())).thenAnswer(i -> { logs.addAll(i.getArgument(0)); return i.getArgument(0); });
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logs.add(i.getArgument(0)); return i.getArgument(0); });
    }

    private AlertEvent event(long id, String level, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("svc" + id + ".example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel(level);
        e.setTeamId(TEAM);
        e.setCreatedAt("2026-10-01T20:0" + id + ":00");
        e.setResolved(resolved);
        if (resolved) e.setResolvedAt("2026-10-01T21:00:00");
        events.put(id, e);
        return e;
    }

    private QuietDigestItem item(long id, long eventId) {
        QuietDigestItem i = new QuietDigestItem();
        i.setId(id);
        i.setAlertEventId(eventId);
        i.setTeamId(TEAM);
        i.setWindowKey("2026-10-01T22:00");
        i.setWindowEnd("2026-10-02T04:00:00");   // 07:00 IST
        i.setDeferredAt("2026-10-01T20:00:00");
        i.setOpeningDeferred(true);
        db.put(id, i);
        return i;
    }

    @SuppressWarnings("unchecked")
    private List<EmailNotificationService.QuietDigestRow> digestRows() {
        ArgumentCaptor<List<EmailNotificationService.QuietDigestRow>> c = ArgumentCaptor.forClass(List.class);
        verify(emailService).buildQuietDigestHtml(eq("Ödeme"), anyString(), c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("Pencere bitti: takıma TEK e-posta; açık/çözülmüş ayrımı; alarm başına QUIET_DIGEST satırı; açık alarm damgası")
    void digest_sentOnce_withOpenResolvedSplit() {
        event(1, "WARNING", false);
        event(2, "WARNING", true);
        item(10, 1);
        item(11, 2);

        QuietDigestService.RunResult r = service.run(AFTER);

        assertThat(r).isEqualTo(new QuietDigestService.RunResult(2, 1, 1));
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendHtml(to.capture(), isNull(), subject.capture(), anyString(), isNull());
        assertThat(to.getValue()).containsExactly("team@x.com");
        assertThat(subject.getValue()).contains("Sessiz saat özeti").contains("Ödeme").contains("2 alarm").contains("1 açık");
        List<EmailNotificationService.QuietDigestRow> rows = digestRows();
        assertThat(rows).extracting(EmailNotificationService.QuietDigestRow::alertId).containsExactly(1L, 2L);   // açık önce
        assertThat(rows).extracting(EmailNotificationService.QuietDigestRow::resolved).containsExactly(false, true);
        assertThat(db.values()).allSatisfy(i -> assertThat(i.getDigestStatus()).isEqualTo("SENT"));
        assertThat(logs).hasSize(2).allSatisfy(l -> {
            assertThat(l.getTrigger()).isEqualTo(EscalationService.TRIGGER_QUIET_DIGEST);
            assertThat(l.getEmailStatus()).isEqualTo("SENT");
            assertThat(l.getRecipientEmail()).isEqualTo("team@x.com");
        });
        // Yalnız HÂLÂ AÇIK alarm "bildirilmiş" sayılır — günlük hatırlatma kadansı özet anından başlar.
        verify(alertEventRepo).stampNotifiedByQuietDigest(eq(Set.of(1L)), eq("2026-10-02T04:01:00"));
    }

    @Test
    @DisplayName("Tam bir kez: ikinci tur (ya da ikinci pod) aynı kayıtları göndermez")
    void secondRun_doesNotResend() {
        event(1, "WARNING", false);
        item(10, 1);

        service.run(AFTER);
        service.run(AFTER.plusSeconds(60));

        verify(emailService, times(1)).sendHtml(any(String[].class), any(), anyString(), anyString(), any());
        assertThat(logs).hasSize(1);
    }

    @Test
    @DisplayName("Yarış: kayıtları başka pod sahiplendiyse (claim=0) bu tur hiçbir şey göndermez")
    void claimLost_sendsNothing() {
        event(1, "WARNING", false);
        item(10, 1);
        when(itemRepo.claim(anyCollection(), anyString(), anyString())).thenReturn(0);

        assertThat(service.run(AFTER)).isEqualTo(QuietDigestService.RunResult.EMPTY);
        verifyNoInteractions(emailService);
        verify(alertEventRepo, never()).stampNotifiedByQuietDigest(any(), any());
    }

    @Test
    @DisplayName("Pencere bitmeden özet yok (window_end gelecekte)")
    void notDueYet_nothingSent() {
        event(1, "WARNING", false);
        item(10, 1).setWindowEnd("2026-10-02T05:00:00");

        assertThat(service.run(AFTER)).isEqualTo(QuietDigestService.RunResult.EMPTY);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Kilit: vakti gelen kayıt varsa iş 'quiet-digest' scheduler_lock altında koşar; yoksa kilit bile alınmaz")
    void scheduledRun_usesLock_onlyWhenDue() {
        when(itemRepo.existsByDigestSentAtIsNullAndWindowEndLessThanEqual(anyString())).thenReturn(false);
        service.scheduledRun();
        verifyNoInteractions(schedulerService);

        when(itemRepo.existsByDigestSentAtIsNullAndWindowEndLessThanEqual(anyString())).thenReturn(true);
        service.scheduledRun();
        verify(schedulerService).runWithSchedulerLock(eq(QuietDigestService.LOCK_NAME), any());
    }

    @Test
    @DisplayName("Pencerede ertelenmeyen bildirim almış (superseded) ve izlemede e-postası kapalı alarm özete girmez")
    void supersededAndMailOff_excluded() {
        event(1, "WARNING", false);
        AlertEvent off = event(2, "WARNING", false);
        off.setContextJson("{\"mail_disabled\":true}");
        event(3, "WARNING", false);
        item(10, 1).setSupersededAt("2026-10-01T23:00:00");
        item(11, 2);
        item(12, 3);

        service.run(AFTER);

        assertThat(digestRows()).extracting(EmailNotificationService.QuietDigestRow::alertId).containsExactly(3L);
        assertThat(db.get(10L).getDigestStatus()).isEqualTo(QuietDigestService.STATUS_SUPERSEDED);
        assertThat(db.get(11L).getDigestStatus()).isEqualTo(QuietDigestService.STATUS_MAIL_OFF);
        assertThat(db.get(12L).getDigestStatus()).isEqualTo("SENT");
        assertThat(logs).extracting(NotificationLog::getAlertEventId).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("Alıcısı olmayan takım: e-posta yok, kayıt ve günlük 'SKIPPED: alıcı yok' (sessiz değil)")
    void noRecipients_skippedWithTrace() {
        event(1, "WARNING", false);
        item(10, 1);
        when(escalationService.teamEmailsForMonitor(TEAM, null)).thenReturn(List.of());

        service.run(AFTER);

        verify(emailService, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any());
        assertThat(db.get(10L).getDigestStatus()).isEqualTo(QuietDigestService.STATUS_NO_RECIPIENT);
        assertThat(logs).singleElement().extracting(NotificationLog::getEmailStatus).isEqualTo(QuietDigestService.STATUS_NO_RECIPIENT);
        verify(alertEventRepo, never()).stampNotifiedByQuietDigest(any(), any());
    }

    @Test
    @DisplayName("Farklı bildirim grubuna damgalı alarmlar ayrı alıcı kümesi → küme başına bir e-posta (grup sınırı aşılmaz)")
    void differentGroupStamps_separateMails() {
        event(1, "WARNING", false);
        event(2, "WARNING", false).setNotificationGroupId(9L);
        item(10, 1);
        item(11, 2);
        when(escalationService.teamEmailsForMonitor(TEAM, 9L)).thenReturn(List.of("vendor@x.com"));

        service.run(AFTER);

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(2)).sendHtml(to.capture(), any(), anyString(), anyString(), any());
        assertThat(to.getAllValues()).extracting(a -> String.join(",", a)).containsExactlyInAnyOrder("team@x.com", "vendor@x.com");
    }

    @Test
    @DisplayName("Pencere etiketi: '01.10.2026 22:00–07:00' (Istanbul)")
    void windowLabel() {
        assertThat(QuietDigestService.windowLabel(List.of(item(10, 1)))).isEqualTo("01.10.2026 22:00–07:00");
    }
}
