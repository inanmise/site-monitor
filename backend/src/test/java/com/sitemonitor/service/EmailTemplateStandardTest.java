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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * OUTLOOK-GÜVENLİ ŞABLON STANDARDI (BRAND.md §5.1) — regresyon kilidi. Ekran görüntüsündeki saha
 * hatasının (dev, kart-dışı logo; boş gri alan) bir daha yaşanmaması için CANLI her alarm/çözülme
 * şablonunda şunları zorlar:
 *  - logo <img> TAM BİR kez, width="32"/height="32" HTML attribute'larıyla, cid:brand-logo src'siyle;
 *  - logo ile "Site Monitor" yazısı AYNI lockup parçasında (kart-dışı serbest logo yakalanır);
 *  - kapsayıcı kart width='600' (haftalık 850px ailesi belgeli istisna);
 *  - <style> bloğu yalnız MSO koşullusu (Outlook/Gmail kırpar — inline-only kural);
 *  - dış URL'li <img> yok; "SiteMonitor" bitişik yazım yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailTemplateStandardTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder templateBuilder = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(templateBuilder, "appBaseUrl", "http://localhost:8080");
        service = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, templateBuilder);
        when(settingsService.getOrDefaults()).thenReturn(new SmtpSettings());
    }

    private Map<String, Object> ctx() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", "https://example.com/x");
        m.put("keyword", "kelime");
        m.put("monitor_name", "Örnek Monitör");
        m.put("team_name", "SY-A");
        return m;
    }

    private static int count(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    private void assertStandard(String slug, String html, String expectedWidth) {
        assertThat(html).as("%s: html", slug).isNotBlank();
        // TEK logo, attribute'lu, cid'li — lockup parçası (logo + "Site Monitor" aynı birim) tam bir kez
        assertThat(count(html, "src=\"cid:brand-logo\"")).as("%s: tek logo", slug).isEqualTo(1);
        assertThat(html).as("%s: attribute boyut", slug).contains("width=\"32\"").contains("height=\"32\"");
        assertThat(html).as("%s: lockup bütünlüğü", slug).contains(BrandMailAssets.headerLockup());
        // Kapsayıcı genişlik standardı
        assertThat(html).as("%s: kart genişliği", slug).contains("width='" + expectedWidth + "'");
        // <style> yalnız MSO koşullu bloklarda
        assertThat(count(html, "<style>")).as("%s: style yalnız MSO", slug)
                .isEqualTo(count(html, "<!--[if mso]><style>"));
        // Dış URL'li görsel yok
        assertThat(html).as("%s: dış img yok", slug)
                .doesNotContain("img src=\"http").doesNotContain("img src='http");
        // Marka yazımı
        assertThat(html).as("%s: bitişik yazım yok", slug).doesNotContain("SiteMonitor");
    }

    @Test
    @DisplayName("canlı alarm şablonları (executive + zengin tip-özel) standarda uyar — 600px, tek 32px lockup, style'sız")
    void alertTemplatesConform() {
        assertStandard("cert-expiry", service.buildAlertEmailHtml("k", "m", "example.com", "CRITICAL", "EXPIRY", 5, ctx()), "600");
        assertStandard("domain-expiry", service.buildAlertEmailHtml("k", "m", "example.com", "WARNING", "DOMAINMON_EXPIRY", 20, ctx()), "600");
        assertStandard("accessibility", service.buildAlertEmailHtml("k", "m", "https://example.com", "CRITICAL", "ACCESSIBILITY", null, ctx()), "600");
        assertStandard("port-down", service.buildAlertEmailHtml("k", "m", "example.com", "CRITICAL", "PORT_DOWN", null, ctx()), "600");
        assertStandard("dns-failure", service.buildAlertEmailHtml("k", "m", "example.com", "CRITICAL", "DNS_FAILURE", null, ctx()), "600");
        assertStandard("keyword", service.buildAlertEmailHtml("k", "m", "https://example.com", "HIGH", "KEYWORD", null, ctx()), "600");
        assertStandard("ping", service.buildAlertEmailHtml("k", "m", "10.0.0.7", "WARNING", "PING_DOWN", null, ctx()), "600");
        assertStandard("dns-changed", service.buildAlertEmailHtml("k", "m", "example.com", "WARNING", "DNS_CHANGED", null, ctx()), "600");
    }

    @Test
    @DisplayName("olay aksiyon butonları eklenince de standart korunur (style/logo/genişlik kuralları)")
    void alertTemplatesWithIncidentActionsConform() {
        Map<String, Object> c = ctx();
        c.put("alert_event_id", 4242L);
        assertStandard("cert-expiry+act", service.buildAlertEmailHtml("k", "m", "example.com", "CRITICAL", "EXPIRY", 5, c), "600");
        assertStandard("accessibility+act", service.buildAlertEmailHtml("k", "m", "https://example.com", "CRITICAL", "ACCESSIBILITY", null, c), "600");
        assertStandard("keyword+act", service.buildAlertEmailHtml("k", "m", "https://example.com", "HIGH", "KEYWORD", null, c), "600");
        assertStandard("ping+act", service.buildAlertEmailHtml("k", "m", "10.0.0.7", "WARNING", "PING_DOWN", null, c), "600");
        assertStandard("dns-changed+act", service.buildAlertEmailHtml("k", "m", "example.com", "WARNING", "DNS_CHANGED", null, c), "600");
        assertStandard("resolved-access+act", service.buildResolutionEmailHtml("https://example.com", "ACCESSIBILITY", "CRITICAL", null, "oto", "2026-08-06 12:00", "2026-08-06 08:00", c, "SY-A", null), "600");
    }

    @Test
    @DisplayName("canlı çözülme şablonları standarda uyar")
    void resolutionTemplatesConform() {
        assertStandard("resolved-cert", service.buildResolutionEmailHtml("example.com", "EXPIRY", "CRITICAL", 90, "admin", "2026-08-06 12:00", "2026-08-05 09:00", ctx(), "SY-A", null), "600");
        assertStandard("resolved-keyword", service.buildResolutionEmailHtml("https://example.com", "KEYWORD", "HIGH", null, "oto", "2026-08-06 12:00", "2026-08-06 09:00", ctx(), "SY-A", null), "600");
        assertStandard("resolved-access", service.buildResolutionEmailHtml("https://example.com", "ACCESSIBILITY", "CRITICAL", null, "oto", "2026-08-06 12:00", "2026-08-06 08:00", ctx(), "SY-A", null), "600");
        assertStandard("resolved-ping", service.buildResolutionEmailHtml("10.0.0.7", "PING_DOWN", "WARNING", null, "oto", "2026-08-06 12:00", "2026-08-06 08:00", ctx(), "SY-A", null), "600");
    }

    @Test
    @DisplayName("haftalık hatırlatma: 850px belgeli istisna + lockup standardı")
    void weeklyReminderConform() {
        String html = service.buildWeeklyReportReminderHtml("SY-A", "2026-W32", "http://localhost:8080/?tab=weeklyreports");
        assertThat(count(html, "src=\"cid:brand-logo\"")).isEqualTo(1);
        assertThat(html).contains(BrandMailAssets.headerLockup());
        assertThat(html).contains("width='850'");
        assertThat(html).doesNotContain("SiteMonitor");
    }

    @Test
    @DisplayName("subjectDisplayName: monitör adı > şema-soyulmuş adres; çıplak http subject'e giremez")
    void subjectDisplayName() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("monitor_name", "Sağlık İçerik Kontrolü");
        assertThat(EscalationService.subjectDisplayName(ctx, "http://localhost:8080/health- duplicate"))
                .isEqualTo("Sağlık İçerik Kontrolü");
        assertThat(EscalationService.subjectDisplayName(Map.of(), "https://example.com/x"))
                .isEqualTo("example.com/x");
        assertThat(EscalationService.subjectDisplayName(null, "example.com")).isEqualTo("example.com");
        Map<String, Object> nullish = new HashMap<>();
        nullish.put("monitor_name", "null");
        assertThat(EscalationService.subjectDisplayName(nullish, "http://x.y")).isEqualTo("x.y");
    }
}
