package com.certmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Executive alarm şablonu: severity seçimi, alan doldurma, XSS-escape, plain-text fallback. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailTemplateBuilderTest {

    @Mock AppSettingsService appSettings;
    EmailTemplateBuilder b;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("cert.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
        b = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(b, "appBaseUrl", "http://cm.local");
    }

    private EmailTemplateBuilder.AlertMail domainMail(String level, int days) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("expiry_date", "2026-08-06T12:37:46Z");
        ctx.put("registrar", "GoDaddy.com, LLC");
        ctx.put("source", "RDAP");
        ctx.put("status_codes", "client transfer prohibited, client delete prohibited");
        return new EmailTemplateBuilder.AlertMail("DOMAINMON_EXPIRY", level, "kartfree.com",
                "kartfree.com alan adının kaydı " + days + " gün içinde doluyor.", days, ctx, "SY-Dijital");
    }

    @Test
    @DisplayName("severity: etiket + renk seçimi (KRİTİK/YÜKSEK/ORTA/BİLGİ)")
    void severity() {
        assertThat(EmailTemplateBuilder.severityLabel("CRITICAL")).isEqualTo("KRİTİK");
        assertThat(EmailTemplateBuilder.severityLabel("HIGH")).isEqualTo("YÜKSEK");
        assertThat(EmailTemplateBuilder.severityLabel("WARNING")).isEqualTo("ORTA");
        assertThat(EmailTemplateBuilder.severityLabel("INFO")).isEqualTo("BİLGİ");
        assertThat(b.buildHtml(domainMail("CRITICAL", 3))).contains("#C0392B").contains("KRİTİK");
        assertThat(b.buildHtml(domainMail("HIGH", 25))).contains("#D68910").contains("YÜKSEK");
        assertThat(b.buildHtml(domainMail("WARNING", 45))).contains("#2874A6").contains("ORTA");
    }

    @Test
    @DisplayName("alan doldurma: domain, 56px hero gün, registrar, EPP pill, footer alt-sistem, CTA, 640px, lacivert header")
    void fields() {
        String html = b.buildHtml(domainMail("HIGH", 25));
        assertThat(html).contains("kartfree.com").contains(">25<").contains("gün kaldı");
        assertThat(html).contains("GoDaddy.com, LLC");
        assertThat(html).contains("client transfer prohibited");
        assertThat(html).contains("Alan Adı İzleme");        // footer alt-sistem
        assertThat(html).contains("tab=domain");             // CTA deep-link
        assertThat(html).contains("#0F1B2D");                // koyu-lacivert üst bant
        assertThat(html).contains("width='640'");            // 640px kart
        assertThat(html).contains("Görüntüle");              // CTA (apostrof HTML'de &#39; olarak escape'li)
    }

    @Test
    @DisplayName("XSS: domain + registrar (user-controlled) HTML escape edilir")
    void escape() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("registrar", "<b>x</b><script>alert(1)</script>");
        var m = new EmailTemplateBuilder.AlertMail("DOMAINMON_EXPIRY", "HIGH",
                "<img src=x onerror=alert(1)>.com", "özet", 5, ctx, null);
        String html = b.buildHtml(m);
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).doesNotContain("<img src=x");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&lt;img");
    }

    @Test
    @DisplayName("plain-text fallback: HTML etiketi yok, temel alanlar + CTA URL var")
    void plainText() {
        String text = b.buildText(domainMail("HIGH", 25));
        assertThat(text).doesNotContain("<").doesNotContain(">");
        assertThat(text).contains("[CertMonitor]").contains("YÜKSEK").contains("kartfree.com");
        assertThat(text).contains("GoDaddy.com, LLC");
        assertThat(text).contains("http://cm.local/?tab=domain&domain=kartfree.com");
    }

    @Test
    @DisplayName("insan-okur tarih: UTC ISO → Europe/Istanbul (+3)")
    void humanDate() {
        assertThat(EmailTemplateBuilder.formatHuman("2026-08-06T12:37:46Z")).contains("6 Ağustos 2026 15:37").contains("(GMT+3)");
    }
}
