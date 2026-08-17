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

    // ── Grafik ilkelleri ─────────────────────────────────────────────────────

    /**
     * Yatay çubuk: {@code maxWidth} üzerinden orantılı, sıfır olmayan değerler için en az 1.5pt.
     *
     * <p>En az genişlik önemli: 400 alarmın yanında 1 alarmlık çubuk 0.2pt olur ve HİÇ çizilmez —
     * "bu türde alarm yok" gibi okunur. Görünür bir iz bırakmak yanlış okumayı önler.
     */
    void hBar(float x, float yy, float maxWidth, double value, double max, float[] color) throws IOException {
        if (max <= 0) return;
        float w = (float) (maxWidth * value / max);
        if (value > 0) w = Math.max(w, 1.5f);
        if (w > 0) rect(x, yy, w, 7f, color);
    }

    /** Çerçeveli oran çubuğu (erişilebilirlik) — dolu kısım renkli, kalan kısım açık gri. */
    void ratioBar(float x, float yy, float w, double pct, float[] fill) throws IOException {
        rect(x, yy, w, 5.5f, new float[]{ 226 / 255f, 232 / 255f, 240 / 255f });
        double clamped = Math.max(0, Math.min(100, pct));
        rect(x, yy, (float) (w * clamped / 100.0), 5.5f, fill);
    }

    /**
     * Halka (donut) dilimi — PDFBox'ta yay ilkelı yok, çokgenle yaklaşıyoruz.
     *
     * <p>2°'lik adım 180 kenarlı bir çokgen verir; bu ölçekte (r ≈ 26pt) kenarlar gözle
     * ayırt edilemez ve Bezier yaklaşımından çok daha az kod tutar.
     */
    void donutSlice(float cx, float cy, float rOuter, float rInner,
                    double startDeg, double sweepDeg, float[] color) throws IOException {
        if (sweepDeg <= 0) return;
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        int steps = Math.max(2, (int) Math.ceil(sweepDeg / 2.0));
        boolean first = true;
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(startDeg + sweepDeg * i / steps);
            float px = cx + (float) (rOuter * Math.cos(a));
            float py = cy + (float) (rOuter * Math.sin(a));
            if (first) { cs.moveTo(px, py); first = false; } else { cs.lineTo(px, py); }
        }
        for (int i = steps; i >= 0; i--) {
            double a = Math.toRadians(startDeg + sweepDeg * i / steps);
            cs.lineTo(cx + (float) (rInner * Math.cos(a)), cy + (float) (rInner * Math.sin(a)));
        }
        cs.closePath();
        cs.fill();
    }

    /**
     * Isı haritası hücresi — yoğunluk 0..1 arasında beyazdan taban renge doğru.
     *
     * <p>Sıfır değerli hücre boş bırakılmaz, çok açık bir zemin alır: ızgaranın kendisi
     * görünmezse "hangi saatler boş" bilgisi de kaybolur.
     */
    void heatCell(float x, float yy, float w, float h, double intensity, float[] base) throws IOException {
        double t = Math.max(0, Math.min(1, intensity));
        float[] empty = { 241 / 255f, 245 / 255f, 249 / 255f };
        float[] c = {
                (float) (empty[0] + (base[0] - empty[0]) * t),
                (float) (empty[1] + (base[1] - empty[1]) * t),
                (float) (empty[2] + (base[2] - empty[2]) * t) };
        rect(x, yy, w, h, c);
    }

    // ── Seviye paleti (TEK kaynak) ───────────────────────────────────────────

    /**
     * Alarm seviyesinin zemin/yazı rengi. Tek yerde tutuluyor ki halka grafiği, zaman çizelgesi
     * ve tablo rozetleri AYNI seviyeye aynı rengi versin — üç yerde ayrı palet olsaydı grafikle
     * tablo birbirini tutmazdı.
     */
    static float[][] levelColors(String level) {
        String l = level == null ? "" : level.toUpperCase(java.util.Locale.ROOT);
        return switch (l) {
            case "CRITICAL" -> new float[][]{ { 254 / 255f, 226 / 255f, 226 / 255f }, RED };
            case "HIGH"     -> new float[][]{ { 255 / 255f, 237 / 255f, 213 / 255f }, AMBER };
            case "WARNING"  -> new float[][]{ { 254 / 255f, 249 / 255f, 195 / 255f }, { 133 / 255f, 77 / 255f, 14 / 255f } };
            case "INFO"     -> new float[][]{ { 219 / 255f, 234 / 255f, 254 / 255f }, BLUE };
            default         -> new float[][]{ { 241 / 255f, 245 / 255f, 249 / 255f }, LABEL };
        };
    }

    /** Grafiklerde kullanılan DOLGU rengi (rozet zemini değil) — çubuk/dilim/çizelge için. */
    static float[] levelSolid(String level) {
        return levelColors(level)[1];
    }

    /** Seviyenin Türkçe kısa adı — renk körlüğü ve siyah-beyaz çıktı için renk TEK BAŞINA yetmez. */
    static String levelLabel(String level) {
        String l = level == null ? "" : level.toUpperCase(java.util.Locale.ROOT);
        return switch (l) {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH"     -> "YÜKSEK";
            case "WARNING"  -> "UYARI";
            case "INFO"     -> "BİLGİ";
            default         -> l.isEmpty() ? "—" : l;
        };
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
