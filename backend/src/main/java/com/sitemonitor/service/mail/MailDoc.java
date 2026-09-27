package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Btn;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Point;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bir e-postanın HTML'ini ve düz metnini BİRLİKTE biriktiren kurucu — Site Monitor'de e-posta
 * kurmanın TEK yolu (e-posta yeniden tasarımı 2026-09-26, BRAND.md §5.1).
 *
 * <p>Her blok metodu iki şey yazar: {@link MailKit} parçası (HTML) ve aynı bilginin düz metni.
 * Böylece her e-posta multipart/alternative gider ve iki parça birbirinden kopamaz — eskiden
 * yönetici/sorun-bildirimi/rapor e-postaları YALNIZ HTML gidiyordu, zengin alarm e-postalarının
 * metin parçası ise HTML'de olmayan jenerik bir özetti.
 *
 * <pre>
 *   String html = MailDoc.create("[Site Monitor] ...").kicker("Port İzleme")
 *       .badges(Badge.solid("KRİTİK", Tone.DESTRUCTIVE))
 *       .title("db.example.com:5432", "Port yanıt vermiyor.")
 *       .keyValue(rows).button(url, "Monitörü aç")
 *       .footerWhy("Takım A").footerMeta("Bildirim: 26.09.2026 10:00")
 *       .html();
 * </pre>
 */
public final class MailDoc {

    /** Tamamlanmış e-posta: HTML gövde + düz metin alternatifi. */
    public record Mail(String html, String text) { }

    private static final int GAP = 16;

    private final String title;
    private String preheader;
    private int width = MailTokens.WIDTH;
    private String kicker;
    private final StringBuilder body = new StringBuilder(8192);
    private final StringBuilder text = new StringBuilder(2048);
    private String whyHtml;
    private String whyText;
    private String metaHtml;
    private String metaText;
    private String rendered;

    private MailDoc(String title) {
        this.title = title == null ? "Site Monitor" : title;
    }

    /** {@code title} = belge başlığı (tarayıcı sekmesi / erişilebilirlik); genelde e-posta konusu. */
    public static MailDoc create(String title) {
        return new MailDoc(title);
    }

    /** Gelen kutusunda konu altında görünen tek satırlık özet. */
    public MailDoc preheader(String p) {
        this.preheader = p;
        return touch();
    }

    /** Veri-yoğun raporlar için 640px kart (varsayılan 600). */
    public MailDoc wide() {
        this.width = MailTokens.WIDTH_WIDE;
        return touch();
    }

    /** Başlık çubuğunun sağındaki alt-sistem etiketi (ör. "Sertifika İzleme"). */
    public MailDoc kicker(String k) {
        this.kicker = k;
        return touch();
    }

    /** Kart içi içerik genişliği (px) — görsel ve VML buton tavanı. */
    public int contentWidth() {
        return width - 2 * MailTokens.GUTTER;
    }

    private MailDoc touch() {
        rendered = null;
        return this;
    }

    private MailDoc html(String blockHtml, int gap) {
        if (blockHtml != null && !blockHtml.isEmpty()) body.append(MailKit.space(blockHtml, gap));
        return touch();
    }

    private void txt(String t) {
        if (t == null || t.isBlank()) return;
        if (text.length() > 0) text.append("\n\n");
        text.append(t.strip());
    }

    // ── Başlık alanı ─────────────────────────────────────────────────────────

    public MailDoc badges(Badge... list) {
        List<Badge> l = new ArrayList<>();
        for (Badge b : list) if (b != null && b.label() != null && !b.label().isBlank()) l.add(b);
        if (l.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (Badge b : l) t.append(t.length() == 0 ? "" : " ").append('[').append(b.label()).append(']');
        txt(t.toString());
        return html(MailKit.badges(l), 10);
    }

    public MailDoc title(String h1, String lead) {
        txt(h1 + (lead == null || lead.isBlank() ? "" : "\n" + lead));
        return html(MailKit.title(h1, lead == null ? null : MailKit.escBr(lead)), 20);
    }

    public MailDoc titleHtml(String h1, String leadHtml, String leadText) {
        txt(h1 + (leadText == null || leadText.isBlank() ? "" : "\n" + leadText));
        return html(MailKit.title(h1, leadHtml), 20);
    }

    // ── Metin ────────────────────────────────────────────────────────────────

    public MailDoc paragraph(String plain) {
        if (plain == null || plain.isBlank()) return this;
        txt(plain);
        return html(MailKit.paragraph(MailKit.escBr(plain)), GAP);
    }

    public MailDoc paragraphHtml(String html, String plain) {
        txt(plain);
        return html(MailKit.paragraph(html), GAP);
    }

    public MailDoc note(String plain) {
        if (plain == null || plain.isBlank()) return this;
        txt(plain);
        return html(MailKit.note(MailKit.escBr(plain)), GAP);
    }

    public MailDoc noteHtml(String html, String plain) {
        txt(plain);
        return html(MailKit.note(html), GAP);
    }

    public MailDoc heading(String h) {
        return heading(h, null);
    }

    public MailDoc heading(String h, String desc) {
        txt(h.toUpperCase(java.util.Locale.forLanguageTag("tr")) + (desc == null || desc.isBlank() ? "" : "\n" + desc));
        return html(MailKit.heading(h, desc == null ? null : MailKit.esc(desc)), 10);
    }

    // ── Bileşenler ───────────────────────────────────────────────────────────

    /** Tonlu uyarı kutusu (düz metin girdiler). */
    public MailDoc alert(Tone t, String title, String body) {
        txt(marker(t) + join(title, body));
        return html(MailKit.alert(t, title == null ? null : MailKit.esc(title), body == null ? null : MailKit.escBr(body)), GAP);
    }

    public MailDoc alertHtml(Tone t, String titleHtml, String bodyHtml, String plain) {
        txt(marker(t) + plain);
        return html(MailKit.alert(t, titleHtml, bodyHtml), GAP);
    }

    public MailDoc keyValue(List<Row> rows) {
        if (rows == null || rows.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (Row r : rows) t.append(r.label()).append(": ").append(r.text() == null || r.text().isBlank() ? "—" : r.text()).append('\n');
        txt(t.toString());
        return html(MailKit.keyValue(rows), GAP);
    }

    public MailDoc keyValue(String heading, List<Row> rows) {
        if (rows == null || rows.isEmpty()) return this;
        heading(heading);
        return keyValue(rows);
    }

    /** İç kart: başlık + açıklama + gövde (HTML ve düz metin ayrı). */
    public MailDoc card(String title, String desc, String bodyHtml, String bodyText) {
        txt((title == null ? "" : title.toUpperCase(java.util.Locale.forLanguageTag("tr")) + "\n")
                + (desc == null || desc.isBlank() ? "" : desc + "\n") + (bodyText == null ? "" : bodyText));
        return html(MailKit.card(title, desc == null ? null : MailKit.esc(desc), bodyHtml), GAP);
    }

    public MailDoc stats(List<Stat> tiles) {
        if (tiles == null || tiles.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (Stat s : tiles) {
            t.append(s.label()).append(": ").append(s.value());
            if (s.hint() != null && !s.hint().isBlank()) t.append(" (").append(s.hint()).append(')');
            t.append('\n');
        }
        txt(t.toString());
        // İstatistik kutularının kendi alt boşluğu (8px) var → blok aralığı buna göre kısa.
        return html(MailKit.stats(tiles), GAP - 8);
    }

    public MailDoc table(List<Col> cols, List<List<Cell>> rows) {
        return table(cols, rows, true);
    }

    /** Veri tablosu; {@code stack=false} → telefonda kart kopyası yerine yalnız düşük öncelikli sütunlar gizlenir. */
    public MailDoc table(List<Col> cols, List<List<Cell>> rows, boolean stack) {
        if (rows == null || rows.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (List<Cell> row : rows) {
            t.append("- ");
            for (int c = 0; c < cols.size() && c < row.size(); c++) {
                String v = row.get(c).text() == null || row.get(c).text().isBlank() ? "—" : row.get(c).text();
                if (c == 0) t.append(v);
                else t.append(" · ").append(cols.get(c).label()).append(": ").append(v);
            }
            t.append('\n');
        }
        txt(t.toString());
        return html(MailKit.dataTable(cols, rows, stack), GAP);
    }

    public MailDoc steps(List<String> plain) {
        if (plain == null || plain.isEmpty()) return this;
        List<String> h = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < plain.size(); i++) {
            h.add(MailKit.esc(plain.get(i)));
            t.append(i + 1).append(". ").append(plain.get(i)).append('\n');
        }
        txt(t.toString());
        return html(MailKit.steps(h), GAP);
    }

    public MailDoc stepsHtml(List<String> html, List<String> plain) {
        if (html == null || html.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < plain.size(); i++) t.append(i + 1).append(". ").append(plain.get(i)).append('\n');
        txt(t.toString());
        return html(MailKit.steps(html), GAP);
    }

    public MailDoc bullets(List<String> plain) {
        if (plain == null || plain.isEmpty()) return this;
        List<String> h = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (String p : plain) {
            h.add(MailKit.esc(p));
            t.append("- ").append(p).append('\n');
        }
        txt(t.toString());
        return html(MailKit.bullets(h), GAP);
    }

    public MailDoc bulletsHtml(List<String> html, List<String> plain) {
        if (html == null || html.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (String p : plain) t.append("- ").append(p).append('\n');
        txt(t.toString());
        return html(MailKit.bullets(html), GAP);
    }

    /** Kod/çıktı bloğu (başlıklı). */
    public MailDoc pre(String label, String content) {
        if (content == null || content.isBlank()) return this;
        if (label != null && !label.isBlank()) heading(label);
        txt(content);
        return html(MailKit.pre(content), GAP);
    }

    public MailDoc chips(List<String> labels) {
        if (labels == null || labels.isEmpty()) return this;
        txt(String.join(", ", labels));
        return html(MailKit.chips(labels), GAP - 6);
    }

    public MailDoc progress(int pct, String color, String caption) {
        txt(caption);
        String cap = caption == null || caption.isBlank() ? ""
                : "<p style=\"margin:6px 0 0;font-size:12px;line-height:18px;color:" + MailTokens.MUTED + "\">" + MailKit.esc(caption) + "</p>";
        return html(MailKit.progress(pct, color) + cap, GAP);
    }

    /**
     * Metrik kartı: büyük rakam + birim, isteğe bağlı ilerleme çubuğu ({@code pct} null = yok)
     * ve alt açıklama. Ör. sertifika/alan adı bitişine kalan gün.
     */
    public MailDoc metricCard(String value, String unit, String color, Integer pct, String caption) {
        txt(value + (unit == null ? "" : " " + unit) + (caption == null || caption.isBlank() ? "" : "\n" + caption));
        String inner = MailKit.metric(value, unit, color)
                + (pct == null ? "" : "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:14px 0 0\">"
                    + MailKit.progress(pct, color) + "</td></tr></table>")
                + (caption == null || caption.isBlank() ? ""
                    : "<p style=\"margin:8px 0 0;font-size:13px;line-height:20px;color:" + MailTokens.MUTED + "\">" + MailKit.esc(caption) + "</p>");
        return html(MailKit.card(null, null, inner), GAP);
    }

    public MailDoc timeline(List<Point> points, String accent) {
        if (points == null || points.isEmpty()) return this;
        StringBuilder t = new StringBuilder();
        for (Point p : points) t.append(t.length() == 0 ? "" : " · ").append(p.label()).append(": ").append(p.date());
        txt(t.toString());
        return html(MailKit.timeline(points, accent), GAP);
    }

    /** Akışkan görsel; genişlik içerik genişliğine kırpılır. */
    public MailDoc image(String src, String alt, int width) {
        txt("[Görsel: " + alt + "]");
        return html(MailKit.image(src, alt, Math.min(width, contentWidth())), 10);
    }

    public MailDoc separator() {
        return html(MailKit.separator(), GAP);
    }

    public MailDoc button(String url, String label) {
        return button(url, label, Variant.PRIMARY);
    }

    public MailDoc button(String url, String label, Variant v) {
        // İzinsiz şema (javascript:/data:/…) boş URL gibi atlanır — HTML ve düz metin birlikte (BD1).
        if (url == null || url.isBlank() || !MailKit.safeHref(url)) return this;
        txt(label + ": " + url);
        return html(MailKit.button(url, label, v, contentWidth()), GAP);
    }

    /** Buton grubu (masaüstünde yan yana, telefonda alt alta); {@code caption} küçük üst başlık. */
    public MailDoc buttons(String caption, List<Btn> btns) {
        List<Btn> ok = new ArrayList<>();
        for (Btn b : btns) if (b != null && b.url() != null && !b.url().isBlank() && MailKit.safeHref(b.url())) ok.add(b);
        if (ok.isEmpty()) return this;
        StringBuilder t = new StringBuilder(caption == null ? "" : caption + "\n");
        for (Btn b : ok) t.append(b.label()).append(": ").append(b.url()).append('\n');
        txt(t.toString());
        String cap = caption == null || caption.isBlank() ? ""
                : "<p style=\"margin:0 0 8px;font-size:12px;line-height:16px;font-weight:600;letter-spacing:0.04em;color:" + MailTokens.MUTED + "\">"
                  + MailKit.esc(caption) + "</p>";
        return html(cap + MailKit.buttonGroup(ok, contentWidth()), GAP - 8);
    }

    /** Hazır HTML parçası (ör. markdown çıktısı) + düz metin karşılığı. */
    public MailDoc raw(String blockHtml, String plain) {
        if (blockHtml == null || blockHtml.isBlank()) return this;
        txt(plain);
        return html(blockHtml, GAP);
    }

    // ── Alt bilgi ────────────────────────────────────────────────────────────

    /** "Neden bu e-postayı aldınız?" — takım adı yoksa jenerik ifade. */
    public MailDoc footerWhy(String team) {
        boolean has = team != null && !team.isBlank();
        this.whyHtml = "Bu bildirim " + (has ? "<strong style=\"color:" + MailTokens.FG + "\">" + MailKit.esc(team) + "</strong> ekibine" : "ilgili izleme grubuna")
                + " tanımlı bir izleme için gönderildi. Site Monitor otomatik bir izleme sistemidir; bildirim tercihleri için sistem yöneticinize başvurun.";
        this.whyText = "Neden bu e-postayı aldınız? Bu bildirim " + (has ? team + " ekibine" : "ilgili izleme grubuna")
                + " tanımlı bir izleme için gönderildi. Site Monitor otomatik bir izleme sistemidir; bildirim tercihleri için sistem yöneticinize başvurun.";
        return touch();
    }

    /** Alt bilgi meta satırı (düz metin; birden çok parça " · " ile birleşir). */
    public MailDoc footerMeta(String... parts) {
        List<String> p = new ArrayList<>();
        for (String s : parts) if (s != null && !s.isBlank()) p.add(s);
        this.metaText = String.join(" · ", p);
        this.metaHtml = MailKit.esc(metaText);
        return touch();
    }

    // ── Çıktı ────────────────────────────────────────────────────────────────

    public String html() {
        if (rendered == null) {
            StringBuilder sb = new StringBuilder(body.length() + 6000);
            sb.append(MailKit.open(title, preheader, width))
              .append(MailKit.header(kicker))
              .append(MailKit.bodyOpen()).append(body).append(MailKit.bodyClose())
              .append(MailKit.footer(whyHtml, metaHtml))
              .append(MailKit.close());
            rendered = sb.toString();
            MailKit.rememberText(rendered, text());
        }
        return rendered;
    }

    public String text() {
        StringBuilder t = new StringBuilder(text);
        String foot = join(whyText, metaText);
        if (!foot.isBlank()) t.append("\n\n--\n").append(foot);
        return t.toString().strip() + "\n";
    }

    public Mail build() {
        return new Mail(html(), text());
    }

    private static String marker(Tone t) {
        return switch (t) {
            case DESTRUCTIVE, WARNING -> "(!) ";
            case SUCCESS -> "(✓) ";
            default -> "(i) ";
        };
    }

    private static String join(String a, String b) {
        boolean ha = a != null && !a.isBlank(), hb = b != null && !b.isBlank();
        if (ha && hb) return a + "\n" + b;
        return ha ? a : (hb ? b : "");
    }

    /** Birden çok parçadan Row listesi — null değerli satırlar atlanır. */
    public static List<Row> rows(Row... rows) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r != null) out.add(r);
        return out;
    }

    /** {@code Arrays.asList} kısaltması (null elemanlar düşer). */
    @SafeVarargs
    public static <T> List<T> list(T... items) {
        List<T> out = new ArrayList<>(Arrays.asList(items));
        out.removeIf(java.util.Objects::isNull);
        return out;
    }
}
