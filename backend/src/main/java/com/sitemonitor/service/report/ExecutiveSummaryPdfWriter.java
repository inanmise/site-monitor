package com.sitemonitor.service.report;

import com.sitemonitor.service.BrandMailAssets;
import com.sitemonitor.service.report.executive.ExecFormat;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.SectionResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.report.PdfCanvas.*;

/**
 * AYLIK YÖNETİCİ ÖZETİ PDF'i (2026-10-10) — A4 dikey, Roboto gömülü (Türkçe karakterler), marka başlığı (turp logosu),
 * sayfa numaraları ({@link PdfCanvas#finish}). Bölümleri TANIMAZ: {@link SectionResult}'ı genel çizer (başlık + durum
 * çipi → hükümler → gösterge kutuları → tablolar → notlar); yeni bir bölüm sağlayıcısı kendiliğinden belgeye girer.
 *
 * <p>Renk tek başına bilgi taşımaz: durum çipleri ve tablo durum hücreleri metinle de yazılır (siyah-beyaz çıktı).
 * Üretim hatası belgeyi değil yalnız eki düşürür: {@link #render} boş dizi döner, posta eksiz gider.
 */
@Slf4j
public final class ExecutiveSummaryPdfWriter implements AutoCloseable {

    private static final float ROW_H = 13f;
    private static final float KPI_H = 50f;
    private static final float KPI_GAP = 6f;
    private static final float[] DARK = { 15 / 255f, 23 / 255f, 42 / 255f };
    private static final float[] OK_BG = { 220 / 255f, 252 / 255f, 231 / 255f };
    private static final float[] WARN_BG = { 254 / 255f, 243 / 255f, 199 / 255f };
    private static final float[] BAD_BG = { 254 / 255f, 226 / 255f, 226 / 255f };
    private static final float[] NEUTRAL_BG = { 241 / 255f, 245 / 255f, 249 / 255f };
    private static final Set<String> RIGHT = Set.of("int", "pct", "pp", "minutes", "days", "pct_change");
    private static final Set<String> WIDE = Set.of("text", "service", "team", "monitor_type", "renewal_class", "overdue_reason");

    private final PdfCanvas c = new PdfCanvas();

    /** PDF baytları; üretilemezse BOŞ dizi (çağıran eki eklemez). */
    public static byte[] render(ExecutiveSummary s) {
        try (ExecutiveSummaryPdfWriter w = new ExecutiveSummaryPdfWriter()) {
            return w.write(s);
        } catch (Exception e) {
            log.warn("Yönetici özeti PDF'i üretilemedi ({}): {}", s == null ? null : s.month(), e.toString(), e);
            return new byte[0];
        }
    }

    /** Ek dosya adı: {@code site-monitor-yonetici-ozeti-2026-09.pdf}. */
    public static String fileName(String month) {
        return "site-monitor-yonetici-ozeti-" + (month == null ? "ay" : month.replaceAll("[^0-9-]", "")) + ".pdf";
    }

    byte[] write(ExecutiveSummary s) throws IOException {
        c.newPage();
        cover(s);
        headline(s);
        for (SectionResult sec : s.sections()) section(sec);
        closing(s);
        return c.finish();
    }

    // ── Kapak + üst şerit ───────────────────────────────────────────────────────────────────────────────────────────

    private void cover(ExecutiveSummary s) throws IOException {
        float top = PAGE.getHeight();
        c.rect(0, top - 92, PAGE.getWidth(), 92, DARK);
        c.image(BrandMailAssets.logoBytes("ok"), MARGIN, top - 66, 34, 34);
        c.text(c.bold, 17f, MARGIN + 46, top - 44, "Aylık Yönetici Özeti", WHITE);
        c.text(c.regular, 10f, MARGIN + 46, top - 60, c.clip(nz(s.monthLabel(), s.month()) + "  ·  Site Monitor  ·  Kurum geneli",
                CONTENT_W - 170, c.regular, 10f), new float[]{ 203 / 255f, 213 / 255f, 225 / 255f });
        c.text(c.regular, 7.5f, MARGIN + 46, top - 74, "Üretim: " + stamp(s.generatedAt()) + " (Türkiye saati)"
                + (s.complete() ? "" : "  ·  ay devam ediyor") + (ExecutiveSummary.SOURCE_SNAPSHOT.equals(s.source())
                ? "  ·  gönderilen rapor kaydı" : ""), MUTED);
        String status = ExecFormat.sectionStatusLabel(s.status());
        float w = c.width(status, c.bold, 8f) + 16;
        float[][] col = statusColors(s.status());
        c.rect(PAGE.getWidth() - MARGIN - w, top - 50, w, 16, col[0]);
        c.text(c.bold, 8f, PAGE.getWidth() - MARGIN - w + 8, top - 45, status, col[1]);
        c.y = top - 112;
        Object target = s.settings().get("availability_target");
        note("Dönem: " + nz(s.monthLabel(), s.month()) + " (Türkiye saati, ayın 1'i 00:00 – sonraki ayın 1'i 00:00). "
                + "Erişilebilirlik hedefi " + ExecFormat.pct(target instanceof Number n ? n.doubleValue() : null, 3)
                + ". Bu belge uygulamanın kendi kayıtlarından üretilir; erişilebilirlik resmî bir SLO değil, kurum hedefine "
                + "göre ölçülen değerdir.");
    }

    private void headline(ExecutiveSummary s) throws IOException {
        sectionTitle("Özet", null);
        for (SectionResult.Verdict v : s.headline()) verdictLine(v);
        c.y -= 4;
        List<SectionResult.Kpi> kpis = new ArrayList<>();
        for (ExecutiveSummary.HeadlineKpi h : s.headlineKpis()) if (h.kpi() != null) kpis.add(h.kpi());
        kpiCards(kpis);
        c.y -= 6;
    }

    // ── Bölüm ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private void section(SectionResult sec) throws IOException {
        c.ensureSpace(90);
        sectionTitle(nz(sec.title(), sec.key()), sec.status());
        if (sec.snapshot() && sec.asOf() != null) note("Rapor anı fotoğrafı: " + stamp(sec.asOf()) + " itibarıyla.");
        for (SectionResult.Verdict v : sec.verdicts()) verdictLine(v);
        if (!sec.kpis().isEmpty()) {
            c.y -= 2;
            kpiCards(sec.kpis());
        }
        for (SectionResult.Table t : sec.tables()) table(t);
        for (SectionResult.Note n : sec.notes()) note(n.text());
        c.y -= 8;
    }

    private void closing(ExecutiveSummary s) throws IOException {
        c.ensureSpace(40);
        c.line(MARGIN, c.y, MARGIN + CONTENT_W, RULE);
        c.y -= 12;
        note("Site Monitor — Aylık Yönetici Özeti · " + nz(s.monthLabel(), s.month()) + " · "
                + s.sections().size() + " bölüm. Ayrıntı ve canlı görünüm: uygulamada Raporlar → Yönetici Özeti.");
    }

    // ── İlkeller ────────────────────────────────────────────────────────────────────────────────────────────────────

    private void sectionTitle(String title, String status) throws IOException {
        c.ensureSpace(40);
        c.text(c.bold, 12.5f, MARGIN, c.y, c.clip(title, CONTENT_W - 120, c.bold, 12.5f), INK);
        if (status != null) {
            String label = ExecFormat.sectionStatusLabel(status);
            float w = c.width(label, c.bold, 7f) + 12;
            float[][] col = statusColors(status);
            c.rect(MARGIN + CONTENT_W - w, c.y - 3, w, 13, col[0]);
            c.text(c.bold, 7f, MARGIN + CONTENT_W - w + 6, c.y + 1, label, col[1]);
        }
        c.y -= 7;
        c.line(MARGIN, c.y, MARGIN + CONTENT_W, RULE);
        c.y -= 13;
    }

    private void verdictLine(SectionResult.Verdict v) throws IOException {
        float[] color = toneColor(v.tone());
        List<String> lines = c.wrap(v.text(), CONTENT_W - 14, c.bold, 9f, 3);
        for (int i = 0; i < lines.size(); i++) {
            c.ensureSpace(13);
            if (i == 0) c.rect(MARGIN, c.y + 1, 5, 5, color);
            c.text(c.bold, 9f, MARGIN + 12, c.y, lines.get(i), INK);
            c.y -= 12;
        }
        c.y -= 1;
    }

    private void kpiCards(List<SectionResult.Kpi> kpis) throws IOException {
        if (kpis.isEmpty()) return;
        int perRow = kpis.size() <= 4 ? Math.max(1, kpis.size()) : (kpis.size() <= 6 ? 3 : 4);
        float w = (CONTENT_W - KPI_GAP * (perRow - 1)) / perRow;
        for (int i = 0; i < kpis.size(); i += perRow) {
            c.ensureSpace(KPI_H + KPI_GAP);
            float top = c.y + 8;
            for (int j = 0; j < perRow && i + j < kpis.size(); j++) {
                SectionResult.Kpi k = kpis.get(i + j);
                float x = MARGIN + j * (w + KPI_GAP);
                c.rect(x, top - KPI_H, w, KPI_H, ZEBRA);
                c.rect(x, top - KPI_H, w, 0.6f, RULE);
                c.text(c.regular, 7f, x + 7, top - 11, c.clip(k.label(), w - 14, c.regular, 7f), LABEL);
                c.text(c.bold, 13f, x + 7, top - 27, c.clip(ExecFormat.value(k.value(), k.format()), w - 14, c.bold, 13f),
                        toneColor(k.tone()));
                String delta = pdfDelta(ExecFormat.delta(k.delta(), k.deltaFormat()));
                if (delta != null) {
                    c.text(c.regular, 6.5f, x + 7, top - 38, c.clip(delta, w - 14, c.regular, 6.5f), toneColor(k.deltaTone()));
                    if (k.hint() != null) c.text(c.regular, 6.5f, x + 7, top - 46, c.clip(k.hint(), w - 14, c.regular, 6.5f), MUTED);
                } else if (k.hint() != null) {
                    // Fark yoksa ipucu iki satıra sarar (kırpılmadan okunur)
                    List<String> lines = c.wrap(k.hint(), w - 14, c.regular, 6.5f, 2);
                    for (int li = 0; li < lines.size(); li++) {
                        c.text(c.regular, 6.5f, x + 7, top - 38 - li * 8, c.clip(lines.get(li), w - 14, c.regular, 6.5f), MUTED);
                    }
                }
            }
            c.y -= KPI_H + KPI_GAP;
        }
    }

    private void table(SectionResult.Table t) throws IOException {
        List<SectionResult.Column> cols = t.columns();
        if (cols.isEmpty()) return;
        c.ensureSpace(36);
        c.text(c.bold, 9f, MARGIN, c.y, c.clip(nz(t.title(), t.code()), CONTENT_W, c.bold, 9f), INK);
        c.y -= 12;
        if (t.rows().isEmpty()) {
            note(nz(t.empty(), "Kayıt yok."));
            return;
        }
        float[] widths = widths(cols);
        Runnable header = () -> {
            try { header(cols, widths); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        header.run();
        c.setPageHeaderHook(header);
        try {
            int i = 0;
            for (Map<String, Object> row : t.rows()) {
                c.ensureSpace(ROW_H);
                if (i++ % 2 == 1) c.rect(MARGIN, c.y - 3.5f, CONTENT_W, ROW_H, ZEBRA);
                float x = MARGIN;
                for (int k = 0; k < cols.size(); k++) {
                    SectionResult.Column col = cols.get(k);
                    Object v = row.get(col.code());
                    String text = ExecFormat.value(v, col.type());
                    PDFont font = k == 0 ? c.bold : c.regular;
                    float[] color = "status".equals(col.type()) ? toneColor(v == null ? null : String.valueOf(v)) : INK;
                    String clipped = c.clip(text, widths[k] - 6, font, 7.2f);
                    float tx = RIGHT.contains(col.type()) ? x + widths[k] - 3 - c.width(clipped, font, 7.2f) : x + 3;
                    c.text(font, 7.2f, tx, c.y, clipped, color);
                    x += widths[k];
                }
                c.y -= ROW_H;
            }
        } finally {
            c.setPageHeaderHook(null);
        }
        if (t.total() > t.rows().size()) note("+" + (t.total() - t.rows().size()) + " kayıt daha — tamamı uygulamada.");
        c.y -= 6;
    }

    private void header(List<SectionResult.Column> cols, float[] widths) throws IOException {
        c.rect(MARGIN, c.y - 4, CONTENT_W, ROW_H + 1, RULE);
        float x = MARGIN;
        for (int k = 0; k < cols.size(); k++) {
            String label = c.clip(cols.get(k).label(), widths[k] - 6, c.bold, 7f);
            float tx = RIGHT.contains(cols.get(k).type()) ? x + widths[k] - 3 - c.width(label, c.bold, 7f) : x + 3;
            c.text(c.bold, 7f, tx, c.y, label, LABEL);
            x += widths[k];
        }
        c.y -= ROW_H + 1;
    }

    /** PDF'te ▲/▼ glifi gömülü yazıtipinde yok (yedek "^"/"v" çirkin) → işaretle yazılır: "+0,12 puan", "−%3". */
    static String pdfDelta(String d) {
        if (d == null) return null;
        return d.replace("▲ ", "+").replace("▼ ", "−");
    }

    /** Sütun genişlikleri: ilk sütun ve metin sütunları geniş, sayılar dar (çok sütunlu tabloda ilk sütun daralır). */
    static float[] widths(List<SectionResult.Column> cols) {
        float[] weight = new float[cols.size()];
        float sum = 0;
        boolean dense = cols.size() > 6;
        for (int i = 0; i < cols.size(); i++) {
            String type = cols.get(i).type();
            weight[i] = i == 0 ? (dense ? 2.4f : 3.2f) : WIDE.contains(type) ? (dense ? 1.7f : 2.2f)
                    : "date".equals(type) ? 1.4f : dense ? 1.25f : 1.1f;
            sum += weight[i];
        }
        float[] out = new float[cols.size()];
        for (int i = 0; i < cols.size(); i++) out[i] = CONTENT_W * weight[i] / sum;
        return out;
    }

    private void note(String text) throws IOException {
        if (text == null || text.isBlank()) return;
        for (String line : c.wrap(text, CONTENT_W, c.regular, 7f, 4)) {
            c.ensureSpace(10);
            c.text(c.regular, 7f, MARGIN, c.y, line, MUTED);
            c.y -= 9;
        }
        c.y -= 3;
    }

    // ── Renk ────────────────────────────────────────────────────────────────────────────────────────────────────────

    static float[] toneColor(String tone) {
        if (tone == null) return INK;
        return switch (tone) {
            case SectionResult.T_OK -> GREEN;
            case SectionResult.T_WARN -> AMBER;
            case SectionResult.T_BAD -> RED;
            case SectionResult.T_INFO -> BLUE;
            default -> INK;
        };
    }

    static float[][] statusColors(String status) {
        if (status == null) return new float[][]{ NEUTRAL_BG, LABEL };
        return switch (status) {
            case SectionResult.OK -> new float[][]{ OK_BG, GREEN };
            case SectionResult.ATTENTION, SectionResult.ERROR -> new float[][]{ WARN_BG, AMBER };
            case SectionResult.CRITICAL -> new float[][]{ BAD_BG, RED };
            default -> new float[][]{ NEUTRAL_BG, LABEL };
        };
    }

    private static String stamp(String utcIso) {
        if (utcIso == null) return "—";
        try {
            return ExecFormat.stamp(LocalDateTime.parse(utcIso).toInstant(ZoneOffset.UTC));
        } catch (Exception e) {
            return utcIso;
        }
    }

    private static String nz(String s, String dflt) {
        return s == null || s.isBlank() ? (dflt == null ? "—" : dflt) : s;
    }

    @Override
    public void close() throws IOException {
        c.close();
    }
}
