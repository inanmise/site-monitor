package com.sitemonitor.service.report;

import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kanal teknik hata raporu PDF'i (2026-10-13): başlık, biçimli değerler, anomali, 2 kolonlu grafikler, dil. */
class TechErrorReportPdfWriterTest {

    static byte[] chart(int w, int h, Color c) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static TechErrorPdfInput input(String lang, Map<String, TechErrorPdfInput.Image> images) {
        Template t = TechErrorTemplate.defaults(lang);
        Map<String, Object> f = new HashMap<>();
        f.put("error-rate", 0.42);
        f.put("total-tx", 1250000);
        f.put("sla-above", true);
        f.put("unmapped-note", "İlk satır\nİkinci satır");
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", "a");
        issue.put("topic", "Kart Limiti");
        issue.put("title_auto", true);
        issue.put("description", "12 Eylül 09:40'ta mobil uygulamada limit sorgusu zaman aşımına uğradı.");
        issue.put("customers", 1200);
        issue.put("errors", 3400);
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", f, "issues", Map.of("issues", List.of(issue))), t);
        return new TechErrorPdfInput(t, v, "Mobil", "Dijital Kanallar", 2026, 9, "Ayşe Yılmaz",
                Instant.parse("2026-10-02T06:30:00Z"), images);
    }

    static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    static int imageCount(byte[] pdf) throws IOException {
        int n = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage p : doc.getPages()) {
                for (var name : p.getResources().getXObjectNames()) {
                    if (p.getResources().getXObject(name) instanceof PDImageXObject) n++;
                }
            }
        }
        return n;
    }

    @Test
    @DisplayName("TR: başlık, dönem, biçimli değerler, anomali başlığı kalıptan, boş alan —, sayfa numarası Türkçe")
    void turkish() throws IOException {
        byte[] pdf = TechErrorReportPdfWriter.render(input("tr", Map.of()));
        String s = text(pdf);
        assertThat(s).contains("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        assertThat(s).contains("%0,42").contains("1.250.000").contains("Evet");
        assertThat(s).contains("1. Kart Limiti Konusu ile yaşanan problem");
        assertThat(s).contains("Etkilenen müşteri sayısı: 1.200").contains("3.400");
        assertThat(s).contains("İlk satır").contains("İkinci satır");
        assertThat(s).contains("Map Yapılmamış Hata Adedi").contains("Kota Hatası Adedi");
        assertThat(s).contains("Bu bölüme grafik yüklenmedi.");
        assertThat(s).contains("Sayfa 1 /");
        assertThat(s).contains("Hazırlayan: Ayşe Yılmaz");
    }

    @Test
    @DisplayName("grafikler: yüklenen görseller gömülür, yüklenmeyen yuva PDF'e girmez, çizilemeyen görselin yerine uyarı")
    void images() throws IOException {
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("monthly-tx", new TechErrorPdfInput.Image(chart(800, 400, Color.BLUE), 800, 400));
        imgs.put("tech-errors", new TechErrorPdfInput.Image(chart(800, 400, Color.RED), 800, 400));
        imgs.put("daily-errors", new TechErrorPdfInput.Image(chart(1600, 500, Color.GREEN), 1600, 500));
        imgs.put("summary-monthly", new TechErrorPdfInput.Image(new byte[]{ 1, 2, 3 }, 100, 100));   // bozuk
        byte[] pdf = TechErrorReportPdfWriter.render(input("tr", imgs));
        String s = text(pdf);
        assertThat(s).contains("Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri").contains("Mobil Kanalı Teknik Hata Grafiği")
                .contains("Mobil Kanalı Teknik Hata (Günlük)");
        assertThat(s).doesNotContain("Kota Hata Grafiği (Aylık)");          // yüklenmedi → yok
        assertThat(s).contains("Görsel çizilemedi");
        assertThat(imageCount(pdf)).isGreaterThanOrEqualTo(3 + 1);           // 3 grafik + marka logosu
    }

    @Test
    @DisplayName("EN şablon: İngilizce ay, sayı biçimi ve sayfa etiketi; dosya adı dile göre")
    void english() throws IOException {
        byte[] pdf = TechErrorReportPdfWriter.render(input("en", Map.of()));
        String s = text(pdf);
        assertThat(s).contains("Mobil Channel Technical Error Messages - September 2026");
        assertThat(s).contains("0.42%").contains("1,250,000").contains("Yes").contains("Page 1 /");
        assertThat(TechErrorReportPdfWriter.fileName("Bireysel İnternet Şubesi", 2026, 9, "tr"))
                .isEqualTo("bireysel-internet-subesi-teknik-hata-2026-09.pdf");
        assertThat(TechErrorReportPdfWriter.fileName("POS", 2025, 12, "en")).isEqualTo("pos-technical-errors-2025-12.pdf");
    }

    @Test
    @DisplayName("kesinti yok ('Hayır'): kesinti günleri hariç oran ve kesinti kaynaklı alanlar PDF'e girmez; 'Evet' ise girer")
    void outageFieldsLeftOutWhenNoOutage() throws IOException {
        Template t = TechErrorTemplate.defaults("tr");
        Map<String, Object> f = new HashMap<>();
        f.put("error-rate", 0.42);
        f.put("had-outage", false);
        f.put("error-rate-excl", 0.3);
        f.put("outage-errors", 12);
        TechErrorValues.Values no = TechErrorValues.normalize(Map.of("fields", f), t);
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, no, "ATM", "Takım", 2026, 9, null, null, Map.of())));
        assertThat(s).contains("Dönemde kesinti oldu mu?").contains("Hayır").contains("Teknik hata oranı");
        assertThat(s).doesNotContain("Kesinti günleri hariç").doesNotContain("Kesinti kaynaklı");
        f.put("had-outage", true);
        TechErrorValues.Values yes = TechErrorValues.normalize(Map.of("fields", f), t);
        String y = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, yes, "ATM", "Takım", 2026, 9, null, null, Map.of())));
        assertThat(y).contains("Kesinti günleri hariç").contains("Kesinti kaynaklı");
    }

    @Test
    @DisplayName("tüm alanları muaf gösterge bölümü başlığıyla birlikte basılmaz; e-posta özetinde yönetici soru yer kaplamaz")
    void fullyExemptSectionAndMailStats() throws IOException {
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new java.util.ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("id", "outage-detail");
        extra.put("kind", "metrics");
        extra.put("title", "Kesinti Ayrıntıları");
        extra.put("fields", List.of(Map.of("id", "outage-days", "label", "Kesinti gün sayısı", "type", "number", "skip_if_no", "had-outage")));
        secs.add(1, extra);
        raw.put("sections", secs);
        Template t = TechErrorTemplate.normalize(raw);
        Map<String, Object> f = new HashMap<>();
        f.put("had-outage", false);
        f.put("error-count", 52500);
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", f), t);
        TechErrorPdfInput in = new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 9, null, null, Map.of());
        assertThat(text(TechErrorReportPdfWriter.render(in))).doesNotContain("Kesinti Ayrıntıları");
        f.put("had-outage", true);
        TechErrorPdfInput yes = new TechErrorPdfInput(t, TechErrorValues.normalize(Map.of("fields", f), t), "ATM", "Takım", 2026, 9,
                null, null, Map.of());
        assertThat(text(TechErrorReportPdfWriter.render(yes))).contains("Kesinti Ayrıntıları");
        // e-posta: "Dönemde kesinti oldu mu?" 8 göstergelik özetin yerini almaz → hata adedi özette kalır
        String html = com.sitemonitor.service.mail.TechErrorReportMail.build(yes, null, "atm.pdf").html();
        assertThat(html).doesNotContain("Dönemde kesinti oldu mu?").contains("Ay içindeki teknik hata adedi");
        // "Hayır": rakamlar muaf, soru e-postada görünür (PDF ile aynı bilgi)
        String noHtml = com.sitemonitor.service.mail.TechErrorReportMail.build(in, null, "atm.pdf").html();
        assertThat(noHtml).contains("Dönemde kesinti oldu mu?").doesNotContain("Kesinti günleri hariç");
    }

    @Test
    @DisplayName("çok uzun metin sessizce kesilmez: sınırı aşınca 'kısaltıldı' notu basılır")
    void overlongTextIsMarked() throws IOException {
        Template t = TechErrorTemplate.defaults("tr");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4000; i++) b.append("x").append(i % 10).append('\n');
        Map<String, Object> f = new HashMap<>();
        f.put("unmapped-note", b.substring(0, Math.min(b.length(), TechErrorValues.MAX_LONG_TEXT)));
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", f), t);
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 1, null, null, Map.of())));
        assertThat(s).contains("metin kısaltıldı");
    }

    @Test
    @DisplayName("çok yassı ve çizilemeyen görsel: uyarı kutusu kendi satırına sığar (sonraki başlıkla çakışmaz)")
    void flatBrokenImageReservesBox() throws IOException {
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("daily-errors", new TechErrorPdfInput.Image(new byte[]{ 9, 9, 9 }, 6000, 16));   // bozuk + çok yassı
        byte[] pdf = TechErrorReportPdfWriter.render(input("tr", imgs));
        assertThat(text(pdf)).contains("Görsel çizilemedi");
    }

    @Test
    @DisplayName("yer tutucu tekrarı başlığı şişiremez: çözüm 300 karakterde kesilir")
    void placeholderExpansionIsCapped() {
        String pattern = "{kanal}".repeat(28);
        String r = TechErrorTemplate.resolve(pattern, "K".repeat(120), 2026, 9, "tr");
        assertThat(r).hasSizeLessThanOrEqualTo(TechErrorTemplate.MAX_RESOLVED).endsWith("…");
        assertThat(TechErrorTemplate.resolveIssueTitle("{konu} ".repeat(17), "T".repeat(120)))
                .hasSizeLessThanOrEqualTo(TechErrorTemplate.MAX_RESOLVED);
        assertThat(TechErrorTemplate.resolve("{kanal} {ay} {yil}", "Mobil", 2026, 9, "tr")).isEqualTo("Mobil Eylül 2026");
    }

    @Test
    @DisplayName("uzun içerik sayfalara bölünür, hiçbir şey düşmez (30 başlık × uzun açıklama)")
    void longContentPaginates() throws IOException {
        Template t = TechErrorTemplate.defaults("tr");
        List<Object> issues = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            issues.add(Map.of("id", "i" + i, "topic", "Konu " + i, "title_auto", true,
                    "description", ("Uzun açıklama satırı " + i + " ").repeat(40)));
        }
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("issues", Map.of("issues", issues)), t);
        byte[] pdf = TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 1, null, null, Map.of()));
        String s = text(pdf);
        assertThat(s).contains("30. Konu 29 Konusu ile yaşanan problem");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
        }
    }
}
