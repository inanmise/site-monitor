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
    /** notification_logs izleri (R2a MP-43: yarıda kalan talebin iz denetimi bunları okur). */
    final List<NotificationLog> savedLogs = new ArrayList<>();

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
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(inv -> { savedLogs.add(inv.getArgument(0)); return inv.getArgument(0); });
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
        // SENDING → PENDING (okuma hatasında yeniden deneme, inceleme 5)
        when(repo.transitionNotifyState(anyCollection(), eq(TlsGradeChange.NOTIFY_SENDING), eq(TlsGradeChange.NOTIFY_PENDING)))
                .thenAnswer(inv -> {
                    int k = 0;
                    for (Long id : (Collection<Long>) inv.getArgument(0)) {
                        TlsGradeChange c = store.get(id);
                        if (c != null && TlsGradeChange.NOTIFY_SENDING.equals(c.getNotifyState())) { c.setNotifyState(TlsGradeChange.NOTIFY_PENDING); k++; }
                    }
                    return k;
                });
        // Talep + damga (R2a, MP-43) ve DB sayaçlı yeniden deneme
        when(repo.claimNotify(anyCollection(), anyString())).thenAnswer(inv -> {
            int k = 0;
            for (Long id : (Collection<Long>) inv.getArgument(0)) {
                TlsGradeChange c = store.get(id);
                if (c != null && TlsGradeChange.NOTIFY_PENDING.equals(c.getNotifyState())) {
                    c.setNotifyState(TlsGradeChange.NOTIFY_SENDING); c.setNotifiedAt(inv.getArgument(1)); k++;
                }
            }
            return k;
        });
        when(repo.retryNotify(anyCollection())).thenAnswer(inv -> {
            int k = 0;
            for (Long id : (Collection<Long>) inv.getArgument(0)) {
                TlsGradeChange c = store.get(id);
                if (c != null && TlsGradeChange.NOTIFY_SENDING.equals(c.getNotifyState())) {
                    c.setNotifyState(TlsGradeChange.NOTIFY_PENDING);
                    c.setNotifyAttempts((c.getNotifyAttempts() == null ? 0 : c.getNotifyAttempts()) + 1);
                    k++;
                }
            }
            return k;
        });
        when(repo.findAllById(anyCollection())).thenAnswer(inv -> {
            List<TlsGradeChange> out = new ArrayList<>();
            for (Long id : (Collection<Long>) inv.getArgument(0)) if (store.containsKey(id)) out.add(store.get(id));
            return out;
        });
        when(repo.finishNotifyIfState(anyCollection(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            int k = 0;
            for (Long id : (Collection<Long>) inv.getArgument(0)) {
                TlsGradeChange c = store.get(id);
                if (c != null && inv.getArgument(1).equals(c.getNotifyState())) { c.setNotifyState(inv.getArgument(2)); k++; }
            }
            return k;
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
        when(repo.claimNotify(anyCollection(), anyString())).thenReturn(0);
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
    @DisplayName("inceleme 4-5: alıcı listesi OKUNAMADI → 'alıcı yok' DEĞİL; düşüş PENDING'e döner ve yeniden denenir, tavanda FAILED; push ulaştıysa SENT")
    void recipientLookupFailureIsNotNoRecipient() {
        when(escalation.teamEmailsForMonitor(any(), isNull())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 0));
        drop(1, "a.example.com", 1L, null, "A", "B");
        n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_PENDING);   // geçici hata: kaybolmaz
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith(TlsGradeDropNotifier.RECIPIENTS_UNREADABLE);
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), any());
        // sınırlı yeniden deneme: tavana gelince kalıcı FAILED (sonsuz döngü yok)
        for (int i = 1; i < TlsGradeDropNotifier.READ_RETRY_MAX; i++) n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_FAILED);
        // push takım üyelerine ulaştı → düşüş bildirildi
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 2));
        drop(2, "b.example.com", 1L, null, "A", "B");
        n.notifyPending();
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
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

    @Test
    @DisplayName("2026-10-12 / R2a MP-43: tur ortasında hata → talep edilen satır SENDING'de KALMAZ; hiçbir takımı DENENMEMİŞ satır PENDING'e döner (kimseye gitmedi), DB sayacı tavanda 'yarıda kaldı'; tur fırlatmaz")
    void failureMidRunMarksInterrupted() {
        // Yeniden temel (2026-10-22, MP-43): eskiden denenmemiş satır da 'yarıda kaldı' oluyor, düşüş bildirimi kayboluyordu
        drop(1, "a.example.com", 7L, null, "A", "C");
        // takım döngüsünün DIŞINDA (talepten sonra) beklenmeyen hata — takım hatası artık takım başına yakalanır
        when(settings.getString(eq(TlsGradeDropNotifier.KEY_MIN_GRADE), anyString())).thenThrow(new IllegalStateException("ayar okunamadı"));
        TlsGradeDropNotifier.Result r = n.notifyPending();
        assertThat(r.claimed()).isZero();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_PENDING);
        assertThat(store.get(1L).getNotifyAttempts()).isEqualTo(1);
        for (int i = 1; i < TlsGradeDropNotifier.READ_RETRY_MAX; i++) n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_INTERRUPTED);   // sınırlı
        assertThat(TlsGradeDropNotifier.uiCode(TlsGradeDropNotifier.NOTIFY_INTERRUPTED)).isEqualTo("interrupted");
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("2026-10-12: önceki turdan 1 saatten eski SENDING satırı 'yarıda kaldı' işaretlenir; taze SENDING'e dokunulmaz")
    void staleSendingIsSwept() {
        TlsGradeChange old = drop(1, "a.example.com", 7L, null, "A", "C");
        old.setNotifyState(TlsGradeChange.NOTIFY_SENDING);
        TlsGradeChange fresh = drop(2, "b.example.com", 7L, null, "A", "C");
        fresh.setNotifyState(TlsGradeChange.NOTIFY_SENDING);
        java.time.Instant now = java.time.Instant.parse("2026-10-12T09:00:00Z");
        fresh.setChangedAt("2026-10-12T08:30:00");
        when(repo.findByNotifyStateOrderByIdAsc(eq(TlsGradeChange.NOTIFY_SENDING), any()))
                .thenAnswer(inv -> store.values().stream().filter(c -> TlsGradeChange.NOTIFY_SENDING.equals(c.getNotifyState())).toList());
        n.sweepStaleSending(now);
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_INTERRUPTED);
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENDING);
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("2026-10-12 / inceleme 5: bir takımın OKUMA hatası ötekileri durdurmaz — o takımın satırları yeniden denenir "
            + "(PENDING), tavan dolunca 'gönderilemedi'; diğer takım gönderilir")
    void oneTeamFailureDoesNotStopOthers() {
        drop(1, "a.example.com", 7L, null, "A", "C");
        drop(2, "b.example.com", 8L, null, "A", "C");
        when(teamRepo.findById(7L)).thenThrow(new IllegalStateException("bağlantı koptu"));
        TlsGradeDropNotifier.Result r = n.notifyPending();
        assertThat(r.claimed()).isEqualTo(2);
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_PENDING);   // geçici: yeniden denenecek
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
        for (int i = 1; i < TlsGradeDropNotifier.READ_RETRY_MAX; i++) n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_FAILED); // sınırlı: sonsuz deneme yok
        verify(email, times(1)).sendHtml(any(), any(), anyString(), anyString(), any());          // takım 8 bir kez
    }

    @Test
    @DisplayName("inceleme 6: yeniden deneme geçişi (SENDING→PENDING) hata verirse teslim edilmiş satır 'yarıda kaldı' OLMAZ — sonucu önce yazılır")
    void retryTransitionFailureKeepsDeliveredRows() {
        drop(1, "a.example.com", 7L, null, "A", "C");
        drop(2, "b.example.com", 8L, null, "A", "C");
        when(teamRepo.findById(7L)).thenThrow(new IllegalStateException("bağlantı koptu"));
        when(repo.retryNotify(anyCollection())).thenThrow(new IllegalStateException("db"));
        n.notifyPending();                                              // tur hatası yakalanıp günlüklenir
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_INTERRUPTED);
    }

    @Test
    @DisplayName("inceleme 5: gönderim sırasında beklenmeyen hata → o takım 'gönderilemedi' + notification_logs izi; öteki takım sürer")
    void unexpectedSendFailureLeavesTrace() {
        drop(1, "a.example.com", 7L, null, "A", "C");
        drop(2, "b.example.com", 8L, null, "A", "C");
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenAnswer(inv -> {
            if (java.util.Arrays.toString((Object[]) inv.getArgument(0)).contains("takim7")) throw new IllegalStateException("smtp çöktü");
            return "SENT";
        });
        n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_FAILED);
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
        ArgumentCaptor<com.sitemonitor.model.NotificationLog> log = ArgumentCaptor.forClass(com.sitemonitor.model.NotificationLog.class);
        verify(logRepo, org.mockito.Mockito.atLeastOnce()).save(log.capture());
        assertThat(log.getAllValues()).anySatisfy(l -> assertThat(l.getEmailStatus()).startsWith(TlsGradeDropNotifier.NOTIFY_FAILED));
    }

    @Test
    @DisplayName("2026-10-12: süpürme koşullu — bu arada SENT olan satır 'yarıda kaldı' ile ezilmez")
    void sweepIsConditional() {
        TlsGradeChange sent = drop(1, "a.example.com", 7L, null, "A", "C");
        sent.setNotifyState(TlsGradeChange.NOTIFY_SENDING);
        // okuma anında SENDING, yazma anında başka tur SENT yazmış
        when(repo.findByNotifyStateOrderByIdAsc(eq(TlsGradeChange.NOTIFY_SENDING), any())).thenAnswer(inv -> {
            List<TlsGradeChange> snap = List.of(sent);
            sent.setNotifyState(TlsGradeChange.NOTIFY_SENT);
            return snap;
        });
        n.sweepStaleSending(java.time.Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
    }

    // ── Çok pod R2a (MP-43, 2026-10-22) ────────────────────────────────────────────────────────────────

    /** Pod'un tur ortasında ölmesinin benzetimi: Exception değil (notifyPending onu yakalar) — tur yarıda kesilir. */
    static final class PodKilled extends Error {
        PodKilled() { super("pod öldü"); }
    }

    @SuppressWarnings("unchecked")
    private org.springframework.jdbc.core.JdbcTemplate tracesFromLog() {
        org.springframework.jdbc.core.JdbcTemplate j = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(j.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any())).thenAnswer(inv -> {
            List<String[]> out = new ArrayList<>();
            for (NotificationLog l : savedLogs) out.add(new String[] { l.getRecipientName(), l.getSentAt() });
            return out;
        });
        return j;
    }

    private void stubSendingRows() {
        when(repo.findByNotifyStateOrderByIdAsc(eq(TlsGradeChange.NOTIFY_SENDING), any()))
                .thenAnswer(inv -> store.values().stream().filter(x -> TlsGradeChange.NOTIFY_SENDING.equals(x.getNotifyState())).toList());
    }

    @Test
    @DisplayName("R2a MP-43: pod 1. takımdan sonra ölür → 1. takımın satırı ZATEN SENT (bir kez); 2. takımın satırı izsiz SENDING → bir saat sonra PENDING, sonraki tur 2. takıma TEK posta")
    void podDiesAfterFirstTeam_secondTeamNotifiedNextTick() {
        n.setJdbc(tracesFromLog());
        stubSendingRows();
        drop(1, "a.example.com", 7L, null, "A", "C");
        drop(2, "b.example.com", 8L, null, "A", "C");
        boolean[] killed = { false };
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenAnswer(inv -> {
            if (!killed[0] && java.util.Arrays.toString((Object[]) inv.getArgument(0)).contains("takim8")) {
                killed[0] = true;
                throw new PodKilled();
            }
            return "SENT";
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> n.notifyPending()).isInstanceOf(PodKilled.class);
        assertThat(store.get(1L).getNotifyState()).as("takımı biten satır hemen sonuçlanır").isEqualTo(TlsGradeChange.NOTIFY_SENT);
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENDING);
        String claim = store.get(2L).getNotifiedAt();
        assertThat(claim).as("talep damgası").isNotNull();

        // yeni pod, bir saat sonra: izsiz satır PENDING'e döner, sonraki tur 2. takıma gönderir; 1. takıma ikinci posta yok
        TlsGradeDropNotifier other = new TlsGradeDropNotifier(repo, settings, escalation, email, logRepo, teamRepo);
        other.setPush(push);
        other.setJdbc(tracesFromLog());
        java.time.Instant later = java.time.LocalDateTime.parse(claim).toInstant(java.time.ZoneOffset.UTC).plusSeconds(3700);
        other.sweepStaleSending(later);
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_PENDING);
        assertThat(store.get(2L).getNotifyAttempts()).isEqualTo(1);
        other.notifyPending();
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENT);
        verify(email, times(3)).sendHtml(any(), isNull(), anyString(), anyString(), isNull());   // takım7 + (öldü) takım8 + takım8
        verify(email, times(1)).sendHtml(org.mockito.ArgumentMatchers.argThat(a -> java.util.Arrays.toString((Object[]) a).contains("takim7")),
                isNull(), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("R2a MP-43: yarıda kalan satırın sahip takımına talepten sonra iz YAZILMIŞSA (gitmiş olabilir) 'yarıda kaldı' — yeniden gönderilmez")
    void staleSendingWithTrace_isInterruptedNotResent() {
        n.setJdbc(tracesFromLog());
        TlsGradeChange c = drop(1, "a.example.com", 7L, null, "A", "C");
        c.setNotifyState(TlsGradeChange.NOTIFY_SENDING);
        c.setNotifiedAt("2026-10-12T08:00:00");
        NotificationLog trace = new NotificationLog();
        trace.setRecipientName("Takım 7");
        trace.setSentAt("2026-10-12T08:00:03");
        savedLogs.add(trace);
        stubSendingRows();
        n.sweepStaleSending(java.time.Instant.parse("2026-10-12T09:30:00Z"));
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_INTERRUPTED);
        // sonraki turun aynı takıma yazdığı iz (pencere dışı) bu satırın gönderildiğini GÖSTERMEZ
        TlsGradeChange d = drop(2, "b.example.com", 7L, null, "A", "C");
        d.setNotifyState(TlsGradeChange.NOTIFY_SENDING);
        d.setNotifiedAt("2026-10-12T06:00:00");
        n.sweepStaleSending(java.time.Instant.parse("2026-10-12T09:30:00Z"));
        assertThat(store.get(2L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_PENDING);
    }

    @Test
    @DisplayName("R2a MP-43: yarıda kalan talep TALEP damgasına göre yaşlanır — düşüşü günler önce olan ama 5 dk önce talep edilen satıra dokunulmaz")
    void staleAgedByClaimStampNotChangedAt() {
        TlsGradeChange c = drop(1, "a.example.com", 7L, null, "A", "C");
        c.setChangedAt("2026-10-10T08:00:00");                     // düşüş iki gün önce
        c.setNotifyState(TlsGradeChange.NOTIFY_SENDING);
        c.setNotifiedAt("2026-10-12T08:55:00");                    // talep 5 dk önce (canlı tur)
        stubSendingRows();
        n.sweepStaleSending(java.time.Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_SENDING);
    }

    @Test
    @DisplayName("R2a MP-43: yeniden deneme sayacı DB'de — yeni örnek (pod yeniden başladı / lider değişti) sayacı SIFIRLAMAZ, tavan toplamda dolar")
    void retryCapSurvivesNewInstance() {
        when(escalation.teamEmailsForMonitor(any(), isNull())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 0));
        drop(1, "a.example.com", 1L, null, "A", "B");
        for (int i = 0; i < 3; i++) n.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeChange.NOTIFY_PENDING);
        assertThat(store.get(1L).getNotifyAttempts()).isEqualTo(3);
        TlsGradeDropNotifier fresh = new TlsGradeDropNotifier(repo, settings, escalation, email, logRepo, teamRepo);
        fresh.setPush(push);
        for (int i = 3; i < TlsGradeDropNotifier.READ_RETRY_MAX; i++) fresh.notifyPending();
        assertThat(store.get(1L).getNotifyState()).isEqualTo(TlsGradeDropNotifier.NOTIFY_FAILED);
    }
}
