package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * E-POSTA ÖNİZLEME HARNESS'I (kalıcı) — canlı her mail türü × severity için üretilen HTML'i
 * {@code target/email-previews/<prefix>-<tur>-<severity>.html} olarak yazar; şablon değişiklikleri
 * gözle (tarayıcıda) doğrulanabilir olsun. SMTP YOK — yalnız HTML üretimi.
 *
 * Önek {@code -Demail.preview.prefix=once} ile değiştirilebilir (varsayılan "sonra") —
 * /mail-denetim akışında değişiklik ÖNCESİ "once-*", sonrası "sonra-*" çiftleri karşılaştırılır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailPreviewHarnessTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    private EmailNotificationService service;
    private Path outDir;
    private String prefix;

    @BeforeEach
    void setUp() throws Exception {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder templateBuilder = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(templateBuilder, "appBaseUrl", "http://localhost:8080");
        service = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, templateBuilder);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(false);
        when(settingsService.getOrDefaults()).thenReturn(s);
        outDir = Path.of("target", "email-previews");
        Files.createDirectories(outDir);
        prefix = System.getProperty("email.preview.prefix", "sonra");
    }

    private Map<String, Object> ctx(String url, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("url", url);
        m.put("keyword", "İçerik SSL Sorunu");
        m.put("monitor_name", name);
        m.put("team_name", "SY-A");
        m.put("status_code", 200);
        m.put("response_ms", 240);
        return m;
    }

    private void write(String slug, String html) throws Exception {
        assertThat(html).isNotBlank();
        Files.writeString(outDir.resolve(prefix + "-" + slug + ".html"), html, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("canlı alarm/çözülme/hatırlatma HTML'leri önizleme dosyalarına yazılır")
    void generatePreviews() throws Exception {
        // Süre-bitişi ailesi (executive şablon)
        for (String sev : new String[] { "CRITICAL", "HIGH", "WARNING" }) {
            write("cert-expiry-" + sev.toLowerCase(),
                    service.buildAlertEmailHtml("konu", "Sertifika süresi doluyor", "example.com", sev, "EXPIRY", 5, ctx("https://example.com", null)));
        }
        write("domain-expiry-critical",
                service.buildAlertEmailHtml("konu", "Alan adı süresi doluyor", "example.com", "CRITICAL", "DOMAINMON_EXPIRY", 3, ctx("example.com", null)));

        // Zengin tip-özel aile
        write("accessibility-critical",
                service.buildAlertEmailHtml("konu", "Site erişilemez", "https://example.com/health", "CRITICAL", "ACCESSIBILITY", null, ctx("https://example.com/health", "Sağlık Ucu")));
        write("port-down-critical",
                service.buildAlertEmailHtml("konu", "Port kapalı", "example.com", "CRITICAL", "PORT_DOWN", null, ctx("example.com", "Ödeme Portu")));
        write("dns-failure-critical",
                service.buildAlertEmailHtml("konu", "DNS çözülemiyor", "example.com", "CRITICAL", "DNS_FAILURE", null, ctx("example.com", null)));
        write("keyword-high",
                service.buildAlertEmailHtml("konu", "Keyword bulunamadı", "http://localhost:8080/health- duplicate", "HIGH", "KEYWORD", null, ctx("http://localhost:8080/health- duplicate", "Sağlık İçerik Kontrolü")));
        write("ping-warning",
                service.buildAlertEmailHtml("konu", "Ping kaybı", "10.0.0.7", "WARNING", "PING_DOWN", null, ctx("10.0.0.7", "Çekirdek Switch")));
        write("dns-changed-warning",
                service.buildAlertEmailHtml("konu", "DNS kaydı değişti", "example.com", "WARNING", "DNS_CHANGED", null, ctx("example.com", null)));

        // Çözülmeler
        write("resolved-cert",
                service.buildResolutionEmailHtml("example.com", "EXPIRY", "CRITICAL", 90, "admin", "2026-08-06 12:00", "2026-08-05 09:00", ctx("https://example.com", null), "SY-A", null));
        write("resolved-keyword",
                service.buildResolutionEmailHtml("http://localhost:8080/health- duplicate", "KEYWORD", "HIGH", null, "oto-toparlanma", "2026-08-06 12:00", "2026-08-06 09:00", ctx("http://localhost:8080/health- duplicate", "Sağlık İçerik Kontrolü"), "SY-A", null));
        write("resolved-accessibility",
                service.buildResolutionEmailHtml("https://example.com/health", "ACCESSIBILITY", "CRITICAL", null, "oto-toparlanma", "2026-08-06 12:00", "2026-08-06 08:00", ctx("https://example.com/health", "Sağlık Ucu"), "SY-A", null));

        // Haftalık hatırlatma
        write("weekly-reminder",
                service.buildWeeklyReportReminderHtml("SY-A", "2026-W32", "http://localhost:8080/?tab=weeklyreports"));
    }
}
