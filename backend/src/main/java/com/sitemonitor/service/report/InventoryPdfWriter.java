package com.sitemonitor.service.report;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.CertificateInventoryOps;
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
        newPage();
        drawBrandHeader(rows.size());
        for (CertificateInventory r : rows) {
            ensureSpace(estimateHeight(r));
            drawRecord(r, teams);
        }
        closeStream();
        stampPageNumbers();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
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
