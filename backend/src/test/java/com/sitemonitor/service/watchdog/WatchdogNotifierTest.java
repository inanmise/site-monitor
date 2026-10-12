package com.sitemonitor.service.watchdog;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.WebhookService;
import com.sitemonitor.service.mail.WatchdogMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kendi kendini izleme kanalları (2026-10-11): e-posta / webhook / doğrudan push, bakım susturması, notification_logs izi. */
class WatchdogNotifierTest {

    AppSettingsService settings;
    EmailNotificationService email;
    NotificationLogRepository logs;
    WebhookService webhooks;
    UserPushService push;
    SystemMaintenanceService maintenance;
    WatchdogNotifier n;
    final List<WatchdogMail.Item> items = List.of(new WatchdogMail.Item("ALERT", "Sertifika taraması", "HIGH", "Son sonuç 6 sa önce."));

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingsService.class);
        email = mock(EmailNotificationService.class);
        logs = mock(NotificationLogRepository.class);
        webhooks = mock(WebhookService.class);
        push = mock(UserPushService.class);
        maintenance = mock(SystemMaintenanceService.class);
        when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(settings.getString(eq(WatchdogSettings.KEY_RECIPIENTS), any())).thenReturn("sre@example.com, noc@example.com");
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("SENT");
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        n = new WatchdogNotifier(settings, email, logs);
        n.setWebhooks(webhooks);
        n.setPush(push);
        n.setMaintenance(maintenance);
    }

    @Test
    @DisplayName("e-posta gider → DELIVERED; günlük satırı tetik WATCHDOG, alarm kimliği 0, alıcılar yazılı")
    void emailDelivered() {
        WatchdogNotifier.Report r = n.send(items, false);
        assertThat(r.outcome()).isEqualTo(WatchdogNotifier.Outcome.DELIVERED);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(to.capture(), isNull(), eq("[Site Monitor] Uygulama sorunu: Sertifika taraması"), anyString(), isNull());
        assertThat(to.getValue()).containsExactly("sre@example.com", "noc@example.com");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getTrigger()).isEqualTo(WatchdogNotifier.TRIGGER);
        assertThat(log.getValue().getAlertEventId()).isZero();
        assertThat(log.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(log.getValue().getRecipientEmail()).contains("sre@example.com");
        verify(webhooks, never()).send(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("alıcı yoksa sistem yöneticisi adresine; o da yoksa NO_CHANNEL ve iz 'SKIPPED: alıcı yok'")
    void recipientsFallback() {
        when(settings.getString(eq(WatchdogSettings.KEY_RECIPIENTS), any())).thenReturn("");
        when(settings.getString(eq("site.monitor.system-admin.email"), any())).thenReturn("admin@example.com");
        n.send(items, false);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(to.capture(), isNull(), anyString(), anyString(), isNull());
        assertThat(to.getValue()).containsExactly("admin@example.com");

        when(settings.getString(eq("site.monitor.system-admin.email"), any())).thenReturn("");
        WatchdogNotifier.Report r = n.send(items, false);
        assertThat(r.outcome()).isEqualTo(WatchdogNotifier.Outcome.NO_CHANNEL);
        assertThat(r.email()).isEqualTo("SKIPPED: alıcı yok");
    }

    @Test
    @DisplayName("sistem bakımı susturuyorsa hiçbir kanal denenmez, iz 'SKIPPED: sistem bakımı'; test bildirimi susturulmaz")
    void mutedByMaintenance() {
        when(maintenance.notificationsMuted()).thenReturn(true);
        WatchdogNotifier.Report r = n.send(items, false);
        assertThat(r.outcome()).isEqualTo(WatchdogNotifier.Outcome.NO_CHANNEL);
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: sistem bakımı");
        assertThat(n.send(items, true).outcome()).isEqualTo(WatchdogNotifier.Outcome.DELIVERED);
    }

    @Test
    @DisplayName("e-posta başarısız + webhook başarısız → FAILED; push doğrudan ağ geçidine (outbox değil)")
    void failuresAndDirectPush() {
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("FAILED: 554");
        when(settings.getString(eq(WatchdogSettings.KEY_WEBHOOK_URL), any())).thenReturn("https://hooks.example.com/x");
        doThrow(new RuntimeException("timeout")).when(webhooks).send(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(n.send(items, false).outcome()).isEqualTo(WatchdogNotifier.Outcome.FAILED);

        when(settings.getString(eq(WatchdogSettings.KEY_PUSH_USERS), any())).thenReturn("N12345, N22222");
        when(push.gatewayConfigured()).thenReturn(true);
        when(push.sendDirect(anyString(), anyString(), anyString())).thenReturn(new UserPushService.DirectResult(true, 200, null));
        WatchdogNotifier.Report r = n.send(items, false);
        assertThat(r.outcome()).isEqualTo(WatchdogNotifier.Outcome.DELIVERED);
        assertThat(r.push()).isEqualTo("SENT");
        verify(push, times(2)).sendDirect(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("2026-10-12: e-posta kanalı kapalı / alıcılar pasif (SKIPPED…) bir deneme değildir → NO_CHANNEL (15 dk'da bir sonsuz yeniden deneme yok)")
    void skippedEmailIsNoChannel() {
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("SKIPPED_DISABLED");
        assertThat(n.send(items, false).outcome()).isEqualTo(WatchdogNotifier.Outcome.NO_CHANNEL);
        when(email.sendHtml(any(), isNull(), anyString(), anyString(), isNull())).thenReturn("SKIPPED: pasif kullanıcı");
        assertThat(n.send(items, false).outcome()).isEqualTo(WatchdogNotifier.Outcome.NO_CHANNEL);
    }

    @Test
    @DisplayName("2026-10-12: pasif hesaba sistem push'u gitmez (izde sayılır); İngilizce push dili seçen kişiye İngilizce metin")
    void pushSkipsPassiveAndFollowsLanguage() {
        com.sitemonitor.repository.AppUserRepository users = mock(com.sitemonitor.repository.AppUserRepository.class);
        n.setUsers(users);
        com.sitemonitor.model.AppUser passive = new com.sitemonitor.model.AppUser();
        passive.setUsername("N11111"); passive.setActive(false);
        com.sitemonitor.model.AppUser en = new com.sitemonitor.model.AppUser();
        en.setUsername("N22222"); en.setActive(true); en.setPushLang("en");
        when(users.findByUsername("N11111")).thenReturn(java.util.Optional.of(passive));
        when(users.findByUsername("N22222")).thenReturn(java.util.Optional.of(en));
        when(settings.getString(eq(WatchdogSettings.KEY_PUSH_USERS), any())).thenReturn("N11111, N22222");
        when(push.gatewayConfigured()).thenReturn(true);
        when(push.sendDirect(anyString(), anyString(), anyString())).thenReturn(new UserPushService.DirectResult(true, 200, null));
        List<WatchdogMail.Item> bilingual = List.of(new WatchdogMail.Item("ALERT", "Sertifika taraması", "HIGH", "gecikti",
                "Certificate sweep", "is late"));
        WatchdogNotifier.Report r = n.send(bilingual, false);
        verify(push, never()).sendDirect(eq("N11111"), anyString(), anyString());
        verify(push).sendDirect(eq("N22222"), eq("[Site Monitor] Application problem: Certificate sweep"), eq("Certificate sweep: problem — is late"));
        assertThat(r.push()).isEqualTo("SENT · 1 pasif kullanıcı atlandı");
    }
}
