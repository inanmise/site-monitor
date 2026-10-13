package com.sitemonitor.service.report;

import com.sitemonitor.service.BrandMailAssets;
import com.sitemonitor.service.techerror.TechErrorFormat;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Slot;
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
 * <p>Rakamların hepsi kullanıcının girdiğidir; yazıcı hesaplamaz. Dil şablondan gelir (TR / EN etiketleri, ay adları,
 * sayı biçimi). Döngüler girdiyle sınırlıdır (bölüm ≤ 20, alan ≤ 30, başlık ≤ 30, yuva ≤ 40, metin ≤ 10 000 karakter).
 */
public final class TechErrorReportPdfWriter implements AutoCloseable {

    private static final float[] DARK = { 15 / 255f, 23 / 255f, 42 / 255f };
    private static final float[] SUB = { 203 / 255f, 213 / 255f, 225 / 255f };
    private static final float CARD_H = 50f;
    private static final float GAP = 8f;
    private static final float HALF_MAX_H = 250f;
    private static final float FULL_MAX_H = 330f;
    private static final int MAX_PARAGRAPH_LINES = 400;
    private static final float FAIL_BOX_H = 44f;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Istanbul"));

    private final PdfCanvas c = new PdfCanvas();
    private boolean en;

    public static byte[] render(TechErrorPdfInput in) throws IOException {
        try (TechErrorReportPdfWriter w = new TechErrorReportPdfWriter()) {
            return w.write(in);
        }
    }

    /** Ek / indirme dosya adı: {@code mobil-teknik-hata-2026-09.pdf} (EN: {@code mobile-technical-errors-2026-09.pdf}). */
    public static String fileName(String channelName, int year, int month, String lang) {
        String slug = ExecutiveSummaryPdfWriter.slug(channelName);
        String kind = "en".equals(lang) ? "technical-errors" : "teknik-hata";
        return (slug.isEmpty() ? "" : slug + "-") + kind + "-" + year + "-" + String.format("%02d", month) + ".pdf";
    }

    byte[] write(TechErrorPdfInput in) throws IOException {
        en = in.template().en();
        c.setPageLabel(en ? "Page" : "Sayfa");
        c.newPage();
        cover(in);
        for (Section s : in.template().sections()) {
            switch (s.kind()) {
                case "metrics" -> metrics(in, s);
                case "issues" -> issues(in, s);
                case "images" -> images(in, s);
                default -> text(in, s);
            }
        }
        closing(in);
        return c.finish();
    }

    // ── Kapak ────────────────────────────────────────────────────────────────────────────────────────

    private void cover(TechErrorPdfInput in) throws IOException {
        float top = PAGE.getHeight();
        float textX = MARGIN + 46;
        float textW = CONTENT_W - 46;
        List<String> titleLines = c.wrapCell(in.title(), textW, c.bold, 15f, 2);
        float bandH = titleLines.size() > 1 ? 112 : 94;
        c.rect(0, top - bandH, PAGE.getWidth(), bandH, DARK);
        c.image(BrandMailAssets.logoBytes("ok"), MARGIN, top - 66, 34, 34);
        float y = top - 40;
        for (String l : titleLines) {
            c.text(c.bold, 15f, textX, y, l, WHITE);
            y -= 18;
        }
        String period = TechErrorTemplate.monthName(in.month(), in.template().lang()) + " " + in.year();
        c.text(c.regular, 9.5f, textX, y - 2, c.clip(join(in.teamName(), in.channelName(), period), textW, c.regular, 9.5f), SUB);
        String meta = (en ? "Prepared by: " : "Hazırlayan: ") + nz(in.preparedBy()) + "  ·  "
                + (en ? "Generated: " : "Üretim: ") + STAMP.format(in.generatedAt() == null ? Instant.now() : in.generatedAt())
                + (en ? " (Turkey time)" : " (Türkiye saati)");
        c.text(c.regular, 7.5f, textX, y - 16, c.clip(meta, textW, c.regular, 7.5f), MUTED);
        c.y = top - bandH - 22;
    }

    // ── Bölümler ─────────────────────────────────────────────────────────────────────────────────────

    private void metrics(TechErrorPdfInput in, Section s) throws IOException {
        sectionTitle(in.resolve(s.title()));
        List<Field> cards = new ArrayList<>();
        List<Field> texts = new ArrayList<>();
        for (Field f : s.fields()) {
            if ("text".equals(f.type()) || "textarea".equals(f.type())) texts.add(f);
            else cards.add(f);
        }
        Map<String, Object> vals = in.values().fields();
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
                paragraphs(String.valueOf(v), 8.5f, MARGIN, CONTENT_W, INK);
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
                paragraphs(i.description(), 8.5f, MARGIN, CONTENT_W, INK);
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
        paragraphs(in.resolveText(text), 9f, MARGIN, CONTENT_W, INK);
        c.y -= 8;
    }

    /** İki kolonlu grafik ızgarası: ardışık iki yarım yan yana; tam tek satır. Yüklenmemiş yuva atlanır. */
    private void images(TechErrorPdfInput in, Section s) throws IOException {
        List<Slot> present = new ArrayList<>();
        for (Slot sl : s.slots()) if (in.images().containsKey(sl.id())) present.add(sl);
        sectionTitle(in.resolve(s.title()));
        if (present.isEmpty()) {
            note(en ? "No chart was uploaded to this section." : "Bu bölüme grafik yüklenmedi.");
            return;
        }
        float colW = (CONTENT_W - GAP * 2) / 2;
        for (int i = 0; i < present.size(); ) {
            Slot a = present.get(i);
            Slot b = i + 1 < present.size() ? present.get(i + 1) : null;
            if ("half".equals(a.width()) && b != null && "half".equals(b.width())) {
                imageRow(in, List.of(a, b), colW, HALF_MAX_H);
                i += 2;
            } else if ("half".equals(a.width())) {
                imageRow(in, List.of(a), colW, HALF_MAX_H);
                i += 1;
            } else {
                imageRow(in, List.of(a), CONTENT_W, FULL_MAX_H);
                i += 1;
            }
        }
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
            float w = cellW, h = cellW * img.height() / Math.max(1f, img.width());
            if (h > maxH) {
                h = maxH;
                w = maxH * img.width() / Math.max(1f, img.height());
            }
            sizes.add(new float[]{ w, h });
            imgH = Math.max(imgH, h);
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
            boolean ok = c.tryImage(img.data(), "chart-" + slots.get(k).id(), ix, iy, wh[0], wh[1]);
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
        note(en
                ? "Figures in this report were entered by the " + nz(in.teamName()) + " team in Site Monitor; the system does not calculate them."
                : "Bu rapordaki değerler " + nz(in.teamName()) + " takımı tarafından Site Monitor'de girilmiştir; sistem hesaplamaz.");
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private void sectionTitle(String title) throws IOException {
        List<String> lines = c.wrapCell(title, CONTENT_W, c.bold, 12.5f, 3);
        c.ensureSpace(lines.size() * 15 + 40);
        for (String l : lines) {
            c.text(c.bold, 12.5f, MARGIN, c.y, l, INK);
            c.y -= 15;
        }
        c.y += 8;
        c.line(MARGIN, c.y, MARGIN + CONTENT_W, RULE);
        c.y -= 14;
    }

    /**
     * Satır sonlarını koruyarak sarar; art arda boş satırlar TEK paragraf aralığı olur. Metin başına en çok
     * {@code 2 × MAX_PARAGRAPH_LINES} satır; aşan metin sessizce kesilmez, sonuna "kısaltıldı" notu düşülür.
     */
    private void paragraphs(String text, float size, float x, float width, float[] color) throws IOException {
        float lh = size + 3.5f;
        int total = 0;
        boolean gap = false;
        for (String para : text.split("\n", -1)) {
            if (para.isBlank()) {
                if (!gap && total > 0) c.y -= lh / 2;
                gap = true;
                continue;
            }
            gap = false;
            for (String l : c.wrapCell(para, width, c.regular, size, MAX_PARAGRAPH_LINES)) {
                if (++total > MAX_PARAGRAPH_LINES * 2) {
                    c.ensureSpace(lh);
                    c.text(c.regular, size - 1, x, c.y, en ? "… (text shortened; the full text is in Site Monitor)"
                            : "… (metin kısaltıldı; tamamı Site Monitor'de)", MUTED);
                    c.y -= lh;
                    return;
                }
                c.ensureSpace(lh);
                c.text(c.regular, size, x, c.y, l, color);
                c.y -= lh;
            }
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
