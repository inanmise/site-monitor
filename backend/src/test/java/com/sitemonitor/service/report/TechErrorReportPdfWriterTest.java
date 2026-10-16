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
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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

    /** Sayfalara çizilen görsellerin ölçüsü {genişlik, yükseklik} (çizim sırasıyla). */
    static List<float[]> drawnImages(byte[] pdf) throws IOException {
        List<float[]> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage p : doc.getPages()) {
                class Recorder extends org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine {
                    Recorder() { super(p); }
                    @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage image) {
                        var m = getGraphicsState().getCurrentTransformationMatrix();
                        out.add(new float[]{ m.getScalingFactorX(), m.getScalingFactorY() });
                    }
                    @Override public void appendRectangle(java.awt.geom.Point2D a, java.awt.geom.Point2D b, java.awt.geom.Point2D c,
                                                          java.awt.geom.Point2D d) { }
                    @Override public void clip(int windingRule) { }
                    @Override public void moveTo(float x, float y) { }
                    @Override public void lineTo(float x, float y) { }
                    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
                    @Override public java.awt.geom.Point2D getCurrentPoint() { return new java.awt.geom.Point2D.Float(); }
                    @Override public void closePath() { }
                    @Override public void endPath() { }
                    @Override public void strokePath() { }
                    @Override public void fillPath(int windingRule) { }
                    @Override public void fillAndStrokePath(int windingRule) { }
                    @Override public void shadingFill(org.apache.pdfbox.cos.COSName name) { }
                }
                new Recorder().processPage(p);
            }
        }
        return out;
    }

    /** PDF'e gömülen görsellerin piksel ölçüleri {genişlik, yükseklik}. */
    static List<int[]> embeddedSizes(byte[] pdf) throws IOException {
        List<int[]> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage p : doc.getPages()) {
                for (var name : p.getResources().getXObjectNames()) {
                    if (p.getResources().getXObject(name) instanceof PDImageXObject img) out.add(new int[]{ img.getWidth(), img.getHeight() });
                }
            }
        }
        return out;
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
        assertThat(s).contains("Map Yapılmamış Hatalar");
        assertThat(s).contains("Bu bölüme görsel yüklenmedi.");
        assertThat(s).contains("Sayfa 1 /");
        assertThat(s).contains("Hazırlayan: Ayşe Yılmaz");
    }

    @Test
    @DisplayName("grafikler: yüklenen görseller gömülür, yüklenmeyen yuva PDF'e girmez, çizilemeyen görselin yerine uyarı")
    void images() throws IOException {
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("monthly-tx", new TechErrorPdfInput.Image(chart(800, 400, Color.BLUE), 800, 400));
        imgs.put("annual-summary", new TechErrorPdfInput.Image(chart(800, 400, Color.RED), 800, 400));
        imgs.put("tech-ratio", new TechErrorPdfInput.Image(chart(1600, 500, Color.GREEN), 1600, 500));
        imgs.put("channel-business-errors", new TechErrorPdfInput.Image(new byte[]{ 1, 2, 3 }, 100, 100));   // bozuk
        byte[] pdf = TechErrorReportPdfWriter.render(input("tr", imgs));
        String s = text(pdf);
        String flat = s.replaceAll("[\\s\\u00a0]+", " ");                          // yarım sütunda başlık iki satıra sarabilir
        assertThat(flat).contains("Toplam Operasyon Adetleri (Son 12 Ay)")
                .contains("Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)")
                .contains("Eylül 2026 Teknik Hataların Tüm Operasyonlara Oranı (Günlük)");
        assertThat(flat).doesNotContain("Kota & Maplenmemiş Hata Adetleri");   // yüklenmedi → yok
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
        secs.add(2, extra);   // gösterge özetinin (1) ardına; 0 = yönetici özeti
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
    @DisplayName("görsel yuvanın genişliğini doldurur (kullanıcı kararı 2026-10-16): küçük büyür, büyük küçülür, oran korunur; yalnız sayfaya sığmayan uzun görsel en çok IMAGE_MAX_H")
    void imagesFillSlotWidth() throws IOException {
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("annual-summary", new TechErrorPdfInput.Image(chart(3000, 1400, Color.BLUE), 3000, 1400));   // yarım, büyük
        imgs.put("monthly-tx", new TechErrorPdfInput.Image(chart(300, 180, Color.RED), 300, 180));            // yarım, küçük
        imgs.put("annual-table", new TechErrorPdfInput.Image(chart(600, 2400, Color.GRAY), 600, 2400));       // tam, çok uzun
        imgs.put("tech-ratio", new TechErrorPdfInput.Image(chart(400, 80, Color.GREEN), 400, 80));            // tam, küçük
        byte[] pdf = TechErrorReportPdfWriter.render(input("tr", imgs));
        List<float[]> d = drawnImages(pdf).stream().filter(x -> x[0] > 120).toList();      // marka logosu küçük
        float full = PdfCanvas.CONTENT_W, half = (PdfCanvas.CONTENT_W - 16) / 2;
        assertThat(d).hasSize(4);
        assertThat(d.get(0)[0]).isCloseTo(half, org.assertj.core.data.Offset.offset(0.5f));
        assertThat(d.get(0)[1]).isCloseTo(half * 1400 / 3000, org.assertj.core.data.Offset.offset(0.5f));
        assertThat(d.get(1)[0]).isCloseTo(half, org.assertj.core.data.Offset.offset(0.5f));               // 300 px → yuva genişliği
        assertThat(d.get(1)[1]).isCloseTo(half * 180 / 300, org.assertj.core.data.Offset.offset(0.5f));
        assertThat(d.get(2)[1]).isCloseTo(TechErrorReportPdfWriter.IMAGE_MAX_H, org.assertj.core.data.Offset.offset(0.5f));
        assertThat(d.get(2)[0]).isCloseTo(TechErrorReportPdfWriter.IMAGE_MAX_H * 600 / 2400, org.assertj.core.data.Offset.offset(0.5f));
        assertThat(d.get(3)[0]).isCloseTo(full, org.assertj.core.data.Offset.offset(0.5f));                // 400 px → tam genişlik
        assertThat(d.get(3)[1]).isCloseTo(full * 80 / 400, org.assertj.core.data.Offset.offset(0.5f));
        // Bölüm başlığı (3 satır) + resim yazısı (3 satır) + en uzun görsel + aralıklar bir sayfaya sığar
        float usable = PdfCanvas.PAGE.getHeight() - PdfCanvas.MARGIN - PdfCanvas.BOTTOM;
        assertThat(3 * 15 + 6 + 3 * 10.5f + 2 + TechErrorReportPdfWriter.IMAGE_MAX_H + 20).isLessThanOrEqualTo(usable);
        assertThat(TechErrorReportPdfWriter.fit(200, 100, 50, 640)).containsExactly(200f, 100f);
        // Gömülen çözünürlük (2026-10-16): çizim ölçüsünün en çok 6 katı (≈ 432 dpi) — 3000 px'lik görsel yarım yuvada alan
        // ortalamasıyla küçülür; sınır içindekiler (300 / 400 / 600 px) olduğu gibi gömülür
        List<int[]> px = embeddedSizes(pdf);
        int halfCap = (int) Math.ceil(half * PdfCanvas.EMBED_SCALE);
        assertThat(px).anySatisfy(s -> assertThat(s[0]).isEqualTo(halfCap));
        assertThat(px).anySatisfy(s -> assertThat(s).containsExactly(300, 180));
        assertThat(px).anySatisfy(s -> assertThat(s).containsExactly(400, 80));
        assertThat(px).anySatisfy(s -> assertThat(s).containsExactly(600, 2400));
        assertThat(px).noneSatisfy(s -> assertThat(s[0]).isEqualTo(3000));
        assertThat(TechErrorReportPdfWriter.fit(200, 1000, 500, 640)).containsExactly(200f, 100f);
    }

    @Test
    @DisplayName("inceleme 2026-10-16: gri PNG PDF'te ekrandaki tonuyla basılır (küçültülse de küçültülmese de 128 → 128, 188 değil)")
    void grayPngKeepsToneInPdf() throws IOException {
        BufferedImage small = new BufferedImage(400, 100, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage wide = new BufferedImage(3000, 400, BufferedImage.TYPE_BYTE_GRAY);
        for (BufferedImage g : List.of(small, wide)) {
            for (int y = 0; y < g.getHeight(); y++) for (int x = 0; x < g.getWidth(); x++) g.getRaster().setSample(x, y, 0, 128);
        }
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("tech-ratio", new TechErrorPdfInput.Image(png(small), 400, 100));               // tam yuva, sığar
        imgs.put("annual-summary", new TechErrorPdfInput.Image(png(wide), 3000, 400));            // yarım yuva, küçülür
        byte[] pdf = TechErrorReportPdfWriter.render(input("tr", imgs));
        int seen = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage p : doc.getPages()) {
                for (var name : p.getResources().getXObjectNames()) {
                    // marka logosu hariç: 400 × 100 (sığan) ve ≈ 1522 × 202 (küçülen)
                    if (p.getResources().getXObject(name) instanceof PDImageXObject img
                            && ((img.getWidth() == 400 && img.getHeight() == 100) || (img.getWidth() > 1400 && img.getHeight() < 300))) {
                        BufferedImage b = img.getImage();
                        assertThat(b.getRGB(b.getWidth() / 2, b.getHeight() / 2) & 0xff).as(name.getName()).isBetween(127, 129);
                        seen++;
                    }
                }
            }
        }
        assertThat(seen).isEqualTo(2);
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("çok yassı ve çizilemeyen görsel: uyarı kutusu kendi satırına sığar (sonraki başlıkla çakışmaz)")
    void flatBrokenImageReservesBox() throws IOException {
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("tech-ratio", new TechErrorPdfInput.Image(new byte[]{ 9, 9, 9 }, 6000, 16));   // bozuk + çok yassı
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

    @Test
    @DisplayName("otomatik trend: girilen rakamlardan son 12 ay çubuk grafik (kısaltılmış etiket, ay adları); iki aydan az veri → not")
    void trendCharts() throws IOException {
        Template t = com.sitemonitor.service.techerror.TechErrorTestTemplates.withTrend("tr");
        TechErrorValues.Values cur = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42, "total-tx", 125_430_112)), t);
        com.sitemonitor.model.TechErrorReport aug = new com.sitemonitor.model.TechErrorReport();
        aug.setId(2L);
        aug.setReportYear(2026);
        aug.setReportMonth(8);
        aug.setTemplateJson(TechErrorTemplate.toJson(t));
        aug.setValuesJson(TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.5)), t)));
        var trend = com.sitemonitor.service.techerror.TechErrorTrend.build(t, cur, 2026, 9, List.of(aug));
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, cur, "Mobil", "Takım", 2026, 9, null, null, Map.of(), trend)))
                .replaceAll("[\\s\\u00a0]+", " ");
        assertThat(s).contains("Otomatik Trendler (Son 12 Ay)").contains("Teknik hata oranı (Son 12 Ay)")
                .contains("%0,42").contains("%0,5").contains("Eki").contains("Ağu").contains("Eyl");
        // toplam işlem yalnız bu ay → grafik yerine not; diğerleri hiç → not
        assertThat(s).contains("Toplam işlem adedi (Son 12 Ay)").contains("Trend için en az iki ayın rakamı gerekir");
        assertThat(s).doesNotContain("125Mn");
        // EN
        Template en = com.sitemonitor.service.techerror.TechErrorTestTemplates.withTrend("en");
        TechErrorValues.Values ev = TechErrorValues.normalize(Map.of("fields", Map.of("total-tx", 1_250_000)), en);
        aug.setTemplateJson(TechErrorTemplate.toJson(en));
        aug.setValuesJson(TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", Map.of("total-tx", 900_000)), en)));
        var et = com.sitemonitor.service.techerror.TechErrorTrend.build(en, ev, 2026, 9, List.of(aug));
        String e = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(en, ev, "ATM", "Team", 2026, 9, null, null, Map.of(), et)))
                .replaceAll("[\\s\\u00a0]+", " ");
        assertThat(e).contains("Automatic Trends (Last 12 Months)").contains("Total transactions (Last 12 Months)")
                .contains("1.3M").contains("900K").contains("Sep");
    }

    @Test
    @DisplayName("trend: bu ay muaf alanın grafiği basılmaz; uzun adda zaman tabanı kalır; negatif değerler çizilir (inceleme 2026-10-15)")
    @SuppressWarnings("unchecked")
    void trendReviewRules() throws IOException {
        Template base = com.sitemonitor.service.techerror.TechErrorTestTemplates.withTrend("tr");
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(base));
        List<Object> sections = new ArrayList<>();
        for (Object o : (List<Object>) raw.get("sections")) {
            Map<String, Object> sec = new LinkedHashMap<>((Map<String, Object>) o);
            if ("auto-trend".equals(sec.get("id"))) sec.put("series", List.of("error-rate", "outage-errors"));
            if ("metrics".equals(sec.get("kind"))) {
                List<Object> fs = new ArrayList<>();
                for (Object fo : (List<Object>) sec.get("fields")) {
                    Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) fo);
                    if ("error-rate".equals(f.get("id"))) f.put("label", "Kanalın tüm işlemleri içinde teknik hataların oranı ".repeat(3).trim());
                    fs.add(f);
                }
                sec.put("fields", fs);
            }
            sections.add(sec);
        }
        raw.put("sections", sections);
        Template t = TechErrorTemplate.normalize(raw);
        com.sitemonitor.model.TechErrorReport aug = new com.sitemonitor.model.TechErrorReport();
        aug.setId(2L);
        aug.setReportYear(2026);
        aug.setReportMonth(8);
        aug.setTemplateJson(TechErrorTemplate.toJson(t));
        aug.setValuesJson(TechErrorValues.toJson(TechErrorValues.normalize(
                Map.of("fields", Map.of("error-rate", -12.25, "had-outage", true, "outage-errors", 437)), t)));
        com.sitemonitor.model.TechErrorReport jul = new com.sitemonitor.model.TechErrorReport();
        jul.setId(3L);
        jul.setReportYear(2026);
        jul.setReportMonth(7);
        jul.setTemplateJson(TechErrorTemplate.toJson(t));
        jul.setValuesJson(TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", -7.75)), t)));
        // Bu ay "Dönemde kesinti oldu mu?" = Hayır → kesinti alanı muaf: grafiği de basılmaz
        TechErrorValues.Values cur = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", -2.5, "had-outage", false)), t);
        var trend = com.sitemonitor.service.techerror.TechErrorTrend.build(t, cur, 2026, 9, List.of(aug, jul));
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, cur, "Mobil", "Takım", 2026, 9, null, null, Map.of(), trend)))
                .replaceAll("[\\s\\u00a0]+", " ");
        assertThat(s).doesNotContain("Kesinti kaynaklı teknik hata adedi (Son 12 Ay)");
        // Uzun ad kısaltılır ama "(Son 12 Ay)" düşmez: kısaltmanın hemen ardında (bölüm başlığındaki ek değil)
        assertThat(s).contains("… (Son 12 Ay)");
        // Yalnız grafikte olan değerler (Temmuz / Ağustos): negatif çubuk etiketleri basılır
        assertThat(s).contains("%-7,75").contains("%-12,25");
        // Soru "Evet" ise kesinti grafiği (ya da notu) basılır
        TechErrorValues.Values yes = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.4, "had-outage", true, "outage-errors", 12)), t);
        var yt = com.sitemonitor.service.techerror.TechErrorTrend.build(t, yes, 2026, 9, List.of(aug));
        String y = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, yes, "Mobil", "Takım", 2026, 9, null, null, Map.of(), yt)))
                .replaceAll("[\\s\\u00a0]+", " ");
        assertThat(y).contains("Kesinti kaynaklı teknik hata adedi (Son 12 Ay)").contains("437");
    }

    /** Sayfadaki sözcükler: metin, sol / sağ x, taban çizgisi (yukarıdan aşağı), sayfa. */
    private record Word(String text, float left, float right, float baseline, int page) {
        float center() { return (left + right) / 2; }
    }

    private static List<Word> words(byte[] pdf) throws IOException {
        List<Word> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper st = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> pos) {
                    if (pos.isEmpty()) return;
                    TextPosition a = pos.get(0);
                    TextPosition b = pos.get(pos.size() - 1);
                    out.add(new Word(text.trim(), a.getXDirAdj(), b.getXDirAdj() + b.getWidthDirAdj(), a.getYDirAdj(), getCurrentPageNo()));
                }
            };
            st.setSortByPosition(true);
            st.getText(doc);
        }
        return out;
    }

    @Test
    @DisplayName("trend: en derin negatif çubuğun etiketi ay adının ÜSTÜNDE kalır (alt etiket payı — inceleme 2026-10-15)")
    void trendNegativeLabelClearsMonthNames() throws IOException {
        Template t = com.sitemonitor.service.techerror.TechErrorTestTemplates.withTrend("tr");
        com.sitemonitor.model.TechErrorReport aug = new com.sitemonitor.model.TechErrorReport();
        aug.setId(2L);
        aug.setReportYear(2026);
        aug.setReportMonth(8);
        aug.setTemplateJson(TechErrorTemplate.toJson(t));
        aug.setValuesJson(TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", -12.25)), t)));
        TechErrorValues.Values cur = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", -2.5)), t);
        var trend = com.sitemonitor.service.techerror.TechErrorTrend.build(t, cur, 2026, 9, List.of(aug));
        List<Word> ws = words(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, cur, "Mobil", "Takım", 2026, 9, null, null, Map.of(), trend)));
        Word label = ws.stream().filter(w -> w.text().equals("%-12,25")).findFirst().orElseThrow();
        // Aynı çubuğun ay adı: aynı sayfada, merkezi etiketin merkezine yakın
        Word month = ws.stream().filter(w -> w.text().equals("Ağu") && w.page() == label.page()
                && Math.abs(w.center() - label.center()) < 6).findFirst().orElseThrow();
        // Yukarıdan aşağı: ay adının taban çizgisi etiketinkinden en az bir satır aşağıda (pay yokken fark ~1,4 pt idi)
        assertThat(month.baseline() - label.baseline()).isGreaterThan(6f);
    }

    @Test
    @DisplayName("görsel bölümü başlığı sayfa sonunda yalnız kalmaz: başlık ile ilk görsel satırı aynı sayfada (inceleme 2026-10-15)")
    void imageSectionTitleNeverOrphaned() throws IOException {
        Template t = TechErrorTemplate.defaults("tr");
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        imgs.put("annual-summary", new TechErrorPdfInput.Image(chart(800, 600, Color.BLUE), 800, 600));
        imgs.put("monthly-tx", new TechErrorPdfInput.Image(chart(800, 600, Color.RED), 800, 600));
        // Konu sayısı bölümün sayfadaki yerini kaydırır: sınır durumlarını da tarar
        for (int n = 0; n < 28; n++) {
            List<Object> issues = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                issues.add(Map.of("id", "k" + i, "topic", "Konu " + i, "title_auto", true, "description", ("Satır " + i + " ").repeat(12)));
            }
            TechErrorValues.Values v = TechErrorValues.normalize(Map.of("issues", Map.of("issues", issues)), t);
            byte[] pdf = TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "Mobil", "Takım", 2026, 9, null, null, imgs));
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                int titlePage = -1, captionPage = -1;
                for (int pg = 1; pg <= doc.getNumberOfPages(); pg++) {
                    PDFTextStripper st = new PDFTextStripper();
                    st.setStartPage(pg);
                    st.setEndPage(pg);
                    String txt = st.getText(doc).replaceAll("[\\s\\u00a0]+", " ");
                    if (titlePage < 0 && txt.contains("12 Aylık Özetler")) titlePage = pg;
                    if (captionPage < 0 && txt.contains("Yıllık Operasyon ve Hata")) captionPage = pg;
                }
                assertThat(titlePage).as("konu sayısı %d", n).isPositive();
                assertThat(captionPage).as("konu sayısı %d", n).isEqualTo(titlePage);
            }
        }
    }

    @Test
    @DisplayName("yönetici özeti PDF'in başında, rakamlar kalıptan; boş tablo basılmaz, dolu tablo grup satırıyla basılır")
    void executiveSummaryAndTable() throws IOException {
        // Varsayılanda tablo yok (SLA tablosu ekran görüntüsü, 2026-10-15) — tablo bölümü türü sınanır: şablona eklenir
        Map<String, Object> rawT = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr")));
        List<Object> secsT = new ArrayList<>((List<?>) rawT.get("sections"));
        secsT.add(Map.of("id", "sla-table", "kind", "table", "title", "SLA Erişilebilirlik Tablosu",
                "columns", List.of(Map.of("id", "functions", "label", "Fonksiyon adedi", "type", "number"),
                        Map.of("id", "availability", "label", "Erişilebilirlik (kesintisiz)", "type", "percent")),
                "rows", List.of(Map.of("id", "total", "label", "{kanal} Kanalı Toplam", "group", true),
                        Map.of("id", "sla-critical", "label", "SLA dahil ve kritik"))));
        rawT.put("sections", secsT);
        Template t = TechErrorTemplate.normalize(rawT);
        String sla = "sla-table";
        String empty = text(TechErrorReportPdfWriter.render(input("tr", Map.of())));
        assertThat(empty).contains("Yönetici Özeti").contains("verilerine göre teknik hata oranı").contains("%0,42");
        assertThat(empty.indexOf("Yönetici Özeti")).isLessThan(empty.indexOf("Teknik hata oranı"));   // özet göstergelerden önce
        // Boş TABLO basılmaz (SLA ekran görüntüsü bölümü ise başlığı ve "görsel yüklenmedi" notuyla basılır)
        assertThat(text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, TechErrorValues.normalize(Map.of(), t), "Mobil",
                "Takım", 2026, 9, null, null, Map.of())))).doesNotContain("Fonksiyon adedi").doesNotContain("Mobil Kanalı Toplam");
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("total", Map.of("functions", 1250, "availability", 99.5));
        rows.put("sla-critical", Map.of("functions", 40));
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42),
                "tables", Map.of(sla, rows)), t);
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "Mobil", "Takım", 2026, 9, null, null, Map.of())));
        assertThat(s).contains("SLA Erişilebilirlik Tablosu").contains("Mobil Kanalı Toplam").contains("SLA dahil ve kritik")
                .contains("1.250").contains("%99,5").contains("Fonksiyon");
    }

    @Test
    @DisplayName("uzun tablo sayfaya bölünür ve başlık satırı yeni sayfada tekrar çizilir")
    void longTableRepeatsHeader() throws IOException {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        List<Object> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(Map.of("id", "r" + i, "label", "Satır " + i, "group", i % 10 == 0));
        raw.put("sections", List.of(Map.of("id", "tb", "kind", "table", "title", "Uzun Tablo",
                "columns", List.of(Map.of("id", "a", "label", "Benzersiz Sütun Başlığı", "type", "text")), "rows", rows)));
        Template t = TechErrorTemplate.normalize(raw);
        Map<String, Object> vals = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) vals.put("r" + i, Map.of("a", ("uzun hücre metni " + i + " ").repeat(8)));
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("tables", Map.of("tb", vals)), t);
        byte[] pdf = TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 9, null, null, Map.of()));
        String s = text(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
        assertThat(s.split("Benzersiz Sütun Başlığı", -1).length - 1).isGreaterThan(1);
        assertThat(s).contains("Satır 39");
    }

    @Test
    @DisplayName("sayı hücresi karakter ortasından bölünmez: önce küçülür, sığmazsa yalnız ayraçtan bölünür")
    void numberCellNeverSplitsDigits() throws IOException {
        try (PdfCanvas c = new PdfCanvas()) {
            PdfCanvas.NumberCell fits = c.numberCell("125.430.112", 31.8f, c.regular, 8f, 5.5f, '.');
            assertThat(fits.lines()).containsExactly("125.430.112");
            assertThat(fits.size()).isLessThan(8f).isGreaterThanOrEqualTo(5.5f);
            PdfCanvas.NumberCell big = c.numberCell("1.250.000.000.000.000", 31.8f, c.regular, 8f, 5.5f, '.');
            assertThat(big.lines().size()).isGreaterThan(1);
            assertThat(String.join("", big.lines())).isEqualTo("1.250.000.000.000.000");
            for (int i = 0; i < big.lines().size() - 1; i++) assertThat(big.lines().get(i)).endsWith(".");
            assertThat(c.numberCell("%99,5", 200f, c.regular, 8f, 5.5f, '.')).isEqualTo(new PdfCanvas.NumberCell(List.of("%99,5"), 8f));
            // Ondalık kısım ve işaret ayrılmaz: yalnız binlik ayracından bölünür (TR '.', EN ',')
            PdfCanvas.NumberCell dec = c.numberCell("%-1.234.567,89", 12f, c.regular, 8f, 5.5f, '.');
            assertThat(String.join("", dec.lines())).isEqualTo("%-1.234.567,89");
            assertThat(dec.lines().get(dec.lines().size() - 1)).endsWith("567,89");
            PdfCanvas.NumberCell en = c.numberCell("1,234,567.89", 12f, c.regular, 8f, 5.5f, ',');
            assertThat(en.lines().get(en.lines().size() - 1)).isEqualTo("567.89");
        }
    }

    /** {@code n} sütunlu tablo (her biri sayı, ya da {@code textCol} ile bir metin sütunu). */
    static Template wideTable(int n, boolean textCol) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        List<Object> cols = new java.util.ArrayList<>();
        for (int k = 0; k < n; k++) cols.add(Map.of("id", "c" + k, "label", "Sütun " + k, "type", textCol && k == 0 ? "text" : "number"));
        raw.put("sections", List.of(Map.of("id", "tb", "kind", "table", "title", "Geniş Tablo", "columns", cols,
                "rows", List.of(Map.of("id", "a", "label", "Toplam", "group", true), Map.of("id", "b", "label", "Alt")))));
        return TechErrorTemplate.normalize(raw);
    }

    @Test
    @DisplayName("10 sütunlu tablo: 9 haneli sayı tek parça basılır; sığmayan metin hücresi kısaltılırsa tablo altına not düşülür")
    void wideTableKeepsNumbersAndNotesCuts() throws IOException {
        Template t = wideTable(10, false);
        Map<String, Object> row = new LinkedHashMap<>();
        for (int k = 0; k < 10; k++) row.put("c" + k, 125430112);
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("tables", Map.of("tb", Map.of("a", row))), t);
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 9, null, null, Map.of())));
        assertThat(s.split("125\\.430\\.112", -1).length - 1).isEqualTo(10);
        assertThat(s).doesNotContain("kısaltıldı");
        Template tt = wideTable(10, true);
        TechErrorValues.Values tv = TechErrorValues.normalize(Map.of("tables", Map.of("tb", Map.of("b",
                Map.of("c0", "çok uzun açıklama metni ".repeat(8).trim())))), tt);
        String ts = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(tt, tv, "ATM", "Takım", 2026, 9, null, null, Map.of())));
        assertThat(ts).contains("Bu tablodaki bazı hücreler kısaltıldı; tamamı Site Monitor'de.");
    }

    @Test
    @DisplayName("tablo başlığı sayfa sonunda yalnız kalmaz: başlık ile sütun başlıkları her uzunlukta aynı sayfada")
    void tableTitleNeverOrphaned() throws IOException {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "pad", "kind", "text", "title", "Dolgu", "text", ""),
                Map.of("id", "tb", "kind", "table", "title", "Erişilebilirlik Tablosu",
                        "columns", List.of(Map.of("id", "a", "label", "Benzersizbaşlık", "type", "number")),
                        "rows", List.of(Map.of("id", "r", "label", "Satır")))));
        Template t = TechErrorTemplate.normalize(raw);
        for (int lines = 1; lines <= 70; lines++) {
            TechErrorValues.Values v = TechErrorValues.normalize(Map.of(
                    "texts", Map.of("pad", "dolgu satırı\n".repeat(lines).trim()),
                    "tables", Map.of("tb", Map.of("r", Map.of("a", 5)))), t);
            byte[] pdf = TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 9, null, null, Map.of()));
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    PDFTextStripper st = new PDFTextStripper();
                    st.setStartPage(p);
                    st.setEndPage(p);
                    String page = st.getText(doc);
                    if (page.contains("Erişilebilirlik Tablosu")) {
                        assertThat(page).as("dolgu %d satır, sayfa %d", lines, p).contains("Benzersizbaşlık").contains("Satır");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("açıklamalarda madde, alt madde ve kalın yazı PDF'te işaretleriyle basılır; ** işareti görünmez")
    void richTextInPdf() throws IOException {
        Template t = TechErrorTemplate.defaults("tr");
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", Map.of(
                "unmapped-note", "Giriş satırı\n- Birinci madde\n  - Alt madde\n1. Numaralı\n**Kalın** son")), t);
        String s = text(TechErrorReportPdfWriter.render(new TechErrorPdfInput(t, v, "ATM", "Takım", 2026, 9, null, null, Map.of())));
        assertThat(s).contains("• Birinci madde").contains("– Alt madde").contains("1. Numaralı").contains("Kalın son");
        assertThat(s).doesNotContain("**");
    }

    @Test
    @DisplayName("e-posta yönetici özetiyle açılır: kalın rakam HTML'de <strong>, düz metin eşinde işaretsiz")
    void mailOpensWithSummary() {
        TechErrorPdfInput in = input("tr", Map.of());
        var m = com.sitemonitor.service.mail.TechErrorReportMail.build(in, "https://sm.example", "mobil.pdf");
        assertThat(m.html()).contains("Yönetici Özeti").contains("<strong>%0,42</strong>");
        assertThat(m.text()).contains("teknik hata oranı %0,42 olarak").doesNotContain("**");
        assertThat(m.html().indexOf("Yönetici Özeti")).isLessThan(m.html().indexOf("ekip girmiştir"));
    }

    @Test
    @DisplayName("2026-10-16: oranlar hesaplanıyorsa PDF ve e-posta notu bunu söyler; e-posta kutucuklarında girilen adetler + otomatik oranlar")
    void computedRatesNoteAndMailStats() throws IOException {
        Template ct = TechErrorTemplate.defaults("tr");
        TechErrorValues.Values cv = com.sitemonitor.service.techerror.TechErrorCalc.apply(ct, TechErrorValues.normalize(
                Map.of("fields", Map.of("total-tx", 1000, "error-count", 5, "quota-count", 1)), ct), null);
        TechErrorPdfInput in = new TechErrorPdfInput(ct, cv, "Mobil", "Takım", 2026, 9, null, null, Map.of());
        assertThat(text(TechErrorReportPdfWriter.render(in)).replaceAll("[\\s\\u00a0]+", " "))
                .contains("oranlar bu adetlerden Site Monitor tarafından hesaplanmıştır").doesNotContain("sistem hesaplamaz");
        var m = com.sitemonitor.service.mail.TechErrorReportMail.build(in, null, "mobil.pdf");
        assertThat(m.html()).contains("Adetleri ekip girmiştir; oranları Site Monitor bu adetlerden hesaplamıştır.")
                .contains("Toplam işlem adedi").contains("Teknik hata oranı").contains("Kota hatalarının teknik hatalara oranı")
                .doesNotContain("bu rakamları hesaplamaz");
        // Şablonda oran formülü var ama bu ay hiçbiri hesaplanamadı (adet girilmemiş; oran elle) → eski not (inceleme 2026-10-16)
        TechErrorPdfInput none = input("tr", Map.of());
        assertThat(text(TechErrorReportPdfWriter.render(none))).contains("sistem hesaplamaz");
        assertThat(com.sitemonitor.service.mail.TechErrorReportMail.build(none, null, "m.pdf").html())
                .contains("Site Monitor bu rakamları hesaplamaz.");
        // Hesaplanan alanı olmayan (eski) şablon: eski not
        Template d = TechErrorTemplate.defaults("tr");
        Template old = new Template(d.lang(), d.title(), d.sections().stream().filter(s -> !"rates".equals(s.id())).toList());
        TechErrorPdfInput oldIn = new TechErrorPdfInput(old, TechErrorValues.normalize(Map.of(), old), "Mobil", "Takım", 2026, 9,
                null, null, Map.of());
        assertThat(text(TechErrorReportPdfWriter.render(oldIn))).contains("sistem hesaplamaz");
        assertThat(com.sitemonitor.service.mail.TechErrorReportMail.build(oldIn, null, "m.pdf").html())
                .contains("Değerleri ekip girmiştir; Site Monitor bu rakamları hesaplamaz.");
    }
}
