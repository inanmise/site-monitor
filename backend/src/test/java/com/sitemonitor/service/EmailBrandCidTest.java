package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alarm/çözülme mailleri CID inline marka logosu taşır — üç gönderim yolunun ortak hunileri
 * ({@code sendAlert} / {@code sendResolutionAlert}) üzerinden MimeMessage yapısı doğrulanır.
 * Gerçek SMTP yok: sender mock'lanır, mesaj bellekte yazılıp okunur.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailBrandCidTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock JavaMailSenderImpl sender;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder templateBuilder = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(templateBuilder, "appBaseUrl", "http://localhost:5173");
        service = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, templateBuilder);

        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        s.setFromAddress("noreply@sitemonitor");
        s.setRetryDelayMs(90000);
        when(settingsService.getOrDefaults()).thenReturn(s);
        when(smtpMailService.currentSender()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    /** Gönderilen mesajın ham MIME'ı. NOT: HTML gövde quoted-printable satır-kaydırmalı olabilir —
     *  "cid:" referansı ham metinde bölünebilir; cid assert'leri HTML string'inde yapılır. */
    private String sentMime() throws Exception {
        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(cap.capture());
        MimeMessage msg = cap.getValue();
        msg.saveChanges();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        msg.writeTo(raw);
        return raw.toString("ISO-8859-1");
    }

    @Test
    @DisplayName("CRITICAL sertifika alarmı → critical logo CID inline + HTML cid referanslı")
    void criticalAlert_carriesCriticalLogo() throws Exception {
        service.sendAlert(new String[]{"ops@example.com"}, "[SiteMonitor] kritik", "mesaj",
                "example.com", "CRITICAL", "EXPIRY", 3, null);
        String mime = sentMime();
        assertThat(mime).contains("Content-ID: <brand-logo>");
        assertThat(mime).contains("multipart/related");
        // Logo artık ŞABLONUN başlık çubuğundan gelir (BRAND.md §5.1) — lockup HTML'de
        String html = service.buildAlertEmailHtml("[Site Monitor] kritik", "mesaj", "example.com", "CRITICAL", "EXPIRY", 3, null);
        assertThat(html).contains("cid:brand-logo").contains("width=\"32\"");
    }

    @Test
    @DisplayName("Çözülme maili → DAİMA ok (yeşil) logo CID inline (yapraklar yeşile döndü)")
    void resolutionMail_carriesOkLogo() throws Exception {
        service.sendResolutionAlert(new String[]{"ops@example.com"}, "[SiteMonitor] çözüldü",
                "example.com", "EXPIRY", "CRITICAL", 30, "admin", "2026-08-06 10:00", "2026-08-05 09:00", null);
        String mime = sentMime();
        assertThat(mime).contains("Content-ID: <brand-logo>");
        assertThat(mime).contains("multipart/related");
    }

    @Test
    @DisplayName("Zengin tip-özel şablon (KEYWORD) da tek huniden logo alır")
    void richTemplate_alsoGetsLogo() throws Exception {
        service.sendAlert(new String[]{"ops@example.com"}, "[SiteMonitor] keyword", "mesaj",
                "https://example.com", "WARNING", "KEYWORD", null, new java.util.HashMap<>());
        String mime = sentMime();
        assertThat(mime).contains("Content-ID: <brand-logo>");
        String html = service.buildAlertEmailHtml("[Site Monitor] keyword", "mesaj", "https://example.com", "WARNING", "KEYWORD", null, new java.util.HashMap<>());
        assertThat(html).contains("cid:brand-logo").contains("width=\"32\"");
    }
}
