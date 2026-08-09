package com.sitemonitor.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * PDFBox'ın tablo API'si yoktur; bu sınıf marka başlıklı, sayfalanan basit bir tablo çizer.
 * Aylık envanter raporu ekinin tek kullanıcısıdır — genel amaçlı bir kütüphane değil,
 * ihtiyacı kadar küçük tutulmuştur (bkz. InventoryExportService.pdf).
 *
 * <p>Türkçe glyph zorunluluğu: PDFBox'ın standart 14 fontu (Helvetica vb.) ş/ğ/İ/ı taşımaz ve
 * yazmaya çalışınca IllegalArgumentException fırlatır. Bu yüzden Roboto TTF gömülür
 * ({@code resources/report-fonts/}); font yüklenemezse yazılamayan karakterler '?' ile
 * değiştirilir ve rapor yine üretilir (ek tamamen kaybolmasın).
 *
 * <p>{@link AutoCloseable}: try-with-resources ile kullanılır, {@link #finish()} bayt döndürür.
 */
class PdfTable implements AutoCloseable {

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 32f;
    private static final float ROW_H = 15f;
    private static final float HEADER_H = 18f;
    private static final float FONT_SIZE = 7.5f;
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private final PDDocument doc = new PDDocument();
    private final String title;
    private final String[] headers;
    private final float[] widths;
    private final List<String[]> rows = new ArrayList<>();

    /** Roboto yüklenemezse Helvetica'ya düşeriz; o durumda metinler {@link #sanitize} ile ASCII'ye iner. */
    private final boolean embedded;
    private final PDFont regular;
    private final PDFont bold;

    PdfTable(String title, String[] headers, float[] widths) throws IOException {
        this.title = title;
        this.headers = headers;
        this.widths = widths;
        PDType0Font r = loadFont("Roboto-Regular.ttf");
        PDType0Font b = loadFont("Roboto-Bold.ttf");
        this.embedded = r != null && b != null;
        this.regular = embedded ? r : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        this.bold = embedded ? b : new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    private PDType0Font loadFont(String name) {
        try (InputStream in = PdfTable.class.getResourceAsStream("/report-fonts/" + name)) {
            if (in == null) return null;
            return PDType0Font.load(doc, in, true);   // subset: dosya boyutu ~1 MB yerine ~40 KB
        } catch (Exception e) {
            return null;                              // fontsuz devam — sanitize() ASCII'ye düşürür
        }
    }

    void row(String[] cells) { rows.add(cells); }

    byte[] finish() throws IOException {
        int perPage = (int) ((PAGE.getHeight() - MARGIN * 2 - 74) / ROW_H);
        int pages = Math.max(1, (int) Math.ceil(rows.size() / (double) perPage));
        for (int p = 0; p < pages; p++) {
            int from = p * perPage;
            int to = Math.min(rows.size(), from + perPage);
            writePage(p + 1, pages, rows.subList(from, to));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private void writePage(int pageNo, int pageCount, List<String[]> pageRows) throws IOException {
        PDPage page = new PDPage(PAGE);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float y = PAGE.getHeight() - MARGIN;

            // ── Marka başlığı: 32px logo + başlık (BRAND.md §5.1 — kalıcı belgede nötr "ok" varyantı) ──
            y -= 26;
            try (InputStream logo = PdfTable.class.getResourceAsStream("/email-assets/email-ok.png")) {
                if (logo != null) {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo.readAllBytes(), "logo");
                    cs.drawImage(img, MARGIN, y - 4, 26, 26);
                }
            } catch (Exception ignored) { /* logosuz devam */ }
            text(cs, bold, 13f, MARGIN + 34, y + 6, "Site Monitor · " + title);
            text(cs, regular, 8f, MARGIN + 34, y - 6,
                    ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " (GMT+3)"
                    + " · " + rows.size() + " kayıt");
            y -= 22;

            // ── Tablo başlığı ──
            cs.setNonStrokingColor(0.06f, 0.11f, 0.18f);      // NAVY (#0F1B2D)
            cs.addRect(MARGIN, y - HEADER_H + 4, tableWidth(), HEADER_H);
            cs.fill();
            cs.setNonStrokingColor(1f, 1f, 1f);
            float x = MARGIN + 4;
            for (int i = 0; i < headers.length; i++) {
                text(cs, bold, FONT_SIZE, x, y - HEADER_H + 9, clip(headers[i], widths[i] - 6, bold, FONT_SIZE));
                x += widths[i];
            }
            cs.setNonStrokingColor(0.12f, 0.16f, 0.22f);
            y -= HEADER_H + 4;

            // ── Satırlar (zebra) ──
            boolean shade = false;
            for (String[] r : pageRows) {
                if (shade) {
                    cs.setNonStrokingColor(0.96f, 0.97f, 0.98f);
                    cs.addRect(MARGIN, y - 4, tableWidth(), ROW_H);
                    cs.fill();
                    cs.setNonStrokingColor(0.12f, 0.16f, 0.22f);
                }
                x = MARGIN + 4;
                for (int i = 0; i < r.length && i < widths.length; i++) {
                    text(cs, regular, FONT_SIZE, x, y + 1, clip(r[i], widths[i] - 6, regular, FONT_SIZE));
                    x += widths[i];
                }
                y -= ROW_H;
                shade = !shade;
            }

            // ── Altlık ──
            cs.setNonStrokingColor(0.6f, 0.64f, 0.69f);
            text(cs, regular, 7f, MARGIN, MARGIN - 8,
                    "Site Monitor — otomatik aylık envanter raporu");
            text(cs, regular, 7f, PAGE.getWidth() - MARGIN - 50, MARGIN - 8, pageNo + " / " + pageCount);
        }
    }

    private float tableWidth() {
        float w = 0;
        for (float f : widths) w += f;
        return w;
    }

    private void text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s)
            throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(embedded ? s : sanitize(s));
        cs.endText();
    }

    /** Sütuna sığmayan metni "…" ile keser (satır taşması tabloyu bozmasın). */
    private String clip(String s, float maxWidth, PDFont font, float size) throws IOException {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ');
        if (!embedded) v = sanitize(v);
        while (v.length() > 1 && width(v, font, size) > maxWidth) {
            v = v.substring(0, v.length() - 2) + (embedded ? "…" : ".");
        }
        return v;
    }

    private float width(String s, PDFont font, float size) throws IOException {
        return font.getStringWidth(embedded ? s : sanitize(s)) / 1000 * size;
    }

    /** Font yüklenemediyse: WinAnsi dışındaki karakterleri kabaca karşılıklarına indirger. */
    private static String sanitize(String s) {
        return s.replace('ş', 's').replace('Ş', 'S').replace('ğ', 'g').replace('Ğ', 'G')
                .replace('ı', 'i').replace('İ', 'I').replace('ç', 'c').replace('Ç', 'C')
                .replace('ö', 'o').replace('Ö', 'O').replace('ü', 'u').replace('Ü', 'U')
                .replace('…', '.')
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    @Override
    public void close() throws IOException {
        doc.close();
    }
}
