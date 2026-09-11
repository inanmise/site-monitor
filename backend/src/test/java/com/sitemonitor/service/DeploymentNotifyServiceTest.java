package com.sitemonitor.service;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.DeploymentHistoryService.Kind;
import com.sitemonitor.service.DeploymentHistoryService.TransitionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3 — sürüm geçişi bildirimi: opt-in ayar, yalnız UPGRADE/ROLLBACK, alıcı CANLI ayardan, alıcı yoksa
 * atla, mail sürüm + ortam taşır, dinleyiciden istisna çıkmaz.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeploymentNotifyServiceTest {

    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock ReleaseIndexService releaseIndex;
    @Mock NotificationLogRepository notificationLogRepo;

    private DeploymentNotifyService service;

    private static DeploymentHistory row() {
        DeploymentHistory d = new DeploymentHistory();
        d.setId(42L); d.setStartedAt("2026-09-11T08:00:00Z"); d.setRecordedAt(d.getStartedAt());
        d.setEnvironment("prod"); d.setVersion("20.54.0"); d.setSource("STARTUP");
        d.setGitCommit("0123456789abcdef0123456789abcdef01234567");
        return d;
    }

    private static TransitionEvent event(Kind kind, String from, String to) {
        return new TransitionEvent(kind, "prod", from, to, row());
    }

    @BeforeEach
    void setUp() {
        service = new DeploymentNotifyService(appSettings, emailService, releaseIndex, notificationLogRepo);
        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(releaseIndex.find(any())).thenReturn(Optional.empty());
        when(emailService.sendDeploymentNotice(any(), any())).thenReturn("SENT");
        when(emailService.senderAddress()).thenReturn("noreply@example.com");
    }

    private void enable(String recipients) {
        when(appSettings.getBoolean(eq(DeploymentNotifyService.ENABLED_KEY), any(Boolean.class))).thenReturn(true);
        when(appSettings.getString(eq(DeploymentNotifyService.RECIPIENT_KEY), any())).thenReturn(recipients);
    }

    @Test
    @DisplayName("varsayılan (ayar kapalı) → hiç mail yok")
    void disabledByDefault() {
        service.onTransition(event(Kind.UPGRADE, "20.53.2", "20.54.0"));
        verify(emailService, never()).sendDeploymentNotice(any(), any());
        verify(notificationLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("açık + UPGRADE → sistem yöneticisine mail; içerik sürüm + ortam + commit taşır; notification_log yazılır")
    void enabledUpgrade_sendsMail() {
        enable("admin@example.com, ops@example.com;");
        service.onTransition(event(Kind.UPGRADE, "20.53.2", "20.54.0"));

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<EmailNotificationService.DeploymentNotice> notice =
                ArgumentCaptor.forClass(EmailNotificationService.DeploymentNotice.class);
        verify(emailService).sendDeploymentNotice(to.capture(), notice.capture());
        assertThat(to.getValue()).containsExactly("admin@example.com", "ops@example.com");
        EmailNotificationService.DeploymentNotice n = notice.getValue();
        assertThat(n.kind()).isEqualTo("UPGRADE");
        assertThat(n.environment()).isEqualTo("prod");
        assertThat(n.fromVersion()).isEqualTo("20.53.2");
        assertThat(n.toVersion()).isEqualTo("20.54.0");
        assertThat(n.commitShort()).isEqualTo("01234567");
        assertThat(n.startedAt()).isEqualTo("2026-09-11T08:00:00Z");
        assertThat(n.highlights()).isEmpty();
        assertThat(n.breaking()).isFalse();

        ArgumentCaptor<NotificationLog> nl = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(nl.capture());
        assertThat(nl.getValue().getTrigger()).isEqualTo("DEPLOYMENT_UPGRADE");
        assertThat(nl.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(nl.getValue().getAlertEventId()).isEqualTo(42L);
        assertThat(nl.getValue().getRecipientEmail()).contains("admin@example.com");
    }

    @Test
    @DisplayName("ROLLBACK bildirilir; CHANGED / RESTART / FIRST_SEEN bildirilmez")
    void kindFilter() {
        enable("admin@example.com");
        service.onTransition(event(Kind.ROLLBACK, "20.54.0", "20.53.2"));
        verify(emailService).sendDeploymentNotice(any(), any());

        service.onTransition(event(Kind.CHANGED, "unknown", "20.54.0"));
        service.onTransition(event(Kind.RESTART, "20.54.0", "20.54.0"));
        service.onTransition(event(Kind.FIRST_SEEN, null, "20.54.0"));
        verify(emailService).sendDeploymentNotice(any(), any());   // hâlâ 1
    }

    @Test
    @DisplayName("alıcı boşsa atlanır (mail yok, log yok) — istisna yok")
    void noRecipientSkips() {
        enable("   ");
        assertThat(service.handle(event(Kind.UPGRADE, "1.0.0", "1.1.0"))).isEqualTo("SKIPPED_NO_RECIPIENT");
        verify(emailService, never()).sendDeploymentNotice(any(), any());
        verify(notificationLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("öne çıkanlar yayın indeksinden: feat önce, en fazla 5; breaking bayrağı taşınır")
    void highlightsFromReleaseIndex() {
        enable("admin@example.com");
        List<ReleaseIndexService.Change> changes = List.of(
                new ReleaseIndexService.Change("docs", null, false, "a", "belge"),
                new ReleaseIndexService.Change("fix", "cache", false, "b", "düzeltme 1"),
                new ReleaseIndexService.Change("feat", "deploy", false, "c", "özellik 1"),
                new ReleaseIndexService.Change("fix", null, false, "d", "düzeltme 2"),
                new ReleaseIndexService.Change("feat", null, true, "e", "özellik 2"),
                new ReleaseIndexService.Change("refactor", null, false, "f", "iç düzen"),
                new ReleaseIndexService.Change("perf", null, false, "g", "hız"));
        when(releaseIndex.find("20.54.0")).thenReturn(Optional.of(new ReleaseIndexService.Release(
                "20.54.0", "v20.54.0", "2026-09-11T07:00:00Z", "sha", "20.53.2", "minor", true,
                Map.of("feat", 2), changes, false, 0)));

        EmailNotificationService.DeploymentNotice n = service.build(event(Kind.UPGRADE, "20.53.2", "20.54.0"));
        assertThat(n.highlights()).hasSize(DeploymentNotifyService.MAX_HIGHLIGHTS);
        assertThat(n.highlights().get(0)).isEqualTo("feat(deploy): özellik 1");
        assertThat(n.highlights().get(1)).isEqualTo("feat: özellik 2");
        assertThat(n.highlights().get(2)).startsWith("fix(cache): ");
        assertThat(n.highlights().get(3)).startsWith("fix: ");
        assertThat(n.breaking()).isTrue();
    }

    @Test
    @DisplayName("mail servisi ya da ayar patlarsa dinleyiciden istisna ÇIKMAZ")
    void listenerNeverThrows() {
        enable("admin@example.com");
        when(emailService.sendDeploymentNotice(any(), any())).thenThrow(new RuntimeException("smtp"));
        assertThatCode(() -> service.onTransition(event(Kind.UPGRADE, "1.0.0", "1.1.0"))).doesNotThrowAnyException();

        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenThrow(new IllegalStateException("db"));
        assertThatCode(() -> service.onTransition(event(Kind.UPGRADE, "1.0.0", "1.1.0"))).doesNotThrowAnyException();
        assertThatCode(() -> service.onTransition(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recipients(): virgül/noktalı virgül ayrılır, kırpılır, boşlar düşer")
    void recipientsParsing() {
        assertThat(DeploymentNotifyService.recipients(null)).isEmpty();
        assertThat(DeploymentNotifyService.recipients(" , ;")).isEmpty();
        assertThat(DeploymentNotifyService.recipients(" a@example.com ;b@example.com,,c@example.com "))
                .containsExactly("a@example.com", "b@example.com", "c@example.com");
    }

    // ── Gerçek HTML üreticisi: içerik sürüm + ortam taşır, Outlook-güvenli ────────────────────

    @Test
    @DisplayName("buildDeploymentNoticeHtml: ortam, sürüm aralığı, tür, commit, öne çıkanlar; style bloğu yalnız MSO; rgba yok")
    void html_containsVersionAndEnvironment() {
        SmtpSettingsService settingsService = org.mockito.Mockito.mock(SmtpSettingsService.class);
        SmtpMailService smtpMailService = org.mockito.Mockito.mock(SmtpMailService.class);
        AppSettingsService settings = org.mockito.Mockito.mock(AppSettingsService.class);
        when(settings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder tb = new EmailTemplateBuilder(settings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", "http://localhost:8080");
        EmailNotificationService real = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, settings, tb);
        when(settingsService.getOrDefaults()).thenReturn(new SmtpSettings());

        String html = real.buildDeploymentNoticeHtml(new EmailNotificationService.DeploymentNotice(
                "ROLLBACK", "prod", "20.54.0", "20.53.2", "01234567", "2026-09-11T08:00:00Z",
                List.of("fix(cache): <önbellek>"), true));
        assertThat(html).contains("prod").contains("20.54.0 → 20.53.2").contains("ROLLBACK")
                .contains("01234567").contains("2026-09-11T08:00:00Z")
                .contains("fix(cache): &lt;önbellek&gt;")
                .contains("Kırıcı değişiklik")
                .contains("cid:brand-logo")
                .doesNotContain("rgba(");
        // <style> yalnız MSO koşullu bloğunda (Outlook/Gmail kırpar — inline-only kural)
        assertThat(html.replaceAll("<!--\\[if mso\\]>.*?<!\\[endif\\]-->", "")).doesNotContain("<style");

        String up = real.buildDeploymentNoticeHtml(new EmailNotificationService.DeploymentNotice(
                "UPGRADE", "staging", "1.0.0", "1.1.0", null, null, List.of(), false));
        assertThat(up).contains("Yeni sürüm devreye alındı").contains("staging").contains("1.0.0 → 1.1.0")
                .doesNotContain("Commit").doesNotContain("Kırıcı");
    }
}
