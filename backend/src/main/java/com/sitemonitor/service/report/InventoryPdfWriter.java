package com.sitemonitor.service.report;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.CertificateInventoryOps;
import com.sitemonitor.service.mail.CertInventoryMail;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailTokens;
import com.sitemonitor.service.mail.MailTokens.Tone;
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
import java.util.Map;

/**
 * Envanter PDF'i — EKRANDAKİ "Dışa Aktar → PDF" ile AYNI düzen (domain başına detay bloğu).
 *
 * <p>Neden ekranla birebir: kullanıcı aynı belgeyi iki yoldan alabiliyor (ekrandan indirerek ve
 * aylık mail ekinden). İki çıktının farklı görünmesi "hangisi doğru?" sorusunu doğurur. Bu yüzden
 * bölüm sırası, alan etiketleri ve renkler {@code frontend/src/utils/exportInventory.js} ile
 * eşleştirilmiştir; tek fark üretim yeri (orada jsPDF/tarayıcı, burada PDFBox/sunucu — zamanlanmış
 * işte tarayıcı yok).
 *
 * <p>Türkçe glyph için Roboto TTF gömülür; standart 14 PDF fontu ş/ğ/İ taşımaz ve yazmaya
 * çalışınca hata fırlatır. Font yoksa ASCII'ye indirgenir, belge yine üretilir.
 */
class InventoryPdfWriter implements AutoCloseable {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(InventoryPdfWriter.class);

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 40f;
    private static final float BOTTOM = 52f;
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    // Ekrandaki renkler (exportInventory.js)
    private static final float[] BLUE = { 37 / 255f, 99 / 255f, 235 / 255f };     // bölüm başlığı
    private static final float[] INK = { 40 / 255f, 40 / 255f, 40 / 255f };
    private static final float[] LABEL = { 110 / 255f, 110 / 255f, 110 / 255f };
    private static final float[] MUTED = { 150 / 255f, 150 / 255f, 150 / 255f };
    private static final float[] OK_GREEN = { 6 / 255f, 95 / 255f, 70 / 255f };

    private final PDDocument doc = new PDDocument();
    /** Gömülü (Roboto) font kullanılabildi mi. false ise TÜM metin ASCII'ye indirgenir
     *  (Türkçe erir) — bu yüzden durum test edilebilir olmalı, bkz. {@link #fontsEmbedded()}. */
    private final boolean embedded;
    private final PDFont regular;
    private final PDFont bold;
    private final PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);

    private PDPage page;
    private PDPageContentStream cs;
    private float y;
    private int pageNo = 0;
    private final List<PDPage> pages = new ArrayList<>();

    InventoryPdfWriter() {
        PDType0Font r = loadFont("Roboto-Regular.ttf");
        PDType0Font b = loadFont("Roboto-Bold.ttf");
        this.embedded = r != null && b != null;
        this.regular = embedded ? r : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        this.bold = embedded ? b : new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    /** Gömülü font kullanılabiliyor mu — kapı testi bunu okur (raporun Türkçe taşıyıp
     *  taşıyamayacağının TEK belirleyicisi). */
    boolean fontsEmbedded() { return embedded; }

    private PDType0Font loadFont(String name) {
        // SESSİZ düşmek yasak: font yüklenemezse rapor üretilmeye devam eder ama Türkçe erir
        // ve kimse NEDENİNİ bilmez. Prod'da "PDF'te Türkçe bozuk" şikâyeti tam bu sessizlikten
        // teşhis edilemiyordu — artık günlükte sebebiyle görünür.
        try (InputStream in = InventoryPdfWriter.class.getResourceAsStream("/report-fonts/" + name)) {
            if (in == null) {
                log.warn("Rapor fontu bulunamadı: /report-fonts/{} — PDF ASCII'ye indirgenecek "
                        + "(Türkçe karakterler kaybolur)", name);
                return null;
            }
            return PDType0Font.load(doc, in, true);
        } catch (Exception e) {
            log.warn("Rapor fontu yüklenemedi ({}): {} — PDF ASCII'ye indirgenecek "
                    + "(Türkçe karakterler kaybolur)", name, e.toString());
            return null;
        }
    }

    // ── Genel akış ───────────────────────────────────────────────────────────

    byte[] write(List<CertificateInventory> rows, Map<Long, String> teams) throws IOException {
        return write(rows, teams, null);
    }

    /**
     * {@code summary} doluysa (aylık rapor eki) YALNIZ e-posta gövdesiyle AYNI özetten özet bölümü basılır — kayıt bazındaki
     * detay blokları eke girmez (kullanıcı kararı 2026-09-28: büyük envanterde ek ~146 sayfaya çıkıyordu; kayıt bazında tam
     * liste CSV ekinde). null → eski belge (ekrandaki "Dışa Aktar → PDF", kayıt başına detay blokları — değişmedi).
     */
    byte[] write(List<CertificateInventory> rows, Map<Long, String> teams, CertInventoryMail.Report summary) throws IOException {
        this.footerLabel = summary == null ? null
                : "Site Monitor · Sertifika Envanteri · " + (summary.monthLabel() == null ? "" : summary.monthLabel());
        newPage();
        if (summary != null) {
            drawSummary(summary, rows.size());
        } else {
            drawBrandHeader(rows.size());
            for (CertificateInventory r : rows) {
                ensureSpace(estimateHeight(r));
                drawRecord(r, teams);
            }
        }
        closeStream();
        stampPageNumbers();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    // ── Özet bölümü (aylık rapor eki, 2026-09-28) ────────────────────────────
    // Renkler e-postanın belirteçlerinden (MailTokens/Tone) türetilir → PDF ile gövde aynı paleti konuşur.

    private static final float[] FG = rgb(MailTokens.FG);
    private static final float[] SOFT = rgb(MailTokens.MUTED);
    private static final float[] RULE = rgb(MailTokens.BORDER);
    private static final float[] ZEBRA = rgb(MailTokens.SECONDARY);
    private static final float[] HEAD_BG = rgb(MailTokens.SUBTLE);
    private static final float[] WHITE = { 1f, 1f, 1f };
    private static final float ROW_H = 15f;
    private static final float CONTENT_W = PAGE.getWidth() - MARGIN * 2;

    /** Alt bilgi sol etiketi (ay) — yalnız özetli belgede. */
    private String footerLabel;

    /** Tablo başlığı sayfa kırılımında yeniden çizilsin diye (null = başlıksız akış). */
    private Runnable tableHeader;

    private void drawSummary(CertInventoryMail.Report s, int count) throws IOException {
        // 1) Marka başlığı + durum hapı (sağda)
        y -= 26;
        try (InputStream logo = InventoryPdfWriter.class.getResourceAsStream("/email-assets/email-ok.png")) {
            if (logo != null) {
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo.readAllBytes(), "logo");
                cs.drawImage(img, MARGIN, y - 12, 34, 34);
            }
        } catch (Exception ignored) { /* logosuz devam */ }
        String month = s.monthLabel() == null ? "" : s.monthLabel();
        text(bold, 14f, MARGIN + 46, y + 6, clip("Sertifika Envanteri — " + month, CONTENT_W - 170, bold, 14f), FG);
        text(regular, 8f, MARGIN + 46, y - 7, clip(
                ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "  ·  " + count + " kayıt  ·  "
                + (s.scope() == null ? "" : s.scope()) + "  ·  " + s.ownerTeams() + " sahip takım", CONTENT_W - 60, regular, 8f), SOFT);
        CertInventoryMail.Buckets b = s.buckets();
        String status; Tone st; boolean solid;
        if (b.expired() > 0 || b.within7() > 0) { status = "AKSİYON GEREKLİ"; st = Tone.DESTRUCTIVE; solid = true; }
        else if (b.within30() > 0 || s.findingTotal() > 0 || s.errors() > 0) { status = "TAKİP GEREKLİ"; st = Tone.WARNING; solid = false; }
        else { status = "SORUNSUZ"; st = Tone.SUCCESS; solid = false; }
        float pw = pillWidth(status);
        pill(PAGE.getWidth() - MARGIN - pw, y + 7, status, st, solid);
        y -= 30;

        // 2) Hüküm
        for (String line : wrap(CertInventoryMail.verdict(s), CONTENT_W, bold, 10.5f, 3)) {
            text(bold, 10.5f, MARGIN, y, line, FG);
            y -= 14;
        }
        y -= 8;

        // 3) KPI ızgarası (4 × 2)
        Integer pt = s.prevTotal(), pf = s.prevFindings();
        List<Tile> tiles = List.of(
                new Tile("Toplam kayıt", s.total(), null, s.active() + " aktif · " + s.passive() + " pasif", pdfDelta(s.total(), pt)),
                new Tile("30 gün içinde", b.within30(), Tone.WARNING.strong, "bitecek sertifika", null),
                new Tile("14 gün içinde", b.within14(), Tone.WARNING.strong, "bitecek sertifika", null),
                new Tile("7 gün içinde", b.within7(), Tone.DESTRUCTIVE.strong, "bitecek sertifika", null),
                new Tile("Süresi dolmuş", b.expired(), Tone.DESTRUCTIVE.text, "yenileme gecikmiş", null),
                new Tile("Hata / erişilemez", s.errors(), Tone.DESTRUCTIVE.strong, "sertifika okunamadı", null),
                new Tile("Veri yok", b.unknown(), MailTokens.FG, "bitiş tarihi bilinmiyor", null),
                new Tile("Hijyen bulgusu", s.findingTotal(), Tone.WARNING.strong,
                        s.findingTotal() == 0 ? "envanter temiz" : s.findings().size() + " grupta", pdfDelta(s.findingTotal(), pf)));
        float gap = 8f, tw = (CONTENT_W - gap * 3) / 4f, th = 50f;
        for (int i = 0; i < tiles.size(); i += 4) {
            ensureSpace(th + gap);
            for (int j = 0; j < 4 && i + j < tiles.size(); j++) {
                Tile t = tiles.get(i + j);
                float x = MARGIN + j * (tw + gap), top = y;
                roundRect(x, top - th, tw, th, 4f, WHITE, RULE);
                text(regular, 7.5f, x + 9, top - 13, clip(t.label(), tw - 18, regular, 7.5f), SOFT);
                String v = String.valueOf(t.value());
                text(bold, 17f, x + 9, top - 32, v,
                        t.value() > 0 && t.color() != null ? rgb(t.color()) : (t.color() == null ? FG : SOFT));
                if (t.delta() != null) {                   // geçen aya göre değişim — değerin yanında küçük
                    float dx = x + 9 + width(v, bold, 17f) + 6;
                    text(regular, 7f, dx, top - 32, clip(t.delta(), x + tw - 8 - dx, regular, 7f), SOFT);
                }
                text(regular, 7f, x + 9, top - 43, clip(t.hint(), tw - 18, regular, 7f), SOFT);
            }
            y -= th + gap;
        }
        y -= 6;

        // 4) Kalan süreye göre dağılım
        if (s.active() > 0) {
            sectionTitle("Kalan süreye göre dağılım", s.active() + " aktif sertifika");
            drawSegmentBar(b);
        }

        // 5) 90 gün içinde bitenler (süresi dolmuşlar dahil) — TAM liste
        List<CertInventoryMail.Cert> horizon = new ArrayList<>(s.upcoming());
        for (CertInventoryMail.Cert c : s.later()) if (c.daysLeft() != null && c.daysLeft() <= 90) horizon.add(c);
        if (s.active() > 0) {
            sectionTitle("90 gün içinde bitenler", horizon.isEmpty() ? "90 gün içinde süresi dolacak sertifika yok."
                    : horizon.size() + " sertifika · süresi dolmuşlar dahil · tarihler Türkiye saatiyle");
            if (!horizon.isEmpty()) drawCertTable(horizon);
        }

        // 6) Envanter hijyeni — TAM liste (gövde grup başına ilk 10'u gösterir)
        if (s.findings().isEmpty()) {
            sectionTitle("Envanter hijyeni", "Envanterde eksik, hatalı veya güncel olmayan kayıt bulunmadı.");
        } else {
            sectionTitle("Envanter hijyeni", s.findingTotal() + " bulgu · " + s.findings().size() + " grup · tam liste");
            for (CertInventoryMail.FindingGroup g : s.findings()) drawFindingGroup(g);
        }
    }

    private record Tile(String label, int value, String color, String hint, String delta) { }

    /** PDF'te değişim: "+8 (geçen ay 418)" — ▲/▼ gömülü fontta yok, işaret yazıyla. Geçen ay bilinmiyorsa null. */
    static String pdfDelta(int now, Integer prev) {
        if (prev == null) return null;
        int diff = now - prev;
        return (diff == 0 ? "değişmedi" : (diff > 0 ? "+" : "-") + Math.abs(diff)) + " (geçen ay " + prev + ")";
    }

    private void sectionTitle(String title, String desc) throws IOException {
        tableHeader = null;
        ensureSpace(52);
        y -= 14;
        text(bold, 11f, MARGIN, y, title, FG);
        y -= 12;
        if (desc != null && !desc.isBlank()) {
            text(regular, 7.5f, MARGIN, y, clip(desc, CONTENT_W, regular, 7.5f), SOFT);
            y -= 12;
        }
        y -= 2;
    }

    private void drawSegmentBar(CertInventoryMail.Buckets b) throws IOException {
        String[] labels = { "Süresi dolmuş", "0–7 gün", "8–30 gün", "31–90 gün", "90 gün üstü", "Tarih yok" };
        int[] counts = { b.expired(), b.days0to7(), b.days8to14() + b.days15to30(), b.days31to90(), b.over90(), b.unknown() };
        String[] colors = { Tone.DESTRUCTIVE.text, Tone.DESTRUCTIVE.strong, Tone.WARNING.strong, Tone.INFO.strong,
                Tone.SUCCESS.strong, Tone.NEUTRAL.strong };
        int total = 0;
        for (int c : counts) total += c;
        if (total == 0) return;
        ensureSpace(40);
        float x = MARGIN, h = 10f, gap = 1.5f;
        int nonZero = 0;
        for (int c : counts) if (c > 0) nonZero++;
        float usable = CONTENT_W - gap * (nonZero - 1);
        // Küçük dilim en az 3 pt; kalan genişlik yalnız büyük dilimlere orantılı dağıtılır. Eskiden asgari genişlik
        // telafisizdi → toplam taşıyor, son dilim ("Tarih yok") eksi genişliğe düşüp kayboluyordu (regresyon 2026-09-28b B4).
        final float minW = 3f;
        float fixed = 0f;
        long bigCount = 0;
        for (int c : counts) {
            if (c <= 0) continue;
            if (usable * c / total < minW) fixed += minW; else bigCount += c;
        }
        float rest = Math.max(0f, usable - fixed);
        int drawn = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            drawn++;
            boolean small = usable * counts[i] / total < minW;
            float natural = small || bigCount == 0 ? minW : rest * counts[i] / bigCount;
            float w = drawn == nonZero ? Math.max(minW, MARGIN + CONTENT_W - x) : natural;
            rect(x, y - h, w, h, rgb(colors[i]));
            x += w + gap;
        }
        y -= h + 12;
        float lx = MARGIN;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            String label = labels[i] + "  " + counts[i] + " · " + MailKit.segmentPct(counts[i], total);
            float w = 10 + width(label, regular, 7.5f) + 14;
            if (lx + w > MARGIN + CONTENT_W) { lx = MARGIN; y -= 12; }
            rect(lx, y - 1, 7, 7, rgb(colors[i]));
            text(regular, 7.5f, lx + 10, y, label, FG);
            lx += w;
        }
        y -= 16;
    }

    private static final float[] CERT_W = { 175f, 95f, 110f, 58f, 0f };   // son sütun kalan genişlik

    private void drawCertTable(List<CertInventoryMail.Cert> certs) throws IOException {
        float[] w = CERT_W.clone();
        w[4] = CONTENT_W - (w[0] + w[1] + w[2] + w[3]);
        String[] head = { "Alan adı", "Takım", "Sağlayıcı", "Bitiş", "Kalan" };
        tableHeader = () -> headerRow(head, w, true);
        ensureSpace(ROW_H * 3);
        tableHeader.run();
        for (int i = 0; i < certs.size(); i++) {
            CertInventoryMail.Cert c = certs.get(i);
            rowBreak();
            if (i % 2 == 1) rect(MARGIN, y - ROW_H, CONTENT_W, ROW_H, ZEBRA);
            float base = y - 10.3f, x = MARGIN + 5;
            String dom = c.domain() + (c.tier() == null ? "" : "  T" + c.tier());
            text(bold, 7.5f, x, base, clip(dom, w[0] - 10, bold, 7.5f), FG);
            x = MARGIN + w[0] + 5;
            text(regular, 7.5f, x, base, clip(c.team() == null ? "Takım atanmamış" : c.team(), w[1] - 10, regular, 7.5f), SOFT);
            x += w[1];
            text(regular, 7.5f, x, base, clip(c.issuer() == null ? "—" : c.issuer(), w[2] - 10, regular, 7.5f), SOFT);
            x += w[2];
            text(regular, 7.5f, x, base, c.expiry() == null ? "—" : c.expiry(), FG);
            String label = pillLabel(c.daysLeft());
            float pw = pillWidth(label);
            pill(MARGIN + CONTENT_W - pw - 5, base, label, pillTone(c.daysLeft()), c.daysLeft() != null && c.daysLeft() <= 0);
            y -= ROW_H;
        }
        tableHeader = null;
        y -= 6;
    }

    private void drawFindingGroup(CertInventoryMail.FindingGroup g) throws IOException {
        tableHeader = null;
        ensureSpace(ROW_H * 4 + 30);
        boolean severe = "health".equals(g.key()) || "error".equals(g.key());
        y -= 4;
        text(bold, 9f, MARGIN, y, clip(g.title(), CONTENT_W - 90, bold, 9f), FG);
        String cnt = g.total() + " kayıt";
        pill(MARGIN + width(g.title(), bold, 9f) + 8, y, cnt, severe ? Tone.DESTRUCTIVE : Tone.WARNING, false);
        y -= 11;
        String ex = CertInventoryMail.explain(g.key()), ac = CertInventoryMail.action(g.key());
        if (ex != null) for (String l : wrap(ex, CONTENT_W, regular, 7.5f, 2)) { text(regular, 7.5f, MARGIN, y, l, SOFT); y -= 10; }
        if (ac != null) for (String l : wrap("Önerilen: " + ac, CONTENT_W, regular, 7.5f, 2)) { text(regular, 7.5f, MARGIN, y, l, FG); y -= 10; }
        y -= 3;
        float[] w = { 200f, CONTENT_W - 200f };
        String[] head = { "Alan adı", "Bulgu" };
        tableHeader = () -> headerRow(head, w, false);
        tableHeader.run();
        List<CertInventoryMail.Finding> all = g.findings();
        for (int i = 0; i < all.size(); i++) {
            CertInventoryMail.Finding f = all.get(i);
            rowBreak();
            if (i % 2 == 1) rect(MARGIN, y - ROW_H, CONTENT_W, ROW_H, ZEBRA);
            float base = y - 10.3f;
            text(bold, 7.5f, MARGIN + 5, base, clip(f.domain() == null ? "—" : f.domain(), w[0] - 10, bold, 7.5f), FG);
            text(regular, 7.5f, MARGIN + w[0] + 5, base, clip(f.detail() == null ? "" : f.detail(), w[1] - 10, regular, 7.5f), SOFT);
            y -= ROW_H;
        }
        // Özet toplam > liste uzunluğu olursa (kırpılmış kaynak) açıkça söylenir — sessiz eksik yok.
        if (g.total() > all.size()) {
            text(regular, 7f, MARGIN + 5, y - 9, "+" + (g.total() - all.size()) + " kayıt daha", SOFT);
            y -= 12;
        }
        tableHeader = null;
        y -= 8;
    }

    /** Satır sığmıyorsa yeni sayfa + tablo başlığını yinele. */
    private void rowBreak() throws IOException {
        if (y - ROW_H < BOTTOM) {
            newPage();
            if (tableHeader != null) tableHeader.run();
        }
    }

    private void headerRow(String[] head, float[] w, boolean lastRight) {
        try {
            rect(MARGIN, y - ROW_H, CONTENT_W, ROW_H, HEAD_BG);
            cs.setStrokingColor(RULE[0], RULE[1], RULE[2]);
            cs.setLineWidth(0.6f);
            cs.moveTo(MARGIN, y - ROW_H);
            cs.lineTo(MARGIN + CONTENT_W, y - ROW_H);
            cs.stroke();
            float x = MARGIN;
            for (int i = 0; i < head.length; i++) {
                if (lastRight && i == head.length - 1) {
                    text(bold, 7f, MARGIN + CONTENT_W - width(head[i], bold, 7f) - 5, y - 10f, head[i], SOFT);
                } else {
                    text(bold, 7f, x + 5, y - 10f, head[i], SOFT);
                }
                x += w[i];
            }
            y -= ROW_H;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ── Hap / dikdörtgen ilkelleri ───────────────────────────────────────────

    static String pillLabel(Integer days) {
        if (days == null) return "veri yok";
        if (days < 0) return Math.abs(days) + " gün önce doldu";
        if (days == 0) return "bugün bitiyor";
        return days + " gün";
    }

    static Tone pillTone(Integer days) {
        if (days == null) return Tone.NEUTRAL;
        if (days <= 7) return Tone.DESTRUCTIVE;
        if (days <= 30) return Tone.WARNING;
        if (days <= 90) return Tone.INFO;
        return Tone.SUCCESS;
    }

    private float pillWidth(String label) throws IOException {
        return width(label, bold, 6.8f) + 10;
    }

    /** Tonlu hap: {@code solid} → dolu zemin + beyaz yazı (e-postadaki MailKit.pill ile aynı kural). */
    private void pill(float x, float baseline, String label, Tone t, boolean solid) throws IOException {
        float w = pillWidth(label);
        float[] bg = solid ? rgb(t.strong) : (t == Tone.NEUTRAL ? rgb(MailTokens.SECONDARY) : rgb(t.bg));
        float[] bd = solid ? rgb(t.strong) : (t == Tone.NEUTRAL ? RULE : rgb(t.border));
        float[] fg = solid ? WHITE : (t == Tone.NEUTRAL ? FG : rgb(t.text));
        roundRect(x, baseline - 3f, w, 10.5f, 3f, bg, bd);
        text(bold, 6.8f, x + 5, baseline, label, fg);
    }

    private void rect(float x, float yy, float w, float h, float[] c) throws IOException {
        cs.setNonStrokingColor(c[0], c[1], c[2]);
        cs.addRect(x, yy, w, h);
        cs.fill();
    }

    /** Yuvarlak köşeli dikdörtgen (Bezier çeyrek daireler); {@code stroke} null → yalnız dolgu. */
    private void roundRect(float x, float yy, float w, float h, float r, float[] fill, float[] stroke) throws IOException {
        float k = 0.5523f * r;
        cs.moveTo(x + r, yy);
        cs.lineTo(x + w - r, yy);
        cs.curveTo(x + w - r + k, yy, x + w, yy + r - k, x + w, yy + r);
        cs.lineTo(x + w, yy + h - r);
        cs.curveTo(x + w, yy + h - r + k, x + w - r + k, yy + h, x + w - r, yy + h);
        cs.lineTo(x + r, yy + h);
        cs.curveTo(x + r - k, yy + h, x, yy + h - r + k, x, yy + h - r);
        cs.lineTo(x, yy + r);
        cs.curveTo(x, yy + r - k, x + r - k, yy, x + r, yy);
        cs.closePath();
        if (fill != null) cs.setNonStrokingColor(fill[0], fill[1], fill[2]);
        if (stroke != null) {
            cs.setStrokingColor(stroke[0], stroke[1], stroke[2]);
            cs.setLineWidth(0.6f);
        }
        if (fill != null && stroke != null) cs.fillAndStroke();
        else if (fill != null) cs.fill();
        else cs.stroke();
    }

    /** "#rrggbb" → PDF RGB (0..1). Palet MailTokens'tan okunur; serbest renk üretilmez. */
    static float[] rgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        int v = Integer.parseInt(h, 16);
        return new float[]{ ((v >> 16) & 0xFF) / 255f, ((v >> 8) & 0xFF) / 255f, (v & 0xFF) / 255f };
    }

    private void newPage() throws IOException {
        closeStream();
        page = new PDPage(PAGE);
        doc.addPage(page);
        pages.add(page);
        pageNo++;
        cs = new PDPageContentStream(doc, page);
        y = PAGE.getHeight() - MARGIN;
    }

    private void closeStream() throws IOException {
        if (cs != null) { cs.close(); cs = null; }
    }

    private void ensureSpace(float needed) throws IOException {
        if (y - needed < BOTTOM) newPage();
    }

    /** Blok yüksekliği kestirimi — sayfa kırılımı satır ortasında olmasın (ekrandaki heuristikle aynı fikir). */
    private float estimateHeight(CertificateInventory r) {
        float h = 18 + 14 + 5 * 13 + 10;                       // başlık + TEMEL BİLGİLER ızgarası
        h += 14 + (float) Math.ceil(CertificateInventoryOps.ALL.size() / 3.0) * 13 + 8;
        if (notBlank(r.getChangeDescription())) h += 26;
        if (notBlank(r.getExpectedFingerprint()) || notBlank(r.getExpectedSubject())) h += 34;
        return h + 22;
    }

    // ── Parçalar ─────────────────────────────────────────────────────────────

    private void drawBrandHeader(int count) throws IOException {
        y -= 26;
        try (InputStream logo = InventoryPdfWriter.class.getResourceAsStream("/email-assets/email-ok.png")) {
            if (logo != null) {
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo.readAllBytes(), "logo");
                cs.drawImage(img, MARGIN, y - 12, 34, 34);       // ekrandaki 40pt logoyla aynı ölçek
            }
        } catch (Exception ignored) { /* logosuz devam */ }
        text(bold, 13f, MARGIN + 46, y + 6, "Sertifika Envanteri", INK);
        text(regular, 8f, MARGIN + 46, y - 6,
                ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                + "  ·  " + count + " kayıt", LABEL);
        y -= 34;
    }

    private void drawRecord(CertificateInventory r, Map<Long, String> teams) throws IOException {
        // Domain başlığı + tier/durum çipleri
        String title = nz(r.getDomain());
        text(bold, 11f, MARGIN, y, title, INK);
        float x = MARGIN + width(title, bold, 11f) + 6;
        String port = ":" + (r.getPort() == null ? 443 : r.getPort());
        text(regular, 9f, x, y, port, LABEL);
        x += width(port, regular, 9f) + 8;
        if (r.getTier() != null) {
            x = chip(x, y, "T" + r.getTier(),
                    new float[]{ 199 / 255f, 210 / 255f, 254 / 255f }, new float[]{ 55 / 255f, 48 / 255f, 163 / 255f });
        }
        boolean active = !Boolean.FALSE.equals(r.getActive());
        chip(x, y, active ? "Aktif" : "Pasif",
                active ? new float[]{ 209 / 255f, 250 / 255f, 229 / 255f } : new float[]{ 243 / 255f, 244 / 255f, 246 / 255f },
                active ? OK_GREEN : new float[]{ 75 / 255f, 85 / 255f, 99 / 255f });
        y -= 18;

        // TEMEL BİLGİLER
        sectionHeader("Temel Bilgiler");
        twoColGrid(List.of(
                new String[]{ "Domain", nz(r.getDomain()) },
                new String[]{ "Port", String.valueOf(r.getPort() == null ? 443 : r.getPort()) },
                new String[]{ "Takım", r.getTeamId() == null ? "—" : nzDash(teams.get(r.getTeamId())) },
                new String[]{ "Kritiklik Seviyesi (Tier)", InventoryExportService.tierLabel(r.getTier()) },
                new String[]{ "Satın Alan Kişi/Ekip", nzDash(r.getPurchasedBy()) },
                new String[]{ "Platform", nzDash(r.getPlatform()) + (r.getPlatformDetail() == null || r.getPlatformDetail().isBlank() ? "" : " — " + r.getPlatformDetail()) },
                new String[]{ "TLS Modu", tlsMode(r.getTlsMode()) }));
        y -= 6;

        // OPERASYONEL BİLGİLER — 3 çift/satır (ekrandaki 6 kolonlu ızgara)
        sectionHeader("Operasyonel Bilgiler");
        var flags = CertificateInventoryOps.ALL;
        float colW = (PAGE.getWidth() - MARGIN * 2) / 3f;
        for (int i = 0; i < flags.size(); i += 3) {
            for (int j = 0; j < 3 && i + j < flags.size(); j++) {
                var f = flags.get(i + j);
                boolean on = Boolean.TRUE.equals(f.getter().apply(r));
                float cx = MARGIN + j * colW;
                text(regular, 7.5f, cx, y, clip(f.label(), colW - 52, regular, 7.5f), LABEL);
                // Ekrandaki PDF "✓ Evet" / "— Hayır" gösterir; Roboto'da ✓ yoksa encodable()
                // güvenli karşılığına indirger (belge düşmez).
                text(bold, 7.5f, cx + colW - 50, y, on ? "✓ Evet" : "— Hayır", on ? OK_GREEN : LABEL);
            }
            y -= 13;
        }
        y -= 4;

        if (notBlank(r.getChangeDescription())) {
            sectionHeader("Değişiklik Açıklaması");
            for (String line : wrap(stripMd(r.getChangeDescription()), PAGE.getWidth() - MARGIN * 2, regular, 8f, 6)) {
                ensureSpace(12);
                text(regular, 8f, MARGIN, y, line, INK);
                y -= 11;
            }
            y -= 4;
        }

        if (notBlank(r.getExpectedFingerprint()) || notBlank(r.getExpectedSubject())) {
            sectionHeader("Gelişmiş");
            if (notBlank(r.getExpectedFingerprint())) {
                text(regular, 7.5f, MARGIN, y, "Beklenen Parmak İzi", LABEL);
                text(mono, 7f, MARGIN + 120, y, clip(sanitize(r.getExpectedFingerprint()),
                        PAGE.getWidth() - MARGIN * 2 - 120, mono, 7f), INK);
                y -= 12;
            }
            if (notBlank(r.getExpectedSubject())) {
                text(regular, 7.5f, MARGIN, y, "Beklenen Subject", LABEL);
                text(mono, 7f, MARGIN + 120, y, clip(sanitize(r.getExpectedSubject()),
                        PAGE.getWidth() - MARGIN * 2 - 120, mono, 7f), INK);
                y -= 12;
            }
            y -= 2;
        }

        // Meta + ayırıcı
        text(regular, 7f, MARGIN, y,
                "Oluşturulma: " + nzDash(shortDate(r.getCreatedAt()))
                + "    Güncelleme: " + nzDash(shortDate(r.getUpdatedAt())), MUTED);
        y -= 10;
        cs.setStrokingColor(0.88f, 0.9f, 0.92f);
        cs.moveTo(MARGIN, y);
        cs.lineTo(PAGE.getWidth() - MARGIN, y);
        cs.stroke();
        y -= 14;
    }

    private void sectionHeader(String label) throws IOException {
        text(bold, 8.5f, MARGIN, y, label.toUpperCase(new java.util.Locale("tr", "TR")), BLUE);
        y -= 13;
    }

    private void twoColGrid(List<String[]> pairs) throws IOException {
        float colW = (PAGE.getWidth() - MARGIN * 2) / 2f;
        for (int i = 0; i < pairs.size(); i += 2) {
            for (int j = 0; j < 2 && i + j < pairs.size(); j++) {
                String[] p = pairs.get(i + j);
                float cx = MARGIN + j * colW;
                text(regular, 7.5f, cx, y, p[0], LABEL);
                text(regular, 8.5f, cx, y - 10, clip(p[1], colW - 12, regular, 8.5f), INK);
            }
            y -= 23;
        }
    }

    private float chip(float x, float yy, String label, float[] bg, float[] fg) throws IOException {
        float w = width(label, bold, 7f) + 10;
        cs.setNonStrokingColor(bg[0], bg[1], bg[2]);
        cs.addRect(x, yy - 3, w, 12);
        cs.fill();
        text(bold, 7f, x + 5, yy, label, fg);
        return x + w + 6;
    }

    // ── Yazı yardımcıları ────────────────────────────────────────────────────

    private void text(PDFont font, float size, float x, float yy, String s, float[] color) throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, yy);
        cs.showText(encodable(font, s));
        cs.endText();
    }

    private float width(String s, PDFont font, float size) throws IOException {
        return font.getStringWidth(encodable(font, s)) / 1000 * size;
    }

    /**
     * Fontun ÇİZEMEYECEĞİ karakterleri güvenli karşılıklarına indirger.
     *
     * <p>Zorunlu: PDFBox eksik bir glyph görünce {@code IllegalArgumentException} fırlatır ve
     * TÜM belge üretimi düşer. Roboto'da örneğin ✓ (U+2713) yok; envanterdeki serbest metin
     * alanlarında (değişiklik açıklaması) ise her türlü karakter olabilir. Tek bir karakter
     * yüzünden aylık raporun eki tamamen kaybolmasın diye burada süzülür.
     *
     * <p>Karakter başına sonuç önbelleklenir — kontrol {@code getStringWidth} denemesiyle yapılır
     * ve yüzlerce kayıtta tekrar etmesi pahalı olurdu.
     */
    private String encodable(PDFont font, String s) {
        if (!embedded || font == mono) return sanitize(s);
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

    private final Map<PDFont, Map<Integer, Boolean>> glyphCache = new java.util.HashMap<>();

    private boolean supports(PDFont font, int cp, String ch) {
        return glyphCache.computeIfAbsent(font, f -> new java.util.HashMap<>())
                .computeIfAbsent(cp, c -> {
                    try { font.getStringWidth(ch); return true; }
                    catch (Exception e) { return false; }
                });
    }

    /** Fontta olmayan tipografik işaretlerin okunur karşılıkları (ASCII '?' yerine). */
    private static final Map<Integer, String> FALLBACK = Map.of(
            0x2713, "+",     // ✓ onay
            0x2014, "-",     // em dash
            0x2013, "-",     // en dash
            0x2026, "...",   // …
            0x00B7, "-",     // ·
            0x201C, "\"", 0x201D, "\"", 0x2018, "'", 0x2019, "'");

    private String clip(String s, float maxWidth, PDFont font, float size) throws IOException {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ');
        while (v.length() > 1 && width(v, font, size) > maxWidth) {
            v = v.substring(0, v.length() - 2) + "…";
        }
        return v;
    }

    /** Uzun metni satırlara böler; en fazla {@code maxLines} satır, gerisi "…". */
    private List<String> wrap(String s, float maxWidth, PDFont font, float size, int maxLines) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split("\\s+")) {
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

    /** Sayfa numaraları en sonda basılır — toplam sayfa ancak tüm kayıtlar çizilince bilinir. */
    private void stampPageNumbers() throws IOException {
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream s = new PDPageContentStream(
                    doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                s.setNonStrokingColor(MUTED[0], MUTED[1], MUTED[2]);
                s.beginText();
                s.setFont(regular, 7f);
                s.newLineAtOffset(PAGE.getWidth() - MARGIN - 60, MARGIN - 16);
                s.showText("Sayfa " + (i + 1) + " / " + total);
                s.endText();
                if (footerLabel != null) {           // özetli belge: sol altta rapor + ay (her sayfada bağlam)
                    s.beginText();
                    s.setFont(regular, 7f);
                    s.newLineAtOffset(MARGIN, MARGIN - 16);
                    s.showText(encodable(regular, footerLabel));
                    s.endText();
                }
            }
        }
    }

    // ── küçük yardımcılar ────────────────────────────────────────────────────

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nzDash(String s) { return notBlank(s) ? s : "—"; }

    private static String tlsMode(String m) {
        if ("browser".equals(m)) return "Browser (TLS 1.2 + ALPN)";
        if ("default".equals(m)) return "Java varsayılanı (TLS 1.3)";
        return "Global ayarı kullan";
    }

    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4);
    }

    /** Markdown işaretlerini temizler (ekrandaki PDF de aynısını yapar — mail/PDF markdown render etmez). */
    private static String stripMd(String s) {
        return s.replaceAll("[*_`#>]", "").replaceAll("\\s+", " ").trim();
    }

    private static String sanitize(String s) {
        return s.replace('ş', 's').replace('Ş', 'S').replace('ğ', 'g').replace('Ğ', 'G')
                .replace('ı', 'i').replace('İ', 'I').replace('ç', 'c').replace('Ç', 'C')
                .replace('ö', 'o').replace('Ö', 'O').replace('ü', 'u').replace('Ü', 'U')
                .replace('…', '.').replace('✓', 'x').replace('—', '-').replace('·', '-')
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    @Override
    public void close() throws IOException {
        closeStream();
        doc.close();
    }
}
