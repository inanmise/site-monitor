package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailTokens;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Daha önce HTML testi OLMAYAN e-posta türlerinin içerik testleri + e-posta yeniden tasarımında
 * (2026-09-26) düzeltilen kusurların kilitleri: açık ton (konu metninden tahmin yok), iade notunun
 * satır sonları, SMTP test e-postasında tırnak kaçışı, sendHtml hunisinin multipart/alternative gönderimi.
 */
class EmailTypeContentTest {

    private static final String BASE = "https://sitemonitor.example.com";
    private EmailNotificationService svc;
    private SmtpSettingsService settingsService;
    private SmtpMailService smtpMailService;
    private JavaMailSenderImpl sender;

    @BeforeEach
    void setUp() {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getString(any(), any())).thenAnswer(inv -> BASE);
        settingsService = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        s.setFromAddress("noreply@example.com");
        lenient().when(settingsService.getOrDefaults()).thenReturn(s);
        smtpMailService = mock(SmtpMailService.class);
        sender = mock(JavaMailSenderImpl.class);
        lenient().when(smtpMailService.currentSender()).thenReturn(sender);
        lenient().when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", BASE);
        svc = new EmailNotificationService(settingsService, smtpMailService, mock(NotificationLogRepository.class), appSettings, tb);
    }

    // ── Aylık sertifika envanteri ────────────────────────────────────────────

    @Test
    @DisplayName("envanter (2026-09-28): KPI kutuları, ≤7 gün kırmızı hap, bulgu grubu sayısı (+N daha), ek adları AYRI kaçırılır, CTA ?tab=domains")
    void inventoryReport() {
        var cert = new com.sitemonitor.service.mail.CertInventoryMail.Cert("a.example.com", "Takım A", 1, 5, "01.10.2026", "DigiCert Inc", "geçerli");
        var findings = new java.util.ArrayList<com.sitemonitor.service.mail.CertInventoryMail.Finding>();
        for (int i = 0; i < 13; i++) findings.add(new com.sitemonitor.service.mail.CertInventoryMail.Finding("b" + i + ".example.com", "Takım yok"));
        var report = new com.sitemonitor.service.mail.CertInventoryMail.Report("Eylül 2026", "Tüm kurum envanteri", 2, 12, 3, 15,
                new com.sitemonitor.service.mail.CertInventoryMail.Buckets(0, 1, 0, 0, 0, 10, 1), 0, List.of(cert), List.of(),
                List.of(new com.sitemonitor.service.mail.CertInventoryMail.FindingGroup("missing", "Sahipsiz kayıtlar", 13, findings)), 13,
                List.of(), List.of(),
                List.of(new com.sitemonitor.service.mail.CertInventoryMail.Attachment("env<1>.csv", "CSV"),
                        new com.sitemonitor.service.mail.CertInventoryMail.Attachment("env.pdf", "PDF")), null, null);
        String html = svc.buildCertInventoryReportHtml(report);
        assertThat(html).contains(">Toplam kayıt</p>").contains(">15</p>").contains("12 aktif · 3 pasif")
                .contains("a.example.com").contains(">5 gün</span>").contains("color:" + MailTokens.DESTRUCTIVE)   // ≤7 gün kırmızı
                .contains(">Veri yok</p>").doesNotContain("null gün")
                .contains(">Sahipsiz kayıtlar</td>").contains(">13 kayıt</td>").contains("+3 kayıt daha")
                .contains("env&lt;1&gt;.csv").doesNotContain("env<1>.csv").contains(">env.pdf</span>")          // her ad AYRI kaçırılır
                .contains(BASE + "/?tab=domains");
        var clean = new com.sitemonitor.service.mail.CertInventoryMail.Report("Eylül 2026", "Tüm kurum envanteri", 1, 1, 0, 1,
                new com.sitemonitor.service.mail.CertInventoryMail.Buckets(0, 0, 0, 0, 0, 1, 0), 0, List.of(),
                List.of(new com.sitemonitor.service.mail.CertInventoryMail.Cert("a.example.com", "Takım A", 1, 200, "13.04.2027", "DigiCert Inc", "geçerli")),
                List.of(), 0, List.of(), List.of(), List.of(), null, null);
        assertThat(svc.buildCertInventoryReportHtml(clean))
                .contains("Envanterde eksik, hatalı veya güncel olmayan kayıt bulunmadı").doesNotContain("ektedir");
    }

    // ── Alan adı süre bitişi hatırlatması ────────────────────────────────────

    @Test
    @DisplayName("alan adı hatırlatması: kalan gün metriği + eşik; dolmuş kayıt 'gün önce doldu' + KRİTİK; monitör yoksa buton yok")
    void domainReminder() {
        String html = svc.buildDomainExpiryReminderHtml("Kurumsal Site", "example.com", 12, "2026-10-08T00:00:00Z", 14,
                "Örnek Registrar", "WARNING", 17L);
        assertThat(html).contains("example.com").contains(">12<").contains("gün kaldı").contains("08.10.2026")
                .contains("14 gün").contains("Örnek Registrar").contains("UYARI")
                .contains(BASE + "/?tab=domain&amp;monitor=17");
        String expired = svc.buildDomainExpiryReminderHtml("Eski", "old.example.com", -3, "2026-09-23T00:00:00Z", 0, null, "CRITICAL", null);
        assertThat(expired).contains("3 gün önce doldu").contains("KRİTİK").contains("gün önce doldu")
                .doesNotContain("tab=domain&amp;monitor=");
    }

    // ── Alarm fırtınası (HTML) ───────────────────────────────────────────────

    @Test
    @DisplayName("fırtına: sayı + kapsam + hedef listesi + kırpma satırı + olaylar CTA; çözümde hâlâ erişilemeyenler ayrı uyarıyla")
    void stormHtml() {
        List<String> t = new ArrayList<>();
        for (int i = 1; i <= 12; i++) t.add("h" + i + ".example.com");
        String alert = svc.buildStormAlertHtml(40, "Takım A", "Ortak alt ağ", "2026-09-26T07:00:00", t, 28);
        assertThat(alert).contains("40 monitör birden erişilemez").contains("Takım A").contains("Ortak alt ağ")
                .contains("26.09.2026 10:00").contains("h12.example.com").contains("+ 28 monitör daha")
                .contains(BASE + "/?tab=incidents").contains("KRİTİK");
        String rec = svc.buildStormRecoveryHtml(38, 2, "Takım A", "2026-09-26T07:00:00", "2026-09-26T07:48:00", t, 26,
                List.of("h3.example.com", "h7.example.com"));
        assertThat(rec).contains("38 monitör kurtarıldı").contains("48 dakika").contains("Hâlâ erişilemeyen (2)")
                .contains("h7.example.com").contains("ÇÖZÜLDÜ");
        assertThat(svc.buildStormRecoveryHtml(3, 0, "Takım A", "2026-09-26T07:00:00", "2026-09-26T07:05:00", t, 0))
                .doesNotContain("Hâlâ erişilemeyen");
    }

    // ── SMTP test ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SMTP test: sunucu adı TIRNAKLAR dahil kaçırılır (eski esc yalnız & < > kaçırıyordu); düz metin parçası dolu")
    void smtpTestMail() {
        SmtpSettings s = new SmtpSettings();
        s.setHost("smtp\"x'<b>.example.com");
        s.setPort(2525);
        s.setStartTlsEnable(true);
        SmtpMailService smtp = new SmtpMailService(settingsService);
        String html = smtp.buildTestHtml(s);
        assertThat(html).contains("smtp&quot;x&#39;&lt;b&gt;.example.com:2525").doesNotContain("smtp\"x'")
                .contains("STARTTLS").contains("Açık").contains("SMTP ayarlarınız çalışıyor");
        assertThat(MailKit.plainTextFor(html)).contains("Sunucu: smtp\"x'<b>.example.com:2525").contains("STARTTLS: Açık");
    }

    // ── Olay & Hata bildirimi ────────────────────────────────────────────────

    @Test
    @DisplayName("olay: NEW'de çözüldü kutusu yok; SLA ihlali kırmızı; markdown tablo + CID görsel (556px); önizlemede /api URL korunur")
    void incident() {
        Map<String, Object> inc = new LinkedHashMap<>();
        inc.put("title", "Ödeme <kesinti>");
        inc.put("team_name", "Takım A");
        inc.put("severity", "HIGH");
        inc.put("status", "OPEN");
        inc.put("sla_breached", true);
        inc.put("rca_summary", "| A | B |\n| --- | --- |\n| 1 | 2 |");
        inc.put("description", "![g](/api/incidents/images/9)");
        String html = svc.buildIncidentNotificationHtml(inc, "Müdür", "NEW", BASE + "/?tab=incident-history");
        assertThat(html).contains("Yeni Olay Bildirimi").contains("Ödeme &lt;kesinti&gt;").doesNotContain("Ödeme <kesinti>")
                .contains("YÜKSEK").contains("Açık").contains("İHLAL EDİLDİ").doesNotContain("Bu olay çözüldü")
                .contains("<th").contains("cid:incimg9").contains("width=\"556\"")
                .contains("Olay kaydını açmak için tıklayınız");
        String preview = svc.buildIncidentNotificationHtml(inc, "Müdür", "NEW", null, false);
        assertThat(preview).contains("/api/incidents/images/9").doesNotContain("cid:incimg9")
                .doesNotContain("Olay kaydını açmak için");   // CTA URL'siz → buton yok
    }

    // ── Sorun bildirimleri: uygulama hatası / kullanıcı bildirimi / günlük özet ──

    @Test
    @DisplayName("uygulama hatası: referans, kullanıcı (kaçışlı), stack satır sonları korunur, IP/UA görünür")
    void clientErrorReport() {
        String html = svc.buildClientErrorHtml("CER-1", "k<script>", "TypeError: x\n    at A (a.js:1)", "Sekme: X",
                "10.0.0.1", "Mozilla/5.0", "2026-09-26T07:00:00");
        assertThat(html).contains("CER-1").contains("k&lt;script&gt;").doesNotContain("k<script>")
                .contains("TypeError: x<br>    at A (a.js:1)").contains("10.0.0.1").contains("Mozilla/5.0")
                .contains("26.09.2026 10:00");
    }

    @Test
    @DisplayName("kullanıcı sorun bildirimi: önem rozeti (Engelliyor), bağlı çökme kaydı, ekran görüntüsü CID, açıklama satır sonları")
    void userIssueReport() {
        String html = svc.buildUserIssueHtml("USR-1", "kullanici.a", "a@example.com", "BLOCKER", "satır 1\nsatır 2", "HTTP 500",
                "CER-9", "reports", "20.87.0",
                List.of(new EmailNotificationService.InlineImage("shot0", new byte[]{1}, "image/png")),
                "10.0.0.1", "Mozilla/5.0", "2026-09-26T07:00:00");
        assertThat(html).contains("ENGELLİYOR").contains("Engelliyor").contains("CER-9").contains("20.87.0")
                .contains("satır 1<br>satır 2").contains("cid:shot0").contains("Ekran Görüntüleri (1)");
    }

    @Test
    @DisplayName("alan adı aktarım talebi (2026-09-28): rozet ve satır 'Alan adı aktarımı' (ham kod DOMAIN_TRANSFER görünmez), satır adı 'Talep Türü'")
    void userIssueReport_domainTransfer() {
        String html = svc.buildUserIssueHtml("USR-2", "kullanici.a", "a@example.com", "DOMAIN_TRANSFER",
                "Alan adı aktarım talebi" + System.lineSeparator() + "Alan adı: shop.example.com", null, null, "inventory", "20.87.0",
                List.of(), "10.0.0.1", "Mozilla/5.0", "2026-09-28T07:00:00");
        assertThat(html).contains("Alan adı aktarımı").contains("Talep Türü").doesNotContain("DOMAIN_TRANSFER");
    }

    @Test
    @DisplayName("çoklu etki (2026-09-28): 'Yaşanan Sorunlar' satırı Türkçe etiketlerle, kanonik sırada; 'Diğer' metni KAÇIŞLI; etkisiz raporda satır yok")
    void userIssueReport_impacts() {
        String html = svc.buildUserIssueHtml("USR-3", "kullanici.a", "a@example.com", "ANNOYANCE", "Pano yavaş", null,
                null, "dashboard", "20.87.0", List.of(), "10.0.0.1", "Mozilla/5.0", "2026-09-28T07:00:00",
                "LOGIN,SLOW,OTHER", "<b>VPN</b> açıkken");
        assertThat(html).contains("Yaşanan Sorunlar").contains("Giriş yapamıyor / oturumu düşüyor").contains("Uygulama yavaş")
                .contains("Diğer: &lt;b&gt;VPN&lt;/b&gt; açıkken").doesNotContain("<b>VPN</b>").doesNotContain("LOGIN,SLOW");
        assertThat(html.indexOf("Giriş yapamıyor")).isLessThan(html.indexOf("Uygulama yavaş"));
        String plain = svc.buildUserIssueHtml("USR-4", "kullanici.a", "a@example.com", "BLOCKER", "m", null,
                null, null, null, List.of(), "10.0.0.1", "UA", "2026-09-28T07:00:00");
        assertThat(plain).doesNotContain("Yaşanan Sorunlar");
    }

    @Test
    @DisplayName("günlük özet: dönem + sayı + her bildirim satırı (referans / kullanıcı / özet)")
    void issueDigest() {
        String html = svc.buildIssueDigestHtml(List.of(
                Map.of("refCode", "USR-1", "username", "a", "summary", "özet <1>"),
                Map.of("refCode", "USR-2", "username", "b", "summary", "özet 2")), "25.09 – 26.09");
        assertThat(html).contains("25.09 – 26.09 döneminde 2 bildirim alındı").contains("USR-1").contains("USR-2")
                .contains("özet &lt;1&gt;");
    }

    // ── Kusur kilitleri ──────────────────────────────────────────────────────

    @Test
    @DisplayName("sade bildirim tonu SEVİYEDEN gelir: konuda 'KRİTİK' geçse de seviye INFO ise kırmızı değil (eski tahmin yolu)")
    void simpleAlertToneIsExplicit() {
        String info = svc.buildAlertEmailHtml("[Site Monitor] KRİTİK kelimesi geçen bilgi", "mesaj", null, "INFO", null, null, null);
        assertThat(info).doesNotContain(MailTokens.DESTRUCTIVE).contains("BİLGİ");
        String crit = svc.buildAlertEmailHtml("[Site Monitor] sıradan konu", "mesaj", null, "CRITICAL", null, null, null);
        assertThat(crit).contains(MailTokens.DESTRUCTIVE).contains("KRİTİK");
    }

    @Test
    @DisplayName("iade maili: çok satırlı düzeltme notu satır sonlarıyla korunur (eskiden tek paragrafa eziliyordu)")
    void rejectedKeepsLineBreaks() {
        String html = svc.buildWeeklyReportRejectedHtml("Takım A", "2026-W39", "Madde 2 eksik.\n- Olay A\n- Olay B", "PO");
        assertThat(html).contains("Madde 2 eksik.<br>- Olay A<br>- Olay B").contains("Düzeltme notu").contains("PO");
    }

    @Test
    @DisplayName("sendHtml hunisi: çağıran metin vermese de multipart/alternative (text/plain + text/html) gider")
    void sendHtmlIsMultipartAlternative() throws Exception {
        String html = svc.buildWeeklyReportReminderHtml("Takım A", "2026-W39", BASE + "/?tab=weeklyreports");
        svc.sendHtml(new String[]{"a@example.com"}, null, "[Site Monitor] hatırlatma", html, null);
        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(cap.capture());
        MimeMessage msg = cap.getValue();
        msg.saveChanges();
        List<String> types = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        collect(msg, types, plain);
        assertThat(types).anyMatch(t -> t.startsWith("multipart/alternative"))
                .anyMatch(t -> t.startsWith("text/plain")).anyMatch(t -> t.startsWith("text/html"));
        assertThat(plain.toString()).contains("Takım A").contains("Son giriş").contains(BASE + "/?tab=weeklyreports")
                .doesNotContain("<table");
    }

    private static void collect(Part p, List<String> types, StringBuilder plain) throws Exception {
        types.add(p.getContentType().toLowerCase());
        Object c = p.getContent();
        if (c instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), types, plain);
        } else if (p.isMimeType("text/plain") && c instanceof String s) {
            plain.append(s);
        }
    }

    @Test
    @DisplayName("MailKit.esc: beş karakterin hepsi (tek kaçış kaynağı)")
    void escapesAllFive() {
        assertThat(MailKit.esc("<a href='x' title=\"y\">&</a>"))
                .isEqualTo("&lt;a href=&#39;x&#39; title=&quot;y&quot;&gt;&amp;&lt;/a&gt;");
    }

    @Test
    @DisplayName("kayıtlı/yabancı HTML'den türetilen düz metin: mobil kopya, MSO/VML ve önizleme dolgusu metne sızmaz")
    void derivedTextSkipsDuplicates() {
        // Haftalık e-posta 2026-09-28'de yeniden tasarlandı: telefon kopyası (.m-only) ÜRETMEYEN sıralı listeye geçti ve
        // alan adı artık hüküm/KPI/liste/bağlantılarda da geçiyor → eski "≤ 4" sayacı yerine düzenden bağımsız, AYNI
        // derecede sıkı ölçüt (kopya metne HİÇBİR ŞEY eklemez) ve kopyayı GERÇEKTEN üreten aile parçaları: veri tablosu
        // (istif kart kopyası) + VML düğme + önizleme dolgusu.
        String weekly = new EmailSamples().svc.buildWeeklyAvailabilityHtml("Takım A", "W39",
                List.of(new EmailNotificationService.AvailabilityRow("x.example.com", 99.0, 1, 5, 5, 100L, 200L, 30)),
                new EmailNotificationService.AvailabilitySummary(1, 1, 99.0, "x.example.com", 99.0, "x.example.com", 99.0, 1, 30));
        assertThat(MailKit.htmlToText(new String(weekly.toCharArray()))).contains("Takım A").contains("x.example.com")
                .doesNotContain("v:roundrect").doesNotContain("@media");
        String html = com.sitemonitor.service.mail.MailDoc.create("Takım A").preheader("Önizleme satırı").title("Takım A", null)
                .table(List.of(MailKit.Col.of("Alan adı"), MailKit.Col.num("Kalan"), MailKit.Col.opt("Registrar")),
                        List.of(List.of(MailKit.Cell.of("x.example.com"), MailKit.Cell.of("5 gün"), MailKit.Cell.of("Reg X"))))
                .button(BASE + "/?tab=domains", "Aç").html();
        assertThat(html).contains("<!--[if !mso]><!--><div class=\"m-only\"");   // kapı boşa geçmesin: kopya VAR
        String copy = new String(html.toCharArray());   // kayıt dışı (farklı nesne) → türetme yolu
        String derived = MailKit.htmlToText(copy);
        assertThat(derived).contains("Takım A").contains("x.example.com").doesNotContain("v:roundrect").doesNotContain("@media")
                .doesNotContain("Önizleme satırı");
        // Kopyalar elle sökülmüş HTML'in türetilmiş metniyle AYNI sayıda geçer → kopya metne ikinci kez girmedi.
        StringBuilder noMobile = new StringBuilder(html);
        for (int i; (i = noMobile.indexOf("<!--[if !mso]><!--><div class=\"m-only\"")) >= 0; ) {
            int end = noMobile.indexOf("</div><!--<![endif]-->", i);
            noMobile.delete(i, end + "</div><!--<![endif]-->".length());
        }
        int n = derived.split("x\\.example\\.com", -1).length - 1;
        int expected = MailKit.htmlToText(noMobile.toString()).split("x\\.example\\.com", -1).length - 1;
        assertThat(n).isPositive().isEqualTo(expected);
    }
}
