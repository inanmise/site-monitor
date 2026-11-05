package com.sitemonitor.service.forward;

import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Alarm iletimi bilgi bildirimi (2026-11-05, kullanıcı kararı 8): eklenen alıcılara TEK SEFERLİK e-posta + push; her karar
 * bir {@code notification_logs} satırı (tetik ALERT_FORWARD_ADDED, rol FORWARD, alertEventId 0, alıcı adı alıcı takım);
 * sistem bakımı susturması postayı da push'u da susturur; hata çağıranı bozmaz; push metninde bağlantı yok.
 */
class AlertForwardInfoNotifierTest {

    AlertForwardService forwards;
    TeamRepository teamRepo;
    AppUserRepository userRepo;
    EmailNotificationService email;
    NotificationLogRepository logs;
    UserPushService push;
    SystemMaintenanceService maintenance;
    AlertForwardInfoNotifier notifier;

    static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setActive(true);
        return t;
    }

    static AlertForward fwd(String kind) {
        AlertForward f = new AlertForward();
        f.setId(7L);
        f.setVersion(3L);
        f.setOwnerTeamId(1L);
        f.setRecipientTeamId(2L);
        f.setScopeKind("TYPES");
        f.setScopeTypes("cert,http");
        f.setRecipientKind(kind);
        f.setNote("Gece nöbeti için");
        return f;
    }

    @BeforeEach
    void setUp() {
        forwards = mock(AlertForwardService.class);
        teamRepo = mock(TeamRepository.class);
        userRepo = mock(AppUserRepository.class);
        email = mock(EmailNotificationService.class);
        logs = mock(NotificationLogRepository.class);
        push = mock(UserPushService.class);
        maintenance = mock(SystemMaintenanceService.class);
        when(teamRepo.findById(1L)).thenReturn(Optional.of(team(1, "Sipariş Sistemleri")));
        when(teamRepo.findById(2L)).thenReturn(Optional.of(team(2, "Ağ Operasyon")));
        when(email.sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), eq(false), any())).thenReturn("SENT");
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 4));
        when(push.enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Map.of("queued", 1));
        notifier = new AlertForwardInfoNotifier(forwards, teamRepo, userRepo);
        notifier.setEmail(email);
        notifier.setLogs(logs);
        notifier.setPush(push);
        notifier.setMaintenance(maintenance);
    }

    @Test
    @DisplayName("takımın tamamı: takımın alarm adresine posta + takım push'u; günlük satırı FORWARD rolüyle; push metni bağlantısız")
    void wholeTeam() {
        when(forwards.teamAlarmEmails(2L)).thenReturn(List.of("noc@example.com", "noc2@example.com"));
        AlertForwardInfoNotifier.Info info = notifier.notifyAdded(fwd("TEAM"), true, List.of(), "Ayşe Yılmaz");
        assertThat(info.emails()).isEqualTo(2);
        assertThat(info.push()).isEqualTo(4);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(to.capture(), isNull(), eq("[Site Monitor] Alarm iletimi: Sipariş Sistemleri takımının alarmları size de gelecek"),
                html.capture(), anyString(), isNull(), eq(false), isNull());
        assertThat(to.getValue()).containsExactly("noc@example.com", "noc2@example.com");
        assertThat(html.getValue()).contains("Sipariş Sistemleri").contains("Ağ Operasyon").contains("Gece nöbeti için")
                .contains("Ayşe Yılmaz").contains("Sertifika").contains("HTTP/Website");

        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        verify(push).enqueueTeamNoticeLocalized(eq(2L), eq("ALERT_FORWARD_ADDED"), eq("WARNING"), eq("ALERT_FORWARD"),
                eq("Sipariş Sistemleri"), text.capture(), eq("fwd-add:7:3"), any());
        assertThat(text.getValue().tr()).isEqualTo("Sipariş Sistemleri takımı alarmlarını size iletiyor (2 izleme türü).")
                .doesNotContain("http");
        assertThat(text.getValue().en()).isEqualTo("Team Sipariş Sistemleri is forwarding its alerts to you (2 monitor types).");

        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(row.capture());
        NotificationLog n = row.getValue();
        assertThat(n.getTrigger()).isEqualTo("ALERT_FORWARD_ADDED");
        assertThat(n.getRecipientRole()).isEqualTo("FORWARD");
        assertThat(n.getRecipientName()).isEqualTo("Ağ Operasyon");
        assertThat(n.getAlertEventId()).isZero();
        assertThat(n.getEmailStatus()).isEqualTo("SENT · push 4");
        assertThat(n.getRecipientEmail()).isEqualTo("noc@example.com, noc2@example.com");
    }

    @Test
    @DisplayName("seçilen üyeler: yalnız YENİ üyelere posta + doğrudan push; opt-out kişinin satırı opt-out")
    void members() {
        AppUser a = new AppUser();
        a.setId(5L); a.setUsername("ayse"); a.setDisplayName("Ayşe Yılmaz (Ağ / Uzman)"); a.setActive(true); a.setPushOptOut(true);
        AppUser p = new AppUser();
        p.setId(6L); p.setUsername("pasif"); p.setActive(false);
        when(userRepo.findAllById(List.of(5L, 6L))).thenReturn(List.of(a, p));
        when(forwards.memberEmails(2L, List.of(5L, 6L))).thenReturn(List.of("ayse@example.com"));
        AlertForwardInfoNotifier.Info info = notifier.notifyAdded(fwd("MEMBERS"), false, List.of(5L, 6L), "Yönetici");
        assertThat(info).isEqualTo(new AlertForwardInfoNotifier.Info(1, 1));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPushService.DirectRecipient>> rec = ArgumentCaptor.forClass(List.class);
        verify(push).enqueueDirectLocalized(rec.capture(), eq(2L), eq("ALERT_FORWARD_ADDED"), eq("WARNING"), eq("ALERT_FORWARD"),
                eq("Sipariş Sistemleri"), any(), eq("fwd-add:7:3"));
        assertThat(rec.getValue()).hasSize(1);
        assertThat(rec.getValue().get(0).username()).isEqualTo("ayse");
        assertThat(rec.getValue().get(0).optOut()).isTrue();
        verify(push, never()).enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("yeni alıcı yoksa hiçbir şey (sorgu yok); adres yoksa 'alıcı yok' satırı ama push gider")
    void nothingNewOrNoAddress() {
        assertThat(notifier.notifyAdded(fwd("MEMBERS"), false, List.of(), "x")).isEqualTo(AlertForwardInfoNotifier.Info.NONE);
        verifyNoInteractions(email, push, logs, teamRepo);

        when(forwards.teamAlarmEmails(2L)).thenReturn(List.of());
        AlertForwardInfoNotifier.Info info = notifier.notifyAdded(fwd("TEAM"), true, List.of(), "x");
        assertThat(info).isEqualTo(new AlertForwardInfoNotifier.Info(0, 4));
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), eq(false), any());
        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(row.capture());
        assertThat(row.getValue().getEmailStatus()).isEqualTo("SKIPPED: alıcı yok · push 4");
    }

    @Test
    @DisplayName("sistem bakımı susturması: posta ve push YOK, 'SKIPPED: sistem bakımı' satırı; yanıt requested + muted")
    void muted() {
        when(maintenance.notificationsMuted()).thenReturn(true);
        when(forwards.teamAlarmEmails(2L)).thenReturn(List.of("noc@example.com"));
        AlertForwardInfoNotifier.Info info = notifier.notifyAdded(fwd("TEAM"), true, List.of(), "x");
        assertThat(info).isEqualTo(new AlertForwardInfoNotifier.Info(true, 0, 0, true));
        assertThat(info.toMap()).containsEntry("requested", true).containsEntry("muted", true)
                .containsEntry("emails", 0).containsEntry("push", 0);
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), eq(false), any());
        verifyNoInteractions(push);
        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(row.capture());
        assertThat(row.getValue().getEmailStatus()).isEqualTo(SystemMaintenanceService.STATUS_SKIPPED);
    }

    @Test
    @DisplayName("duraklatılmış iletim bilgi göndermez (etkinleştirilince gider): sorgu / posta / push / satır yok, requested=false")
    void pausedForwardSendsNothing() {
        AlertForward f = fwd("TEAM");
        f.setEnabled(false);
        AlertForwardInfoNotifier.Info info = notifier.notifyAdded(f, true, List.of(), "x");
        assertThat(info).isEqualTo(AlertForwardInfoNotifier.Info.NONE);
        assertThat(info.toMap()).containsEntry("requested", false).containsEntry("muted", false);
        verifyNoInteractions(email, push, logs, teamRepo, forwards);
    }

    @Test
    @DisplayName("hata çağıranı bozmaz: posta hatası → sayılar 0, istisna yok; teslim edilmeyen posta adres saymaz")
    void failuresDoNotThrow() {
        when(forwards.teamAlarmEmails(2L)).thenThrow(new RuntimeException("db"));
        assertThat(notifier.notifyAdded(fwd("TEAM"), true, List.of(), "x"))
                .as("istendi ama gitmedi — ön yüz uyarır").isEqualTo(new AlertForwardInfoNotifier.Info(true, 0, 0, false));

        doReturn(List.of("noc@example.com")).when(forwards).teamAlarmEmails(2L);
        when(email.sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), eq(false), any())).thenReturn("FAILED: 550");
        AlertForwardInfoNotifier.Info info = notifier.notifyAdded(fwd("TEAM"), true, List.of(), "x");
        assertThat(info.emails()).isZero();
        assertThat(info.push()).isEqualTo(4);
    }
}
