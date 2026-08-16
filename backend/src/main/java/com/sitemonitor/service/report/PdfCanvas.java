package com.sitemonitor.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF çiziminin ALT KATMANI: font gömme, sayfa yönetimi, metin/tablo/rozet ilkelleri.
 *
 * <p>Türkçe glyph için Roboto TTF gömülür; standart 14 PDF fontu ş/ğ/İ taşımaz ve yazmaya
 * çalışınca {@code IllegalArgumentException} fırlatıp TÜM belge üretimini düşürür. Font
 * bulunamazsa ASCII'ye indirgenir ve belge yine üretilir — ek kaybolmaz.
 *
 * <p><b>Bilinen tekrar (bilinçli).</b> {@code InventoryPdfWriter} bu plumbing'in kendi
 * kopyasını taşıyor. Çalışan ve testli aylık rapora bu iş kapsamında dokunmamak için
 * taşınmadı; sessiz kalmasın diye burada ve TESTING.md'de yazılı. Yeni bir PDF yazarken
 * kopyalama — bu sınıfı kullan.
 */
class PdfCanvas implements AutoCloseable {

    static final PDRectangle PAGE = PDRectangle.A4;
    static final float MARGIN = 36f;
    static final float BOTTOM = 52f;
    static final float CONTENT_W = PAGE.getWidth() - MARGIN * 2;

    static final float[] INK    = { 30 / 255f, 41 / 255f, 59 / 255f };
    static final float[] BLUE   = { 37 / 255f, 99 / 255f, 235 / 255f };
    static final float[] LABEL  = { 100 / 255f, 116 / 255f, 139 / 255f };
    static final float[] MUTED  = { 148 / 255f, 163 / 255f, 184 / 255f };
    static final float[] RED    = { 190 / 255f, 18 / 255f, 60 / 255f };
    static final float[] AMBER  = { 180 / 255f, 83 / 255f, 9 / 255f };
    static final float[] GREEN  = { 6 / 255f, 95 / 255f, 70 / 255f };
    static final float[] RULE   = { 226 / 255f, 232 / 255f, 240 / 255f };
    static final float[] ZEBRA  = { 248 / 255f, 250 / 255f, 252 / 255f };
    static final float[] WHITE  = { 1f, 1f, 1f };

    private final PDDocument doc = new PDDocument();
    private final boolean embedded;
    final PDFont regular;
    final PDFont bold;

    private PDPageContentStream cs;
    private final List<PDPage> pages = new ArrayList<>();
    float y;

    /** Yeni sayfa açıldığında çağrılır — tablo başlıklarını tekrar çizmek için. */
    private Runnable pageHeaderHook;

    PdfCanvas() {
        PDType0Font r = loadFont("Roboto-Regular.ttf");
        PDType0Font b = loadFont("Roboto-Bold.ttf");
        this.embedded = r != null && b != null;
        this.regular = embedded ? r : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        this.bold = embedded ? b : new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    private PDType0Font loadFont(String name) {
        try (InputStream in = PdfCanvas.class.getResourceAsStream("/report-fonts/" + name)) {
            return in == null ? null : PDType0Font.load(doc, in, true);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Sayfa yönetimi ───────────────────────────────────────────────────────

    void newPage() throws IOException {
        closeStream();
        PDPage page = new PDPage(PAGE);
        doc.addPage(page);
        pages.add(page);
        cs = new PDPageContentStream(doc, page);
        y = PAGE.getHeight() - MARGIN;
        if (pageHeaderHook != null) pageHeaderHook.run();
    }

    void ensureSpace(float needed) throws IOException {
        if (y - needed < BOTTOM) newPage();
    }

    void setPageHeaderHook(Runnable hook) { this.pageHeaderHook = hook; }

    private void closeStream() throws IOException {
        if (cs != null) { cs.close(); cs = null; }
    }

    int pageCount() { return pages.size(); }

    byte[] finish() throws IOException {
        closeStream();
        stampPageNumbers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /** Sayfa numaraları en sonda basılır — toplam ancak her şey çizilince bilinir. */
    private void stampPageNumbers() throws IOException {
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream s = new PDPageContentStream(
                    doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                s.setNonStrokingColor(MUTED[0], MUTED[1], MUTED[2]);
                s.beginText();
                s.setFont(regular, 7f);
                s.newLineAtOffset(PAGE.getWidth() - MARGIN - 60, MARGIN - 18);
                s.showText("Sayfa " + (i + 1) + " / " + total);
                s.endText();
            }
        }
    }

    // ── Çizim ilkelleri ──────────────────────────────────────────────────────

    void text(PDFont font, float size, float x, float yy, String s, float[] color) throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, yy);
        cs.showText(encodable(font, s));
        cs.endText();
    }

    void rect(float x, float yy, float w, float h, float[] color) throws IOException {
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        cs.addRect(x, yy, w, h);
        cs.fill();
    }

    void line(float x1, float yy, float x2, float[] color) throws IOException {
        cs.setStrokingColor(color[0], color[1], color[2]);
        cs.setLineWidth(0.6f);
        cs.moveTo(x1, yy);
        cs.lineTo(x2, yy);
        cs.stroke();
    }

    /** Küçük renkli etiket (seviye/rozet). Çizimden sonraki x'i döner. */
    float chip(float x, float yy, String label, float[] bg, float[] fg) throws IOException {
        float w = width(label, bold, 6.5f) + 8;
        rect(x, yy - 2.5f, w, 10.5f, bg);
        text(bold, 6.5f, x + 4, yy, label, fg);
        return x + w + 4;
    }

    float width(String s, PDFont font, float size) throws IOException {
        return font.getStringWidth(encodable(font, s)) / 1000 * size;
    }

    // ── Metin güvenliği ──────────────────────────────────────────────────────

    /**
     * Fontun ÇİZEMEYECEĞİ karakterleri güvenli karşılıklarına indirger.
     *
     * <p>Zorunlu: alarm mesajları serbest metindir (uzak sunucudan gelen hata dizeleri dahil) ve
     * içlerinde her türlü karakter olabilir. Tek bir çizilemeyen karakter yüzünden haftalık
     * raporun eki tamamen kaybolmasın diye burada süzülür.
     */
    String encodable(PDFont font, String s) {
        if (!embedded) return sanitize(s);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            if (supports(font, cp, ch)) out.append(ch);
            else out.append(FALLBACK.getOrDefault(cp, "?"));
        }
        return out.toString();
    }

    private final Map<PDFont, Map<Integer, Boolean>> glyphCache = new HashMap<>();

    private boolean supports(PDFont font, int cp, String ch) {
        return glyphCache.computeIfAbsent(font, f -> new HashMap<>())
                .computeIfAbsent(cp, c -> {
                    try { font.getStringWidth(ch); return true; }
                    catch (Exception e) { return false; }
                });
    }

    private static final Map<Integer, String> FALLBACK = Map.ofEntries(
            Map.entry(0x2713, "+"), Map.entry(0x2014, "-"), Map.entry(0x2013, "-"),
            Map.entry(0x2026, "..."), Map.entry(0x00B7, "-"), Map.entry(0x25B2, "^"),
            Map.entry(0x25BC, "v"), Map.entry(0x201C, "\""), Map.entry(0x201D, "\""),
            Map.entry(0x2018, "'"), Map.entry(0x2019, "'"));

    /** Tek satıra sığdır; taşarsa sonunu "…" ile kes. */
    String clip(String s, float maxWidth, PDFont font, float size) throws IOException {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (v.isEmpty()) return "";
        while (v.length() > 1 && width(v, font, size) > maxWidth) {
            v = v.substring(0, v.length() - 2) + "…";
        }
        return v;
    }

    /** Uzun metni satırlara böler; en fazla {@code maxLines} satır. */
    List<String> wrap(String s, float maxWidth, PDFont font, float size, int maxLines) throws IOException {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : s.replaceAll("\\s+", " ").trim().split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, size) > maxWidth && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
                if (out.size() == maxLines) { out.set(maxLines - 1, out.get(maxLines - 1) + " …"); return out; }
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private static String sanitize(String s) {
        return s.replace('ş', 's').replace('Ş', 'S').replace('ğ', 'g').replace('Ğ', 'G')
                .replace('ı', 'i').replace('İ', 'I').replace('ç', 'c').replace('Ç', 'C')
                .replace('ö', 'o').replace('Ö', 'O').replace('ü', 'u').replace('Ü', 'U')
                .replace('…', '.').replace('—', '-').replace('·', '-')
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    @Override
    public void close() throws IOException {
        closeStream();
        doc.close();
    }
}
