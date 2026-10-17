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

    /** Sayfa numarası öneki (varsayılan Türkçe; İngilizce raporlar "Page" verir — 2026-10-13, teknik hata raporu). */
    private String pageLabel = "Sayfa";

    /**
     * Sayfa üst bilgisi (2026-10-17, R19 — teknik hata raporu): ilk sayfa (kapak) HARİÇ her sayfanın üst kenar boşluğunda
     * ince bir satır. Açıkken devam sayfalarının çizim üst kenarı {@link #HEADER_BAND} kadar aşağı iner ({@link #pageTop()}),
     * böylece hiçbir içerik bandın içine çizilmez; kapak kendi {@code y}'sini kurduğundan ilk sayfanın yerleşimi aynı kalır.
     * Kapalıyken (diğer PDF'ler) hiçbir şey değişmez.
     */
    static final float HEADER_BAND = 10f;
    private boolean runningHeader;
    private String headerLeft;
    private String headerRight;

    /** Çizimin sürdüğü sayfa ({@link #insertPageAfter} sonrası eklenen sayfa). */
    private PDPage current;

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
        current = page;
        cs = new PDPageContentStream(doc, page);
        y = pageTop();
        if (pageHeaderHook != null) pageHeaderHook.run();
    }

    /**
     * Çizimi DAHA ÖNCE açılmış bir sayfaya döndürür (2026-10-17, R19 inceleme): içindekiler satırları içerikten önce yerine
     * yazılır, sayfa numaraları ise ancak bütün içerik çizilince bilinir — numara, noktalı ayraç ve iç bağlantı o satırların
     * sayfasına SONRADAN eklenir (mevcut çizimin üstüne, {@code APPEND}). Sayfa eklenmez, sayfa sırası değişmez; {@code y}
     * dokunulmaz (çağıran koordinatı kendisi verir).
     */
    void drawOn(PDPage page) throws IOException {
        if (page == null || (page == current && cs != null)) return;
        closeStream();
        cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true);
        current = page;
    }

    /** Şu an çizilen sayfa. */
    PDPage currentPage() { return current; }

    /** Sayfanın belgedeki 1 tabanlı numarası; belgede yoksa 0. */
    int pageNumber(PDPage page) { return pages.indexOf(page) + 1; }

    /**
     * Belge bilgisindeki başlık (2026-10-17, R19 inceleme): görüntüleyicinin sekmesi / pencere başlığı dosya adı yerine bunu
     * gösterir ({@code DisplayDocTitle}). Yalnız üst veri — çizime ve sayfalara dokunmaz.
     */
    void setDocumentTitle(String title) {
        if (title == null || title.isBlank()) return;
        doc.getDocumentInformation().setTitle(title.replaceAll("\\s+", " ").trim());
        try {
            org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences prefs =
                    new org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences(new org.apache.pdfbox.cos.COSDictionary());
            prefs.setDisplayDocTitle(true);
            doc.getDocumentCatalog().setViewerPreferences(prefs);
        } catch (Exception ignored) { /* tercih yazılamazsa başlık yine belge bilgisinde */ }
    }

    void ensureSpace(float needed) throws IOException {
        if (y - needed < BOTTOM) newPage();
    }

    /** Sayfanın çizilebilir üst kenarı (yeni sayfada {@code y} buradan başlar); üst bilgi açıkken bandın altı. */
    float pageTop() { return PAGE.getHeight() - MARGIN - (runningHeader ? HEADER_BAND : 0f); }

    void setPageHeaderHook(Runnable hook) { this.pageHeaderHook = hook; }

    void setPageLabel(String label) { if (label != null && !label.isBlank()) this.pageLabel = label; }

    /**
     * Kapak dışındaki sayfalara üst bilgi: solda {@code left} (ör. "Mobil · Eylül 2026"), sağda sığarsa {@code right}
     * (rapor başlığı, gerekirse "…" ile kısaltılır; çok dar kalırsa hiç basılmaz). İlk {@link #newPage()}'den ÖNCE çağrılır.
     */
    void setRunningHeader(String left, String right) {
        this.runningHeader = true;
        this.headerLeft = left;
        this.headerRight = right;
    }

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

    /**
     * Sayfa numaraları (ve açıksa üst bilgi) en sonda basılır — toplam ancak her şey çizilince bilinir; sonradan
     * basıldıkları için yerleşimi kaydırmazlar.
     */
    private void stampPageNumbers() throws IOException {
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream s = new PDPageContentStream(
                    doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                s.setNonStrokingColor(MUTED[0], MUTED[1], MUTED[2]);
                s.beginText();
                s.setFont(regular, 7f);
                s.newLineAtOffset(PAGE.getWidth() - MARGIN - 60, MARGIN - 18);
                s.showText(pageLabel + " " + (i + 1) + " / " + total);
                s.endText();
                if (runningHeader && i > 0) stampHeader(s);
            }
        }
    }

    private static final float HEADER_FONT = 7.5f;
    /** Üst bilgi taban çizgisi: sayfa üst kenarından ~24 pt (alttaki sayfa numarasıyla simetrik). */
    static final float HEADER_BASELINE = PAGE.getHeight() - 24f;

    /** Üst bilgi satırı + altındaki ince çizgi; içerik {@link #pageTop()}'tan başladığından bandın altında kalır. */
    private void stampHeader(PDPageContentStream s) throws IOException {
        String left = clip(headerLeft, CONTENT_W * 0.6f, regular, HEADER_FONT);
        float leftW = left.isEmpty() ? 0 : width(left, regular, HEADER_FONT);
        float avail = CONTENT_W - leftW - (left.isEmpty() ? 0 : 16f);
        String full = clip(headerRight, Float.MAX_VALUE, regular, HEADER_FONT);      // yalnız boşluk düzeltmesi, kesmez
        String right = "";
        if (!full.isEmpty() && width(full, regular, HEADER_FONT) <= avail) {
            right = full;                                    // sığan başlık uzunluğu ne olursa olsun basılır ("POS")
        } else if (!full.isEmpty() && avail >= 60f) {
            right = clip(full, avail, regular, HEADER_FONT);
            // KISALTILAN başlıktan yalnız "…" ya da birkaç harf kaldıysa hiç basılmaz (anlamsız parça göstermektense)
            if (right.length() < 8) right = "";
        }
        s.setNonStrokingColor(MUTED[0], MUTED[1], MUTED[2]);
        if (!left.isEmpty()) {
            s.beginText();
            s.setFont(regular, HEADER_FONT);
            s.newLineAtOffset(MARGIN, HEADER_BASELINE);
            s.showText(encodable(regular, left));
            s.endText();
        }
        if (!right.isEmpty()) {
            s.beginText();
            s.setFont(regular, HEADER_FONT);
            s.newLineAtOffset(PAGE.getWidth() - MARGIN - width(right, regular, HEADER_FONT), HEADER_BASELINE);
            s.showText(encodable(regular, right));
            s.endText();
        }
        s.setStrokingColor(RULE[0], RULE[1], RULE[2]);
        s.setLineWidth(0.6f);
        s.moveTo(MARGIN, HEADER_BASELINE - 4.5f);
        s.lineTo(PAGE.getWidth() - MARGIN, HEADER_BASELINE - 4.5f);
        s.stroke();
    }

    // ── Yer imleri ve iç bağlantılar (2026-10-17, R19) ───────────────────────────────────────

    /** Yer imi / içindekiler girdisi: başlık, sayfası ve başlığın üst kenarı (sayfa koordinatı). */
    record Mark(String title, PDPage page, float top) { }

    /** PDF yer imleri (görüntüleyicinin kenar çubuğu): her girdi kendi sayfasına, başlığın hizasına götürür. */
    void outline(List<Mark> marks) {
        if (marks == null || marks.isEmpty()) return;
        org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline out =
                new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline();
        for (Mark m : marks) {
            if (m.title() == null || m.title().isBlank() || pageNumber(m.page()) == 0) continue;
            org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item =
                    new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem();
            item.setTitle(m.title());
            item.setDestination(destination(m));
            out.addLast(item);
        }
        out.openNode();
        doc.getDocumentCatalog().setDocumentOutline(out);
    }

    private static org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination destination(Mark m) {
        org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination d =
                new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination();
        d.setPage(m.page());
        d.setTop(Math.round(Math.min(PAGE.getHeight(), m.top())));
        return d;
    }

    /** Geçerli sayfada görünmez iç bağlantı (içindekiler satırı → bölümün sayfası); çerçeve çizilmez. */
    void link(float x, float yy, float w, float h, Mark target) {
        try {
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link =
                    new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
            link.setRectangle(new PDRectangle(x, yy, w, h));
            org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary border =
                    new org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary();
            border.setWidth(0);
            link.setBorderStyle(border);
            link.setDestination(destination(target));
            current.getAnnotations().add(link);
        } catch (Exception ignored) { /* bağlantısız satır da okunur */ }
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

    /**
     * Dolu üçgen (2026-10-17, kutucuk şeridinin ok işareti — yazı tipine bağlı ▲▼ karakteri yerine çizim): {@code (x, yy)}
     * sol alt köşe, {@code size} taban; {@code up} true → tepe üstte (artış), false → tepe altta (azalış).
     */
    void triangle(float x, float yy, float size, boolean up, float[] color) throws IOException {
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        float h = size * 0.85f;
        if (up) {
            cs.moveTo(x, yy);
            cs.lineTo(x + size, yy);
            cs.lineTo(x + size / 2, yy + h);
        } else {
            cs.moveTo(x, yy + h);
            cs.lineTo(x + size, yy + h);
            cs.lineTo(x + size / 2, yy);
        }
        cs.closePath();
        cs.fill();
    }

    /**
     * PNG/JPEG görsel (marka logosu, 2026-10-10 yönetici özeti). Bozuk/boş bayt sessizce atlanır — logo yüzünden belge
     * düşmez.
     */
    void image(byte[] bytes, float x, float yy, float w, float h) {
        if (bytes == null || bytes.length == 0) return;
        try {
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                    org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc, bytes, "logo");
            cs.drawImage(img, x, yy, w, h);
        } catch (Exception ignored) { /* logosuz devam */ }
    }

    /**
     * Kullanıcının yüklediği görsel (teknik hata raporu grafikleri, 2026-10-13): {@link #image}'den farkı sonucu
     * BİLDİRMESİ — çizilemeyen grafik sessizce kaybolmasın, çağıran yerine "çizilemedi" kutusu koysun.
     */
    boolean tryImage(byte[] bytes, String name, float x, float yy, float w, float h) {
        if (bytes == null || bytes.length == 0) return false;
        try {
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                    org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc, bytes, name);
            cs.drawImage(img, x, yy, w, h);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Görsel PDF'e en çok çizim ölçüsünün bu katı pikselle gömülür (≈ 432 dpi — baskı ve yakınlaştırma için fazlasıyla
     * yeterli). Teknik hata raporu görselleri tam çözünürlükte saklandığından (2026-10-16) daha büyüğü gömerken ALAN
     * ORTALAMASIYLA ({@link ImageAreaAverage}) küçültülür — e-posta eki sınırı (15 MB) zorlanmasın. PNG kaynak kayıpsız
     * kalır, JPEG kaynak JPEG 0,95; sınırın içindeki görsel olduğu gibi gömülür (JPEG yeniden kodlanmaz).
     */
    static final float EMBED_SCALE = 6f;

    /**
     * {@link #tryImage(byte[], String, float, float, float, float)} + gömülen çözünürlük sınırı: çizim ölçüsünün en çok
     * {@code scale} katı piksel ({@link #EMBED_SCALE}; PDF e-posta sınırını aşarsa yazıcı daha düşük katla yeniden üretir).
     */
    boolean tryImage(byte[] bytes, int pxW, int pxH, String name, float x, float yy, float w, float h, float scale) {
        if (bytes == null || bytes.length == 0) return false;
        try {
            cs.drawImage(embed(bytes, pxW, pxH, name, w, h, scale), x, yy, w, h);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** PNG imzası + IHDR renk türü 0 (gri) ya da 4 (gri + saydam). */
    static boolean isGrayPng(byte[] b) {
        return b != null && b.length > 25 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && (b[25] == 0 || b[25] == 4);
    }

    org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject embed(byte[] bytes, int pxW, int pxH, String name, float w, float h,
                                                                  float scale) throws IOException {
        int maxW = Math.max(1, (int) Math.ceil(w * scale));
        int maxH = Math.max(1, (int) Math.ceil(h * scale));
        // Gri PNG (renk türü 0 / 4): PDFBox griyi doğrusal sayıp 128'i 188 yapıyordu — sığsa da kendi yolumuzdan gömülür
        boolean grayPng = isGrayPng(bytes);
        if (!grayPng && pxW > 0 && pxH > 0 && pxW <= maxW && pxH <= maxH) {
            return org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc, bytes, name);
        }
        java.awt.image.BufferedImage src;
        try {
            // Bellekte okunur: salt-okunur kök dosya sisteminde geçici dosya açılmaz
            src = javax.imageio.ImageIO.read(new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            src = null;
        }
        if (src == null) return org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc, bytes, name);
        double f = Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight());
        if (f >= 1.0) {
            if (!grayPng) return org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc, bytes, name);
            return org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, ImageAreaAverage.toSrgb(src));
        }
        // +1e-9: kayan nokta 1522 / 3000 × 3000'i 1521,99… yapıp bir piksel kaybettirmesin
        int tw = Math.max(1, (int) Math.floor(src.getWidth() * f + 1e-9));
        int th = Math.max(1, (int) Math.floor(src.getHeight() * f + 1e-9));
        java.awt.image.BufferedImage small = ImageAreaAverage.resize(src, tw, th);
        boolean jpeg = bytes.length > 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
        return jpeg
                ? org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, small, 0.95f)
                : org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, small);
    }

    /** İnce çerçeve (görsel / yer tutucu kutusu). */
    void strokeRect(float x, float yy, float w, float h, float[] color) throws IOException {
        cs.setStrokingColor(color[0], color[1], color[2]);
        cs.setLineWidth(0.6f);
        cs.addRect(x, yy, w, h);
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
            Map.entry(0x2018, "'"), Map.entry(0x2019, "'"), Map.entry(0x00A0, " "), Map.entry(0x202F, " "),
            Map.entry(0x2007, " "));

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

    /**
     * Tablo hücresi sarması (2026-10-12; adlar ve uzun metin kesilmez): kelime sınırında böler; sütundan geniş tek
     * "kelime" (boşluksuz alan adı, e-posta) karakter sınırında bölünür. En fazla {@code maxLines} satır — döngü metin
     * uzunluğuyla sınırlı, sığmayan kalan son satırda "…" ile biter.
     */
    List<String> wrapCell(String s, float maxWidth, PDFont font, float size, int maxLines) throws IOException {
        return wrapCellChecked(s, maxWidth, font, size, maxLines).lines();
    }

    /** Sarma sonucu: satırlar ve metnin sığmayıp "…" ile kesilip kesilmediği (kesilen yerde not düşmek için). */
    record Wrapped(List<String> lines, boolean truncated) { }

    Wrapped wrapCellChecked(String s, float maxWidth, PDFont font, float size, int maxLines) throws IOException {
        List<String> out = new ArrayList<>();
        if (s == null || maxLines < 1) return new Wrapped(out, false);
        String v = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (v.isEmpty()) return new Wrapped(out, false);
        StringBuilder line = new StringBuilder();
        for (String word : v.split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, size) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == maxLines) return new Wrapped(ellipsize(out, maxWidth, font, size), true);
            }
            int start = 0;
            while (start < word.length()) {
                int end = fitChars(word, start, maxWidth, font, size);
                if (end >= word.length()) {
                    line.append(word, start, end);
                    break;
                }
                out.add(word.substring(start, end));
                if (out.size() == maxLines) return new Wrapped(ellipsize(out, maxWidth, font, size), true);
                start = end;
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return new Wrapped(out, false);
    }

    /** Sayı hücresi: satırlar ve çizim boyu. */
    record NumberCell(List<String> lines, float size) { }

    /**
     * Sayı / yüzde hücresi (2026-10-14 inceleme: dar sütunda "125.430.112" → "125.430.1" + "12" yanıltıcı rakam basıyordu).
     * Sayı karakter ortasından ASLA bölünmez: önce yazı {@code minSize}'a kadar küçültülerek tek satıra sığdırılır;
     * yine sığmazsa yalnız BİNLİK ayracından sonra ("125.430." + "112") satıra bölünür — ondalık kısım ("%99," + "95")
     * ve işaret ayrılmaz. {@code group}: dilin binlik ayracı (TR '.', EN ','). Döngüler metin uzunluğu ve 0,5 puntoluk
     * adımlarla sınırlı.
     */
    NumberCell numberCell(String s, float maxWidth, PDFont font, float size, float minSize, char group) throws IOException {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return new NumberCell(List.of(), size);
        float sz = size;
        while (sz - 0.5f >= minSize && width(v, font, sz) > maxWidth) sz -= 0.5f;
        if (width(v, font, sz) <= maxWidth) return new NumberCell(List.of(v), sz);
        List<String> parts = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == group && i + 1 < v.length()) {
                parts.add(v.substring(from, i + 1));
                from = i + 1;
            }
        }
        parts.add(v.substring(from));
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : parts) {
            if (!cur.isEmpty() && width(cur + p, font, sz) > maxWidth) {
                lines.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(p);
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return new NumberCell(lines, sz);
    }

    /** Karışık yazı tipli metin parçası (kalın / normal) — zengin metin sarması için (2026-10-14, teknik hata raporu). */
    record Span(String text, boolean bold) { }

    /**
     * Kalın ve normal parçalardan oluşan metni satırlara böler (kelime sınırında; sütundan geniş tek kelime karakter
     * sınırında). En fazla {@code maxLines} satır; sığmayan kalan son satırda "…" ile biter. Döngü metin uzunluğuyla
     * sınırlı.
     */
    List<List<Span>> wrapSpans(List<Span> in, float maxWidth, float size, int maxLines) throws IOException {
        List<List<Span>> out = new ArrayList<>();
        if (in == null || maxLines < 1) return out;
        List<Span> line = new ArrayList<>();
        float lineW = 0;
        boolean space = false;
        for (Span sp : in) {
            if (sp == null || sp.text() == null) continue;
            String t = sp.text().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            PDFont f = sp.bold() ? bold : regular;
            int i = 0;
            while (i < t.length()) {
                if (t.charAt(i) == ' ') { space = true; i++; continue; }
                int j = t.indexOf(' ', i);
                if (j < 0) j = t.length();
                String word = t.substring(i, j);
                i = j;
                float sw = space && !line.isEmpty() ? width(" ", f, size) : 0;
                float ww = width(word, f, size);
                space = false;
                if (lineW + sw + ww <= maxWidth) {
                    addSpan(line, (sw > 0 ? " " : "") + word, sp.bold());
                    lineW += sw + ww;
                    continue;
                }
                if (!line.isEmpty()) {
                    out.add(line);
                    if (out.size() == maxLines) return ellipsizeSpans(out, maxWidth, size);
                    line = new ArrayList<>();
                    lineW = 0;
                }
                int start = 0;
                while (start < word.length()) {
                    int end = ww <= maxWidth && start == 0 ? word.length() : fitChars(word, start, maxWidth, f, size);
                    String part = word.substring(start, end);
                    if (end >= word.length()) {
                        addSpan(line, part, sp.bold());
                        lineW = width(part, f, size);
                        break;
                    }
                    addSpan(line, part, sp.bold());
                    out.add(line);
                    if (out.size() == maxLines) return ellipsizeSpans(out, maxWidth, size);
                    line = new ArrayList<>();
                    lineW = 0;
                    start = end;
                }
            }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    private static void addSpan(List<Span> line, String text, boolean b) {
        if (!line.isEmpty() && line.get(line.size() - 1).bold() == b) {
            Span last = line.remove(line.size() - 1);
            line.add(new Span(last.text() + text, b));
        } else {
            line.add(new Span(text, b));
        }
    }

    private List<List<Span>> ellipsizeSpans(List<List<Span>> out, float maxWidth, float size) throws IOException {
        List<Span> last = new ArrayList<>(out.get(out.size() - 1));
        last.add(new Span("…", false));
        for (int guard = 0; spansWidth(last, size) > maxWidth && guard < 4096; guard++) {
            int k = last.size() - 2;               // "…" kalır, ondan önceki parça kısalır
            if (k < 0) break;
            String t = last.get(k).text();
            if (t.length() <= 1) last.remove(k);
            else last.set(k, new Span(t.substring(0, t.length() - 1), last.get(k).bold()));
        }
        out.set(out.size() - 1, last);
        return out;
    }

    float spansWidth(List<Span> line, float size) throws IOException {
        float w = 0;
        for (Span s : line) w += width(s.text(), s.bold() ? bold : regular, size);
        return w;
    }

    /** Bir zengin metin satırını çizer. */
    void spans(List<Span> line, float x, float yy, float size, float[] color) throws IOException {
        float cx = x;
        for (Span s : line) {
            PDFont f = s.bold() ? bold : regular;
            text(f, size, cx, yy, s.text(), color);
            cx += width(s.text(), f, size);
        }
    }

    /** {@code word[start..end)} genişliğe sığan en uzun parça (en az bir karakter; vekil çift bölünmez). */
    private int fitChars(String word, int start, float maxWidth, PDFont font, float size) throws IOException {
        int lo = start + 1, hi = word.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(word.substring(start, mid), font, size) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        int end = lo;
        if (end < word.length() && Character.isLowSurrogate(word.charAt(end)) && end - 1 > start) end--;
        return end;
    }

    /** Kesilen sarmanın son satırı "…" ile biter (genişliğe sığdırılarak). */
    private List<String> ellipsize(List<String> out, float maxWidth, PDFont font, float size) throws IOException {
        int last = out.size() - 1;
        out.set(last, clip(out.get(last) + "…", maxWidth, font, size));
        return out;
    }

    private static String sanitize(String s) {
        return s.replace('ş', 's').replace('Ş', 'S').replace('ğ', 'g').replace('Ğ', 'G')
                .replace('ı', 'i').replace('İ', 'I').replace('ç', 'c').replace('Ç', 'C')
                .replace('ö', 'o').replace('Ö', 'O').replace('ü', 'u').replace('Ü', 'U')
                .replace('…', '.').replace('—', '-').replace('·', '-').replace('•', '-').replace('–', '-')
                .replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2007', ' ')
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    @Override
    public void close() throws IOException {
        closeStream();
        doc.close();
    }
}
