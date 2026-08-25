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
import java.util.List;
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

    /** Envanter zenginleştirmesi olan sertifika bağlamı (EscalationService bunu üretir). */
    private Map<String, Object> inventoryCtx() {
        Map<String, Object> m = ctx("https://www.akbank.com", null);
        m.put("team_name", "SY-Dijital Bankacilik");
        m.put("not_after", "2026-09-09T02:59:00Z");
        m.put("issuer_cn", "DigiCert EV RSA CA G2");
        m.put("subject", "CN=www.akbank.com");
        m.put("fingerprint", "EB0B59B1AA31C0F5C2D8E4A76B93F1D0C4A85E2739BD61FA0C8E7B4D53B8DD8C3");
        m.put("inv_ops", List.of("Netscaler", "WAF'ta Var", "Kullanım Durumu"));
        // Sorumlu Ekipler kartı — önizlemede de görünsün ki şablon değişikliği gözle kontrol edilebilsin.
        m.put("inv_contacts", new java.util.LinkedHashMap<>(java.util.Map.of(
                "Servis Yönetimi", "Ad Soyad - ad.soyad@example.com",
                "Uygulama Geliştirme", "ekip@example.com",
                "IISAdmin Ekibi", "iisadmin@example.com")));
        m.put("inv_change_desc",
                "1. Sertifika alım süreci IISAdmins tarafından yapılır. IISAdmins PFX halindeki sertifikayı "
                + "AdcAdmins ve Güvenlik ekibi ile paylaşır.\n"
                + "2. Değişiklik planlaması Servis Yönetimi tarafından ilgili ekiplerle koordineli yapılır.\n"
                + "3. Sertifika Netscaler ve Waf da güncellenir.");
        return m;
    }

    /** DNS değişiklik postası — verilen eski/yeni değerlerle (fark tablosunu gözle kontrol için). */
    private String dnsPreview(List<String> oldValues, List<String> newValues) {
        Map<String, Object> m = ctx("example.com", null);
        m.put("record_type", "A");
        m.put("changed_at", "2026-08-08T15:53:00");
        m.put("old_values", oldValues);
        m.put("new_values", newValues);
        m.put("alert_event_id", 4242L);
        return service.buildAlertEmailHtml("konu", "DNS kaydı değişti", "callcenterfacechat.akbank.com",
                "HIGH", "DNS_CHANGED", null, m);
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
        // Envanter bölümleri (operasyonel çipler + değişiklik açıklaması) — gerçek prod verisine yakın
        write("cert-expiry-inventory",
                service.buildAlertEmailHtml("konu", "www.akbank.com adresindeki sertifikanın süresi 30 gün içinde doluyor.",
                        "www.akbank.com", "MEDIUM", "EXPIRY", 30, inventoryCtx()));
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

        // DNS fark tablosu — gerçek değerlerle ve kenar durumlarıyla (gözle kontrol için)
        write("dns-changed-values", dnsPreview(List.of("172.31.129.6", "172.31.6.32"), List.of("172.31.6.19")));
        write("dns-changed-empty-old", dnsPreview(List.of(), List.of("172.31.6.19")));
        write("dns-changed-empty-new", dnsPreview(List.of("172.31.129.6"), List.of()));
        write("dns-changed-many", dnsPreview(
                List.of("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5", "10.0.0.6", "10.0.0.7", "10.0.0.8"),
                List.of("10.1.0.1")));
        write("dns-changed-txt", dnsPreview(
                List.of("v=DKIM1; k=rsa; p=" + "A".repeat(240)),
                List.of("cname-hedefi.uzun-alan-adi.akbank.com")));

        // Olay aksiyon butonlu sürümler (alert_event_id dolu)
        Map<String, Object> act = ctx("https://example.com/health", "Sağlık Ucu");
        act.put("alert_event_id", 4242L);
        write("accessibility-critical-actions",
                service.buildAlertEmailHtml("konu", "Site erişilemez", "https://example.com/health", "CRITICAL", "ACCESSIBILITY", null, act));
        write("resolved-accessibility-actions",
                service.buildResolutionEmailHtml("https://example.com/health", "ACCESSIBILITY", "CRITICAL", null, "oto-toparlanma", "2026-08-06 12:00", "2026-08-06 08:00", act, "SY-A", null));

        // Haftalık hatırlatma
        write("weekly-reminder",
                service.buildWeeklyReportReminderHtml("SY-A", "2026-W32", "http://localhost:8080/?tab=weeklyreports"));
    }
}
