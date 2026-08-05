package com.sitemonitor.service;

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

/** Executive alarm şablonu: aciliyet skalası, hero sayaç, progress bar, timeline, EPP açıklamaları,
 *  aksiyon adımları, XSS-escape, plain-text paritesi. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailTemplateBuilderTest {

    @Mock AppSettingsService appSettings;
    EmailTemplateBuilder b;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
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

    // ── PAGE_INTEGRITY çözüm maili — "sorun neydi + ne çözüldü" (2026-08-04) ──

    private Map<String, Object> pageResolvedCtx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("url", "https://x.example.com/");
        ctx.put("monitor_id", 55);
        ctx.put("detail", "1 kırık, 1 zaman aşımı, 0 mixed content");
        ctx.put("problem_rows", "LINK\thttps://dead.example.com/welcome\t\nIFRAME\thttps://gtm.example.com/ns.html\t");
        ctx.put("problem_total", 2);
        ctx.put("resolved_page_status", "OK");
        ctx.put("resolved_total_resources", 135);
        ctx.put("resolved_checked_at", "2026-08-04T09:42:00");
        return ctx;
    }

    @Test
    @DisplayName("PAGE_INTEGRITY çözüm HTML'i: sorun detayı + giderilen kaynaklar + güncel durum satırları")
    void resolvedHtml_pageIntegrity_showsProblemAndCurrentState() {
        String html = b.buildResolvedHtml("https://x.example.com/", "PAGE_INTEGRITY",
                "Sistem (otomatik)", "2026-08-04T06:42:00", "2026-08-03T16:35:00", pageResolvedCtx(), "SY-Dijital");
        assertThat(html)
                .contains("Çözülen Alarm").contains("Sayfa Bütünlüğü")
                .contains("1 kırık, 1 zaman aşımı, 0 mixed content")     // sorun neydi
                .contains("Giderilen Sorunlu Kaynaklar")
                .contains("dead.example.com")                             // sorunlu kaynak listesi
                .contains("Sağlıklı")                                     // güncel durum
                .contains("135 kaynağın tümü erişilebilir")
                .contains("bütünlük sorunu giderildi");                   // yeni başlık satırı
    }

    @Test
    @DisplayName("PAGE_INTEGRITY çözüm: ESKİ alarm (snapshot'sız ctx) → sade düzen bozulmaz, sayfa satırları yok")
    void resolvedHtml_pageIntegrity_oldAlert_gracefulFallback() {
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("url", "https://x.example.com/");   // eski snapshot yalnız url/monitor_id taşırdı
        String html = b.buildResolvedHtml("https://x.example.com/", "PAGE_INTEGRITY",
                "Sistem (otomatik)", "2026-08-04T06:42:00", "2026-08-03T16:35:00", bare, "SY-Dijital");
        assertThat(html).contains("Alan Adı").contains("Alarm Süresi").contains("Çözen")
                .doesNotContain("Giderilen Sorunlu Kaynaklar").doesNotContain("Güncel Durum");
    }

    @Test
    @DisplayName("PAGE_INTEGRITY çözüm düz-metni HTML ile aynı bilgiyi taşır")
    void resolvedText_pageIntegrity_parity() {
        String text = b.buildResolvedText("https://x.example.com/", "PAGE_INTEGRITY",
                "Sistem (otomatik)", "2026-08-04T06:42:00", "2026-08-03T16:35:00", pageResolvedCtx());
        assertThat(text)
                .contains("Sorun (alarm anı): 1 kırık, 1 zaman aşımı, 0 mixed content")
                .contains("Giderilen Sorunlu Kaynaklar")
                .contains("dead.example.com")
                .contains("Güncel Durum: Sağlıklı — 135 kaynağın tümü erişilebilir");
    }

    @Test
    @DisplayName("Alarm maili: 'Neden bu e-postayı aldınız?' şeffaflık bloğu + takım adı görünür")
    void alarm_whyReceivingBlock() {
        String html = b.buildHtml(domainMail("HIGH", 20));
        assertThat(html).contains("Neden bu e-postayı aldınız?").contains("SY-Dijital")
                        .contains("yöneticinize başvurun");
    }

    @Test
    @DisplayName("whyReceivingBlock: takım varsa adı; yoksa jenerik ifade (çökme yok)")
    void whyReceivingBlock_teamOrGeneric() {
        assertThat(EmailTemplateBuilder.whyReceivingBlock("SY-Dijital")).contains("SY-Dijital").contains("ekibine");
        assertThat(EmailTemplateBuilder.whyReceivingBlock(null)).contains("Neden bu e-postayı aldınız?")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("severity etiketleri (KRİTİK/YÜKSEK/ORTA/BİLGİ) korunur; rozet metninde görünür")
    void severity() {
        assertThat(EmailTemplateBuilder.severityLabel("CRITICAL")).isEqualTo("KRİTİK");
        assertThat(EmailTemplateBuilder.severityLabel("HIGH")).isEqualTo("YÜKSEK");
        assertThat(EmailTemplateBuilder.severityLabel("WARNING")).isEqualTo("ORTA");
        assertThat(EmailTemplateBuilder.severityLabel("INFO")).isEqualTo("BİLGİ");
        assertThat(b.buildHtml(domainMail("CRITICAL", 3))).contains("KRİTİK");
        assertThat(b.buildHtml(domainMail("HIGH", 25))).contains("YÜKSEK");
    }

    @Test
    @DisplayName("aciliyet skalası: renk kalan günden gelir (35 yeşil, 20 amber, 10 turuncu, 5 kırmızı, 2 koyu+ACİL)")
    void urgencyColors() {
        assertThat(b.buildHtml(domainMail("HIGH", 35))).contains("#1E8449");
        assertThat(b.buildHtml(domainMail("HIGH", 20))).contains("#D68910");
        assertThat(b.buildHtml(domainMail("HIGH", 10))).contains("#CA6F1E");
        assertThat(b.buildHtml(domainMail("HIGH", 5))).contains("#C0392B");
        String urgent = b.buildHtml(domainMail("HIGH", 2));
        assertThat(urgent).contains("#7B241C").contains("ACİL");
        // Gün yoksa severity fallback
        assertThat(EmailTemplateBuilder.urgencyColor(null, "CRITICAL")).isEqualTo("#C0392B");
        assertThat(EmailTemplateBuilder.urgencyColor(null, "WARNING")).isEqualTo("#2874A6");
    }

    @Test
    @DisplayName("alan doldurma: domain, 72px hero sayaç, registrar, footer alt-sistem, CTA, 640px, lacivert header")
    void fields() {
        String html = b.buildHtml(domainMail("HIGH", 25));
        assertThat(html).contains("kartfree.com").contains(">25<").contains("GÜN<br>KALDI").contains("font-size:72px");
        assertThat(html).contains("GoDaddy.com, LLC");
        assertThat(html).contains("client transfer prohibited");
        assertThat(html).contains("Alan Adı İzleme");        // footer alt-sistem
        assertThat(html).contains("tab=domain");             // CTA deep-link
        assertThat(html).contains("#0F1B2D");                // koyu-lacivert üst bant
        assertThat(html).contains("width='640'");            // 640px kart
        assertThat(html).contains("Görüntüle");              // CTA (apostrof HTML'de &#39; olarak escape'li)
        assertThat(html).contains("color-scheme");           // light-only meta
    }

    @Test
    @DisplayName("progress bar: 15 gün → ~%17 dolu td + 'Bitişe 15 gün · Son tarih'; gün yoksa bar yok + tip etiketi")
    void progressBar() {
        String html = b.buildHtml(domainMail("HIGH", 15));
        assertThat(html).contains("width='17%'").contains("Bitişe 15 gün").contains("Son tarih:");
        var noDays = new EmailTemplateBuilder.AlertMail("DOMAINMON_STATUS", "HIGH", "kartfree.com",
                "durum kodu uyarısı", null, new LinkedHashMap<>(), null);
        String html2 = b.buildHtml(noDays);
        assertThat(html2).doesNotContain("Bitişe ").contains("ALAN ADI DURUM UYARISI");
    }

    @Test
    @DisplayName("timeline: İlk Alarm → Bu Hatırlatma (#N) → Son Kontrol → BİTİŞ; veri yoksa render edilmez")
    void timeline() {
        var m = domainMail("HIGH", 15);
        m.ctx().put("first_alert_at", "2026-07-16T20:04:00Z");
        m.ctx().put("realert_count", 2);
        m.ctx().put("checked_at", "2026-07-21T13:50:00Z");
        String html = b.buildHtml(m);
        assertThat(html).contains("İlk Alarm").contains("Bu Hatırlatma (#2)").contains("Son Kontrol").contains("BİTİŞ");
        // Yalnız bitiş tarihi varken (tek nokta) timeline çizilmez
        String bare = b.buildHtml(domainMail("HIGH", 15));
        assertThat(bare).doesNotContain("İlk Alarm").doesNotContain("BİTİŞ");
    }

    @Test
    @DisplayName("EPP: pill + Türkçe açıklama HTML'de; plain-text'te ', ' ayraçlı + parantezli açıklama (bitişik DEĞİL)")
    void eppDescriptions() {
        String html = b.buildHtml(domainMail("HIGH", 25));
        assertThat(html).contains("Transfer kilidi aktif (registrar)").contains("Silme kilidi aktif (registrar)");
        String text = b.buildText(domainMail("HIGH", 25));
        assertThat(text).contains("client transfer prohibited (Transfer kilidi aktif");
        assertThat(text).contains(", client delete prohibited (");             // ayraç regresyon guard'ı
        assertThat(text).doesNotContain("prohibitedclient");                    // eski bitişik yazım hatası
    }

    @Test
    @DisplayName("aksiyon planı: registrar adlı numaralı adımlar; ≤7 gün 'Bugün aksiyon alın' uyarısı")
    void actionSteps() {
        String html = b.buildHtml(domainMail("HIGH", 25));
        assertThat(html).contains("Registrar paneline giriş yapın (GoDaddy.com, LLC)")
                        .contains("en az 1 yıl yenileyin").contains("Auto-renew");
        assertThat(html).doesNotContain("Bugün aksiyon alın");
        String urgent = b.buildHtml(domainMail("HIGH", 5));
        assertThat(urgent).contains("Bugün aksiyon alın");
        String text = b.buildText(domainMail("HIGH", 25));
        assertThat(text).contains("1. Registrar paneline giriş yapın").contains("2. Alan adını en az 1 yıl yenileyin");
    }

    @Test
    @DisplayName("PAGE_INTEGRITY: sayfa aksiyon adımları + sorunlu kaynak listesi; sertifika (CA/PKI) içeriği SIZMAZ")
    void pageIntegrity_actionsAndResources_noCertLeak() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("url", "https://www.akbank.com/");
        ctx.put("page_status", "DEGRADED");
        ctx.put("broken_resources", 3);
        ctx.put("mixed_content_count", 0);
        ctx.put("problem_rows", "BROKEN\thttps://www.akbank.com/x.png\t404\nBROKEN\thttps://www.akbank.com/a.css\t404");
        ctx.put("problem_total", 2);
        var m = new EmailTemplateBuilder.AlertMail("PAGE_INTEGRITY", "HIGH", "https://www.akbank.com/",
                "Sayfada bütünlük sorunu.", null, ctx, "DijitalSY");
        String html = b.buildHtml(m);
        // Sayfa-özel aksiyon + kaynak listesi görünür
        assertThat(html).contains("Sayfa Bütünlüğü İzleme")
                        .contains("Sorunlu Kaynaklar")
                        .contains("x.png")
                        .contains("Bozulmuş");
        // Sertifika aksiyonları ASLA sızmamalı (asıl bug)
        assertThat(html).doesNotContain("CA/PKI").doesNotContain("yeni sertifika talep");
        String text = b.buildText(m);
        assertThat(text).contains("Sorunlu Kaynaklar").doesNotContain("CA/PKI");
    }

    @Test
    @DisplayName("PAGE_INTEGRITY: Mod + Alarm Kapsamı + Doğrulama satırları; sorunlu kaynak ≤10 (overflow); HATA=null gizli")
    void pageIntegrity_enrichedRows_maxTenAndNoNull() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("url", "https://www.akbank.com/");
        ctx.put("page_status", "DEGRADED");
        ctx.put("page_mode", "SITE_CRAWL");
        ctx.put("broken_resources", 12);
        ctx.put("mixed_content_count", 0);
        ctx.put("alert_third_party", false);
        ctx.put("alert_mixed_content", true);
        ctx.put("alert_timeout", true);
        ctx.put("monitor_confirm_attempts", 3);
        ctx.put("monitor_confirm_interval_ms", 30000L);
        ctx.put("error", "null");   // ctx'e sızan literal "null" → HATA satırı GÖRÜNMEMELİ
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 10; i++) rows.append("TIMEOUT\thttps://cdn.example.com/asset-").append(i).append(".js\t\n");
        ctx.put("problem_rows", rows.toString().trim());   // 10 satır gösterilir
        ctx.put("problem_total", 12);                       // toplam 12 → "2 kaynak daha"
        var m = new EmailTemplateBuilder.AlertMail("PAGE_INTEGRITY", "HIGH", "https://www.akbank.com/",
                "Sayfada bütünlük sorunu.", null, ctx, "DijitalSY");
        String html = b.buildHtml(m);
        assertThat(html)
                .contains("Site Tarama")                       // Mod
                .contains("Alarm Kapsamı").contains("Zaman aşımı: İzleniyor")
                .contains("Doğrulama").contains("3 ardışık kontrolde")
                .contains("asset-10.js")                        // 10. gösterilir
                .doesNotContain("asset-11.js")                  // 11. gösterilmez (≤10)
                .contains("toplam 12");                         // overflow özeti
        // "HATA = null" ARTIK YOK (strCtx literal "null"'ı yok sayar) — "Hata" satır etiketi hiç basılmaz
        assertThat(html).doesNotContain(">null<").doesNotContain("Hata");
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
    @DisplayName("plain-text paritesi: HTML yok; sayaç, pencere satırı, adımlar, CTA URL var")
    void plainText() {
        String text = b.buildText(domainMail("HIGH", 25));
        assertThat(text).doesNotContain("<").doesNotContain(">");
        assertThat(text).contains("[Site Monitör] 25 GÜN KALDI").contains("kartfree.com");
        assertThat(text).contains("Bitişe 25 gün / 90 günlük pencere");
        assertThat(text).contains("GoDaddy.com, LLC");
        assertThat(text).contains("Önerilen Aksiyon:");
        assertThat(text).contains("http://cm.local/?tab=domain&domain=kartfree.com");
        // ≤3 gün → ACİL öneki
        assertThat(b.buildText(domainMail("CRITICAL", 2))).contains("ACİL 2 GÜN KALDI");
    }

    @Test
    @DisplayName("sertifika dalı: CN + kısaltılmış SHA-256 parmak izi render edilir")
    void certRows() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("not_after", "2026-08-06T12:37:46Z");
        ctx.put("issuer_cn", "Akbank Internal CA");
        ctx.put("subject", "CN=app.akbank.com");
        ctx.put("fingerprint", "AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12");
        var m = new EmailTemplateBuilder.AlertMail("EXPIRY", "HIGH", "app.akbank.com", "özet", 25, ctx, null);
        String html = b.buildHtml(m);
        assertThat(html).contains("Akbank Internal CA").contains("CN=app.akbank.com")
                        .contains("SHA-256 Parmak İzi").contains("AB12CD34…");
    }

    @Test
    @DisplayName("insan-okur tarih: UTC ISO → Europe/Istanbul (+3); kısa format timeline için")
    void humanDate() {
        assertThat(EmailTemplateBuilder.formatHuman("2026-08-06T12:37:46Z")).contains("6 Ağustos 2026 15:37").contains("(GMT+3)");
        assertThat(EmailTemplateBuilder.formatShort("2026-08-06T12:37:46Z")).contains("6").contains("Ağu");
    }
}
