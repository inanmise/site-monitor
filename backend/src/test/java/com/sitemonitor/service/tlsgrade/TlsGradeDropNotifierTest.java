package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.TlsGradeDropMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TLS notu düşüşü → takım bildirimi (2026-10-11): talep (bir kez), kapalı / eşik / bakım / takımsız / alıcısız kararları,
 * SY + UG takımlarına ayrı özet, push, notification_logs izi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TlsGradeDropNotifierTest {

    @Mock TlsGradeChangeRepository repo;
    @Mock AppSettingsService settings;
    @Mock EscalationService escalation;
    @Mock EmailNotificationService email;
    @Mock NotificationLogRepository logRepo;
    @Mock TeamRepository teamRepo;
    @Mock UserPushService push;
    @Mock SystemMaintenanceService maintenance;
    TlsGradeDropNotifier n;

    final Map<Long, TlsGradeChange> store = new HashMap<>();
    final Map<String, List<Long>> finished = new HashMap<>();

    @BeforeEach
    void setUp() {
        n = new TlsGradeDropNotifier(repo, settings, escalation, email, logRepo, teamRepo);
        n.setPush(push);
        n.setSystemMaintenance(maintenance);
        when(settings.getBoolean(eq(TlsGradeDropNotifier.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(settings.getString(eq(TlsGradeDropNotifier.KEY_MIN_GRADE), anyString())).thenReturn("A");
        when(settings.getString(eq("site.monitor.app.base-url"), anyString())).thenReturn("https://sm.example.com");
        when(teamRepo.findById(any())).thenAnswer(inv -> {
            Team t = new Team(); t.setId(inv.getArgument(0)); t.setName("Takım " + inv.getArgument(0)); return Optional.of(t);
        });
        when(escalation.teamEmailsForMonitor(any(), isNull())).thenAnswer(inv -> List.of("takim" + inv.getArgument(0) + "@example.com"));
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("SENT");
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 2));
        when(repo.findByNotifyStateOrderByIdAsc(eq(TlsGradeChange.NOTIFY_PENDING), any()))
                .thenAnswer(inv -> store.values().stream().filter(c -> TlsGradeChange.NOTIFY_PENDING.equals(c.getNotifyState())).toList());
        when(repo.transitionNotifyState(anyCollection(), eq(TlsGradeChange.NOTIFY_PENDING), eq(TlsGradeChange.NOTIFY_SENDING)))
                .thenAnswer(inv -> {
                    int k = 0;
                    for (Long id : (Collection<Long>) inv.getArgument(0)) {
                        TlsGradeChange c = store.get(id);
                        if (c != null && TlsGradeChange.NOTIFY_PENDING.equals(c.getNotifyState())) { c.setNotifyState(TlsGradeChange.NOTIFY_SENDING); k++; }
                    }
                    return k;
                });
        when(repo.findAllById(anyCollection())).thenAnswer(inv -> {
            List<TlsGradeChange> out = new ArrayList<>();
            for (Long id : (Collection<Long>) inv.getArgument(0)) if (store.containsKey(id)) out.add(store.get(id));
            return out;
        });
        when(repo.finishNotify(anyCollection(), anyString(), anyString())).thenAnswer(inv -> {
            List<Long> ids = new ArrayList<>((Collection<Long>) inv.getArgument(0));
            for (Long id : ids) store.get(id).setNotifyState(inv.getArgument(1));
            finished.computeIfAbsent(inv.getArgument(1), k -> new ArrayList<>()).addAll(ids);
            return ids.size();
        });
    }

    private TlsGradeChange drop(long id, String domain, Long sy, Long ug, String from, String to) {
        TlsGradeChange c = new TlsGradeChange();
        c.setId(id); c.setDomain(domain); c.setTeamId(sy); c.setUgTeamId(ug); c.setFromGrade(from); c.setToGrade(to);
        c.setDirection(TlsGradeChange.DROP); c.setReasons("TLS10_ENABLED,HSTS_MISSING"); c.setChangedAt("2026-10-11T08:00:00");
        c.setNotifyState(TlsGradeChange.NOTIFY_PENDING);
        store.put(id, c);
        return c;
    }

    @Test
    @DisplayName("bekleyen yok → hiçbir şey; talep yarışı kaybedilirse (0 satır) gönderim yok")
    void nothingPending_orClaimLost() {
        assertThat(n.notifyPending().claimed()).isZero();
        drop(1, "a.example.com", 1L, null, "A", "B");
        when(repo.transitionNotifyState(anyCollection(), any(), any())).thenReturn(0);
        assertThat(n.notifyPending().claimed()).isZero();
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("SY + UG takımlarına AYRI özet; her takım yalnız kendi satırları; push; iki log; satırlar SENT")
    void sendsPerOwningTeam() {
        drop(1, "a.example.com", 1L, 2L, "A", "B");
        drop(2, "b.example.com", 1L, null, "A+", "F");
        var r = n.notifyPending();
        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.teams()).isEqualTo(2);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(any(), isNull(), subject.capture(), anyString(), isNull());
        assertThat(subject.getAllValues()).contains("[Site Monitor] TLS notu düştü: a.example.com A → B");
        assertThat(subject.getAllValues()).anyMatch(s -> s.contains("2 sertifika") && s.contains("Takım 1"));
        verify(logRepo, times(2)).save(any(NotificationLog.class));
        verify(push).enqueueTeamNoticeLocalized(eq(1L), eq(TlsGradeDropNotifier.TRIGGER), eq("CRITICAL"), eq("CERT"), anyString(), any(), anyString(), any());
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
    }

    @Test
    @DisplayName("kapalı → SKIPPED: bildirim kapalı (sonra açılınca geriye dönük gitmez); bakım → SKIPPED: sistem bakımı")
    void disabledAndMaintenance() {
        drop(1, "a.example.com", 1L, null, "A", "B");
        when(settings.getBoolean(eq(TlsGradeDropNotifier.KEY_ENABLED), anyBoolean())).thenReturn(false);
        n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.SKIP_OFF);

        drop(2, "b.example.com", 1L, null, "A", "B");
        when(settings.getBoolean(eq(TlsGradeDropNotifier.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(maintenance.notificationsMuted()).thenReturn(true);
        n.notifyPending();
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.SKIP_MAINT);
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("eşik C: B'ye düşüş atlanır (eşik üstü), D'ye düşüş gider; takımsız satır SKIPPED: takım yok")
    void thresholdAndTeamless() {
        when(settings.getString(eq(TlsGradeDropNotifier.KEY_MIN_GRADE), anyString())).thenReturn("c");
        drop(1, "b.example.com", 1L, null, "A", "B");
        drop(2, "d.example.com", 1L, null, "A", "D");
        drop(3, "x.example.com", null, null, "A", "F");
        n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.SKIP_BELOW);
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
        assertThat(store.get(3L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.SKIP_NO_TEAM);
        assertThat(TlsGradeDropNotifier.normalizeMinGrade("bogus")).isEqualTo("A");
    }

    @Test
    @DisplayName("2026-10-12: e-posta gönderilemedi + push yok → FAILED (\"alıcı yok\" değil); kuyrukta (QUEUED) gönderilmiş sayılır; uzun iz kırpılır")
    void failedMailIsFailedNotNoRecipient() {
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 0));
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("FAILED: " + "x".repeat(400));
        drop(1, "a.example.com", 1L, null, "A", "B");
        n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_FAILED);
        assertThat(TlsGradeDropNotifier.uiCode(TlsGradeDropNotifier.NOTIFY_FAILED)).isEqualTo("failed");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).hasSizeLessThanOrEqualTo(255);

        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("QUEUED_RETRY: 421");
        drop(2, "b.example.com", 1L, null, "A", "B");
        n.notifyPending();
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);

        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("SKIPPED_DISABLED");
        drop(3, "c.example.com", 1L, null, "A", "B");
        n.notifyPending();
        assertThat(store.get(3L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.SKIP_NO_RECIPIENT);
    }

    @Test
    @DisplayName("takımın e-postası yok ve push kuyruğa alınamadı → SKIPPED: alıcı yok (iz yine yazılır)")
    void noRecipient() {
        when(escalation.teamEmailsForMonitor(any(), isNull())).thenReturn(List.of());
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 0));
        drop(1, "a.example.com", 1L, null, "A", "B");
        n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.SKIP_NO_RECIPIENT);
        verify(logRepo).save(any(NotificationLog.class));
    }

    @Test
    @DisplayName("e-posta: tek düşüşte konu alan adı + notlar; nedenler Türkçe adla; sertifika penceresine derin bağlantı")
    void mail() {
        var rows = List.of(new TlsGradeDropMail.Row("a.example.com", "A", "B", "TLS10_ENABLED,HSTS_MISSING", "2026-10-11T08:00:00"));
        assertThat(TlsGradeDropMail.subject("Takım A", rows)).isEqualTo("[Site Monitor] TLS notu düştü: a.example.com A → B");
        var mail = TlsGradeDropMail.build("Takım A", rows, "https://sm.example.com/");
        assertThat(mail.html()).contains("a.example.com").contains("A → B").contains("TLS 1.0 açık")
                .contains("https://sm.example.com/?tab=dashboard&amp;domain=a.example.com&amp;open=cert");
        assertThat(mail.text()).contains("a.example.com");
        assertThat(TlsGradeDropMail.worstGrade(List.of(rows.get(0),
                new TlsGradeDropMail.Row("b", "A", "F", "", "")))).isEqualTo("F");
    }
}
