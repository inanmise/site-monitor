package com.sitemonitor.service.mail;

import com.sitemonitor.service.techerror.TechErrorCalc;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorTestTemplates;
import com.sitemonitor.service.techerror.TechErrorValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teknik hata raporu e-postası — yeniden tasarım (2026-10-17, S5): 1000 px kart, lacivert kapak bandı, PDF'teki bölümler
 * gövdede şablon sırasıyla, görseller CID gömülü (≤ 1000 px) ya da "PDF ekinde" kutusu, telefonda tek sütun.
 */
class TechErrorReportMailTest {

    static Map<String, TechErrorPdfInput.Image> images() throws IOException {
        Map<String, TechErrorPdfInput.Image> m = new LinkedHashMap<>();
        m.put("annual-summary", new TechErrorPdfInput.Image(TechErrorMailImagesTest.png(1400, 700, Color.BLUE), 1400, 700));      // yarım
        m.put("monthly-tx", new TechErrorPdfInput.Image(TechErrorMailImagesTest.png(800, 400, Color.RED), 800, 400));             // yarım (çift)
        m.put("annual-tech-rate", new TechErrorPdfInput.Image(TechErrorMailImagesTest.png(1600, 500, Color.GREEN), 1600, 500));  // tam
        return m;
    }

    static TechErrorPdfInput input(String lang, Map<String, TechErrorPdfInput.Image> imgs) {
        Template t = TechErrorTemplate.defaults(lang);
        Map<String, Object> f = new HashMap<>();
        f.put("total-tx", 1000);
        f.put("error-count", 5);
        f.put("quota-count", 1);
        f.put("unmapped-count", 2);
        f.put("unmapped-note", "Yeni **kodlar** eşlenmedi");
        f.put("sla-rate", 99.9);
        f.put("had-outage", false);
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", "a");
        issue.put("topic", "Kart Limiti");
        issue.put("title_auto", true);
        issue.put("description", "12 Eylül **09:40** zaman aşımı.\n- Mobil\n- İnternet");
        issue.put("customers", 1200);
        issue.put("errors", 3400);
        Map<String, Object> prev = new HashMap<>();
        prev.put("total-tx", 800);
        prev.put("error-count", 4);
        prev.put("error-rate", 0.6);
        TechErrorValues.Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", f, "issues", Map.of("issues", List.of(issue))), t), prev);
        return new TechErrorPdfInput(t, v, "Mobil", "Dijital Kanallar", 2026, 9, "Ayşe Yılmaz", Instant.parse("2026-10-02T06:30:00Z"), imgs,
                null, prev);
    }

    private static final Pattern MSO = Pattern.compile("(?s)<!--\\[if mso\\]>.*?<!\\[endif\\]-->");

    @Test
    @DisplayName("1000 px kart + Outlook hayalet tablosu; lacivert kapak bandı başlık / takım · kanal · dönem / hazırlayan ile en başta; özet, bölümler, PDF notu şablon sırasıyla")
    void layoutAndOrder() throws IOException {
        TechErrorPdfInput in = input("tr", images());
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET), "https://sm.example", "mobil.pdf");
        String html = b.mail().html();
        assertThat(html).contains("max-width:" + MailTokens.WIDTH_XWIDE + "px;background-color")
                .contains("<!--[if mso]><table role=\"presentation\" align=\"center\" width=\"" + MailTokens.WIDTH_XWIDE + "\"");
        int band = html.indexOf("data-slot=\"te-mail-band\"");
        assertThat(band).isPositive();
        assertThat(html).contains("TEKNİK HATA RAPORU · EYLÜL 2026").contains("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026")
                .contains("Dijital Kanallar · Mobil · Eylül 2026").contains("Hazırlayan: Ayşe Yılmaz").contains("bgcolor=\"#0f172a\"");
        assertThat(html).doesNotContain("Oluşturan:");                  // kimlik verilmedi → eski tek satır
        assertThat(html).contains("ekteki PDF&#39;tedir (mobil.pdf)");
        assertThat(b.mail().text()).contains("ekteki PDF'tedir (mobil.pdf)");
        // Sıra: band → özet (kutucuk şeridi + metin) → Verileri → Oranları → anomaliler → SLA görselleri → metin → grafikler
        int summary = html.indexOf("Yönetici Özeti");
        int tiles = html.indexOf("data-slot=\"te-tiles\"");
        int data = html.indexOf("Mobil Kanalı — Eylül 2026 Verileri");
        int rates = html.indexOf("Mobil Kanalı — Eylül 2026 Oranları (otomatik)");
        int issues = html.indexOf("Anormal Artış Gösteren Hatalar");
        int charts = html.indexOf("12 Aylık Özetler - Yıllık Göstergeler");
        int note = html.indexOf("Adetleri ekip girmiştir");
        assertThat(band).isLessThan(summary);
        assertThat(summary).isLessThan(tiles);
        assertThat(tiles).isLessThan(data);
        assertThat(data).isLessThan(rates);
        assertThat(rates).isLessThan(issues);
        assertThat(issues).isLessThan(charts);
        assertThat(charts).isLessThan(note);
        // Map / kota açıklaması "Verileri" bölümünün alanı (7. sürüm): ayrı bölüm başlığı yok, alanın adıyla basılır
        assertThat(html).doesNotContain("Map Yapılmamış Hatalar").contains("Map yapılmamış hatalar hakkında açıklama").contains("<strong>kodlar</strong>");
        // Düz metin eşi: band + kutucuklar + değerler + görsel satırları
        String text = b.mail().text();
        assertThat(text).contains("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026\nDijital Kanallar · Mobil · Eylül 2026\nHazırlayan: Ayşe Yılmaz")
                .contains("Toplam işlem adedi: 1.000 (▲ +200 (+%25))").contains("Toplam işlem adedi: 1.000").contains("[Görsel] Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)")
                .doesNotContain("<table");
        assertThat(b.embeddedImages()).isEqualTo(3);
        assertThat(b.skippedImages()).isZero();
        // 6 kutucuk = Outlook için 2 hayalet satır (tam bir satır sonu), gövde Gmail kırpma sınırının çok altında
        String strip = html.substring(tiles, data);
        assertThat(strip.split(java.util.regex.Pattern.quote("<!--[if mso]></tr><tr><![endif]-->"), -1).length - 1).isEqualTo(1);
        assertThat(html.length()).isLessThan(90_000);
        assertThat(b.inline()).extracting(i -> i.cid()).containsExactly("te-img-annual-summary", "te-img-monthly-tx", "te-img-annual-tech-rate");
    }

    @Test
    @DisplayName("gösterge bölümleri PDF'teki gibi: değer kutucukları (telefonda .stack), muaf alanlar yok, uzun açıklama alan adıyla; anomali kartı turuncu sayaç + zengin açıklama; tablo bölümü grup satırıyla")
    void sectionsLikePdf() throws IOException {
        TechErrorPdfInput in = input("tr", Map.of());
        String html = TechErrorReportMail.build(in, null, "m.pdf").html();
        // Değer kutucukları: Verileri bölümü girilenler, Oranları hesaplananlar; "Hayır" → kesinti alanları muaf (yok)
        String values = html.substring(html.indexOf("Mobil Kanalı — Eylül 2026 Verileri"), html.indexOf("Anormal Artış Gösteren Hatalar"));
        assertThat(values).contains("data-slot=\"te-values\"").contains("Toplam işlem adedi").contains(">1.000<").contains("Dönemde kesinti oldu mu?").contains(">Hayır<")
                .contains("Kritik fonksiyonların SLA oranı").contains(">%99,9<").contains("SLA hedefin üstünde mi?")
                .doesNotContain("Kesinti kaynaklı teknik hata adedi").doesNotContain("Kesinti günleri hariç")
                .contains("Teknik hata oranı").contains(">%0,5<").contains("Map yapılmamış hataların teknik hatalara oranı").contains(">%40<");
        assertThat(values.split("class=\"stack\"", -1).length - 1).isGreaterThanOrEqualTo(8);
        // Anomali kartı
        String issue = html.substring(html.indexOf("data-slot=\"te-issue\""));
        assertThat(issue).contains("1. Kart Limiti: Hata Artışı").contains("Etkilenen müşteri sayısı: 1.200 · Bu konudan kaynaklı teknik hata adedi: 3.400")
                .contains("color:" + MailTokens.WARNING).contains("<strong>09:40</strong>").contains("&#8226;&nbsp;Mobil");
        // Metin bölümü (varsayılan metin, çözülmüş)
        assertThat(html).contains("Grafikler hakkında").contains("Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır.");
        // Görselsiz görsel bölümü PDF notuyla
        assertThat(html).contains("Bu bölüme görsel yüklenmedi.").doesNotContain("cid:te-img-");
        // Tablo bölümü: değerli tablo basılır (grup satırı kalın), değersiz tablo hiç basılmaz
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr")));
        List<Object> secs = new ArrayList<>((List<?>) raw.get("sections"));
        secs.add(Map.of("id", "sla-table", "kind", "table", "title", "Fonksiyon SLA Tablosu",
                "columns", List.of(Map.of("id", "functions", "label", "Fonksiyon adedi", "type", "number"),
                        Map.of("id", "availability", "label", "Erişilebilirlik", "type", "percent")),
                "rows", List.of(Map.of("id", "total", "label", "{kanal} Kanalı Toplam", "group", true), Map.of("id", "crit", "label", "Kritik"))));
        raw.put("sections", secs);
        Template tt = TechErrorTemplate.normalize(raw);
        TechErrorValues.Values empty = TechErrorValues.normalize(Map.of(), tt);
        assertThat(TechErrorReportMail.build(new TechErrorPdfInput(tt, empty, "Mobil", "T", 2026, 9, null, null, Map.of()), null, "m.pdf").html())
                .doesNotContain("Fonksiyon SLA Tablosu");
        TechErrorValues.Values tv = TechErrorValues.normalize(Map.of("tables", Map.of("sla-table", Map.of("total", Map.of("functions", 42, "availability", 99.95),
                "crit", Map.of("functions", 7)))), tt);
        String th = TechErrorReportMail.build(new TechErrorPdfInput(tt, tv, "Mobil", "T", 2026, 9, null, null, Map.of()), null, "m.pdf").html();
        assertThat(th).contains("Fonksiyon SLA Tablosu").contains("Mobil Kanalı Toplam").contains(">42<").contains(">%99,95<")
                .contains("&nbsp;&nbsp;&nbsp;Kritik");           // grup satırından sonraki satır girintili (PDF ile aynı)
    }

    @Test
    @DisplayName("görseller: yarım yuvalar ikişer yan yana (telefonda alt alta), tam yuva tek; Outlook'ta piksel width, diğerlerinde akışkan (MSO dışı ≥ 500 sabit genişlik yok); bütçe aşımı → PDF kutusu; trend bölümü PDF notu")
    void imagesAndBudget() throws IOException {
        TechErrorPdfInput in = input("tr", images());
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET), null, "m.pdf");
        String html = b.mail().html();
        // Yarım çift aynı satırda, 50 %; tam yuva 100 %
        int pair = html.indexOf("data-slot=\"te-images\"");
        String row = html.substring(pair, html.indexOf("</td></tr></table>", html.indexOf("<!--[if mso]></tr></table><![endif]-->", pair)));
        assertThat(row).contains("cid:te-img-annual-summary").contains("cid:te-img-monthly-tx").contains("max-width:50%")
                .contains("Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)").contains("Toplam Operasyon Adetleri (Son 12 Ay)");
        assertThat(row.split("class=\"stack\"", -1).length - 1).isEqualTo(2);
        // Yarım sütun 459 px (hücre 475 − iki yanda 8 px, simetrik — Outlook hayalet hücresi taşmaz): 1400 px kaynak
        // 1000'e indi, sütuna sığdırıldı (MSO width ve akışkan max-width aynı)
        assertThat(row).contains("<!--[if mso]><img src=\"cid:te-img-annual-summary\" width=\"459\"").contains("max-width:459px;height:auto")
                .contains("padding:0 8px 12px 8px");
        // 800 px kaynak olduğu gibi: 459 sütuna sığdırılır
        assertThat(row).contains("<!--[if mso]><img src=\"cid:te-img-monthly-tx\" width=\"459\"");
        // Tam yuva 950 px
        assertThat(html).contains("<!--[if mso]><img src=\"cid:te-img-annual-tech-rate\" width=\"950\"").contains("max-width:950px;height:auto");
        // MSO dışında ≥ 500 px sabit genişlik yok (sözleşme)
        String outside = MSO.matcher(html).replaceAll("");
        Matcher w = Pattern.compile("(?<![a-z-])width=\"(\\d+)\"").matcher(outside);
        while (w.find()) assertThat(Integer.parseInt(w.group(1))).as("MSO dışı width=").isLessThan(500);
        Matcher cw = Pattern.compile("(?<![a-z-])width:(\\d+)px").matcher(outside);
        while (cw.find()) assertThat(Integer.parseInt(cw.group(1))).as("MSO dışı width:px").isLessThan(500);
        assertThat(html).doesNotContain("img src=\"http");
        // Bütçe aşımı: hiçbiri gömülmez, her yuva "PDF ekinde" kutusu; düz metinde de söylenir
        TechErrorReportMail.Built none = TechErrorReportMail.buildFull(in, TechErrorMailImages.prepare(in, 0), null, "m.pdf");
        assertThat(none.mail().html()).doesNotContain("cid:te-img-").contains("data-slot=\"te-image-pdf\"").contains("Görsel ekteki PDF&#39;te (tam çözünürlük).");
        assertThat(none.mail().text()).contains("[Görsel — PDF ekinde] Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)");
        assertThat(none.inline()).isEmpty();
        assertThat(none.skippedImages()).isEqualTo(3);
        // Hazırlama hiç yapılamadı (allSkipped) ve girdi görselsiz (withoutImages — gönderici PDF'ten sonra böyle tutar):
        // görseli olan yuvalar yine "PDF ekinde", hiçbiri "yüklenmedi" değil; null sonuç → hepsi "yüklenmedi"
        TechErrorReportMail.Built fallback = TechErrorReportMail.buildFull(in.withoutImages(), TechErrorMailImages.allSkipped(in), null, "m.pdf");
        assertThat(fallback.mail().html()).contains("data-slot=\"te-image-pdf\"").doesNotContain("cid:te-img-");
        assertThat(fallback.skippedImages()).isEqualTo(3);
        assertThat(TechErrorReportMail.buildFull(in.withoutImages(), null, null, "m.pdf").mail().html())
                .contains("Bu bölüme görsel yüklenmedi.").doesNotContain("data-slot=\"te-image-pdf\"");
        // Okunamayan görsel: "PDF ekinde" değil, "okunamadı" (PDF'te de çizilemez)
        TechErrorMailImages.Result broken = new TechErrorMailImages.Result(Map.of(), List.of("annual-summary"), List.of("annual-tech-rate"), 0);
        TechErrorReportMail.Built bb = TechErrorReportMail.buildFull(in.withoutImages(), broken, null, "m.pdf");
        assertThat(bb.mail().html()).contains("data-slot=\"te-image-unreadable\"").contains("Görsel okunamadı (PDF&#39;te de çizilemedi).")
                .contains("data-slot=\"te-image-pdf\"");
        assertThat(bb.mail().text()).contains("[Görsel — okunamadı] Teknik/Business Hata Oranı Trendi (Son 12 Ay)")
                .contains("[Görsel — PDF ekinde] Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)");
        assertThat(bb.skippedImages()).isEqualTo(2);
        // Trend bölümü olan şablon: e-postada grafik yok, PDF notu
        Template tr = TechErrorTestTemplates.withTrend("tr");
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", Map.of("total-tx", 10)), tr);
        assertThat(TechErrorReportMail.build(new TechErrorPdfInput(tr, v, "Mobil", "T", 2026, 9, null, null, Map.of()), null, "m.pdf").html())
                .contains("Son 12 ayın trend grafikleri ekteki PDF&#39;tedir.");
    }

    @Test
    @DisplayName("EN şablon: band, notlar ve görsel metinleri İngilizce; konu = rapor başlığı")
    void english() throws IOException {
        TechErrorPdfInput in = input("en", images());
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET), "https://sm.example", "m.pdf");
        assertThat(TechErrorReportMail.subject(in)).isEqualTo(in.title());
        assertThat(b.mail().html()).contains("TECHNICAL ERROR REPORT · SEPTEMBER 2026").contains("Prepared by: Ayşe Yılmaz").contains("(Turkey time)")
                .contains("is in the attached PDF (m.pdf)").contains("Open technical error reports");
        assertThat(b.mail().text()).contains("[Image] ").contains("Total transactions: 1,000");
    }

    // ── Kimlik satırları (2026-10-17, 10. sürüm) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("band kimlik satırları: Oluşturan · tarih, farklıysa Son düzenleyen, onay akışlı kanalda Onaylayan ya da onay durumu, sonra Üretim; EN karşılıkları; düz metin aynı")
    void provenanceLines() {
        TechErrorPdfInput base = input("tr", Map.of());
        TechErrorPdfInput.Provenance p = new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"),
                "Ali Veli", Instant.parse("2026-09-05T06:12:00Z"), "approved", "Mehmet PO", Instant.parse("2026-09-06T07:00:00Z"));
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(base.withProvenance(p), TechErrorMailImages.allSkipped(base), "https://sm.example", "m.pdf");
        String html = b.mail().html();
        assertThat(html).contains("Oluşturan: Ayşe Yılmaz · 03.09.2026 14:05").contains("Son düzenleyen: Ali Veli · 05.09.2026 09:12")
                .contains("Onaylayan: Mehmet PO · 06.09.2026 10:00").contains("Üretim: ").doesNotContain("Hazırlayan:");
        assertThat(html.indexOf("Oluşturan:")).isLessThan(html.indexOf("Son düzenleyen:"));
        assertThat(html.indexOf("Son düzenleyen:")).isLessThan(html.indexOf("Onaylayan:"));
        assertThat(html.indexOf("Onaylayan:")).isLessThan(html.indexOf("Üretim:"));
        assertThat(html.split("data-slot=\"te-mail-provenance\"").length - 1).isEqualTo(4);
        assertThat(b.mail().text()).contains("Oluşturan: Ayşe Yılmaz · 03.09.2026 14:05\nSon düzenleyen: Ali Veli · 05.09.2026 09:12\nOnaylayan: Mehmet PO · 06.09.2026 10:00\nÜretim: ");
        // Aynı kişi, aynı an: tek satır; onay akışı yok (state null): onay satırı yok
        TechErrorPdfInput.Provenance same = new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"),
                "Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"), null, null, null);
        String h2 = TechErrorReportMail.buildFull(base.withProvenance(same), TechErrorMailImages.allSkipped(base), "https://sm.example", "m.pdf").mail().html();
        assertThat(h2).contains("Oluşturan: Ayşe Yılmaz").doesNotContain("Son düzenleyen:").doesNotContain("Onay");
        // Onay akışlı ama henüz onaylanmamış: durum satırı, onaylayan adı yok; eski (stale) onay da ad basmaz
        for (String[] c : new String[][] { { "pending", "Onay: PO onayı bekleniyor" }, { "none", "Onay: henüz onaya gönderilmedi" },
                { "rejected", "Onay: reddedildi — taslağa döndü" }, { "stale", "Onay: içerik onaydan sonra değişti — yeniden onay gerekir" } }) {
            TechErrorPdfInput.Provenance st = new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"),
                    null, null, c[0], "Mehmet PO", Instant.parse("2026-09-06T07:00:00Z"));
            String h = TechErrorReportMail.buildFull(base.withProvenance(st), TechErrorMailImages.allSkipped(base), "https://sm.example", "m.pdf").mail().html();
            assertThat(h).as(c[0]).contains(c[1]).doesNotContain("Onaylayan:").doesNotContain("Mehmet PO");
        }
        // EN
        TechErrorPdfInput en = input("en", Map.of());
        String hEn = TechErrorReportMail.buildFull(en.withProvenance(p), TechErrorMailImages.allSkipped(en), "https://sm.example", "m.pdf").mail().html();
        assertThat(hEn).contains("Created by: Ayşe Yılmaz · 03.09.2026 14:05").contains("Last edited by: Ali Veli").contains("Approved by: Mehmet PO")
                .contains("Generated: ").doesNotContain("Prepared by:");
        assertThat(TechErrorReportMail.approvalText("pending", true)).isEqualTo("awaiting PO approval");
        // Silinmiş kullanıcı: ad yok → "—", satır yine basılır
        TechErrorPdfInput.Provenance gone = new TechErrorPdfInput.Provenance(null, Instant.parse("2026-09-03T11:05:00Z"), null, null, null, null, null);
        assertThat(TechErrorReportMail.buildFull(base.withProvenance(gone), TechErrorMailImages.allSkipped(base), "https://sm.example", "m.pdf").mail().html())
                .contains("Oluşturan: — · 03.09.2026 14:05");
    }

    // ── Dipnot "neden aldınız" kitleye göre (inceleme 2026-10-17) ───────────────────────────────────

    @Test
    @DisplayName("dipnot: TEAM eski metin (bayt bayt), EXECUTIVE yönetici alıcısı + onaylayan PO, APPROVER onaylayıcı; EN karşılıkları")
    void footerWhyByAudience() {
        TechErrorPdfInput base = input("tr", Map.of());
        TechErrorMailImages.Result none = TechErrorMailImages.allSkipped(base);
        String team = TechErrorReportMail.buildFull(base, none, "https://sm.example", "m.pdf").mail().html();
        assertThat(team).contains("Dijital Kanallar takımının adresi olduğu ve bir takım üyesi raporu Site Monitor&#39;den gönderdiği için geldi")
                .doesNotContain("yönetici (executive)");
        assertThat(TechErrorReportMail.buildFull(base, none, "https://sm.example", "m.pdf", null, TechErrorReportMail.Audience.TEAM).mail().html()).isEqualTo(team);
        TechErrorPdfInput.Provenance approved = new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"),
                null, null, "approved", "Mehmet PO", Instant.parse("2026-09-06T07:00:00Z"));
        String exec = TechErrorReportMail.buildFull(base.withProvenance(approved), none, "https://sm.example", "m.pdf", null,
                TechErrorReportMail.Audience.EXECUTIVE).mail().html();
        assertThat(exec).contains("Mobil kanalının yönetici (executive) alıcısı olarak tanımlı olduğunuz için geldi; Dijital Kanallar takımının adresi bilgidedir.")
                .contains("Raporu Mehmet PO 06.09.2026 10:00 tarihinde onayladı.").doesNotContain("takımının adresi olduğu");
        // Kimlik yok (eski imza) → onaylayan cümlesi yok
        assertThat(TechErrorReportMail.buildFull(base, none, "https://sm.example", "m.pdf", null, TechErrorReportMail.Audience.EXECUTIVE).mail().html())
                .contains("yönetici (executive) alıcısı").doesNotContain("onayladı.");
        String approver = TechErrorReportMail.buildFull(base, none, "https://sm.example", "m.pdf", null, TechErrorReportMail.Audience.APPROVER).mail().html();
        assertThat(approver).contains("Dijital Kanallar takımının PO onaylayıcısı olduğunuz için geldi; rapor yöneticilere gönderilmeden önce onayınızı bekliyor.");
        TechErrorPdfInput en = input("en", Map.of());
        assertThat(TechErrorReportMail.buildFull(en.withProvenance(approved), TechErrorMailImages.allSkipped(en), "https://sm.example", "m.pdf", null,
                TechErrorReportMail.Audience.EXECUTIVE).mail().html())
                .contains("registered executive recipient of the Mobil channel").contains("approved by Mehmet PO on 06.09.2026 10:00.");
        assertThat(TechErrorReportMail.buildFull(en, TechErrorMailImages.allSkipped(en), "https://sm.example", "m.pdf", null,
                TechErrorReportMail.Audience.APPROVER).mail().html()).contains("PO approver of Dijital Kanallar");
    }
}
