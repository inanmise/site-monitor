package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailTokens;
import com.sitemonitor.service.mail.WeeklyAvailabilityMail;
import com.sitemonitor.service.report.WeeklyOutageReportService.OutageRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Haftalık Erişilebilirlik e-postası — yeniden tasarım (2026-09-28) içerik kilitleri: KPI'lar + geçen haftaya göre
 * değişim, en kötü domain sırası, CANLI taban adresli derin bağlantılar, kaçış, "+M daha" tavanları, düz metin
 * paritesi, sol şerit yokluğu, mobil + karanlık-tuval stil kuralları ve gövde boyutu (Gmail 102 KB kırpması).
 * Veri {@link WeeklyAvailabilitySamples}'tan (alarm tarafı gerçek eşleyiciden geçer).
 */
class WeeklyAvailabilityEmailTest {

    private static final String BASE = "https://sitemonitor.example.com";
    private final AtomicReference<String> liveBase = new AtomicReference<>(BASE + "/");
    private EmailNotificationService svc;

    @BeforeEach
    void setUp() {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getString(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        // CANLI ayar: her okuma o anki değeri döner (Genel Ayarlar'da değiştirilen adres yeniden başlatmasız geçerli).
        lenient().when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> liveBase.get());
        SmtpSettingsService settingsService = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        lenient().when(settingsService.getOrDefaults()).thenReturn(s);
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        svc = new EmailNotificationService(settingsService, mock(SmtpMailService.class), mock(NotificationLogRepository.class), appSettings, tb);
        // Statik @Value yedeği BAŞKA bir adres: bağlantılar bunu değil CANLI ayarı kullanmalı.
        ReflectionTestUtils.setField(svc, "appBaseUrl", "https://static-fallback.example.com");
    }

    private static String text(String html) {
        return MailKit.plainTextFor(html);
    }

    // ── Sözleşme / stil ──────────────────────────────────────────────────────

    @Test
    @DisplayName("her haftalık örnek aile sözleşmesine uyar: sol şerit YOK, ≥12px yazı, ≥44px düğme, tek stil bloğu, düz metin dolu")
    void everyWeeklySampleConforms() {
        for (WeeklyAvailabilitySamples.Case c : WeeklyAvailabilitySamples.all()) {
            String html = c.html(svc);
            assertThat(EmailResponsiveContractTest.violations(html, text(html))).as(c.slug()).isEmpty();
            assertThat(html.toLowerCase()).as(c.slug()).doesNotContain("border-left").doesNotContain("inset 4px");
            assertThat(html).as(c.slug()).doesNotContain("rgba(").doesNotContain("linear-gradient");
        }
    }

    @Test
    @DisplayName("mobil + karanlık tuval kuralları TEK stil bloğunda; kart açık kalır (light-only kilidi); tuval yalnız bu e-postada (opt-in)")
    void mobileAndDarkRules() {
        String html = WeeklyAvailabilitySamples.typical().html(svc);
        assertThat(html).contains("@media only screen and (max-width:" + MailKit.BREAKPOINT + "px)")
                .contains(MailKit.DARK_CANVAS_CSS + "</style>").contains(MailKit.LIGHT_SCHEME_META)
                .contains("class=\"tile\"");                                         // KPI'lar telefonda 2×2
        assertThat(html.split("prefers-color-scheme", -1).length - 1).isEqualTo(1);
        for (EmailSamples.Sample s : new EmailSamples().all()) {
            if (s.slug().startsWith("report-availability")) {
                assertThat(s.html()).as(s.slug()).contains(MailKit.DARK_CANVAS_CSS);
            } else if (s.family().equals("alert") || s.family().equals("resolved")) {
                assertThat(s.html()).as(s.slug()).doesNotContain("prefers-color-scheme");   // opt-in: aile varsayılanı değişmedi
            }
        }
    }

    // ── KPI'lar + hüküm ──────────────────────────────────────────────────────

    @Test
    @DisplayName("KPI'lar: ortalama + geçen haftaya göre değişim, toplam kesinti, etkilenen domain, en yakın sertifika; alarm KPI'ları (MTTR dahil)")
    void kpisPresentAndExact() {
        String html = WeeklyAvailabilitySamples.typical().html(svc);
        String t = text(html);
        assertThat(t).contains("Ort. erişilebilirlik: 99.77% (▲ 0.12 puan · geçen hafta 99.65%)")
                .contains("Toplam kesinti süresi: 4sa 32dk (4 kesinti)")
                .contains("Kesinti yaşayan domain: 2 / 13 (en uzun kesinti 3sa)")
                .contains("En yakın sertifika: 5 gün (" + WeeklyAvailabilitySamples.LONG_HOST + ")")
                .contains("Alarm: 14 (▲ 10 · bu hafta açılan 13 · geçen hafta 3)")
                .contains("Hâlâ açık: 2 (1 alarm önceki haftadan devretti)")
                .contains("Ort. çözüm süresi: 27dk (MTTR · 12 alarm çözüldü)")
                .contains("Etkilenen hedef: 7 (5 izleme türünde)");
        // HTML'de de: değer rengi bant rengiyle, değişim çipi tonlu
        assertThat(html).contains("Ort. erişilebilirlik").contains("99.77%").contains("▲ 0.12 puan")
                .contains("color:" + MailTokens.WARNING + ";word-break:break-word;overflow-wrap:anywhere\">99.77%");
    }

    @Test
    @DisplayName("hüküm yalnız veriden: veri yok / kesintisiz / sağlıklı / dikkat / kritik — rozet + tek cümle")
    void verdictVariants() {
        assertThat(text(WeeklyAvailabilitySamples.noData().html(svc))).contains("[VERİ YOK]").contains("Bu hafta ölçüm verisi yok")
                .doesNotContain("ALARMLAR");
        String clean = WeeklyAvailabilitySamples.clean().html(svc);
        assertThat(text(clean)).contains("[KESİNTİSİZ]").contains("Kesintisiz hafta · 100.00% erişilebilirlik")
                .contains("Bu hafta hiçbir domain kesinti yaşamadı (2 domain ölçüldü).").contains("değişmedi")
                .contains("Bu hafta alarm yok").doesNotContain("En düşük:");   // hepsi %100: en düşük = en yüksek, satır yok
        assertThat(text(WeeklyAvailabilitySamples.typical().html(svc))).contains("[DİKKAT]")
                .contains("En düşük: " + WeeklyAvailabilitySamples.LONG_HOST + " (97.42%) · En yüksek: svc1.example.com (100.00%)")
                .contains("99.77% erişilebilirlik · 4 kesinti, toplam 4sa 32dk")
                .contains("Kesinti yaşayan domain: 2 / 13. En uzun kesinti 3sa — " + WeeklyAvailabilitySamples.LONG_HOST + ". 1 domainde bu hafta ölçüm yok.");
        String bad = WeeklyAvailabilitySamples.bad().html(svc);
        assertThat(text(bad)).contains("[KRİTİK]").contains("98.36% erişilebilirlik").contains("▼ 1.16 puan");
        assertThat(bad).contains("background-color:" + MailTokens.Tone.DESTRUCTIVE.strong + ";border:1px solid "
                + MailTokens.Tone.DESTRUCTIVE.strong);                              // KRİTİK rozeti dolu kırmızı
        List<AvailabilityRow> ok = List.of(new AvailabilityRow("a.example.com", 99.95, 1, 5, 5, 100L, 200L, 90),
                new AvailabilityRow("b.example.com", 100.0, 0, 0, 0, 100L, 200L, 90));
        String healthy = svc.buildWeeklyAvailabilityHtml("Takım A", "W39", ok, WeeklyAvailabilitySamples.summary(ok),
                null, null, null, null, null, null);
        assertThat(text(healthy)).contains("[SAĞLIKLI]").contains("99.98% erişilebilirlik · 1 kesinti, toplam 5dk");
    }

    @Test
    @DisplayName("bağlamsız çağrı (eski imza): alarm/tür bölümleri ve değişim çipi YOK, ek kapsam notu eski metinle")
    void legacyWithoutInsights() {
        WeeklyAvailabilitySamples.Case c = WeeklyAvailabilitySamples.typical();
        String html = svc.buildWeeklyAvailabilityHtml("Takım A", "W39", c.rows(), c.summary(), c.att(), c.ps(), c.dep(), c.weak(), c.dom());
        String t = text(html);
        assertThat(t).doesNotContain("ALARMLAR").doesNotContain("İZLEME TÜRLERİNE GÖRE").doesNotContain("puan")
                .contains("Ek, bu e-postadan DAHA GENİŞ bir kapsamı raporlar")
                .contains("Raporu uygulamada aç: " + BASE + "/?tab=weeklyreports");
    }

    // ── Sıra, tavan, liste ───────────────────────────────────────────────────

    /** Başlığın altındaki "- " satırları (bir sonraki boş satıra kadar). */
    private static List<String> sectionLines(String text, String headingUpper) {
        int i = text.indexOf(headingUpper);
        assertThat(i).as(headingUpper).isGreaterThanOrEqualTo(0);
        List<String> out = new ArrayList<>();
        boolean started = false;
        for (String line : text.substring(i).split("\n")) {
            if (line.startsWith("- ")) { out.add(line); started = true; }
            else if (started && line.isBlank()) break;
        }
        return out;
    }

    @Test
    @DisplayName("en kötü domainler: kesinti yaşayanlar (erişilebilirlik artan) → ölçümsüzler → sorunsuzlar; tavan 10")
    void worstPerformersOrdering() {
        List<String> typical = sectionLines(text(WeeklyAvailabilitySamples.typical().html(svc)), "DOMAİNLER — EN DÜŞÜK ERİŞİLEBİLİRLİK ÜSTTE");
        assertThat(typical).hasSize(10);
        assertThat(typical.get(0)).startsWith("- " + WeeklyAvailabilitySamples.LONG_HOST + " — 97.42% [KESİNTİ]");
        assertThat(typical.get(1)).startsWith("- www.example.com — 99.87% [KESİNTİ]");
        assertThat(typical.get(2)).startsWith("- nodata.example.com — veri yok [VERİ YOK]");
        assertThat(typical.get(3)).startsWith("- svc1.example.com — 100.00% [SORUNSUZ]");

        List<String> large = sectionLines(text(WeeklyAvailabilitySamples.large().html(svc)), "DOMAİNLER — EN DÜŞÜK ERİŞİLEBİLİRLİK ÜSTTE");
        assertThat(large).hasSize(WeeklyAvailabilityMail.TOP_DOMAINS);
        for (int k = 0; k < large.size(); k++) {
            assertThat(large.get(k)).startsWith("- app" + (14 - k) + ".example.com — ");   // 98.18% → 99.35%, artan
        }
    }

    @Test
    @DisplayName("uzun listeler tavanlı, kesilen kısım TÜRÜNE göre sayılıp tam listeye bağlanır (HTML + düz metin)")
    void listCapsWithMore() {
        String html = WeeklyAvailabilitySamples.large().html(svc);
        String t = text(html);
        assertThat(t).contains("+50 domain daha (4 kesintili · 3 ölçümsüz · 43 sorunsuz). Tam liste ekteki PDF'te. Pano: " + BASE + "/?tab=dashboard")
                .contains("+32 alarm daha — tamamı ekteki PDF'te ve Alarm Geçmişi'nde. Alarm Geçmişi: " + BASE
                        + "/?tab=alerthistory&view=all&from=2026-09-21&to=2026-09-27&team=7")
                .contains("+2 sertifika daha. Dikkat gerektiren sertifikalar: " + BASE + "/?tab=warnings");
        assertThat(sectionLines(t, "HAFTANIN ALARMLARI — EN UZUN 8")).hasSize(WeeklyAvailabilityMail.TOP_INCIDENTS);
        assertThat(sectionLines(t, "YAKLAŞAN SERTİFİKA BİTİŞLERİ")).hasSize(WeeklyAvailabilityMail.TOP_CERTS);
        assertThat(html).contains("+50 domain daha (4 kesintili · 3 ölçümsüz · 43 sorunsuz)")
                .contains("href=\"" + BASE + "/?tab=dashboard\"");
        // Tavan altında kalan takımda "+M" satırı çizilmez.
        assertThat(text(WeeklyAvailabilitySamples.clean().html(svc))).doesNotContain(" daha (").doesNotContain("alarm daha");
    }

    @Test
    @DisplayName("haftanın alarmları: haftaya düşen süreye göre, açık/çözüldü rozeti, devreden işareti, not; tür çubukları aşağı yuvarlanır")
    void incidentsAndTypeBars() {
        String html = WeeklyAvailabilitySamples.typical().html(svc);
        List<String> inc = sectionLines(text(html), "HAFTANIN ALARMLARI — EN UZUN 8");
        assertThat(inc.get(0)).isEqualTo("- " + WeeklyAvailabilitySamples.LONG_HOST + " — Sertifika süresi doluyor · Sertifika · Başlangıç 10.09 09:00 "
                + "(önceki haftadan devreden) · Seviye KRİTİK · Süre 18g 1sa 20dk [AÇIK] · Olay: " + BASE + "/?tab=incidents&incident=4105");
        assertThat(inc.get(1)).startsWith("- db.example.com:5432 — Port yanıt vermiyor · Port · Başlangıç 24.09 04:00 · Seviye KRİTİK · Süre 4g 16sa [AÇIK]");
        assertThat(inc.get(2)).contains("[ÇÖZÜLDÜ] · Not: Yük dengeleyicide sertifika zinciri eksikti; yeniden dağıtıldı.");
        assertThat(text(html)).contains("- Port: 96.4% — 4 izleme · 2.688 kontrol · 1 alarm açıldı · 1 şu an açık · alarm süresi 4g 16sa · geçen haftaya göre ▼ 3.5 puan")
                .contains("+6 alarm daha")
                .contains("- Sertifika: 100.0% — 13 izleme · 91 kontrol · 1 şu an açık · alarm süresi 6g 23sa 59dk");   // devreden: bu hafta açılmadı
        assertThat(html).contains("width=\"96%\" height=\"8\" bgcolor=\"" + MailTokens.Tone.DESTRUCTIVE.strong + "\"");   // 96.4 → %96
        assertThat(text(html)).doesNotContain("Keyword:");                          // izlemesi/kontrolü/alarmı olmayan tür çizilmez
    }

    // ── Bağlantılar ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("derin bağlantılar CANLI taban adresten (sondaki / kırpılır, statik yedek kullanılmaz); ayar değişince yeni adres")
    void deepLinksUseLiveBaseUrl() {
        String html = WeeklyAvailabilitySamples.typical().html(svc);
        assertThat(html)
                .contains("href=\"" + BASE + "/?tab=dashboard&amp;domain=www.example.com&amp;open=cert\"")
                .contains("href=\"" + MailKit.esc(MailCta.incidentDetailsUrl(BASE, 4101L)) + "\"")
                .contains("href=\"" + BASE + "/?tab=weeklyreports&amp;w_year=2026&amp;w_week=39&amp;w_team=7\"")
                .contains("href=\"" + BASE + "/?tab=alerthistory&amp;view=all&amp;from=2026-09-21&amp;to=2026-09-27&amp;team=7\"")
                .contains("href=\"" + BASE + "/?tab=admin&amp;g_tab=teams\"")
                .contains("Raporu uygulamada aç").contains("Haftanın alarmlarını aç")
                .doesNotContain("static-fallback.example.com").doesNotContain(BASE + "//?");

        liveBase.set("https://yeni.example.com");
        String after = WeeklyAvailabilitySamples.typical().html(svc);
        assertThat(after).contains("href=\"https://yeni.example.com/?tab=weeklyreports").doesNotContain(BASE);

        liveBase.set("");   // ayar boş → @Value yedeği (testte statik adres) — boş yedekte bağlantı hiç üretilmez
        ReflectionTestUtils.setField(svc, "appBaseUrl", "");
        String none = WeeklyAvailabilitySamples.typical().html(svc);
        assertThat(none).doesNotContain("href=\"/?tab").doesNotContain("Raporu uygulamada aç").doesNotContain("Takım ayarları")
                .contains("www.example.com");
    }

    @Test
    @DisplayName("düz metin paritesi: HTML'deki HER bağlantı ve ana olgular metinde de var")
    void plainTextParity() {
        for (WeeklyAvailabilitySamples.Case c : WeeklyAvailabilitySamples.all()) {
            String html = c.html(svc);
            String t = text(html);
            Set<String> hrefs = new LinkedHashSet<>();
            Matcher m = Pattern.compile("href=\"([^\"]+)\"").matcher(html);
            while (m.find()) hrefs.add(m.group(1).replace("&amp;", "&"));
            for (String h : hrefs) assertThat(t).as("%s: %s", c.slug(), h).contains(h);
            assertThat(t).as(c.slug()).contains("Haftalık erişilebilirlik özeti").contains("Neden bu e-postayı aldınız?");
        }
        assertThat(text(WeeklyAvailabilitySamples.typical().html(svc)))
                .contains("Takım ayarları: " + BASE + "/?tab=admin&g_tab=teams")
                .contains("Ayrıntı: " + BASE + "/?tab=dashboard&domain=www.example.com&open=cert");
    }

    // ── Kaçış ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("her dinamik değer kaçırılır: takım adı, domain, alarm notu, hedef; bağlantıdaki domain URL-kodlanır")
    void escapesEveryDynamicValue() {
        String evilDomain = "<b>x</b>.example.com";
        List<AvailabilityRow> rows = List.of(new AvailabilityRow(evilDomain, 98.0, 1, 30, 30, 100L, 200L, 10));
        List<OutageRow> alarms = List.of(WeeklyAvailabilitySamples.alarm(9001, "http", "HTTP_DOWN", "<i>hedef</i>", "CRITICAL",
                "2026-09-23T06:00:00", "2026-09-23T06:30:00", false, false, 30, 30, "<img src=x onerror=alert(1)> & \"not\""));
        var o = WeeklyAvailabilitySamples.outage(alarms, List.of(), rows, 0);
        var ins = WeeklyAvailabilityReportService.insightsFor(WeeklyAvailabilitySamples.team(), WeeklyAvailabilitySamples.W39, 99.0, o);
        String html = svc.buildWeeklyAvailabilityHtml("Takım <A> & \"B\"", "W39", rows, WeeklyAvailabilitySamples.summary(rows),
                null, null, null, null, null, ins);
        assertThat(html).doesNotContain("<b>x</b>").doesNotContain("<img src=x").doesNotContain("<i>hedef</i>").doesNotContain("Takım <A>")
                .contains("&lt;b&gt;x&lt;/b&gt;.example.com").contains("&lt;img src=x onerror=alert(1)&gt; &amp; &quot;not&quot;")
                .contains("&lt;i&gt;hedef&lt;/i&gt;").contains("Takım &lt;A&gt; &amp; &quot;B&quot;")
                .contains("domain=%3Cb%3Ex%3C%2Fb%3E.example.com");
        assertThat(text(html)).contains("Takım <A> & \"B\"").contains(evilDomain);   // düz metinde olduğu gibi
    }

    // ── Boyut ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gövde boyutu: büyük takım (60 domain, 40 alarm) bile Gmail'in 102 KB kırpma sınırının altında")
    void bodyStaysUnderGmailClip() {
        for (WeeklyAvailabilitySamples.Case c : WeeklyAvailabilitySamples.all()) {
            int bytes = c.html(svc).getBytes(StandardCharsets.UTF_8).length;
            assertThat(bytes).as(c.slug()).isLessThan(95_000);
        }
    }
}
