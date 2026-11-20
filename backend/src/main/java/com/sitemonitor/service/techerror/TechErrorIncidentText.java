package com.sitemonitor.service.techerror;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Olay kaydının markdown metnini teknik hata raporunun zengin metin alt kümesine çevirir (2026-11-20, olay → anomali konusu
 * içe alma). Hedef biçim {@link TechErrorRichText}: {@code - } / {@code 1. } maddeler (her 2 boşluk bir alt düzey, en çok 3
 * düzey), satır içi {@code **kalın**}, boş satır = paragraf aralığı.
 *
 * <ul>
 *   <li>başlık → kalın satır; kalın → {@code **x**}; italik düşer (metni kalır); satır içi kod → düz metin;</li>
 *   <li>madde / numaralı liste → {@code - } / {@code 1. }, iç içe en çok 3 düzey (daha derini 3. düzeye düzleşir); görev
 *       maddesi {@code - ☑} / {@code - ☐};</li>
 *   <li>bağlantı → {@code metin (adres)} (metin adresle aynıysa yalnız adres);</li>
 *   <li>görsel → kaldırılır; olay düzenleyicisinin görselin hemen üstüne yazdığı kalın ad satırı da düşer;</li>
 *   <li>tablo → her satır {@code a | b | c}; kod bloğu → düz satırlar; ham HTML → etiketleri atılmış metin;</li>
 *   <li>metindeki 2+ yıldız dizisi tek yıldıza iner (raporda kalın işareti sanılmasın).</li>
 * </ul>
 *
 * <p><b>Sınırlar.</b> Girdi ayrıştırmadan ÖNCE {@link #MAX_INPUT_CHARS} karakterde kesilir; ayrıştırıcının blok / satır içi
 * iç içeliği {@link #MAX_NESTING} ile sınırlı (CommonMark 0.32 — derin iç içe girdi ağacı derinleştirmez); dolaşım özyineli
 * ama derinlik denetimli ({@link #MAX_DEPTH}'ten derin düğüm düz metne iner, yine iteratif toplanır). Düzenli ifade yok.
 */
public final class TechErrorIncidentText {

    private TechErrorIncidentText() {}

    /** Bir parçanın ayrıştırmaya girebilecek en çok karakteri (64 KB). */
    public static final int MAX_INPUT_CHARS = 64 * 1024;
    static final int MAX_NESTING = 32;
    static final int MAX_DEPTH = 64;
    static final int MAX_LIST_LEVEL = TechErrorRichText.MAX_LEVEL;   // 0, 1, 2 → 3 düzey

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), TaskListItemsExtension.create()))
            .maxOpenBlockParsers(MAX_NESTING)
            .maxInlineNesting(MAX_NESTING)
            .build();

    /** Dönüşüm sonucu: metin + girdi {@link #MAX_INPUT_CHARS}'ta kesildi mi. */
    public record Converted(String text, boolean inputClipped) { }

    /** Markdown → zengin metin; boş / null girdi boş metin. */
    public static Converted convert(String markdown) {
        if (markdown == null || TechErrorRichText.blank(markdown)) return new Converted("", false);
        boolean clipped = false;
        String in = markdown;
        if (in.length() > MAX_INPUT_CHARS) {
            int cut = MAX_INPUT_CHARS;
            if (Character.isHighSurrogate(in.charAt(cut - 1))) cut--;
            in = in.substring(0, cut);
            clipped = true;
        }
        Node doc = PARSER.parse(in);
        Out out = new Out();
        blocks(doc, out, 0, 0);
        return new Converted(out.finish(), clipped);
    }

    // ── Çıktı ────────────────────────────────────────────────────────────────────────────────────────

    /** Satır biriktirici: bloklar arasında tek boş satır, baştaki / sondaki boşluk yok. */
    private static final class Out {
        final List<String> lines = new ArrayList<>();
        boolean gapPending;

        void gap() {
            if (!lines.isEmpty()) gapPending = true;
        }

        void line(String s) {
            String t = rstrip(s);
            if (TechErrorRichText.blank(t)) return;
            if (gapPending) {
                lines.add("");
                gapPending = false;
            }
            lines.add(t);
        }

        String finish() {
            return String.join("\n", lines);
        }
    }

    private static String rstrip(String s) {
        int b = s.length();
        while (b > 0 && TechErrorRichText.ws(s.charAt(b - 1))) b--;
        return s.substring(0, b);
    }

    // ── Bloklar ──────────────────────────────────────────────────────────────────────────────────────

    /** {@code parent}'ın blok çocuklarını yazar; {@code level} liste düzeyi (girinti), {@code depth} özyineleme derinliği. */
    private static void blocks(Node parent, Out out, int level, int depth) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (depth > MAX_DEPTH) {
                out.line(indent(level) + para(collapse(plainText(n))));
                continue;
            }
            // Olay düzenleyicisi görseli "**ad**" + boş satır + "![ad](…)" diye yazar: görselden hemen önceki kalın ad satırı
            // görselle gider
            if (n instanceof Paragraph p && captionOfNextImage(p)) continue;
            block(n, out, level, depth);
        }
    }

    private static void block(Node n, Out out, int level, int depth) {
        if (n instanceof Heading) {
            String text = collapse(inlinePlain(n, depth + 1));
            if (!text.isEmpty()) {
                out.gap();
                out.line(indent(level) + "**" + text + "**");
                out.gap();
            }
        } else if (n instanceof Paragraph) {
            if (imageOnly(n)) return;
            out.gap();
            for (String l : splitLines(inline(n, depth + 1))) out.line(indent(level) + para(l));
            out.gap();
        } else if (n instanceof BulletList || n instanceof OrderedList) {
            out.gap();
            list((ListBlock) n, out, level, depth + 1);
            out.gap();
        } else if (n instanceof FencedCodeBlock f) {
            out.gap();
            for (String l : splitLines(neutral(f.getLiteral()))) out.line(indent(level) + para(l));
            out.gap();
        } else if (n instanceof IndentedCodeBlock c) {
            out.gap();
            for (String l : splitLines(neutral(c.getLiteral()))) out.line(indent(level) + para(l));
            out.gap();
        } else if (n instanceof HtmlBlock h) {
            out.gap();
            for (String l : splitLines(htmlText(h.getLiteral()))) out.line(indent(level) + para(l));
            out.gap();
        } else if (n instanceof BlockQuote) {
            blocks(n, out, level, depth + 1);
        } else if (n instanceof TableBlock) {
            out.gap();
            table(n, out, level, depth + 1);
            out.gap();
        } else if (n instanceof ThematicBreak || n instanceof LinkReferenceDefinition) {
            out.gap();
        } else if (n instanceof CustomBlock) {
            blocks(n, out, level, depth + 1);
        }
    }

    private static void list(ListBlock list, Out out, int level, int depth) {
        int lvl = Math.min(level, MAX_LIST_LEVEL);
        int number = 1;
        if (list instanceof OrderedList ol) {
            Integer start = ol.getMarkerStartNumber();
            number = start == null || start < 0 ? 1 : Math.min(start, 999);
        }
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            String marker = list instanceof OrderedList ? Math.min(number++, 999) + ". " : "- ";
            listItem((ListItem) item, marker, out, lvl, depth + 1);
        }
    }

    private static void listItem(ListItem item, String marker, Out out, int level, int depth) {
        String task = "";
        boolean head = false;
        for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof TaskListItemMarker m) {
                task = m.isChecked() ? "☑ " : "☐ ";
                continue;
            }
            if (!head && c instanceof Paragraph p && !imageOnly(p)) {
                // Görev işareti paragrafın ilk çocuğu da olabilir
                if (p.getFirstChild() instanceof TaskListItemMarker m) task = m.isChecked() ? "☑ " : "☐ ";
                List<String> lines = splitLines(inline(p, depth + 1));
                String first = lines.isEmpty() ? "" : lines.get(0).strip();
                if (!first.isEmpty() || !task.isEmpty()) out.line(indent(level) + marker + task + first);
                for (int k = 1; k < lines.size(); k++) out.line(indent(level + 1) + para(lines.get(k)));
                head = true;
                continue;
            }
            head = true;
            if (c instanceof ListBlock sub) {
                list(sub, out, Math.min(level + 1, MAX_LIST_LEVEL), depth + 1);
            } else if (c instanceof Paragraph p) {
                if (imageOnly(p)) continue;
                for (String l : splitLines(inline(p, depth + 1))) out.line(indent(level + 1) + para(l));
            } else {
                Out inner = new Out();
                block(c, inner, 0, depth + 1);
                for (String l : inner.lines) if (!l.isEmpty()) out.line(indent(level + 1) + l.strip());
            }
        }
        if (!head && !task.isEmpty()) out.line(indent(level) + marker + task.strip());
    }

    private static void table(Node table, Out out, int level, int depth) {
        Deque stack = new Deque();
        stack.push(table);
        int rows = 0;
        while (!stack.isEmpty() && rows < 2_000) {
            Node n = stack.pop();
            if (n instanceof TableRow) {
                List<String> cells = new ArrayList<>();
                for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof TableCell) cells.add(collapse(inline(c, depth + 1)));
                }
                boolean any = false;
                for (String c : cells) if (!c.isEmpty()) any = true;
                if (any) out.line(indent(level) + para(String.join(" | ", cells)));
                rows++;
                continue;
            }
            // Çocukları ters sırayla yığına koy → satırlar belge sırasıyla çıkar
            List<Node> kids = new ArrayList<>();
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) kids.add(c);
            for (int k = kids.size() - 1; k >= 0; k--) stack.push(kids.get(k));
        }
    }

    /** Basit dizi yığını (java.util.ArrayDeque'in null kısıtı olmadan, sınırlı kullanım). */
    private static final class Deque {
        private final ArrayList<Node> items = new ArrayList<>();
        void push(Node n) { items.add(n); }
        Node pop() { return items.remove(items.size() - 1); }
        boolean isEmpty() { return items.isEmpty(); }
    }

    // ── Satır içi ────────────────────────────────────────────────────────────────────────────────────

    /** Satır içi içerik (kalın işaretli); yumuşak satır sonu boşluk, sert satır sonu {@code \n}. */
    private static String inline(Node parent, int depth) {
        StringBuilder b = new StringBuilder();
        inlineInto(parent, b, depth, false);
        return b.toString();
    }

    /** Satır içi içerik, kalın işareti OLMADAN (başlık / kalın içi). */
    private static String inlinePlain(Node parent, int depth) {
        StringBuilder b = new StringBuilder();
        inlineInto(parent, b, depth, true);
        return b.toString();
    }

    private static void inlineInto(Node parent, StringBuilder b, int depth, boolean plain) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (b.length() > MAX_INPUT_CHARS * 2) return;
            if (depth > MAX_DEPTH) {
                b.append(plainText(n));
                continue;
            }
            if (n instanceof Text t) {
                b.append(neutral(t.getLiteral()));
            } else if (n instanceof Code c) {
                b.append(neutral(c.getLiteral()));
            } else if (n instanceof SoftLineBreak) {
                b.append(' ');
            } else if (n instanceof HardLineBreak) {
                b.append(plain ? " " : "\n");
            } else if (n instanceof StrongEmphasis) {
                String inner = collapse(inlinePlain(n, depth + 1));
                if (inner.isEmpty()) continue;
                if (plain) b.append(inner);
                else b.append("**").append(inner).append("**");
            } else if (n instanceof Emphasis) {
                inlineInto(n, b, depth + 1, plain);
            } else if (n instanceof Link l) {
                String text = collapse(inlinePlain(l, depth + 1));
                String url = l.getDestination() == null ? "" : TechErrorTemplate.line(l.getDestination());
                if (url.isEmpty()) b.append(text);
                else if (text.isEmpty() || text.equals(url)) b.append(url);
                else b.append(text).append(" (").append(url).append(')');
            } else if (n instanceof Image) {
                // görsel kaldırılır (raporda konu görseli olarak ayrıca kopyalanabilir)
            } else if (n instanceof HtmlInline h) {
                String lit = h.getLiteral() == null ? "" : h.getLiteral().toLowerCase(java.util.Locale.ROOT);
                if (lit.startsWith("<br")) b.append(plain ? " " : "\n");
            } else if (n instanceof TaskListItemMarker) {
                // listItem yazar
            } else {
                inlineInto(n, b, depth + 1, plain);
            }
        }
    }

    /** Alt ağacın düz metni — İTERATİF (derinlik sınırını aşan düğüm için). */
    private static String plainText(Node root) {
        StringBuilder b = new StringBuilder();
        Deque stack = new Deque();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited++ < 200_000 && b.length() <= MAX_INPUT_CHARS * 2) {
            Node n = stack.pop();
            if (n instanceof Text t) b.append(neutral(t.getLiteral()));
            else if (n instanceof Code c) b.append(neutral(c.getLiteral()));
            else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) b.append(' ');
            List<Node> kids = new ArrayList<>();
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) kids.add(c);
            for (int k = kids.size() - 1; k >= 0; k--) stack.push(kids.get(k));
        }
        return b.toString();
    }

    // ── Görsel + ad satırı ───────────────────────────────────────────────────────────────────────────

    /** Paragraf YALNIZ görsel(ler)den (ve boşluk / satır sonundan) mı oluşuyor — önünde görselin kalın ad satırı olabilir. */
    private static boolean imageOnly(Node n) {
        if (!(n instanceof Paragraph)) return false;
        boolean image = false;
        Node caption = null;
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Image) image = true;
            else if (c instanceof SoftLineBreak || c instanceof HardLineBreak) continue;
            else if (c instanceof Text t && TechErrorRichText.blank(t.getLiteral())) continue;
            else if (c instanceof StrongEmphasis && caption == null && !image) caption = c;
            else return false;
        }
        if (!image) return false;
        if (caption == null) return true;
        // Aynı paragrafta "**ad**" + görsel: yalnız ad görselin alt metniyle aynıysa ad satırı da görselle gider
        Image img = firstImage(n);
        return img != null && collapse(plainText(caption)).equals(collapse(plainText(img)));
    }

    /** Paragraf tek bir kalın metinden oluşuyor VE hemen sonraki paragraf aynı alt metinli bir görsel mi (görselin ad satırı). */
    private static boolean captionOfNextImage(Paragraph p) {
        Node only = null;
        for (Node c = p.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t && TechErrorRichText.blank(t.getLiteral())) continue;
            if (only != null) return false;
            only = c;
        }
        if (!(only instanceof StrongEmphasis)) return false;
        String text = collapse(plainText(only));
        if (text.isEmpty() || !imageOnly(p.getNext())) return false;
        Image img = firstImage(p.getNext());
        return img != null && text.equals(collapse(plainText(img)));
    }

    private static Image firstImage(Node paragraph) {
        for (Node c = paragraph.getFirstChild(); c != null; c = c.getNext()) if (c instanceof Image i) return i;
        return null;
    }

    // ── Olay görseli başvuruları ─────────────────────────────────────────────────────────────────────

    /** Olay görseli başvurusu: görselin kimliği + alt metni (belge sırasıyla). */
    public record ImageRef(long id, String alt) { }

    static final String IMAGE_PATH = "/api/incidents/images/";
    static final int MAX_IMAGE_REFS = 50;

    /**
     * Belgedeki olay görseli başvuruları ({@code ![alt](/api/incidents/images/<id>)}, mutlak adres de olur) belge sırasıyla,
     * tekrarsız, en çok {@link #MAX_IMAGE_REFS}. İTERATİF dolaşım; ayrıştırma {@link #convert} ile aynı sınırlarla.
     */
    public static List<ImageRef> imageRefs(String markdown) {
        List<ImageRef> out = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return out;
        String in = markdown.length() > MAX_INPUT_CHARS ? markdown.substring(0, MAX_INPUT_CHARS) : markdown;
        if (!in.contains(IMAGE_PATH)) return out;
        Node doc = PARSER.parse(in);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Deque stack = new Deque();
        stack.push(doc);
        int visited = 0;
        while (!stack.isEmpty() && visited++ < 200_000 && out.size() < MAX_IMAGE_REFS) {
            Node n = stack.pop();
            if (n instanceof Image img) {
                Long id = imageId(img.getDestination());
                if (id != null && seen.add(id)) out.add(new ImageRef(id, collapse(plainText(img))));
                continue;
            }
            List<Node> kids = new ArrayList<>();
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) kids.add(c);
            for (int k = kids.size() - 1; k >= 0; k--) stack.push(kids.get(k));
        }
        return out;
    }

    /** {@code …/api/incidents/images/<rakamlar>[?#…]} → kimlik; değilse null (doğrusal). */
    static Long imageId(String dest) {
        if (dest == null) return null;
        int at = dest.indexOf(IMAGE_PATH);
        if (at < 0) return null;
        int from = at + IMAGE_PATH.length(), k = from;
        while (k < dest.length() && k - from < 18 && dest.charAt(k) >= '0' && dest.charAt(k) <= '9') k++;
        if (k == from) return null;
        if (k < dest.length() && dest.charAt(k) != '?' && dest.charAt(k) != '#') return null;
        try {
            return Long.parseLong(dest.substring(from, k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Metin yardımcıları ───────────────────────────────────────────────────────────────────────────

    /**
     * Madde OLMAYAN satır: uçlardan kırpılır; maddeye benziyorsa ("- x", "1. x" — markdown'da kaçışlı "1\\. x" ya da kod
     * satırı) raporda madde sanılmasın diye işaretten sonraki boşluk bölünmez boşluk olur ({@link TechErrorSummary#keepParagraph}).
     */
    private static String para(String l) {
        return TechErrorSummary.keepParagraph(l.strip());
    }

    private static String indent(int level) {
        return "  ".repeat(Math.max(0, Math.min(level, MAX_LIST_LEVEL)));
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int from = 0;
        for (int k = 0; k <= s.length(); k++) {
            if (k == s.length() || s.charAt(k) == '\n' || s.charAt(k) == '\r') {
                out.add(s.substring(from, k));
                if (k < s.length() && s.charAt(k) == '\r' && k + 1 < s.length() && s.charAt(k + 1) == '\n') k++;
                from = k + 1;
            }
        }
        return out;
    }

    /** Boşlukları tek boşluğa indirir, satır sonlarını boşluk yapar, iki uçtan kırpar (doğrusal). */
    static String collapse(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean space = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (TechErrorRichText.ws(c) || Character.isISOControl(c)) {
                space = true;
                continue;
            }
            if (space && !b.isEmpty()) b.append(' ');
            space = false;
            b.append(c);
        }
        return b.toString();
    }

    /** 2+ yıldız dizisi tek yıldıza iner; denetim karakterleri (satır sonu hariç) boşluk olur (doğrusal). */
    static String neutral(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '*' && k + 1 < s.length() && s.charAt(k + 1) == '*') {
                b.append('*');
                while (k + 1 < s.length() && s.charAt(k + 1) == '*') k++;
                continue;
            }
            if (c != '\n' && c != '\r' && Character.isISOControl(c)) {
                b.append(' ');
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Ham HTML → metin: etiketler atılır (blok etiketleri satır sonu olur), temel varlıklar çözülür (doğrusal). */
    static String htmlText(String html) {
        if (html == null || html.isEmpty()) return "";
        StringBuilder b = new StringBuilder(html.length());
        int n = html.length();
        for (int k = 0; k < n; k++) {
            char c = html.charAt(k);
            if (c == '<') {
                int end = html.indexOf('>', k + 1);
                if (end < 0) {
                    b.append(html, k, n);
                    break;
                }
                String tag = html.substring(k + 1, Math.min(end, k + 12)).toLowerCase(java.util.Locale.ROOT);
                String name = tag.startsWith("/") ? tag.substring(1) : tag;
                if (name.startsWith("br") || name.startsWith("p") || name.startsWith("div") || name.startsWith("li")
                        || name.startsWith("tr") || name.startsWith("h1") || name.startsWith("h2") || name.startsWith("h3")) {
                    b.append('\n');
                }
                k = end;
                continue;
            }
            if (c == '&') {
                // Varlık adı en çok 7 karakter: ';' yalnız o pencerede aranır (uzun '&' dizisinde ikinci dereceden tarama yok)
                int semi = -1;
                for (int j = k + 1; j < n && j <= k + 8; j++) {
                    if (html.charAt(j) == ';') {
                        semi = j;
                        break;
                    }
                }
                if (semi > k) {
                    String ent = html.substring(k + 1, semi);
                    String rep = switch (ent) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "#39", "apos" -> "'";
                        case "nbsp" -> " ";
                        default -> null;
                    };
                    if (rep != null) {
                        b.append(rep);
                        k = semi;
                        continue;
                    }
                }
            }
            b.append(c);
        }
        return neutral(b.toString());
    }
}
