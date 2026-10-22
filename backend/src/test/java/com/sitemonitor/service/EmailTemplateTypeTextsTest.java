package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tür-özel e-posta metinleri (2026-10-22):
 * <ul>
 *   <li>ALERTS-13 — HTTP alarmı (HTTP_DOWN / HTTP_SLOW / HTTP_SSL) sertifika şablonuyla çiziliyordu: "Sertifika İzleme"
 *       başlığı, sertifika satırları, "CA/PKI ekibinden yeni sertifika talep edin".</li>
 *   <li>ALERTS-15 — her DOMAINMON_* alarmı "alan adını yenileyin" diyordu; kara listeden çıkış / kilit / durum çözümü ve elle
 *       kapanış "Alan adı yenilendi … otomatik olarak kapandı" + "Yeni Bitiş Tarihi" yazıyordu.</li>
 * </ul>
 */
class EmailTemplateTypeTextsTest {

    EmailTemplateBuilder b;

    @BeforeEach
    void setUp() {
        AppSettingsService app = mock(AppSettingsService.class);
        when(app.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
        b = new EmailTemplateBuilder(app);
        ReflectionTestUtils.setField(b, "appBaseUrl", "http://cm.local");
    }

    private static Map<String, Object> httpCtx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("url", "https://shop.example.com/health");
        c.put("monitor_id", 42);
        c.put("http_status", 503);
        c.put("last_error", "Service Unavailable");
        c.put("checked_at", "2026-10-22T06:00:00");
        return c;
    }

    private EmailTemplateBuilder.AlertMail mail(String type, String level, Map<String, Object> ctx) {
        return new EmailTemplateBuilder.AlertMail(type, level, "shop.example.com", "KRİTİK: test iletisi.", null, ctx, "Takım A");
    }

    @Test
    @DisplayName("ALERTS-13: HTTP_DOWN e-postası HTTP şablonuyla — sertifika başlığı / satırları / CA-PKI adımı YOK, HTTP durumu + hata VAR")
    void httpDownUsesHttpTemplate() {
        String html = b.buildHtml(mail("HTTP_DOWN", "CRITICAL", httpCtx()));
        String text = b.buildText(mail("HTTP_DOWN", "CRITICAL", httpCtx()));
        for (String s : new String[]{html, text}) {
            assertThat(s).doesNotContain("Sertifika İzleme").doesNotContain("CA/PKI").doesNotContain("Veren Kurum")
                    .contains("HTTP/Website İzleme").contains("HTTP Durumu").contains("503").contains("Service Unavailable");
        }
        assertThat(html).contains("tab=http").contains("monitor=42");
        assertThat(EmailTemplateBuilder.isCert("HTTP_DOWN")).isFalse();
        assertThat(EmailTemplateBuilder.isCert("HTTP_SLOW")).isFalse();
        assertThat(EmailTemplateBuilder.isCert("HTTP_SSL")).isFalse();
        assertThat(EmailTemplateBuilder.isCert("EXPIRY")).isTrue();
    }

    @Test
    @DisplayName("ALERTS-13: HTTP_SLOW yanıt süresi + eşik satırı ve kendi adımları; HTTP_SSL sertifika adımı (yalnız orada)")
    void httpSlowAndSsl() {
        Map<String, Object> slow = httpCtx();
        slow.put("response_ms", 4200);
        slow.put("threshold_ms", 2000);
        String s = b.buildHtml(mail("HTTP_SLOW", "HIGH", slow));
        assertThat(s).contains("Yanıt Süresi").contains("4200 ms").contains("2000 ms").doesNotContain("CA/PKI");
        Map<String, Object> ssl = httpCtx();
        ssl.put("ssl_days_remaining", 5);
        String t = b.buildText(mail("HTTP_SSL", "HIGH", ssl));
        assertThat(t).contains("CA/PKI").contains("HTTP/Website İzleme").doesNotContain("— Site Monitor Sertifika İzleme");
    }

    @Test
    @DisplayName("ALERTS-13: HTTP çözüm postası 'Sertifika yenilendi' demez")
    void httpResolvedNoCertWording() {
        Map<String, Object> c = httpCtx();
        c.put("not_after", "2027-01-01T00:00:00Z");
        String html = b.buildResolvedHtml("shop.example.com", "HTTP_DOWN", "Sistem (otomatik)", "2026-10-22T07:00:00",
                "2026-10-22T06:00:00", c);
        assertThat(html).doesNotContain("Sertifika yenilendi").contains("Adres yeniden yanıt veriyor");
    }

    private static Map<String, Object> domainCtx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("expiry_date", "2027-08-06T12:37:46Z");
        c.put("registrar", "GoDaddy.com, LLC");
        c.put("status_codes", "client transfer prohibited");
        return c;
    }

    @Test
    @DisplayName("ALERTS-15: kara liste / transfer kilidi / durum / veri / değişiklik alarmı 'alan adını yenileyin' demez; süre bitişi der")
    void domainActionStepsByType() {
        for (String type : new String[]{"DOMAINMON_BLACKLIST", "DOMAINMON_TRANSFER_LOCK", "DOMAINMON_STATUS",
                "DOMAINMON_UNKNOWN", "DOMAINMON_CHANGED"}) {
            String html = b.buildHtml(new EmailTemplateBuilder.AlertMail(type, "HIGH", "kartfree.com", "test", null, domainCtx(), "Takım A"));
            assertThat(html).as(type).doesNotContain("en az 1 yıl yenileyin").doesNotContain("Auto-renew");
        }
        assertThat(b.buildHtml(new EmailTemplateBuilder.AlertMail("DOMAINMON_BLACKLIST", "HIGH", "kartfree.com", "x", null, domainCtx(), null)))
                .contains("DNSBL");
        assertThat(b.buildHtml(new EmailTemplateBuilder.AlertMail("DOMAINMON_TRANSFER_LOCK", "HIGH", "kartfree.com", "x", null, domainCtx(), null)))
                .contains("clientTransferProhibited");
        assertThat(b.buildText(new EmailTemplateBuilder.AlertMail("DOMAINMON_EXPIRY", "HIGH", "kartfree.com", "x", 12, domainCtx(), null)))
                .contains("en az 1 yıl yenileyin");
    }

    @Test
    @DisplayName("ALERTS-15: kara listeden çıkış 'yenilendi' / 'Yeni Bitiş Tarihi' demez; süre bitişinin OTOMATİK kapanışı der; ELLE kapanış 'elle kapatıldı'")
    void resolvedHeadlineByTypeAndResolver() {
        String bl = b.buildResolvedHtml("kartfree.com", "DOMAINMON_BLACKLIST", "Sistem (otomatik)", "2026-10-22T07:00:00",
                "2026-10-20T06:00:00", domainCtx());
        assertThat(bl).doesNotContain("yenilendi").doesNotContain("Yeni Bitiş Tarihi").doesNotContain("Yeni bitiş tarihi")
                .contains("kara listede görünmüyor");
        String exp = b.buildResolvedHtml("kartfree.com", "DOMAINMON_EXPIRY", "Sistem (otomatik)", "2026-10-22T07:00:00",
                "2026-10-20T06:00:00", domainCtx());
        assertThat(exp).contains("Alan adı yenilendi").contains("Yeni Bitiş Tarihi");
        String manual = b.buildResolvedHtml("kartfree.com", "DOMAINMON_EXPIRY", "Ayşe Yılmaz", "2026-10-22T07:00:00",
                "2026-10-20T06:00:00", domainCtx());
        assertThat(manual).contains("Alarm elle kapatıldı.").doesNotContain("otomatik olarak kapandı")
                .doesNotContain("Yeni Bitiş Tarihi").contains("Ayşe Yılmaz");
        String manualText = b.buildResolvedText("kartfree.com", "DOMAINMON_EXPIRY", "Ayşe Yılmaz", "2026-10-22T07:00:00",
                "2026-10-20T06:00:00", domainCtx());
        assertThat(manualText).contains("Alarm elle kapatıldı.").doesNotContain("Yeni Bitiş Tarihi");
        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("not_after", "2027-01-01T00:00:00Z");
        assertThat(b.buildResolvedHtml("a.example.com", "CHAIN_BROKEN", null, "2026-10-22T07:00:00", "2026-10-20T06:00:00", cert))
                .contains("Sertifika zinciri düzeldi").doesNotContain("Sertifika yenilendi");
        assertThat(b.buildResolvedHtml("a.example.com", "EXPIRY", null, "2026-10-22T07:00:00", "2026-10-20T06:00:00", cert))
                .contains("Sertifika yenilendi");
    }

    @Test
    @DisplayName("ALERTS-15: elle / sistem kapanışı ayrımı — 'Sistem (…)' / 'system' / boş = otomatik, kişi adı = elle")
    void manualResolveRule() {
        assertThat(EmailTemplateBuilder.manualResolve(null)).isFalse();
        assertThat(EmailTemplateBuilder.manualResolve(" ")).isFalse();
        assertThat(EmailTemplateBuilder.manualResolve("Sistem (izleme silindi)")).isFalse();
        assertThat(EmailTemplateBuilder.manualResolve("system")).isFalse();
        assertThat(EmailTemplateBuilder.manualResolve("Ayşe Yılmaz")).isTrue();
        assertThat(EmailTemplateBuilder.resolvedHeadline("DOMAINMON_CHANGED", true, "N12345")).isEqualTo("Alarm elle kapatıldı.");
        // doğrulanmamış sistem kapanışı (ayar kapatıldı, domain değişti …) "düzeldi / yenilendi" demez
        assertThat(EmailTemplateBuilder.verifiedRecovery("Sistem (otomatik)")).isTrue();
        assertThat(EmailTemplateBuilder.verifiedRecovery(null)).isTrue();
        assertThat(EmailTemplateBuilder.verifiedRecovery("Sistem (domain değişti)")).isFalse();
        assertThat(EmailTemplateBuilder.resolvedHeadline("HTTP_DOWN", false,
                "Sistem (alarm türü ayardan kapatıldı — sorun sürüyor olabilir)")).isEqualTo("Alarm otomatik olarak kapandı.");
        assertThat(EmailTemplateBuilder.resolvedHeadline("DOMAINMON_EXPIRY", true, "Sistem (domain değişti)"))
                .isEqualTo("Alarm otomatik olarak kapandı.");
        assertThat(EmailTemplateBuilder.resolvedHeadline("DOMAINMON_EXPIRY", true, "Sistem (otomatik)"))
                .isEqualTo("Alan adı yenilendi, alarm otomatik olarak kapandı.");
    }
}
