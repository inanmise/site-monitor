package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.mail.CertInventoryMail;
import com.sitemonitor.service.mail.CertInventoryMail.Attachment;
import com.sitemonitor.service.mail.CertInventoryMail.Breakdown;
import com.sitemonitor.service.mail.CertInventoryMail.Buckets;
import com.sitemonitor.service.mail.CertInventoryMail.Cert;
import com.sitemonitor.service.mail.CertInventoryMail.Finding;
import com.sitemonitor.service.mail.CertInventoryMail.FindingGroup;
import com.sitemonitor.service.mail.CertInventoryMail.Report;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailTokens;
import com.sitemonitor.service.report.CertInventorySamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * AYLIK SERTİFİKA ENVANTERİ e-postası (yeniden tasarım 2026-09-28) — içerik, sıralama, derin bağlantı (CANLI taban
 * adres), kaçış, liste tavanı, düz metin paritesi, sözleşme ve boyut bütçesi. Veri gerçek özetleyiciden geçer
 * ({@link CertInventorySamples}); servis {@code EmailNotificationService} üzerinden çağrılır (canlı taban adres yolu).
 */
class CertInventoryMailTest {

    /** CANLI ayar (sondaki bölüyle) — @Value yedeğinden ({@link #STATIC}) farklı: bağlantı canlıdan gelmeli. */
    private static final String LIVE = "https://live.example.com/";
    private static final String BASE = "https://live.example.com";
    private static final String STATIC = "https://static.example.com";

    private AppSettingsService appSettings;
    private EmailNotificationService svc;

    @BeforeEach
    void setUp() {
        appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getString(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenReturn(LIVE);
        SmtpSettingsService settings = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        lenient().when(settings.getOrDefaults()).thenReturn(s);
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", STATIC);
        svc = new EmailNotificationService(settings, mock(SmtpMailService.class), mock(NotificationLogRepository.class), appSettings, tb);
        ReflectionTestUtils.setField(svc, "appBaseUrl", STATIC);
    }

    private String html(Report r) {
        return svc.buildCertInventoryReportHtml(r);
    }

    // ── Başlık / KPI ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("başlık: hüküm satırı (başlık + önizleme), durum rozeti, kapsam notu — sayılar özetten")
    void headerVerdict() {
        String h = html(CertInventorySamples.large());
        String verdict = "412 aktif sertifika · 2 tanesinin süresi dolmuş · 36 tanesi 30 gün içinde bitiyor · 31 hijyen bulgusu";
        assertThat(h).contains("Eylül 2026 · Sertifika Envanteri").contains(verdict)
                .contains("AKSİYON GEREKLİ").contains("Tüm kurum envanteri")
                .contains("426 kayıt (412 aktif, 14 pasif; silinmişler hariç) · 14 sahip takım");
        // önizleme (preheader) aynı hükmü taşır
        assertThat(h).contains("class=\"preheader\"").contains("Eylül 2026 · " + verdict);
        assertThat(html(CertInventorySamples.quiet())).contains("SORUNSUZ").doesNotContain("AKSİYON GEREKLİ");
    }

    @Test
    @DisplayName("KPI: toplam, 30/14/7 gün (kümülatif), süresi dolmuş, hata, veri yok, hijyen + geçen aya göre değişim çipi")
    void kpis() {
        String h = html(CertInventorySamples.large());
        assertThat(kpi(h, "Toplam kayıt")).isEqualTo("426");
        assertThat(kpi(h, "30 gün içinde")).isEqualTo("36");
        assertThat(kpi(h, "14 gün içinde")).isEqualTo("8");
        assertThat(kpi(h, "7 gün içinde")).isEqualTo("3");
        assertThat(kpi(h, "Süresi dolmuş")).isEqualTo("2");
        assertThat(kpi(h, "Hata / erişilemez")).isEqualTo("4");
        assertThat(kpi(h, "Veri yok")).isEqualTo("7");
        assertThat(kpi(h, "Hijyen bulgusu")).isEqualTo("31");
        assertThat(h).contains("▲ 8 · geçen ay 418").contains("▼ 4 · geçen ay 35")
                .contains("412 aktif · 14 pasif").contains("5 grupta");
        // 7 gün içindeki dolu değer kırmızı; sıfır değer ikincil renkte
        assertThat(h).contains("color:" + MailTokens.Tone.DESTRUCTIVE.strong + ";word-break:break-word;overflow-wrap:anywhere\">3</p>");
        String quiet = html(CertInventorySamples.quiet());
        assertThat(quiet).contains("color:" + MailTokens.MUTED + ";word-break:break-word;overflow-wrap:anywhere\">0</p>")
                .contains("değişmedi · geçen ay 3");
        // geçen ay kaydı yoksa çip YOK (uydurma karşılaştırma yok)
        assertThat(html(CertInventorySamples.medium())).doesNotContain("geçen ay");
    }

    private static String kpi(String html, String label) {
        Matcher m = Pattern.compile(Pattern.quote(">" + label + "</p>") + "<p [^>]*>([^<]*)</p>").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    // ── Önümüzdeki 30 gün ────────────────────────────────────────────────────

    @Test
    @DisplayName("Önümüzdeki 30 gün: süresi dolmuş en üstte, artan kalan gün; aciliyet hapları; kurum saatinde bitiş tarihi")
    void upcomingOrderingAndBadges() {
        String h = html(CertInventorySamples.large());
        int legacy = h.indexOf("domain=legacy.example.com"), longHost = h.indexOf("domain=" + CertInventorySamples.LONG_HOST),
                odeme = h.indexOf("domain=odeme.example.com"), www = h.indexOf("domain=www.example.com"),
                api = h.indexOf("domain=api.example.com"), portal = h.indexOf("domain=portal.example.com");
        assertThat(legacy).isPositive();
        assertThat(legacy).isLessThan(longHost);
        assertThat(longHost).isLessThan(odeme);
        assertThat(odeme).isLessThan(www);
        assertThat(www).isLessThan(api);
        assertThat(api).isLessThan(portal);
        assertThat(h).contains(">9 gün önce doldu</span>").contains(">bugün bitiyor</span>").contains(">5 gün</span>");
        // dolmuş = DOLU kırmızı hap (beyaz yazı), ≤7 gün = tonlu kırmızı, 8–30 = amber
        assertThat(h).contains("background-color:" + MailTokens.Tone.DESTRUCTIVE.strong + ";border:1px solid " + MailTokens.Tone.DESTRUCTIVE.strong)
                .contains("color:" + MailTokens.Tone.WARNING.text + ";white-space:nowrap\">9 gün</span>");
        // www.example.com 22:30 UTC'de bitiyor → kurum gününde (UTC+3) ertesi gün
        assertThat(h).contains("01.10.2026");
        assertThat(h).contains("Sertifika").contains(">Takım</td>").contains(">Bitiş</td>").contains(">Kalan</td>");
    }

    @Test
    @DisplayName("tavan: 30 gün penceresi 20 satır + '+N daha' (Vade Takvimi bağlantısı); acil liste kırpılınca 30+ gün listesi YOK")
    void capsAndBudget() {
        String big = html(CertInventorySamples.large());
        assertThat(count(big, "class=\"col-opt nw\"")).isEqualTo(CertInventoryMail.UPCOMING_CAP);
        assertThat(big).contains("+18 sertifika daha bu pencerede — tamamı ekteki PDF raporunda ve ")
                .contains(BASE + "/?tab=forecast").doesNotContain("Sıradaki yenilemeler");
        // orta envanter: 6 acil (tavan altı → '+N' yok) + kalan bütçeden 8 sıradaki yenileme
        String mid = html(CertInventorySamples.medium());
        assertThat(mid).doesNotContain("sertifika daha bu pencerede").contains("Sıradaki yenilemeler")
                .contains("30 günden sonra bitecek en yakın 8 sertifika").contains("+1 sertifika daha — tamamı ekteki dosyalarda.");
        assertThat(count(mid, "class=\"col-opt nw\"")).isEqualTo(6 + 8);
        // sakin ay: acil yok → başarı kutusu + en yakın bitiş; sıradaki yenilemeler görünür
        String quiet = html(CertInventorySamples.quiet());
        assertThat(quiet).contains("Önümüzdeki 30 gün içinde süresi dolacak sertifika yok.")
                .contains("En yakın bitiş: api.example.com — 143 gün kaldı").contains("Sıradaki yenilemeler");
    }

    // ── Bağlantılar ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("derin bağlantılar CANLI taban adresten (sondaki bölü atılır; @Value yedeği kullanılmaz); CTA + ayarlar bağlantısı")
    void deepLinksUseLiveBaseUrl() {
        String h = html(CertInventorySamples.large());
        assertThat(h).contains("href=\"" + BASE + "/?tab=dashboard&amp;domain=www.example.com&amp;open=cert\"")
                .contains("href=\"" + BASE + "/?tab=domains\"").contains("Envanteri uygulamada aç")
                .contains("href=\"" + BASE + "/?tab=forecast\"")
                .contains("href=\"" + BASE + "/?tab=settings&amp;sec=certinvreport\"")
                .doesNotContain(STATIC).doesNotContain("example.com//?");
        // hijyen: sağlık/hata grubundaki kayıt da sertifika penceresine bağlanır
        assertThat(h).contains("domain=err2.example.com&amp;open=cert");
    }

    @Test
    @DisplayName("taban adres şemasızsa/boşsa göreli (kırık) bağlantı ÜRETİLMEZ — alan adları düz metin, buton yok")
    void noSchemeNoLinks() {
        lenient().when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenReturn("  ");
        ReflectionTestUtils.setField(svc, "appBaseUrl", "");
        String h = html(CertInventorySamples.large());
        assertThat(h).doesNotContain("open=cert").doesNotContain("?tab=domains").doesNotContain("class=\"btn-full\"")
                .contains("www.example.com");
    }

    // ── Kaçış ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("her dinamik değer kaçırılır: takım adı, hata metni, sağlayıcı, ek adı")
    void escaping() {
        String h = html(CertInventorySamples.large());
        assertThat(h).contains("Takım L &lt;Ödeme &amp; Kart&gt;").doesNotContain("<Ödeme & Kart>")
                .contains("&lt;b&gt;PKIX&lt;/b&gt;").doesNotContain("<b>PKIX</b>")
                .contains("Let&#39;s Encrypt");
        Report r = CertInventorySamples.quiet().withAttachments(List.of(new Attachment("rapor<x>.csv", "a & b")));
        assertThat(html(r)).contains("rapor&lt;x&gt;.csv").contains("a &amp; b").doesNotContain("rapor<x>.csv");
    }

    // ── Hijyen ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hijyen: grup başına sayı rozeti + açıklama + önerilen aksiyon + bütçeli örnek (5 grupta 6) + '+N kayıt daha' (tamamı PDF'te)")
    void hygieneGroups() {
        String h = html(CertInventorySamples.large());
        assertThat(h).contains(">Sorumlu ekip bilgisi eksik</td>").contains(">14 kayıt</td>")
                .contains(CertInventoryMail.explain("contacts")).contains("Önerilen:</strong> " + CertInventoryMail.action("contacts"))
                .contains("host006.example.com").doesNotContain("host007.example.com").doesNotContain("host012.example.com")
                .contains("+8 kayıt daha — tamamı ekteki PDF raporunda.");
        assertThat(h).contains("31 bulgu · 5 grup · her grupta en fazla 6 kayıt listelenir");
        // grup sayısı azsa önceki rapor gibi 10'ar; çoksa bütçe paylaşılır ama 5'in altına inmez
        assertThat(List.of(CertInventoryMail.sampleCap(1), CertInventoryMail.sampleCap(3), CertInventoryMail.sampleCap(4),
                CertInventoryMail.sampleCap(5), CertInventoryMail.sampleCap(9))).containsExactly(10, 10, 7, 6, 5);
        String clean = html(CertInventorySamples.quiet());
        assertThat(clean).contains("Envanterde eksik, hatalı veya güncel olmayan kayıt bulunmadı.");
    }

    // ── Kırılım / ekler / boş envanter ───────────────────────────────────────

    @Test
    @DisplayName("kırılım: takımlar aciliyete göre ilk 8 (+N takım daha), sağlayıcılar paya göre; ekler kartı + dosya açıklamaları")
    void breakdownsAndAttachments() {
        String h = html(CertInventorySamples.large());
        assertThat(h).contains("Takıma göre").contains("+5 takım daha").contains("Sağlayıcıya göre").contains("DigiCert Inc");
        assertThat(h).contains("Ekteki dosyalar").contains(">2 dosya</td>").contains("sertifika-envanteri-2026-09.csv")
                .contains("sertifika-envanteri-2026-09.pdf").contains("Envanterin tamamı ektedir");
        String empty = html(CertInventorySamples.empty());
        assertThat(empty).contains("Envanterde aktif sertifika kaydı yok.").doesNotContain("ektedir")
                .doesNotContain("Önümüzdeki 30 gün").doesNotContain("Takıma göre");
    }

    // ── Düz metin paritesi ───────────────────────────────────────────────────

    @Test
    @DisplayName("düz metin: HTML ile aynı olgular ve bağlantılar (hüküm, KPI, acil satırlar + URL, hijyen, ekler, CTA, neden)")
    void plainTextParity() {
        String h = html(CertInventorySamples.large());
        String t = MailKit.plainTextFor(h);
        assertThat(t).contains("412 aktif sertifika · 2 tanesinin süresi dolmuş · 36 tanesi 30 gün içinde bitiyor")
                .contains("Toplam kayıt: 426 (▲ 8 · geçen ay 418 · 412 aktif · 14 pasif)")
                .contains("7 gün içinde: 3")
                .contains("- legacy.example.com — 9 gün önce doldu · Bitiş: ")
                .contains("  " + BASE + "/?tab=dashboard&domain=legacy.example.com&open=cert")
                .contains("- www.example.com — 5 gün kaldı · Bitiş: 01.10.2026")
                .contains("+18 sertifika daha bu pencerede")
                .contains("Sorumlu ekip bilgisi eksik (14)").contains("Önerilen: ").contains("+8 kayıt daha")
                .contains("Takım: Takım L <Ödeme & Kart>").contains(" · Aktif: ")
                .contains("sertifika-envanteri-2026-09.pdf: Özet")
                .contains("Envanteri uygulamada aç: " + BASE + "/?tab=domains")
                .contains("Neden bu e-postayı aldınız?").contains(BASE + "/?tab=settings&sec=certinvreport")
                .contains("Dağılım: Süresi dolmuş 2 (<%1)")
                .doesNotContain("<table").doesNotContain("<td").doesNotContain("&amp;");
    }

    // ── Sözleşme / duyarlılık / boyut ────────────────────────────────────────

    @Test
    @DisplayName("sözleşme: sol şerit YOK, tek stil bloğu; mobil (620px) + koyu tuval medya kuralları VAR; mobil kopyalar Outlook'tan gizli")
    void contractMobileDark() {
        for (Report r : List.of(CertInventorySamples.large(), CertInventorySamples.medium(), CertInventorySamples.quiet(), CertInventorySamples.empty())) {
            String h = html(r);
            assertThat(EmailResponsiveContractTest.violations(h, MailKit.plainTextFor(h))).isEmpty();
            assertThat(h.toLowerCase()).doesNotContain("border-left");
            assertThat(h).contains("@media only screen and (max-width:" + MailKit.BREAKPOINT + "px)")
                    .contains("@media (prefers-color-scheme:dark)").contains(MailKit.LIGHT_SCHEME_META)
                    .contains("max-width:" + MailTokens.WIDTH_WIDE + "px");
        }
        String h = html(CertInventorySamples.large());
        // ikincil sütunlar telefonda gizlenir, içerikleri m-only satırına iner (Outlook'a gitmez)
        assertThat(h).contains("<td class=\"col-opt\" valign=\"top\"")
                .contains("<!--[if !mso]><!--><div class=\"m-only\" style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;");
    }

    @Test
    @DisplayName("boyut bütçesi: büyük örnek TAM tavanlarla ≤ 95 KB; en kötü durumda kurucu tavanları sıkılaştırır — Gmail kırpmaz, '+N daha' doğru kalır")
    void sizeBudget() {
        String big = html(CertInventorySamples.large());
        assertThat(big.getBytes(StandardCharsets.UTF_8).length).as("büyük örnek bayt").isLessThanOrEqualTo(CertInventoryMail.MAX_BYTES);
        assertThat(count(big, "class=\"col-opt nw\"")).as("büyük örnek tam düzeyde").isEqualTo(CertInventoryMail.UPCOMING_CAP);

        String w = html(worstCase());
        assertThat(w.getBytes(StandardCharsets.UTF_8).length).as("en kötü durum bayt").isLessThanOrEqualTo(CertInventoryMail.MAX_BYTES);
        int rows = count(w, "class=\"col-opt nw\"");
        assertThat(rows).as("sıkılaştırılmış satır").isLessThan(CertInventoryMail.UPCOMING_CAP).isPositive();
        assertThat(w).contains("+" + (60 - rows) + " sertifika daha bu pencerede")
                .contains("Neden bu e-postayı aldınız?").contains("Envanteri uygulamada aç").contains("Ekteki dosyalar");
        assertThat(MailKit.plainTextFor(w)).contains("+" + (60 - rows) + " sertifika daha bu pencerede");
    }

    /** Her tavanı dolduran, her alanı uzun en kötü rapor. */
    private static Report worstCase() {
        String pad = "a-very-long-label-segment-for-stress-testing-purposes.internal.example.com";
        List<Cert> up = new ArrayList<>(), later = new ArrayList<>();
        for (int i = 0; i < 60; i++) up.add(new Cert("h" + i + "-" + pad, "Uzun Takım Adı Numara " + i + " — Ödeme Ağ Geçidi", 1, i % 31 - 1,
                "01.10.2026", "Kurumsal İç Sertifika Otoritesi Numara " + i, "zincir kırık"));
        for (int i = 0; i < 400; i++) later.add(new Cert("l" + i + "-" + pad, "Takım", 2, 31 + i, "01.12.2026", "DigiCert Inc", "geçerli"));
        List<FindingGroup> groups = new ArrayList<>();
        String[] keys = { "missing", "contacts", "stale", "error", "health" };
        String longDetail = "javax.net.ssl.SSLHandshakeException: PKIX path building failed: unable to find valid certification path to requested target — ".repeat(3);
        for (String k : keys) {
            List<Finding> f = new ArrayList<>();
            for (int i = 0; i < 60; i++) f.add(new Finding("f" + i + "-" + pad, longDetail));
            groups.add(new FindingGroup(k, "Grup " + k, 60, f));
        }
        List<Breakdown> teams = new ArrayList<>(), cas = new ArrayList<>();
        for (int i = 0; i < 40; i++) teams.add(new Breakdown("Uzun Takım Adı Numara " + i + " — Ödeme Ağ Geçidi", 50, 5, 1));
        for (int i = 0; i < 20; i++) cas.add(new Breakdown("Kurumsal İç Sertifika Otoritesi Numara " + i, 50, 5, 1));
        return new Report("Eylül 2026", "Tüm kurum envanteri", 60, 2000, 100, 2100,
                new Buckets(40, 60, 60, 60, 300, 1400, 80), 30, up, later, groups, 300, teams, cas,
                CertInventorySamples.attachments(2100), 2080, 310);
    }

    private static int count(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
}
