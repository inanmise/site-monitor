package com.sitemonitor.service.report;

import com.sitemonitor.service.BrandMailAssets;
import com.sitemonitor.service.techerror.TechErrorFormat;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorRichText;
import com.sitemonitor.service.techerror.TechErrorSummary;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Column;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Slot;
import com.sitemonitor.service.techerror.TechErrorTemplate.TableRow;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.PdfCanvas.*;

/**
 * KANAL TEKNİK HATA RAPORU PDF'i (2026-10-13) — A4 dikey, Roboto gömülü, marka başlığı, sayfa numaraları. Bölümleri
 * şablon sırasıyla çizer: gösterge kartları, anomali başlıkları (açıklama satır sonları korunarak sarılır), metin ve
 * İKİ KOLONLU grafik ızgarası — ardışık iki "yarım" görsel yan yana, "tam" görsel tek satır; en-boy oranı korunur,
 * sayfaya sığmayan satır sonraki sayfaya geçer. Boş alan "—"; yüklenmemiş yuva PDF'e girmez; çizilemeyen görsel
 * sessizce kaybolmaz, yerine "çizilemedi" kutusu gelir.
 *
 * <p>2026-10-14: YÖNETİCİ ÖZETİ (kalıplardan üretilen ya da elle düzenlenen paragraf, rakamlar kalın), grup / alt
 * satırlı TABLO (başlık her sayfada tekrar, sayılar sağa yaslı) ve açıklamalarda madde / alt madde / kalın yazı
 * ({@link TechErrorRichText}).
 *
 * <p>2026-10-17 (R19): kapak dışındaki sayfalarda ÜST BİLGİ (kanal · dönem, sığarsa rapor başlığı — sayfa numarasıyla
 * birlikte en sonda basılır, devam sayfalarının içeriği bandın altından başlar); en az {@link #TOC_MIN_PAGES} içerik
 * sayfasında ilk sayfanın arkasına İÇİNDEKİLER (yalnız basılan bölümler, sayfa numarası + iç bağlantı); her uzunlukta
 * PDF YER İMLERİ. {@code in.sample()} (şablon önizlemesi) ilk sayfaya "ÖRNEK ÖNİZLEME" şeridini ekler.
 *
 * <p>Yazıcı hesaplamaz: adetleri kullanıcı girer, otomatik oranları ({@code TechErrorCalc}) servis PDF girdisini kurarken
 * hesaplar. Dil şablondan gelir (TR / EN etiketleri, ay adları,
 * sayı biçimi). Döngüler girdiyle sınırlıdır (bölüm ≤ 20, alan ≤ 30, başlık ≤ 30, yuva ≤ 40, metin ≤ 10 000 karakter).
 */
public final class TechErrorReportPdfWriter implements AutoCloseable {

    private static final float[] DARK = { 15 / 255f, 23 / 255f, 42 / 255f };
    private static final float[] SUB = { 203 / 255f, 213 / 255f, 225 / 255f };
    private static final float CARD_H = 50f;
    private static final float GAP = 8f;
    /**
     * Görselin PDF'teki en büyük yüksekliği. Görsel yuvasının GENİŞLİĞİNİ doldurur, oran korunur (kullanıcı kararı 2026-10-16:
     * "hepsi yüklendiği alanın boyutunu alsın; büyütme ve küçültme ona göre"); yalnız sayfaya sığmayacak kadar uzun (dikey)
     * görsel bu yüksekliğe iner. Bölüm başlığı (3 satır) + resim yazısı (3 satır) + görsel bir sayfaya sığar.
     */
    static final float IMAGE_MAX_H = 640f;
    private static final int MAX_PARAGRAPH_LINES = 400;
    private static final float FAIL_BOX_H = 44f;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Istanbul"));

    private final PdfCanvas c = new PdfCanvas();
    private boolean en;
    private float embedScale = PdfCanvas.EMBED_SCALE;

    /**
     * İçindekiler sayfası en az bu kadar İÇERİK sayfasında eklenir (2026-10-17, R19 — kullanıcı onayı); daha kısa raporun
     * sayfa takımı aynı kalır. Yer imleri her uzunlukta eklenir.
     */
    static final int TOC_MIN_PAGES = 4;

    /** Basılan bölüm başlıkları (çizim sırasıyla): içindekiler ve yer imleri bunlardan kurulur. */
    private final List<PdfCanvas.Mark> marks = new ArrayList<>();

    /**
     * Görselleri gömme katları (çizim ölçüsünün katı piksel): önce ≈ 432 dpi; PDF e-posta sınırını aşarsa servis aynı
     * veriden sırayla daha düşük katla yeniden üretir (≈ 288 / 216 / 144 dpi) — görseller tam çözünürlükte saklandığından
     * (2026-10-16) retina ekran görüntüleriyle dolu rapor 15 MB'ı aşabiliyordu (inceleme 2026-10-16).
     */
    public static final float[] EMBED_SCALES = { PdfCanvas.EMBED_SCALE, 4f, 3f, 2f };

    public static byte[] render(TechErrorPdfInput in) throws IOException {
        return render(in, PdfCanvas.EMBED_SCALE);
    }

    public static byte[] render(TechErrorPdfInput in, float embedScale) throws IOException {
        try (TechErrorReportPdfWriter w = new TechErrorReportPdfWriter()) {
            w.embedScale = embedScale;
            return w.write(in);
        }
    }

    /** Ek / indirme dosya adı: {@code mobil-teknik-hata-2026-09.pdf} (EN: {@code mobile-technical-errors-2026-09.pdf}). */
    public static String fileName(String channelName, int year, int month, String lang) {
        String slug = ExecutiveSummaryPdfWriter.slug(channelName);
        String kind = "en".equals(lang) ? "technical-errors" : "teknik-hata";
        return (slug.isEmpty() ? "" : slug + "-") + kind + "-" + year + "-" + String.format("%02d", month) + ".pdf";
    }

    /** Şablon önizleme PDF'inin adı (R19): {@code mobil-sablon-onizleme.pdf} (EN: {@code mobile-template-preview.pdf}). */
    public static String previewFileName(String channelName, String lang) {
        String slug = ExecutiveSummaryPdfWriter.slug(channelName);
        return (slug.isEmpty() ? "" : slug + "-") + ("en".equals(lang) ? "template-preview" : "sablon-onizleme") + ".pdf";
    }

    byte[] write(TechErrorPdfInput in) throws IOException {
        en = in.template().en();
        c.setPageLabel(en ? "Page" : "Sayfa");
        // Üst bilgi (R19): kapak dışındaki her sayfada kanal · dönem (şablon dili) ve sığarsa rapor başlığı
        String period = TechErrorTemplate.monthName(in.month(), in.template().lang()) + " " + in.year();
        c.setRunningHeader(join(in.channelName(), period), in.title());
        c.newPage();
        cover(in);
        if (in.sample()) sampleBanner();
        for (Section s : in.template().sections()) {
            switch (s.kind()) {
                // Yönetici özetine gömülü bölüm (2026-10-17) kendi bölümü olarak basılmaz: rakamları özet cümlelerinde,
                // açıklamaları cümlenin altında (summary)
                case "metrics" -> { if (TechErrorTemplate.hostOf(in.template(), s) == null) metrics(in, s); }
                case "issues" -> issues(in, s);
                case "images" -> images(in, s);
                case "summary" -> summary(in, s);
                case "table" -> table(in, s);
                case "trend" -> trend(in, s);
                default -> text(in, s);
            }
        }
        closing(in);
        // İçindekiler (R19): bütün içerik çizildikten sonra — sayfa numaraları ancak şimdi bellidir; ilk sayfanın arkasına
        // eklenir, sonraki sayfaların numarası bir kayar (Mark sayfa nesnesini tuttuğundan numara son sıradan okunur)
        if (c.pageCount() >= TOC_MIN_PAGES && !marks.isEmpty()) toc();
        c.outline(marks);
        return c.finish();
    }

    // ── İçindekiler (2026-10-17, R19) ───────────────────────────────────────────────────────────────

    private static final float TOC_FONT = 10f;
    private static final float TOC_LINE = 13f;
    private static final float TOC_GAP = 5f;
    private static final float TOC_NUM_W = 28f;

    /**
     * İçindekiler sayfası: BASILAN her bölüm başlığı (muaf / boş olup hiç basılmayan bölüm burada da yok) ve sayfa numarası;
     * uzun başlık en çok iki satır, sonra "…"; numara sağa yaslı, başlıkla arası noktalı. Satır görünmez bir iç bağlantıdır
     * (tıklanınca bölüme gider). Bölüm sayısı ≤ 20 (şablon sınırı): 20 × (2 satır + aralık) tek sayfaya sığar; sayfa sonu
     * koruması yine de var (sayfanın dışına çizilmez).
     */
    private void toc() throws IOException {
        c.insertPageAfter(0);
        String heading = en ? "Contents" : "İçindekiler";
        c.text(c.bold, 14f, MARGIN, c.y, heading, INK);
        c.y -= 8;
        c.line(MARGIN, c.y, MARGIN + CONTENT_W, RULE);
        c.y -= 18;
        float textW = CONTENT_W - TOC_NUM_W - 12;
        for (PdfCanvas.Mark m : marks) {
            List<String> lines = c.wrapCell(m.title(), textW, c.regular, TOC_FONT, 2);
            if (lines.isEmpty()) continue;
            float h = lines.size() * TOC_LINE;
            if (c.y - h < BOTTOM) break;
            float top = c.y;
            for (String l : lines) {
                c.text(c.regular, TOC_FONT, MARGIN, c.y, l, INK);
                c.y -= TOC_LINE;
            }
            float lastY = c.y + TOC_LINE;
            String num = String.valueOf(c.pageNumber(m.page()));
            float numW = c.width(num, c.bold, TOC_FONT);
            float numX = MARGIN + CONTENT_W - numW;
            c.text(c.bold, TOC_FONT, numX, lastY, num, INK);
            // Noktalı ayraç: son satırın bitiminden numaraya kadar (döngü yok — tek dizgi, uzunluğu genişlikle sınırlı)
            float from = MARGIN + c.width(lines.get(lines.size() - 1), c.regular, TOC_FONT) + 6;
            float to = numX - 6;
            float dot = c.width(" .", c.regular, TOC_FONT);
            int n = dot <= 0 ? 0 : (int) Math.min(200, Math.floor((to - from) / dot));
            if (n >= 2) c.text(c.regular, TOC_FONT, to - n * dot, lastY, " .".repeat(n), MUTED);
            c.link(MARGIN, lastY - 3, CONTENT_W, top - lastY + TOC_FONT + 3, m);
            c.y -= TOC_GAP;
        }
    }

    // ── Kapak ────────────────────────────────────────────────────────────────────────────────────────

    private void cover(TechErrorPdfInput in) throws IOException {
        float top = PAGE.getHeight();
        float textX = MARGIN + 46;
        float textW = CONTENT_W - 46;
        List<String> titleLines = c.wrapCell(in.title(), textW, c.bold, 15f, 2);
        // Kimlik satırları (2026-10-17, 10. sürüm): oluşturan / son düzenleyen / onay + üretim — band onlara göre uzar
        List<String> metaLines = in.provenance() == null
                ? List.of((en ? "Prepared by: " : "Hazırlayan: ") + nz(in.preparedBy()) + "  ·  " + generatedLine(in))
                : withGenerated(com.sitemonitor.service.mail.TechErrorReportMail.provenanceLines(in.provenance(), en), in);
        float bandH = (titleLines.size() > 1 ? 112 : 94) + (metaLines.size() - 1) * 10f;
        c.rect(0, top - bandH, PAGE.getWidth(), bandH, DARK);
        c.image(BrandMailAssets.logoBytes("ok"), MARGIN, top - 66, 34, 34);
        float y = top - 40;
        for (String l : titleLines) {
            c.text(c.bold, 15f, textX, y, l, WHITE);
            y -= 18;
        }
        String period = TechErrorTemplate.monthName(in.month(), in.template().lang()) + " " + in.year();
        c.text(c.regular, 9.5f, textX, y - 2, c.clip(join(in.teamName(), in.channelName(), period), textW, c.regular, 9.5f), SUB);
        float my = y - 16;
        for (String meta : metaLines) {
            c.text(c.regular, 7.5f, textX, my, c.clip(meta, textW, c.regular, 7.5f), MUTED);
            my -= 10;
        }
        c.y = top - bandH - 22;
    }

    private static final float[] SAMPLE_BG = { 254 / 255f, 243 / 255f, 199 / 255f };
    private static final float[] SAMPLE_FG = { 120 / 255f, 53 / 255f, 15 / 255f };

    /**
     * Şablon önizlemesi işareti (R19): kapak bandının hemen altında, ilk sayfada görünür bir şerit — bu PDF örnek
     * değerlerle üretildi, hiçbir yere gönderilmez. Yalnız {@code in.sample()}; gerçek raporun ilk sayfası aynı kalır.
     */
    private void sampleBanner() throws IOException {
        String head = en ? "SAMPLE PREVIEW — generated with sample values, never sent"
                : "ÖRNEK ÖNİZLEME — örnek değerlerle üretildi, gönderilmez";
        String sub = en ? "Figures, text and topics were made up by Site Monitor to show the template's layout; image slots stay empty and no earlier month exists for trends."
                : "Rakamlar, metinler ve konular şablonun görünümünü göstermek için Site Monitor tarafından uyduruldu; görsel yuvaları boş kalır, trend için geçmiş ay yoktur.";
        List<String> subLines = c.wrapCell(sub, CONTENT_W - 20, c.regular, 7.5f, 2);
        float h = 24 + subLines.size() * 9.5f;
        float top = c.y + 12;
        c.rect(MARGIN, top - h, CONTENT_W, h, SAMPLE_BG);
        c.rect(MARGIN, top - h, CONTENT_W, 0.8f, AMBER);
        c.text(c.bold, 9.5f, MARGIN + 10, top - 14, c.clip(head, CONTENT_W - 20, c.bold, 9.5f), SAMPLE_FG);
        float ly = top - 26;
        for (String l : subLines) {
            c.text(c.regular, 7.5f, MARGIN + 10, ly, l, SAMPLE_FG);
            ly -= 9.5f;
        }
        c.y = top - h - 22;
    }

    private String generatedLine(TechErrorPdfInput in) {
        return (en ? "Generated: " : "Üretim: ") + STAMP.format(in.generatedAt() == null ? Instant.now() : in.generatedAt())
                + (en ? " (Turkey time)" : " (Türkiye saati)");
    }

    private List<String> withGenerated(List<String> lines, TechErrorPdfInput in) {
        List<String> out = new ArrayList<>(lines);
        out.add(generatedLine(in));
        return out;
    }

    // ── Bölümler ─────────────────────────────────────────────────────────────────────────────────────

    public static boolean blankText(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private void metrics(TechErrorPdfInput in, Section s) throws IOException {
        List<Field> cards = new ArrayList<>();
        List<Field> texts = new ArrayList<>();
        Map<String, Object> vals = in.values().fields();
        for (Field f : s.fields()) {
            if (TechErrorTemplate.exempt(f, vals)) continue;     // "Hayır" cevabıyla muaf alan PDF'e girmez
            // Boş uzun açıklama (isteğe bağlı) basılmaz — özet kartına gömülü notlarla aynı kural (7. sürüm, inceleme 2026-10-17);
            // kısa metin alanı boşsa "—" (alan vardı, doldurulmadı)
            if ("textarea".equals(f.type()) && blankText(vals.get(f.id()))) continue;
            if ("text".equals(f.type()) || "textarea".equals(f.type())) texts.add(f);
            else cards.add(f);
        }
        if (cards.isEmpty() && texts.isEmpty()) return;          // bölümün tüm alanları muaf → başlık da basılmaz
        sectionTitle(in.resolve(s.title()));
        if (!cards.isEmpty()) {
            int perRow = cards.size() == 1 ? 1 : cards.size() == 2 || cards.size() == 4 ? 2 : 3;
            float w = (CONTENT_W - GAP * (perRow - 1)) / perRow;
            for (int i = 0; i < cards.size(); i += perRow) {
                c.ensureSpace(CARD_H + GAP);
                float top = c.y + 8;
                for (int j = 0; j < perRow && i + j < cards.size(); j++) {
                    Field f = cards.get(i + j);
                    float x = MARGIN + j * (w + GAP);
                    c.rect(x, top - CARD_H, w, CARD_H, ZEBRA);
                    c.rect(x, top - CARD_H, w, 0.6f, RULE);
                    List<String> label = c.wrapCell(in.resolve(f.label()), w - 14, c.regular, 7.5f, 2);
                    float ly = top - 11;
                    for (String l : label) {
                        c.text(c.regular, 7.5f, x + 7, ly, l, LABEL);
                        ly -= 9;
                    }
                    Object v = vals.get(f.id());
                    String shown = TechErrorFormat.value(f, v, in.template().lang());
                    c.text(c.bold, 14f, x + 7, top - CARD_H + 9, c.clip(shown, w - 14, c.bold, 14f), v == null ? MUTED : INK);
                }
                c.y -= CARD_H + GAP;
            }
        }
        for (Field f : texts) {
            Object v = vals.get(f.id());
            c.ensureSpace(24);
            c.text(c.bold, 8.5f, MARGIN, c.y, c.clip(in.resolve(f.label()), CONTENT_W, c.bold, 8.5f), INK);
            c.y -= 11;
            if (v == null) {
                c.text(c.regular, 8.5f, MARGIN, c.y, TechErrorFormat.EMPTY, MUTED);
                c.y -= 13;
            } else {
                rich(String.valueOf(v), 8.5f, MARGIN, CONTENT_W, INK);
                c.y -= 3;
            }
        }
        c.y -= 6;
    }

    private void issues(TechErrorPdfInput in, Section s) throws IOException {
        sectionTitle(in.resolve(s.title()));
        List<Issue> list = in.values().issues().getOrDefault(s.id(), List.of());
        if (list.isEmpty()) {
            note(en ? "No topic was entered for this month." : "Bu ay için başlık girilmedi.");
            return;
        }
        int n = 0;
        for (Issue i : list) {
            n++;
            String title = i.displayTitle(s.itemTitle());
            if (title.isBlank()) title = en ? "Untitled topic" : "Başlıksız konu";
            List<String> head = c.wrapCell(n + ". " + title, CONTENT_W, c.bold, 10.5f, 3);
            c.ensureSpace(head.size() * 13 + 30);
            for (String l : head) {
                c.text(c.bold, 10.5f, MARGIN, c.y, l, INK);
                c.y -= 13;
            }
            String counts = in.resolve(s.customersLabel()) + ": " + TechErrorFormat.count(i.customers(), in.template().lang())
                    + "     " + in.resolve(s.errorsLabel()) + ": " + TechErrorFormat.count(i.errors(), in.template().lang());
            for (String l : c.wrapCell(counts, CONTENT_W, c.bold, 8f, 2)) {
                c.ensureSpace(11);
                c.text(c.bold, 8f, MARGIN, c.y, l, BLUE);
                c.y -= 11;
            }
            if (i.description() != null && !i.description().isBlank()) {
                c.y -= 1;
                rich(i.description(), 8.5f, MARGIN, CONTENT_W, INK);
            }
            c.y -= 4;
            c.ensureSpace(8);
            c.line(MARGIN, c.y + 2, MARGIN + CONTENT_W, RULE);
            c.y -= 10;
        }
        c.y -= 2;
    }

    private void text(TechErrorPdfInput in, Section s) throws IOException {
        String stored = in.values().texts().get(s.id());
        String text = stored != null ? stored : s.text();
        if (text == null || text.isBlank()) return;     // kullanıcı metni bilerek boşalttı → bölüm basılmaz
        sectionTitle(in.resolve(s.title()));
        rich(in.resolveText(text), 9f, MARGIN, CONTENT_W, INK);
        c.y -= 8;
    }

    /** Not etiketiyle notun ilk satırı arasındaki ve not metninin girintisi. */
    private static final float NOTE_INDENT = 10f;
    private static final float NOTE_SIZE = 9.5f;

    /**
     * Yönetici özeti: kalıplardan üretilen (ya da elle düzenlenen) metin; hiç cümle ve not kalmadıysa bölüm basılmaz.
     * 2026-10-17: özete gömülü bölümlerin açıklamaları ilgili cümlenin hemen altına düşer ({@link TechErrorSummary#blocks}):
     * küçük kalın başlık = alanın adı (inceleme 2026-10-17: sabit "Açıklama" iki notu ayırt ettirmezdi; ad boşsa "Açıklama",
     * en çok iki satır) + girintili zengin metin (renkli sol şerit yok). Her blok kendi {@code rich()} çağrısıdır: uzun bir
     * not sonraki cümleleri kısaltmaz.
     */
    private void summary(TechErrorPdfInput in, Section s) throws IOException {
        List<TechErrorSummary.Block> blocks = TechErrorSummary.blocks(s, in.template(), in.values(), in.previousOrNull(),
                in.channelName(), in.year(), in.month());
        List<com.sitemonitor.service.techerror.TechErrorTiles.Tile> tiles = com.sitemonitor.service.techerror.TechErrorTiles.build(
                in.template(), s, in.values(), in.previousOrNull(), in.channelName(), in.year(), in.month());
        boolean any = !tiles.isEmpty();
        for (TechErrorSummary.Block b : blocks) any |= b.note() || !TechErrorRichText.blank(b.text());
        if (!any) return;
        String title = in.resolve(s.title());
        if (!tiles.isEmpty()) {
            // Başlık ilk kutucuk satırıyla aynı sayfada kalır (görsel bölümleriyle aynı kural; inceleme 2026-10-17)
            int titleLines = c.wrapCell(title, CONTENT_W, c.bold, 12.5f, 3).size();
            c.ensureSpace(titleLines * 15 + 6 + TILE_H + GAP);
        }
        sectionTitle(title);
        if (!tiles.isEmpty()) tiles(tiles);
        boolean first = true;
        for (TechErrorSummary.Block b : blocks) {
            if (b.note()) {
                if (!first) c.y -= 2;
                String label = b.label() == null || b.label().isBlank() ? (en ? "Note" : "Açıklama") : b.label();
                List<String> labelLines = c.wrap(label, CONTENT_W - NOTE_INDENT, c.bold, 8f, 2);
                c.ensureSpace(11f * labelLines.size() + NOTE_SIZE + 3.5f);   // başlık notun ilk satırıyla aynı sayfada
                for (String l : labelLines) {
                    c.text(c.bold, 8f, MARGIN + NOTE_INDENT, c.y, l, LABEL);
                    c.y -= 11;
                }
                rich(b.text(), NOTE_SIZE, MARGIN + NOTE_INDENT, CONTENT_W - NOTE_INDENT, INK);
                c.y -= 4;
            } else {
                if (TechErrorRichText.blank(b.text())) continue;
                if (!first) c.y -= 4;
                rich(b.text(), 10f, MARGIN, CONTENT_W, INK);
            }
            first = false;
        }
        c.y -= 10;
    }

    // ── Kutucuk şeridi (2026-10-17, 4b) ─────────────────────────────────────────────────────────────
    private static final float TILE_H = 58f;
    private static final float[] GOOD_BG = { 220 / 255f, 252 / 255f, 231 / 255f };
    private static final float[] GOOD_FG = { 22 / 255f, 101 / 255f, 52 / 255f };
    private static final float[] BAD_BG = { 254 / 255f, 226 / 255f, 226 / 255f };
    private static final float[] BAD_FG = { 153 / 255f, 27 / 255f, 27 / 255f };
    private static final float[] NEUTRAL_BG = { 219 / 255f, 234 / 255f, 254 / 255f };
    private static final float[] NEUTRAL_FG = { 30 / 255f, 58 / 255f, 138 / 255f };

    /** Ton → {zemin, yazı}: iyi yeşil, kötü kırmızı, nötr mavi, bilinmeyen gri (kullanıcı kararı 2026-10-16 renk paleti). */
    static float[][] tileColors(String tone) {
        return switch (tone) {
            case "good" -> new float[][] { GOOD_BG, GOOD_FG };
            case "bad" -> new float[][] { BAD_BG, BAD_FG };
            case "neutral" -> new float[][] { NEUTRAL_BG, NEUTRAL_FG };
            default -> new float[][] { ZEBRA, MUTED };
        };
    }

    /**
     * Yönetici özetinin başındaki renkli kutucuklar: satırda 3 (1–2 kutucukta o kadar), her kutuda etiket (en çok 2 satır),
     * kalın değer, altta önceki aya göre fark (üçgen + metin) ve varsa hedef. Zemin ve yazı rengi tondan; renk tek bilgi
     * taşıyıcısı değildir — ok üçgeni ve metin aynı şeyi söyler.
     */
    private void tiles(List<com.sitemonitor.service.techerror.TechErrorTiles.Tile> tiles) throws IOException {
        int perRow = Math.min(3, tiles.size());
        float w = (CONTENT_W - GAP * (perRow - 1)) / perRow;
        for (int i = 0; i < tiles.size(); i += perRow) {
            c.ensureSpace(TILE_H + GAP);
            // Başlığın altındaki boşluğa 8 pt sokulur; yeni sayfada üst kenar boşluğuna taşmaz (inceleme 2026-10-17)
            float top = Math.min(c.y + 8, c.pageTop());
            for (int j = 0; j < perRow && i + j < tiles.size(); j++) {
                var tile = tiles.get(i + j);
                float[][] col = tileColors(tile.tone());
                float x = MARGIN + j * (w + GAP);
                c.rect(x, top - TILE_H, w, TILE_H, col[0]);
                List<String> label = c.wrapCell(tile.label(), w - 14, c.regular, 7.5f, 2);
                float ly = top - 11;
                for (String l : label) {
                    c.text(c.regular, 7.5f, x + 7, ly, l, col[1]);
                    ly -= 9;
                }
                c.text(c.bold, 13f, x + 7, top - TILE_H + 20, c.clip(tile.value(), w - 14, c.bold, 13f), col[1]);
                StringBuilder sub = new StringBuilder();
                float sx = x + 7;
                if (tile.dir() != null && !"flat".equals(tile.dir())) {
                    c.triangle(sx, top - TILE_H + 8.5f, 5f, "up".equals(tile.dir()), col[1]);
                    sx += 7.5f;
                }
                if (tile.delta() != null) sub.append(tile.delta());
                if (tile.target() != null) {
                    if (!sub.isEmpty()) sub.append(" · ");
                    sub.append(en ? "target " : "hedef ").append(tile.target());
                }
                if (!sub.isEmpty()) c.text(c.regular, 7.5f, sx, top - TILE_H + 8, c.clip(sub.toString(), w - 14 - (sx - x - 7), c.regular, 7.5f), col[1]);
            }
            c.y -= TILE_H + GAP;
        }
        c.y -= 4;
    }

    private static final float TABLE_PAD = 5f;
    private static final float TABLE_FONT = 8f;
    private static final float TABLE_FONT_MIN = 5.5f;
    private static final float TABLE_HEAD_FONT = 7.5f;
    private static final int TABLE_HEAD_LINES = 6;
    private static final int TABLE_LABEL_LINES = 6;
    private static final int TABLE_TEXT_LINES = 8;
    private static final float[] GROUP_BG = { 238 / 255f, 242 / 255f, 255 / 255f };

    /** Bir tablo satırının yerleşimi: satır adı satırları, hücre başına satırlar ve çizim boyu, yükseklik. */
    private record RowLayout(TableRow row, boolean group, float indent, List<String> label, List<List<String>> cells,
                             float[] sizes, float height) { }

    /**
     * Grup / alt satırlı tablo: ilk sütun satır adı, diğerleri şablon sütunları; başlık her yeni sayfada tekrar çizilir.
     * Hiç değer girilmemiş tablo basılmaz. İnceleme 2026-10-14:
     * <ul>
     *   <li>metin sütunu sayı sütununun iki katı yer alır; çok sütunda satır adı sütunu daralır;</li>
     *   <li>sayı hücresi karakter ortasından BÖLÜNMEZ ({@link PdfCanvas#numberCell}: küçült, gerekirse ayraçtan böl);</li>
     *   <li>sığmayan başlık / satır adı / metin hücresi "…" ile biterse tablonun altına "kısaltıldı" notu düşülür
     *       (modül kuralı: metin sessizce kesilmez);</li>
     *   <li>başlık, tablo başlığı ve ilk satır birlikte sığmıyorsa tablo yeni sayfada başlar (başlık yalnız kalmaz);</li>
     *   <li>içeriden başlama yalnız bir grup satırının ALTINDAKİ satırlar için (ilk grup satırından önceki satırlar düz).</li>
     * </ul>
     */
    private void table(TechErrorPdfInput in, Section s) throws IOException {
        boolean any = false;
        for (TableRow r : s.rows()) for (Column col : s.columns()) if (in.values().cell(s.id(), r.id(), col.id()) != null) any = true;
        if (!any) return;                                   // hiç değer girilmemiş tablo yönetici raporunda yer kaplamaz
        List<Column> cols = s.columns();
        int n = cols.size();
        float labelW = n > 8 ? Math.max(100f, CONTENT_W * 0.20f) : n > 6 ? Math.max(120f, CONTENT_W * 0.24f) : CONTENT_W * 0.30f;
        float[] colW = new float[n];
        float[] colX = new float[n];
        float weights = 0;
        for (Column col : cols) weights += "text".equals(col.type()) ? 2f : 1f;
        float x = MARGIN + labelW;
        for (int k = 0; k < n; k++) {
            colW[k] = (CONTENT_W - labelW) * ("text".equals(cols.get(k).type()) ? 2f : 1f) / Math.max(1f, weights);
            colX[k] = x;
            x += colW[k];
        }
        boolean cut = false;
        List<List<String>> heads = new ArrayList<>();
        float headH = 0;
        for (int k = 0; k < n; k++) {
            PdfCanvas.Wrapped h = c.wrapCellChecked(in.resolve(cols.get(k).label()), colW[k] - TABLE_PAD * 2, c.bold,
                    TABLE_HEAD_FONT, TABLE_HEAD_LINES);
            cut |= h.truncated();
            heads.add(h.lines());
            headH = Math.max(headH, h.lines().size() * 9f);
        }
        headH += TABLE_PAD * 2;
        String lang = in.template().lang();
        List<RowLayout> layouts = new ArrayList<>();
        boolean seenGroup = false;
        for (TableRow r : s.rows()) {
            boolean group = r.group();
            float indent = seenGroup && !group ? 8f : 0;
            seenGroup |= group;
            var font = group ? c.bold : c.regular;
            PdfCanvas.Wrapped label = c.wrapCellChecked(in.resolve(r.label()), labelW - TABLE_PAD * 2 - indent, font,
                    TABLE_FONT, TABLE_LABEL_LINES);
            cut |= label.truncated();
            List<List<String>> cells = new ArrayList<>();
            float[] sizes = new float[n];
            int maxLines = Math.max(1, label.lines().size());
            for (int k = 0; k < n; k++) {
                Column col = cols.get(k);
                String v = TechErrorFormat.cell(col, in.values().cell(s.id(), r.id(), col.id()), lang);
                float w = colW[k] - TABLE_PAD * 2;
                if ("text".equals(col.type())) {
                    PdfCanvas.Wrapped t = c.wrapCellChecked(v, w, font, TABLE_FONT, TABLE_TEXT_LINES);
                    cut |= t.truncated();
                    cells.add(t.lines());
                    sizes[k] = TABLE_FONT;
                } else {
                    PdfCanvas.NumberCell nc = c.numberCell(v, w, font, TABLE_FONT, TABLE_FONT_MIN, "en".equals(lang) ? ',' : '.');
                    cells.add(nc.lines());
                    sizes[k] = nc.size();
                }
                maxLines = Math.max(maxLines, cells.get(k).size());
            }
            layouts.add(new RowLayout(r, group, indent, label.lines(), cells, sizes, maxLines * 10f + TABLE_PAD * 2 - 2));
        }
        // Tablo başlığı + sütun başlıkları + ilk satır birlikte sığmazsa yeni sayfa: başlık sayfa sonunda yalnız kalmasın
        String title = in.resolve(s.title());
        int titleLines = c.wrapCell(title, CONTENT_W, c.bold, 12.5f, 3).size();
        c.ensureSpace(titleLines * 15 + 6 + headH + 20 + (layouts.isEmpty() ? 0 : layouts.get(0).height()));
        sectionTitle(title);
        tableHead(heads, colX, colW, headH, cols);
        for (RowLayout row : layouts) {
            float rowH = row.height();
            if (c.y - rowH < BOTTOM) {
                c.newPage();
                tableHead(heads, colX, colW, headH, cols);
            }
            float top = c.y;
            var font = row.group() ? c.bold : c.regular;
            if (row.group()) c.rect(MARGIN, top - rowH, CONTENT_W, rowH, GROUP_BG);
            float ty = top - TABLE_PAD - TABLE_FONT + 1;
            for (String l : row.label()) {
                c.text(font, TABLE_FONT, MARGIN + TABLE_PAD + row.indent(), ty, l, INK);
                ty -= 10f;
            }
            for (int k = 0; k < n; k++) {
                Column col = cols.get(k);
                boolean empty = in.values().cell(s.id(), row.row().id(), col.id()) == null;
                float size = row.sizes()[k];
                float cy = top - TABLE_PAD - TABLE_FONT + 1;
                for (String l : row.cells().get(k)) {
                    float lx = "text".equals(col.type()) ? colX[k] + TABLE_PAD : colX[k] + colW[k] - TABLE_PAD - c.width(l, font, size);
                    c.text(font, size, lx, cy, l, empty ? MUTED : INK);
                    cy -= 10f;
                }
            }
            c.line(MARGIN, top - rowH, MARGIN + CONTENT_W, RULE);
            c.y = top - rowH;
        }
        if (cut) {
            c.y -= 6;
            note(en ? "Some cells of this table were shortened; the full text is in Site Monitor."
                    : "Bu tablodaki bazı hücreler kısaltıldı; tamamı Site Monitor'de.");
            c.y -= 10;
        } else {
            c.y -= 22;
        }
    }

    private void tableHead(List<List<String>> heads, float[] colX, float[] colW, float headH, List<Column> cols) throws IOException {
        if (c.y - headH - 20 < BOTTOM) c.newPage();
        float top = c.y;
        c.rect(MARGIN, top - headH, CONTENT_W, headH, DARK);
        for (int k = 0; k < heads.size(); k++) {
            float hy = top - TABLE_PAD - TABLE_HEAD_FONT + 1;
            for (String l : heads.get(k)) {
                float lx = "text".equals(cols.get(k).type()) ? colX[k] + TABLE_PAD : colX[k] + colW[k] - TABLE_PAD - c.width(l, c.bold, TABLE_HEAD_FONT);
                c.text(c.bold, TABLE_HEAD_FONT, lx, hy, l, WHITE);
                hy -= 9f;
            }
        }
        c.y = top - headH;
    }

    /** İki kolonlu grafik ızgarası: ardışık iki yarım yan yana; tam tek satır. Yüklenmemiş yuva atlanır. */
    private void images(TechErrorPdfInput in, Section s) throws IOException {
        List<Slot> present = new ArrayList<>();
        for (Slot sl : s.slots()) if (in.images().containsKey(sl.id())) present.add(sl);
        String title = in.resolve(s.title());
        float colW = (CONTENT_W - GAP * 2) / 2;
        if (!present.isEmpty()) {
            // Başlık + ilk görsel satırı birlikte sığmazsa yeni sayfa: başlık sayfa sonunda yalnız kalmasın (tablodaki kural;
            // varsayılan şablonda artık beş görsel bölümü var — inceleme 2026-10-15)
            Slot a = present.get(0);
            Slot b = present.size() > 1 ? present.get(1) : null;
            boolean pair = "half".equals(a.width()) && b != null && "half".equals(b.width());
            boolean half = "half".equals(a.width());
            float firstH = rowHeight(in, pair ? List.of(a, b) : List.of(a), half ? colW : CONTENT_W, IMAGE_MAX_H);
            int titleLines = c.wrapCell(title, CONTENT_W, c.bold, 12.5f, 3).size();
            c.ensureSpace(titleLines * 15 + 6 + firstH);
        }
        sectionTitle(title);
        if (present.isEmpty()) {
            note(en ? "No image was uploaded to this section." : "Bu bölüme görsel yüklenmedi.");
            return;
        }
        for (int i = 0; i < present.size(); ) {
            Slot a = present.get(i);
            Slot b = i + 1 < present.size() ? present.get(i + 1) : null;
            if ("half".equals(a.width()) && b != null && "half".equals(b.width())) {
                imageRow(in, List.of(a, b), colW, IMAGE_MAX_H);
                i += 2;
            } else if ("half".equals(a.width())) {
                imageRow(in, List.of(a), colW, IMAGE_MAX_H);
                i += 1;
            } else {
                imageRow(in, List.of(a), CONTENT_W, IMAGE_MAX_H);
                i += 1;
            }
        }
    }

    // ── Otomatik trend (2026-10-15, Faz C) ─────────────────────────────────────────────────────────

    private static final float TREND_CAPTION_LINE = 10.5f;
    private static final float TREND_LABEL_H = 9f;      // en uzun (ve en derin negatif) çubuğun değer etiketi payı
    private static final float TREND_CHART_H = 100f;
    private static final float TREND_AXIS_H = 18f;      // ay + yıl etiketleri
    private static final int TREND_LABEL_STEPS = 7;    // 5,6 pt'den 0,2'şer en çok 7 adım = 4,2 pt; sığmazsa yazılmaz
    private static final float[] TREND_BAR = { 148 / 255f, 163 / 255f, 184 / 255f };

    /**
     * Girilen rakamlardan son 12 ayın çubuk grafikleri: alan başına bir hücre, iki hücre yan yana (yarım yuva gibi).
     * Raporun ayı vurgulu; değeri olmayan ay boş (sıfır çizilmez). İki aydan az değerli alan grafik yerine kısa not
     * basar. Şablonda olmayan ya da sayı / yüzde olmayan başvuru çizilmez (düzenleyici uyarır).
     */
    private void trend(TechErrorPdfInput in, Section s) throws IOException {
        List<Field> fields = new ArrayList<>();
        Map<String, Object> vals = in.values().fields();
        for (String ref : s.lines()) {
            for (Field f : in.template().allFields()) {
                // "Hayır" cevabıyla bu ay muaf olan alan PDF'e girmez — grafiği de (inceleme 2026-10-15)
                if (f.id().equals(ref) && TechErrorTemplate.trendable(f) && !TechErrorTemplate.exempt(f, vals)) fields.add(f);
            }
        }
        if (fields.isEmpty()) return;
        String title = in.resolve(s.title());
        int titleLines = c.wrapCell(title, CONTENT_W, c.bold, 12.5f, 3).size();
        float colW = (CONTENT_W - GAP * 2) / 2;
        c.ensureSpace(titleLines * 15 + 6 + trendRowHeight(in, fields, 0, colW));   // başlık ilk grafik satırıyla birlikte
        sectionTitle(title);
        for (int i = 0; i < fields.size(); i += 2) {
            float rowH = trendRowHeight(in, fields, i, colW);
            float capH = trendCaptionH(in, fields, i, colW);
            c.ensureSpace(rowH);
            float top = c.y;
            trendCell(in, fields.get(i), MARGIN, top, colW, capH);
            if (i + 1 < fields.size()) trendCell(in, fields.get(i + 1), MARGIN + colW + GAP * 2, top, colW, capH);
            c.y = top - rowH;
        }
        c.y -= 4;
    }

    /**
     * Grafik başlığı satırları. Zaman tabanı ("(Son 12 Ay)") hiç düşmez: ad ile birlikte iki satıra sığmıyorsa ad iki
     * satıra kısaltılır ("…") ve ek kendi satırında kalır (inceleme 2026-10-15: kesilen kısım ekti).
     */
    private List<String> trendCaption(TechErrorPdfInput in, Field f, float w) throws IOException {
        String label = in.resolve(f.label());
        String suffix = en ? "(Last 12 Months)" : "(Son 12 Ay)";
        PdfCanvas.Wrapped full = c.wrapCellChecked(label + " " + suffix, w, c.bold, 8.5f, 2);
        if (!full.truncated()) return full.lines();
        List<String> out = new ArrayList<>(c.wrapCell(label, w, c.bold, 8.5f, 2));
        out.add(suffix);
        return out;
    }

    /** Satırın başlık yüksekliği — iki hücrenin uzun olanı; grafikler aynı hizada başlar. */
    private float trendCaptionH(TechErrorPdfInput in, List<Field> fields, int i, float colW) throws IOException {
        int lines = trendCaption(in, fields.get(i), colW).size();
        if (i + 1 < fields.size()) lines = Math.max(lines, trendCaption(in, fields.get(i + 1), colW).size());
        return Math.max(1, lines) * TREND_CAPTION_LINE;
    }

    /** Satır yüksekliği; iki hücrede de grafik yoksa ("en az iki ay" notu) boş grafik alanı bırakılmaz. */
    private float trendRowHeight(TechErrorPdfInput in, List<Field> fields, int i, float colW) throws IOException {
        int min = com.sitemonitor.service.techerror.TechErrorTrend.MIN_POINTS;
        boolean chart = in.trend().points(fields.get(i).id()) >= min
                || (i + 1 < fields.size() && in.trend().points(fields.get(i + 1).id()) >= min);
        float head = trendCaptionH(in, fields, i, colW) + 4 + TREND_LABEL_H;
        return head + (chart ? TREND_CHART_H + TREND_AXIS_H + 10 : 10 + 32);
    }

    private void trendCell(TechErrorPdfInput in, Field f, float x, float top, float w, float capH) throws IOException {
        float y = top;
        for (String l : trendCaption(in, f, w)) {
            c.text(c.bold, 8.5f, x, y, l, INK);
            y -= TREND_CAPTION_LINE;
        }
        float chartTop = top - capH - 4 - TREND_LABEL_H;
        float chartBottom = chartTop - TREND_CHART_H;
        List<Double> vals = in.trend().series(f.id());
        List<java.time.YearMonth> months = in.trend().months();
        int points = in.trend().points(f.id());
        if (points < com.sitemonitor.service.techerror.TechErrorTrend.MIN_POINTS || months.size() != vals.size()) {
            String n = en ? "A trend needs figures for at least two months; months with figures so far: " + points + "."
                    : "Trend için en az iki ayın rakamı gerekir; şu ana kadar rakamı girilmiş ay: " + points + ".";
            float ny = chartTop - 10;
            for (String l : c.wrapCell(n, w, c.regular, 7.5f, 3)) {
                c.text(c.regular, 7.5f, x, ny, l, MUTED);
                ny -= 10;
            }
            return;
        }
        double max = 0, min = 0;
        for (Double d : vals) {
            if (d == null) continue;
            max = Math.max(max, d);
            min = Math.min(min, d);
        }
        if (max - min <= 0) max = min + 1;                           // hepsi sıfır: düz çizgi, bölme yok
        // Negatif değer varsa altta da etiket payı: en derin çubuğun etiketi ay adlarına binmez (inceleme 2026-10-15)
        float plotBottom = chartBottom + (min < 0 ? TREND_LABEL_H : 0);
        float plotH = chartTop - plotBottom;
        float zeroY = plotBottom + (float) ((0 - min) / (max - min)) * plotH;
        float slot = w / vals.size();
        float bw = slot * 0.62f;
        c.line(x, zeroY, x + w, RULE);
        for (int i = 0; i < vals.size(); i++) {
            float sx = x + i * slot;
            Double d = vals.get(i);
            if (d != null) {
                float h = (float) (Math.abs(d) / (max - min) * plotH);
                if (d != 0 && h < 0.8f) h = 0.8f;
                float by = d >= 0 ? zeroY : zeroY - h;
                if (h > 0) c.rect(sx + (slot - bw) / 2, by, bw, h, i == vals.size() - 1 ? BLUE : TREND_BAR);
                String lab = TechErrorFormat.compact(d, f.type(), en);
                float size = 5.6f;
                float lw = c.width(lab, c.regular, size);
                // Dar hücrede etiket komşusuna taşmasın: tamsayı adım (5,6 − 7 × 0,2f kayan noktada 4,2'den büyük kalıp
                // bir adım daha atıyordu)
                for (int step = 1; lw > slot - 1 && step <= TREND_LABEL_STEPS; step++) {
                    size = 5.6f - step * 0.2f;
                    lw = c.width(lab, c.regular, size);
                }
                // En küçük puntoda bile sığmayan (aşırı büyük) değer yazılmaz — komşu etiketin üstüne binmez
                if (lw <= slot - 1) {
                    float ly = d >= 0 ? zeroY + h + 2 : zeroY - h - size - 1;
                    c.text(c.regular, size, sx + slot / 2 - lw / 2, ly, lab, i == vals.size() - 1 ? INK : LABEL);
                }
            }
            java.time.YearMonth ym = months.get(i);
            String mon = TechErrorTemplate.monthName(ym.getMonthValue(), in.template().lang());
            mon = mon.length() > 3 ? mon.substring(0, 3) : mon;
            float mw = c.width(mon, c.regular, 5.8f);
            c.text(c.regular, 5.8f, sx + slot / 2 - mw / 2, chartBottom - 8, mon, LABEL);
            if (i == 0 || ym.getMonthValue() == 1) {
                String yy = String.valueOf(ym.getYear());
                float yw = c.width(yy, c.regular, 5.2f);
                c.text(c.regular, 5.2f, sx + slot / 2 - yw / 2, chartBottom - 15, yy, MUTED);
            }
        }
    }

    /**
     * Görselin çizim ölçüsü {genişlik, yükseklik}: yuvanın genişliği (küçük görsel büyür, büyük görsel küçülür), oran korunur;
     * {@code maxH}'yi aşan uzun görsel o yüksekliğe iner (genişlik orantılı daralır, yuvada ortalanır).
     */
    static float[] fit(float cellW, int imgW, int imgH, float maxH) {
        float w = cellW, h = cellW * imgH / Math.max(1f, imgW);
        if (h > maxH) {
            h = maxH;
            w = maxH * imgW / Math.max(1f, imgH);
        }
        return new float[]{ w, h };
    }

    /** {@link #imageRow}'un ayırdığı yükseklik (başlık + görsel + aralık) — çizmeden. */
    private float rowHeight(TechErrorPdfInput in, List<Slot> slots, float cellW, float maxH) throws IOException {
        float capH = 0, imgH = 0;
        for (Slot sl : slots) {
            capH = Math.max(capH, c.wrapCell(in.resolve(sl.label()), cellW, c.bold, 8.5f, 3).size() * 10.5f);
            TechErrorPdfInput.Image img = in.images().get(sl.id());
            imgH = Math.max(imgH, fit(cellW, img.width(), img.height(), maxH)[1]);
        }
        return capH + Math.max(imgH, FAIL_BOX_H) + 20;
    }

    private void imageRow(TechErrorPdfInput in, List<Slot> slots, float cellW, float maxH) throws IOException {
        List<List<String>> captions = new ArrayList<>();
        List<float[]> sizes = new ArrayList<>();
        float capH = 0, imgH = 0;
        for (Slot sl : slots) {
            List<String> cap = c.wrapCell(in.resolve(sl.label()), cellW, c.bold, 8.5f, 3);
            captions.add(cap);
            capH = Math.max(capH, cap.size() * 10.5f);
            TechErrorPdfInput.Image img = in.images().get(sl.id());
            float[] wh = fit(cellW, img.width(), img.height(), maxH);
            sizes.add(wh);
            imgH = Math.max(imgH, wh[1]);
        }
        // Çizilemeyen görselin kutusu da sığsın (çok yassı bir görselin yüksekliği birkaç punto olabilir)
        imgH = Math.max(imgH, FAIL_BOX_H);
        c.ensureSpace(capH + imgH + 20);
        float top = c.y;
        for (int k = 0; k < slots.size(); k++) {
            float x = MARGIN + k * (cellW + GAP * 2);
            float y = top;
            for (String l : captions.get(k)) {
                c.text(c.bold, 8.5f, x, y, l, INK);
                y -= 10.5f;
            }
            float[] wh = sizes.get(k);
            float imgTop = top - capH - 2;
            float ix = x + (cellW - wh[0]) / 2;
            float iy = imgTop - wh[1];
            TechErrorPdfInput.Image img = in.images().get(slots.get(k).id());
            boolean ok = c.tryImage(img.data(), img.width(), img.height(), "chart-" + slots.get(k).id(), ix, iy, wh[0], wh[1],
                    embedScale);
            if (ok) {
                c.strokeRect(ix, iy, wh[0], wh[1], RULE);
            } else {
                float boxH = Math.max(wh[1], FAIL_BOX_H);
                c.rect(x, imgTop - boxH, cellW, boxH, ZEBRA);
                c.strokeRect(x, imgTop - boxH, cellW, boxH, RULE);
                List<String> msg = c.wrapCell(en ? "The image couldn't be drawn; open it in Site Monitor."
                        : "Görsel çizilemedi; Site Monitor'de açın.", cellW - 16, c.regular, 8f, 2);
                float ty = imgTop - boxH / 2 + (msg.size() - 1) * 5f - 3;
                for (String l : msg) {
                    c.text(c.regular, 8f, x + 8, ty, l, AMBER);
                    ty -= 10;
                }
            }
        }
        c.y = top - capH - imgH - 18;
    }

    private void closing(TechErrorPdfInput in) throws IOException {
        c.y -= 4;
        if (com.sitemonitor.service.techerror.TechErrorCalc.anyRatioComputed(in.template(), in.values().fields())) {
            // 2026-10-16: bu raporda oran adetlerden hesaplandı — "sistem hesaplamaz" demek yanıltırdı
            note(en
                    ? "Counts in this report were entered by the " + nz(in.teamName()) + " team in Site Monitor; the rates were calculated by Site Monitor from those counts."
                    : "Bu rapordaki adetler " + nz(in.teamName()) + " takımı tarafından Site Monitor'de girilmiştir; oranlar bu adetlerden Site Monitor tarafından hesaplanmıştır.");
        } else {
            note(en
                    ? "Figures in this report were entered by the " + nz(in.teamName()) + " team in Site Monitor; the system does not calculate them."
                    : "Bu rapordaki değerler " + nz(in.teamName()) + " takımı tarafından Site Monitor'de girilmiştir; sistem hesaplamaz.");
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private void sectionTitle(String title) throws IOException {
        List<String> lines = c.wrapCell(title, CONTENT_W, c.bold, 12.5f, 3);
        c.ensureSpace(lines.size() * 15 + 40);
        // Basılan bölüm (R19): içindekiler + yer imi — başlığın çizildiği sayfa (ensureSpace'ten SONRA) ve üst kenarı
        if (!lines.isEmpty()) marks.add(new PdfCanvas.Mark(title.replaceAll("\\s+", " ").trim(), c.currentPage(), c.y + 14));
        for (String l : lines) {
            c.text(c.bold, 12.5f, MARGIN, c.y, l, INK);
            c.y -= 15;
        }
        c.y += 8;
        c.line(MARGIN, c.y, MARGIN + CONTENT_W, RULE);
        c.y -= 14;
    }

    /**
     * Zengin metin ({@link TechErrorRichText}): paragraf satırları, madde / alt madde (asılı girinti), numaralı madde,
     * kalın yazı; boş satır paragraf aralığı. Metin başına en çok {@code 2 × MAX_PARAGRAPH_LINES} satır; aşan metin
     * sessizce kesilmez, sonuna "kısaltıldı" notu düşülür.
     */
    private void rich(String text, float size, float x, float width, float[] color) throws IOException {
        if (text == null || text.isBlank()) return;
        float lh = size + 3.5f;
        int total = 0;
        for (TechErrorRichText.Block b : TechErrorRichText.parse(text)) {
            if (b.gap()) {
                c.y -= lh / 2;
                continue;
            }
            float indent = b.level() * 14f;
            float markerW = 0;
            String marker = null;
            if (b.item()) {
                marker = b.marker() != null ? b.marker() : switch (b.level()) {
                    case 0 -> "•";
                    case 1 -> "–";
                    default -> "·";
                };
                markerW = Math.max(10f, c.width(marker, c.regular, size) + 4f);
            }
            float tx = x + indent + markerW;
            float tw = Math.max(40f, width - indent - markerW);
            List<PdfCanvas.Span> spans = new ArrayList<>();
            for (TechErrorRichText.Run r : b.runs()) spans.add(new PdfCanvas.Span(r.text(), r.bold()));
            List<List<PdfCanvas.Span>> lines = c.wrapSpans(spans, tw, size, MAX_PARAGRAPH_LINES);
            boolean first = true;
            for (List<PdfCanvas.Span> l : lines) {
                if (++total > MAX_PARAGRAPH_LINES * 2) {
                    c.ensureSpace(lh);
                    c.text(c.regular, size - 1, x, c.y, en ? "… (text shortened; the full text is in Site Monitor)"
                            : "… (metin kısaltıldı; tamamı Site Monitor'de)", MUTED);
                    c.y -= lh;
                    return;
                }
                c.ensureSpace(lh);
                if (first && marker != null) c.text(c.regular, size, x + indent, c.y, marker, LABEL);
                first = false;
                c.spans(l, tx, c.y, size, color);
                c.y -= lh;
            }
            if (lines.isEmpty() && marker != null) {      // boş madde: işaret yine görünsün (yazan bilerek koymuş)
                c.ensureSpace(lh);
                c.text(c.regular, size, x + indent, c.y, marker, LABEL);
                c.y -= lh;
            }
            if (b.item()) c.y -= 1.5f;
        }
    }

    private void note(String text) throws IOException {
        for (String l : c.wrapCell(text, CONTENT_W, c.regular, 7.5f, 4)) {
            c.ensureSpace(10);
            c.text(c.regular, 7.5f, MARGIN, c.y, l, MUTED);
            c.y -= 10;
        }
        c.y -= 6;
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!b.isEmpty()) b.append("  ·  ");
            b.append(p.trim());
        }
        return b.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    @Override
    public void close() throws IOException {
        c.close();
    }
}
