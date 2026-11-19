package com.sitemonitor.service.mail;

import com.sitemonitor.service.BrandMailAssets;
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
                .contains("Dijital Kanallar · Mobil · Eylül 2026").contains("bgcolor=\"#0f172a\"");
        assertThat(html).doesNotContain("Hazırlayan:").doesNotContain("Üretim:").doesNotContain("data-slot=\"te-mail-provenance\"");
        // 2026-11-19 (kullanıcı isteği): PDF ekliyken "Tam rapor — görseller tam çözünürlükte — ekteki PDF'tedir (…)" giriş
        // cümlesi YOK — ne HTML'de ne düz metinde; dosya adı gövdede geçmez (ek postanın kendisinde görünür)
        assertThat(html).doesNotContain("Tam rapor").doesNotContain("tam çözünürlükte").doesNotContain("mobil.pdf");
        assertThat(b.mail().text()).doesNotContain("Tam rapor").doesNotContain("tam çözünürlükte").doesNotContain("mobil.pdf");
        // Sıra: band → özet (kutucuk şeridi + metin + taşınan not) → anomaliler → SLA görselleri → metin → grafikler. Verileri
        // ve Oranları bölümü YOK (2026-10-18, veri tekrarı yok + kullanıcı kararı "Verileri'ni kaldıralım"): bütün değerleri
        // özetin cümlelerinde / kutucuklarında (kesinti cevabı dahil); dolu map açıklaması özetin sonunda alanın adıyla
        int summary = html.indexOf("Yönetici Özeti");
        int tiles = html.indexOf("data-slot=\"te-tiles\"");
        int data = html.indexOf("Mobil Kanalı — Eylül 2026 Verileri");
        int rates = html.indexOf("Mobil Kanalı — Eylül 2026 Oranları (otomatik)");
        int issues = html.indexOf("Anormal Artış Gösteren Hatalar");
        int charts = html.indexOf("12 Aylık Özetler - Yıllık Göstergeler");
        int note = html.indexOf("Adetleri ekip girmiştir");
        assertThat(band).isLessThan(summary);
        assertThat(summary).isLessThan(tiles);
        assertThat(data).as("Verileri: her değeri özette — bölüm basılmaz").isEqualTo(-1);
        assertThat(rates).as("Oranları: her oran özette — bölüm basılmaz").isEqualTo(-1);
        int moved = html.indexOf("Map yapılmamış hatalar hakkında açıklama");
        assertThat(tiles).isLessThan(moved);
        assertThat(moved).as("not özetin sonunda, anomalilerden önce").isLessThan(issues);
        assertThat(issues).isLessThan(charts);
        assertThat(charts).isLessThan(note);
        // 2026-11-19 (kullanıcı isteği): posta ÇERÇEVESİZ — tepede logo + "Teknik hata raporu" etiketi taşıyan başlık satırı
        // yok (kartın ilk içeriği lacivert band), altta düğme / "Neden bu e-postayı aldınız?" / meta satırı yok: raporun
        // kapanış notu gövdenin SON bloğudur, ardından yalnız kapanış etiketleri gelir
        assertThat(html).doesNotContain("cid:" + BrandMailAssets.CID).doesNotContain("width=\"32\" height=\"32\"")
                .doesNotContain("Neden bu e-postayı aldınız?").doesNotContain("takımının adresi olduğu")
                .doesNotContain("Teknik hata raporlarını aç").doesNotContain("tab=channelreports")
                .doesNotContain("Site Monitor — teknik hata raporu").doesNotContain("class=\"btn-full\"");
        assertThat(count(html, ">Teknik hata raporu<")).as("sağ üst köşedeki alt-sistem etiketi yok").isZero();
        int cardOpen = html.indexOf("class=\"card\"");
        assertThat(html.substring(cardOpen, band)).as("kart ile band arasında başlık satırı yok")
                .doesNotContain("<img").doesNotContain("border-bottom:1px solid");
        String afterNote = html.substring(html.indexOf("</p>", note));
        assertThat(MSO.matcher(afterNote).replaceAll("").replaceAll("<[^>]+>", "").strip())
                .as("kapanış notundan sonra görünür metin yok").isEmpty();
        assertThat(b.mail().text()).doesNotContain("Neden bu e-postayı aldınız?").doesNotContain("\n--\n")
                .doesNotContain("Teknik hata raporlarını aç").doesNotContain("sm.example");
        assertThat(b.mail().text().strip()).endsWith("Adetleri ekip girmiştir; oranları Site Monitor bu adetlerden hesaplamıştır.");
        // Map / kota açıklaması "Verileri" bölümünün alanı (7. sürüm): ayrı bölüm başlığı yok, alanın adıyla basılır (Verileri
        // çözülünce özetin sonunda — 2026-10-18)
        assertThat(html).doesNotContain("Map Yapılmamış Hatalar").contains("Map yapılmamış hatalar hakkında açıklama").contains("<strong>kodlar</strong>");
        // Düz metin eşi: band + kutucuklar + değerler + görsel satırları
        String text = b.mail().text();
        assertThat(text).contains("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026\nDijital Kanallar · Mobil · Eylül 2026\n")
                .doesNotContain("Hazırlayan:")
                .contains("Toplam işlem adedi: 1.000 (▲ +200 (+%25))").contains("Toplam işlem adedi: 1.000").contains("[Görsel] Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)")
                .doesNotContain("<table");
        assertThat(b.embeddedImages()).isEqualTo(3);
        assertThat(b.skippedImages()).isZero();
        // 6 kutucuk = Outlook için 2 hayalet satır (tam bir satır sonu), gövde Gmail kırpma sınırının çok altında
        String strip = html.substring(tiles, html.indexOf("</table>", html.indexOf("<!--[if mso]></tr></table><![endif]-->", tiles)));
        assertThat(strip.split(java.util.regex.Pattern.quote("<!--[if mso]></tr><tr><![endif]-->"), -1).length - 1).isEqualTo(1);
        assertThat(html.length()).isLessThan(90_000);
        assertThat(b.inline()).extracting(i -> i.cid()).containsExactly("te-img-annual-summary", "te-img-monthly-tx", "te-img-annual-tech-rate");
    }

    @Test
    @DisplayName("gösterge bölümleri PDF'teki gibi: değer kutucukları (telefonda .stack), muaf alanlar yok, uzun açıklama alan adıyla; anomali kartı turuncu sayaç + zengin açıklama; tablo bölümü grup satırıyla")
    void sectionsLikePdf() throws IOException {
        TechErrorPdfInput in = input("tr", Map.of());
        String html = TechErrorReportMail.build(in, null, "m.pdf").html();
        // 2026-10-18 (veri tekrarı yok + kullanıcı kararı "Verileri'ni kaldıralım"): Verileri ve Oranları bölümü yok, değer
        // kutucuğu yok — adetler, SLA, bütün oranlar ve kesinti cevabı ("Hayır" → kesinti alanları muaf) özetin kutucuk /
        // cümlelerinde; dolu map açıklaması özetin sonunda alanın adıyla
        assertThat(html).doesNotContain("Mobil Kanalı — Eylül 2026 Verileri").doesNotContain("Oranları (otomatik)")
                .doesNotContain("data-slot=\"te-values\"").doesNotContain("Dönemde kesinti oldu mu?")
                .doesNotContain("Kesinti kaynaklı teknik hata adedi").doesNotContain("Kesinti günleri hariç")
                .doesNotContain("Map yapılmamış hataların teknik hatalara oranı").doesNotContain("SLA hedefin üstünde mi?");
        String head = html.substring(0, html.indexOf("Anormal Artış Gösteren Hatalar"));
        assertThat(head).contains("<strong>%0,5</strong>").contains("<strong>%40</strong>").contains("<strong>%99,9</strong>")
                .contains("Toplam işlem adedi").contains("Dönemde kesinti yaşanmamıştır.").contains("Map yapılmamış hatalar hakkında açıklama");
        assertThat(head.indexOf("Map yapılmamış hatalar hakkında açıklama")).isGreaterThan(head.indexOf("Dönemde kesinti yaşanmamıştır."));
        // Anomali kartı
        String issue = html.substring(html.indexOf("data-slot=\"te-issue\""));
        // 2026-10-18: varsayılan şablonun anomali soruları açık — sayaç satırı yerine cevaplardan kurulan giriş metni (müşteri
        // sayısı girilmiş, cevapsız → "müşteri etkisi var"; konunun adedi toplamdan büyük → pay yok)
        assertThat(issue).contains("1. Kart Limiti: Hata Artışı")
                .contains("Bu konu nedeniyle <strong>3.400</strong> teknik hata alındı. Müşteri etkisi: <strong>1.200</strong> müşteri.")
                .doesNotContain("Bu konudan kaynaklı teknik hata adedi: 3.400")
                .contains("<strong>09:40</strong>").contains("&#8226;&nbsp;Mobil");
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
    @DisplayName("inceleme 2026-10-18: PDF EKLENEMEDİYSE (onay isteği, fileName null) gömülmeyen görselin kutusu 'ekteki PDF'te' demez"
            + " — 'bu postaya eklenmedi, PDF'i Site Monitor'den indirin' (20.157.0 metni; TR / EN, düz metin de); ayrı data-slot"
            + " te-image-no-pdf; okunamayan görsel yine 'okunamadı'")
    void imageBoxesWhenPdfNotAttached() throws IOException {
        TechErrorPdfInput in = input("tr", images());
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in.withoutImages(), TechErrorMailImages.allSkipped(in), null, null);
        String html = b.mail().html();
        assertThat(html).contains("PDF bu postanın boyut sınırına sığmadığından eklenmedi")
                .doesNotContain("data-slot=\"te-image-pdf\"").doesNotContain("Görsel ekteki PDF&#39;te");
        assertThat(html.split("data-slot=\"te-image-no-pdf\"", -1).length - 1).isEqualTo(3);
        assertThat(html).contains("Görsel bu postaya eklenmedi — görmek için PDF&#39;i Site Monitor&#39;den indirin (tam çözünürlük).");
        assertThat(b.mail().text()).contains("[Görsel — Site Monitor'deki PDF'te] Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)")
                .doesNotContain("[Görsel — PDF ekinde]");
        // Okunamayan görselin metni değişmez (PDF'te de çizilemez); PDF ekliyse kutular eskisi gibi
        TechErrorMailImages.Result broken = new TechErrorMailImages.Result(Map.of(), List.of("annual-summary"), List.of("annual-tech-rate"), 0);
        String bh = TechErrorReportMail.buildFull(in.withoutImages(), broken, null, null).mail().html();
        assertThat(bh).contains("data-slot=\"te-image-unreadable\"").contains("data-slot=\"te-image-no-pdf\"").doesNotContain("data-slot=\"te-image-pdf\"");
        assertThat(TechErrorReportMail.buildFull(in.withoutImages(), TechErrorMailImages.allSkipped(in), null, "m.pdf").mail().html())
                .contains("data-slot=\"te-image-pdf\"").doesNotContain("te-image-no-pdf");
        TechErrorPdfInput en = input("en", images());
        TechErrorReportMail.Built eb = TechErrorReportMail.buildFull(en.withoutImages(), TechErrorMailImages.allSkipped(en), null, null);
        assertThat(eb.mail().html()).contains("Image not included in this e-mail — download the PDF from Site Monitor to see it (full resolution).")
                .doesNotContain("Image in the attached PDF");
        assertThat(eb.mail().text()).contains("[Image — in the PDF in Site Monitor]");
    }

    @Test
    @DisplayName("EN şablon: band, notlar ve görsel metinleri İngilizce; konu = rapor başlığı")
    void english() throws IOException {
        TechErrorPdfInput in = input("en", images());
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET), "https://sm.example", "m.pdf");
        assertThat(TechErrorReportMail.subject(in)).isEqualTo(in.title());
        assertThat(b.mail().html()).contains("TECHNICAL ERROR REPORT · SEPTEMBER 2026").doesNotContain("Prepared by:").doesNotContain("Turkey time")
                .doesNotContain("The full report").doesNotContain("at full resolution, is in").doesNotContain("m.pdf")
                .doesNotContain("Open technical error reports").doesNotContain("You received this e-mail")
                .doesNotContain(">Technical error report<").doesNotContain("cid:" + BrandMailAssets.CID);
        assertThat(b.mail().text()).contains("[Image] ").contains("Total transactions: 1,000");
    }

    // ── Bantta kimlik yok (2026-11-16) ───────────────────────────────────────────────────────────────

    /** Çıktıda hiçbir zaman geçmemesi gereken kimlik / üretim sözcükleri (TR + EN; PDF testindeki listenin eşi). */
    private static final List<String> PROVENANCE_WORDS = List.of("Hazırlayan:", "Oluşturan:", "Son düzenleyen:", "Onaylayan:", "Onay:",
            "Üretim:", "Türkiye saati", "Prepared by:", "Created by:", "Last edited by:", "Approved by:", "Approval:", "Generated:", "Turkey time");

    @Test
    @DisplayName("band yalnız üst etiket + başlık + takım · kanal · dönem: oluşturan / son düzenleyen / onaylayan / onay durumu / üretim ve kişi adı gövdeye yazılmaz (kullanıcı isteği 2026-11-16); TR + EN; HTML ve düz metin")
    void bandHasNoProvenance() {
        TechErrorPdfInput.Provenance p = new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"),
                "Ali Veli", Instant.parse("2026-09-05T06:12:00Z"), "approved", "Mehmet PO", Instant.parse("2026-09-06T07:00:00Z"));
        for (String lang : List.of("tr", "en")) {
            TechErrorPdfInput base = input(lang, Map.of());
            for (TechErrorPdfInput.Provenance pv : java.util.Arrays.asList(null, p,
                    new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"), null, null, "pending", null, null))) {
                TechErrorPdfInput in = pv == null ? base : base.withProvenance(pv);
                TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, TechErrorMailImages.allSkipped(in), "https://sm.example", "m.pdf", null);
                for (String body : List.of(b.mail().html(), b.mail().text())) {
                    for (String w : PROVENANCE_WORDS) {
                        assertThat(body).as(lang + " " + w).doesNotContain(w);
                    }
                    assertThat(body).as(lang).doesNotContain("Ayşe Yılmaz").doesNotContain("Ali Veli").doesNotContain("Mehmet PO")
                            .doesNotContain("03.09.2026 14:05").doesNotContain("06.09.2026");
                }
                assertThat(b.mail().html()).doesNotContain("data-slot=\"te-mail-provenance\"");
            }
        }
    }

    // ── Çerçevesiz posta: başlık satırı, düğme ve dipnot yok (2026-11-19) ───────────────────────────

    @Test
    @DisplayName("çerçevesiz posta (kullanıcı isteği 2026-11-19): logo + etiket başlığı yok, 'neden aldınız' dipnotu / meta satırı / 'raporları aç' düğmesi yok — onay durumu ne olursa olsun, TR + EN, PDF ekli ya da eksiz; kartın dört köşesi gövde hücresinde; rapor kapanış notuyla biter")
    void bareMailHasNoHeaderFooterOrButton() {
        TechErrorPdfInput.Provenance approved = new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"),
                null, null, "approved", "Mehmet PO", Instant.parse("2026-09-06T07:00:00Z"));
        for (String lang : List.of("tr", "en")) {
            TechErrorPdfInput base = input(lang, Map.of());
            for (TechErrorPdfInput in : List.of(base, base.withProvenance(approved))) {
                for (String fileName : java.util.Arrays.asList("m.pdf", null)) {
                    TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, TechErrorMailImages.allSkipped(in), "https://sm.example", fileName, null);
                    String html = b.mail().html();
                    String text = b.mail().text();
                    assertThat(html).as(lang).doesNotContain("cid:" + BrandMailAssets.CID).doesNotContain("width=\"32\" height=\"32\"")
                            .doesNotContain("Neden bu e-postayı aldınız?").doesNotContain("takımının adresi olduğu")
                            .doesNotContain("yönetici (executive)").doesNotContain("PO onaylayıcısı").doesNotContain("tarafından onaylandı")
                            .doesNotContain("You received this e-mail").doesNotContain("approved by the team")
                            .doesNotContain("Teknik hata raporlarını aç").doesNotContain("Open technical error reports")
                            .doesNotContain("tab=channelreports").doesNotContain("class=\"btn-full\"")
                            .doesNotContain(">Teknik hata raporu<").doesNotContain(">Technical error report<")
                            .doesNotContain("Site Monitor — teknik hata raporu").doesNotContain("Site Monitor — technical error report");
                    // kart yalnız gövde hücresinden oluşur: dört köşe de o hücrede yuvarlanır; alt bilgi satırı yok
                    assertThat(html).contains("style=\"padding:24px 24px 8px;border-radius:" + MailTokens.RADIUS_CARD + "px;")
                            .doesNotContain("border-radius:0 0 " + MailTokens.RADIUS_CARD + "px");
                    assertThat(text).as(lang).doesNotContain("Neden bu e-postayı aldınız?").doesNotContain("\n--\n")
                            .doesNotContain("sm.example").doesNotContain("Mehmet PO");
                    // rapor kapanış notuyla biter (PDF'in son satırıyla aynı anlam)
                    assertThat(text.strip()).as(lang).endsWith(lang.equals("en")
                            ? "Counts were entered by the team; Site Monitor calculated the rates from those counts."
                            : "Adetleri ekip girmiştir; oranları Site Monitor bu adetlerden hesaplamıştır.");
                }
            }
        }
    }

    @Test
    @DisplayName("MailDoc.bare(): başlık ve alt bilgi çizilmez, düz metne alt bilgi eklenmez; bare olmayan belge eskisi gibi (tek logo, etiket, 'Neden bu e-postayı aldınız?')")
    void mailDocBareSkipsChromeOnly() {
        MailDoc normal = MailDoc.create("Konu").kicker("Port İzleme");
        normal.paragraph("Gövde");
        normal.footerWhy("Takım A").footerMeta("Meta");
        assertThat(count(normal.html(), "src=\"cid:" + BrandMailAssets.CID + "\"")).isEqualTo(1);
        assertThat(normal.html()).contains("Port İzleme").contains("Neden bu e-postayı aldınız?").contains("Meta");
        assertThat(normal.text()).contains("Neden bu e-postayı aldınız?").contains("Meta");

        MailDoc bare = MailDoc.create("Konu").kicker("Port İzleme").bare();
        bare.paragraph("Gövde");
        bare.footerWhy("Takım A").footerMeta("Meta");      // verilse de çizilmez
        assertThat(bare.html()).contains("Gövde").doesNotContain("cid:" + BrandMailAssets.CID).doesNotContain("Port İzleme")
                .doesNotContain("Neden bu e-postayı aldınız?").doesNotContain("Meta")
                .contains("<title>Konu</title>").endsWith("</table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>");
        assertThat(bare.text()).isEqualTo("Gövde\n");
    }

    private static int count(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    // ── PDF eklenmeyen posta (inceleme 2026-10-18) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("inceleme 2026-10-18: PDF eklenmeyen postada (onay isteği, PDF sınırı aşıyor) hiçbir yer 'ekteki PDF' demez — ön başlık, giriş, görsel kutuları, düz metin etiketleri ve trend notu PDF'in Site Monitor'de olduğunu söyler; PDF ekliyken metinler eskisi gibi")
    void noAttachmentNeverClaimsAttachedPdf() throws IOException {
        Template t = TechErrorTestTemplates.withTrend("tr");
        Map<String, Object> f = new HashMap<>();
        f.put("total-tx", 1000);
        f.put("error-count", 5);
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", f), t);
        TechErrorPdfInput in = new TechErrorPdfInput(t, v, "Mobil", "Dijital Kanallar", 2026, 9, "Ayşe Yılmaz",
                Instant.parse("2026-10-02T06:30:00Z"), images(), null, null);
        TechErrorMailImages.Result none = TechErrorMailImages.allSkipped(in);
        TechErrorReportMail.Built noPdf = TechErrorReportMail.buildFull(in, none, "https://sm.example", null);
        String html = noPdf.mail().html();
        String text = noPdf.mail().text();
        assertThat(html).contains("PDF eklenmedi · Eylül 2026")
                .contains("PDF bu postanın boyut sınırına sığmadığından eklenmedi")
                .contains("Görsel bu postaya eklenmedi — görmek için PDF&#39;i Site Monitor&#39;den indirin (tam çözünürlük).")
                .contains("PDF bu postaya eklenmedi — Site Monitor&#39;den indirin.")
                .doesNotContain("ekteki PDF").doesNotContain("PDF rapor ektedir");
        assertThat(text).contains("[Görsel — Site Monitor'deki PDF'te]").doesNotContain("PDF ekinde").doesNotContain("ekteki PDF");
        // PDF ekli: eski metinler (bayt bayt aynı sözcükler)
        TechErrorReportMail.Built withPdf = TechErrorReportMail.buildFull(in, none, "https://sm.example", "m.pdf");
        assertThat(withPdf.mail().html()).contains("PDF rapor ektedir · Eylül 2026").contains("Görsel ekteki PDF&#39;te (tam çözünürlük).")
                .contains("Son 12 ayın trend grafikleri ekteki PDF&#39;tedir.").doesNotContain("PDF eklenmedi");
        assertThat(withPdf.mail().text()).contains("[Görsel — PDF ekinde]");
        // Okunamayan görsel her iki durumda da kendi metnini taşır
        TechErrorMailImages.Result unreadable = new TechErrorMailImages.Result(Map.of(), List.of(), List.of("annual-summary"), 0);
        assertThat(TechErrorReportMail.buildFull(in, unreadable, null, null).mail().html()).contains("Görsel okunamadı (PDF&#39;te de çizilemedi).");
        // İngilizce
        Template te = TechErrorTestTemplates.withTrend("en");
        TechErrorPdfInput inEn = new TechErrorPdfInput(te, TechErrorValues.normalize(Map.of("fields", f), te), "Mobile", "Digital", 2026, 9,
                "Ayşe", Instant.parse("2026-10-02T06:30:00Z"), images(), null, null);
        String en = TechErrorReportMail.buildFull(inEn, TechErrorMailImages.allSkipped(inEn), null, null).mail().html();
        assertThat(en).contains("PDF not attached · September 2026").contains("Image not included in this e-mail")
                .contains("which is not attached to this e-mail").doesNotContain("in the attached PDF");
    }

    // ── Anomali konuları (2026-10-18) ────────────────────────────────────────────────────────────────

    static Map<String, Object> topic(String id, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("topic", "Konu " + id);
        m.put("title_auto", true);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Template withIssueOption(String lang, String key, Object value) {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults(lang)));
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> sec : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> sc = new LinkedHashMap<>(sec);
            if ("issues".equals(sec.get("kind"))) sc.put(key, value);
            secs.add(sc);
        }
        raw.put("sections", secs);
        return TechErrorTemplate.normalize(raw);
    }

    static TechErrorPdfInput anomaly(Template t, List<Object> topics, Map<String, TechErrorPdfInput.Image> imgs) {
        TechErrorValues.Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", Map.of("error-count", 5000),
                "issues", Map.of("issues", topics)), t, java.time.YearMonth.of(2026, 9)), null);
        return new TechErrorPdfInput(t, v, "Mobil", "Dijital Kanallar", 2026, 9, "Ayşe Yılmaz", Instant.parse("2026-10-02T06:30:00Z"), imgs);
    }

    @Test
    @DisplayName("anomali (2026-10-18): kartta giriş metni (PDF ile aynı, rakamlar kalın), açıklama, konunun görseli kartın içinde CID gömülü (açıklaması üstte; Content-ID'de ':' yok); müşteri etkisi yok → 'Müşteri etkisi yok.'; düz metin de aynı")
    void anomalyCards() throws IOException {
        Template t = TechErrorTemplate.defaults("tr");
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("iss:issues:k1:1", new TechErrorPdfInput.Image(TechErrorMailImagesTest.png(1400, 500, Color.BLUE), 1400, 500));
        TechErrorPdfInput in = anomaly(t, List.of(
                topic("k1", "date", "2026-09-12", "start", "14:00", "end", "15:30", "errors", 170, "outage", true, "outage_id", "INC-2026-0915",
                        "impact", true, "customers", 320, "description", "Limit **sorgusu** zaman aşımı.", "captions", Map.of("1", "Hata grafiği")),
                topic("k2", "errors", 25, "outage", false, "impact", false)), imgs);
        TechErrorMailImages.Result prepared = TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET);
        assertThat(prepared.embedded()).containsOnlyKeys("iss:issues:k1:1");
        assertThat(prepared.embedded().get("iss:issues:k1:1").cid()).isEqualTo("te-img-iss.issues.k1.1");
        TechErrorReportMail.Built b = TechErrorReportMail.buildFull(in, prepared, null, "m.pdf");
        String html = b.mail().html();
        String card = html.substring(html.indexOf("data-slot=\"te-issue\""));
        assertThat(card).contains("1. Konu k1: Hata Artışı")
                .contains("12 Eylül 2026, 14:00–15:30 arasında <strong>170</strong> teknik hata alındı; bu, dönemdeki tüm teknik hataların <strong>%3,4</strong> kadarıdır.")
                .contains("Kesinti kaynaklıdır (Kesinti ID: <strong>INC-2026-0915</strong>). Müşteri etkisi: <strong>320</strong> müşteri.")
                .contains("Limit <strong>sorgusu</strong> zaman aşımı.")
                .contains("Hata grafiği").contains("cid:te-img-iss.issues.k1.1")
                .contains("2. Konu k2: Hata Artışı").contains("Kesinti kaynaklı değildir. Müşteri etkisi yok.");
        // Görsel kartın içinde: kartın kapanışından önce
        int img = card.indexOf("cid:te-img-iss.issues.k1.1");
        int k2 = card.indexOf("2. Konu k2");
        assertThat(img).isLessThan(k2);
        assertThat(b.inline()).extracting(x -> x.cid()).contains("te-img-iss.issues.k1.1");
        assertThat(b.mail().text()).contains("12 Eylül 2026, 14:00–15:30 arasında 170 teknik hata alındı")
                .contains("[Görsel] Hata grafiği").contains("Müşteri etkisi yok.");
        // Bütçe yetmezse "PDF ekinde" kutusu kartın içinde
        String none = TechErrorReportMail.buildFull(in, TechErrorMailImages.prepare(in, 0), null, "m.pdf").mail().html();
        assertThat(none.substring(none.indexOf("data-slot=\"te-issue\""))).contains("data-slot=\"te-image-pdf\"").doesNotContain("cid:te-img-iss");
    }

    @Test
    @DisplayName("anomali: 'konuyu rapora alma' — etkisiz konu e-postada da yok (görseli de gömülmez); eski düzen bölümü eski sayaç satırını basar")
    void anomalyHideTopicAndLegacy() throws IOException {
        Template hide = withIssueOption("tr", "no_impact_display", "hide_topic");
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("iss:issues:k1:1", new TechErrorPdfInput.Image(TechErrorMailImagesTest.png(400, 200, Color.RED), 400, 200));
        TechErrorPdfInput in = anomaly(hide, List.of(topic("k1", "errors", 10, "impact", false), topic("k2", "errors", 20, "impact", true)), imgs);
        TechErrorMailImages.Result prepared = TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET);
        assertThat(prepared.embedded()).isEmpty();                          // rapora alınmayan konunun görseli bütçe yemez
        String html = TechErrorReportMail.buildFull(in, prepared, null, "m.pdf").mail().html();
        assertThat(html).doesNotContain("Konu k1").contains("1. Konu k2: Hata Artışı").contains("Müşteri etkisi var.");

        Template legacy = TechErrorTestTemplates.beforeAnomaly("tr");
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("issues", Map.of("issues", List.of(topic("k1", "customers", 1200, "errors", 3400)))), legacy);
        String old = TechErrorReportMail.build(new TechErrorPdfInput(legacy, v, "Mobil", "T", 2026, 9, null, null, Map.of()), null, "m.pdf").html();
        assertThat(old).contains("Etkilenen müşteri sayısı: 1.200 · Bu konudan kaynaklı teknik hata adedi: 3.400")
                .contains("color:" + MailTokens.WARNING).doesNotContain("teknik hata alındı");
    }
}
