package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Görsel doğrulama yardımcısı (varsayılan KAPALI): E2 haftalık rapor dağıtım satırı ve E3 dağıtım bildirimi
 * HTML'lerini dosyaya döker — {@code -Dmail.preview.dir=<klasör>} verilince koşar. Üretim kodunu test etmez,
 * tarayıcıda göz kontrolü içindir; yer tutucu veriyle (example.com / Takım A).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnabledIfSystemProperty(named = "mail.preview.dir", matches = ".+")
class MailPreviewDumpTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    @Test
    void dumpPreviews() throws Exception {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getString(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", "http://localhost:5173");
        EmailNotificationService svc = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, tb);
        SmtpSettings s = new SmtpSettings(); s.setEnabled(true);
        when(settingsService.getOrDefaults()).thenReturn(s);

        Path dir = Path.of(System.getProperty("mail.preview.dir"));
        Files.createDirectories(dir);

        String weekly = svc.buildWeeklyAvailabilityHtml("Takım A", "36. hafta (31 Ağu – 6 Eyl)",
                List.of(new EmailNotificationService.AvailabilityRow("www.example.com", 99.87, 1, 12, 12, 210L, 480L, 120),
                        new EmailNotificationService.AvailabilityRow("api.example.com", 100.0, 0, 0, 0, 95L, 180L, 45)),
                new EmailNotificationService.AvailabilitySummary(2, 2, 99.93, "api.example.com", 100.0, "www.example.com", 99.87, 0, 45),
                null, null, new EmailNotificationService.DeploymentWeekly(2, "20.52.1", "20.53.2", 1, 0));
        Files.writeString(dir.resolve("weekly-e2.html"), weekly, StandardCharsets.UTF_8);

        String notice = svc.buildDeploymentNoticeHtml(new EmailNotificationService.DeploymentNotice(
                "UPGRADE", "prod", "20.53.2", "20.54.0", "abcdef01", "2026-09-11T06:30:00Z",
                List.of("feat(ui): sürüm çipi ve dağıtım geçmişi", "fix(page): timeout retry kaldırıldı", "docs: Grafana anotasyon rehberi"), false));
        Files.writeString(dir.resolve("deploy-e3-upgrade.html"), notice, StandardCharsets.UTF_8);
        String rollback = svc.buildDeploymentNoticeHtml(new EmailNotificationService.DeploymentNotice(
                "ROLLBACK", "prod", "20.54.0", "20.53.2", "0123abcd", "2026-09-11T07:10:00Z", List.of(), true));
        Files.writeString(dir.resolve("deploy-e3-rollback.html"), rollback, StandardCharsets.UTF_8);
    }
}
