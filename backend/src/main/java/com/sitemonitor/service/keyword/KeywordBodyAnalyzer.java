package com.sitemonitor.service.keyword;

import com.sitemonitor.config.RequestLoggingFilter;
import com.sitemonitor.service.SecretMask;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword kontrolünün "NEDEN BULUNAMADI" çözümleyicisi — saf, durumsuz, ek istek ATMAZ (2026-10-04).
 *
 * <p>Gövde zaten bellekte (kontrol onu okudu); burada yalnız onun üzerinde hesap yapılır: görünür metin (etiket / betik /
 * stil ayıklanmış, HTML varlıkları çözülmüş, boşluklar indirgenmiş), alternatif aramalar (harf duyarsız, boşluk/varlık
 * normalleştirilmiş, bildirilen karakter kümesiyle çözülmüş), sayfa türü ipuçları (WAF/engelleme, giriş sayfası,
 * JavaScript ile çizilen sayfa, başka siteye yönlendirme, bakım sayfası, yumuşak hata sayfası, metin olmayan içerik)
 * ve kayda yazılacak ≤ 600 karakterlik maskeli alıntı.
 *
 * <p><b>Sınırlı iş:</b> düz {@code indexOf} / tek geçişli tarama — düzenli ifade yalnız ≤ 4 KB'lık meta koklamasında ve
 * iç içe niceleyicisiz. Görünür metin en çok {@value #SCAN_CAP} karakter taranır; işaret aramaları ilk
 * {@value #MARKER_CAP} karakterde. Yalnız BAŞARISIZ kontrolde çağrılır (başarılı sweep'e maliyet yok).
 *
 * <p>İpucu kodları arayüzde {@code kwhint.<KOD>.title|cause|effect|fix} anahtarlarına çevrilir — kod eklemek = TR + EN
 * metnini aynı değişiklikte eklemek ({@code KeywordDiagFindingsI18nGateTest}).
 */
public final class KeywordBodyAnalyzer {

    private KeywordBodyAnalyzer() {}

    public static final String CASE_MISMATCH = "CASE_MISMATCH";
    public static final String WHITESPACE_OR_ENTITY = "WHITESPACE_OR_ENTITY";
    public static final String CHARSET = "CHARSET";
    public static final String WAF_OR_BLOCK_PAGE = "WAF_OR_BLOCK_PAGE";
    public static final String LOGIN_PAGE = "LOGIN_PAGE";
    public static final String JS_RENDERED = "JS_RENDERED";
    public static final String REDIRECTED_ELSEWHERE = "REDIRECTED_ELSEWHERE";
    public static final String MAINTENANCE_PAGE = "MAINTENANCE_PAGE";
    public static final String ERROR_PAGE = "ERROR_PAGE";
    public static final String NON_TEXT_CONTENT = "NON_TEXT_CONTENT";

    /** TAM ipucu listesi — i18n kapısı bunu okur. */
    public static final List<String> HINT_CODES = List.of(
            CASE_MISMATCH, WHITESPACE_OR_ENTITY, CHARSET, WAF_OR_BLOCK_PAGE, LOGIN_PAGE, JS_RENDERED,
            REDIRECTED_ELSEWHERE, MAINTENANCE_PAGE, ERROR_PAGE, NON_TEXT_CONTENT);

    /** Görünür metin taraması tavanı (karakter). */
    public static final int SCAN_CAP = 512 * 1024;
    /** Sayfa türü işaretlerinin arandığı baş kısım (karakter). */
    public static final int MARKER_CAP = 64 * 1024;
    /** Kayda yazılan alıntı tavanı (karakter). */
    public static final int EXCERPT_MAX = 600;
    /** "JavaScript ile çiziliyor" eşiği: görünür metin bundan kısaysa ve SPA kökü varsa. */
    static final int JS_TEXT_MAX = 200;

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Girdi / çıktı
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Çözümleme girdisi.
     *
     * @param body          okunan gövde (tavanla sınırlı)
     * @param contentType   Content-Type başlığı (null olabilir)
     * @param keyword       aranan metin
     * @param caseSensitive izlemenin harf kuralı
     * @param count         kontrolün bulduğu adet (UTF-8, izlemenin harf kuralı)
     * @param operator      izlemenin operatörü (GTE|LTE|EQ|GT|LT)
     * @param threshold     izlemenin eşiği
     * @param status        son yanıtın durum kodu
     * @param originalUrl   izlemenin URL'si (yönlendirme karşılaştırması)
     * @param finalUri      yönlendirmeler sonrası son URI (null olabilir)
     * @param redirectCount izlenen yönlendirme sayısı
     * @param secrets       alıntıdan süzülecek sır değerleri (izlemenin özel başlık değerleri)
     */
    public record Input(byte[] body, String contentType, String keyword, boolean caseSensitive, int count,
                        String operator, int threshold, int status, String originalUrl, URI finalUri,
                        int redirectCount, Collection<String> secrets) {}

    /** Çözümleme çıktısı: ipuçları + maskeli alıntı + (tanılama için) alternatif sayımlar. */
    public record Analysis(List<String> hints, String excerpt, Map<String, Object> alternatives, String declaredCharset) {}

    /** Kontrolün başarısız sonucu için ipuçları + alıntı. Asla fırlatmaz (bozuk girdi → boş çıktı). */
    public static Analysis analyze(Input in) {
        try {
            return analyzeUnsafe(in);
        } catch (RuntimeException e) {
            return new Analysis(List.of(), null, Map.of(), null);
        }
    }

    private static Analysis analyzeUnsafe(Input in) {
        byte[] bytes = in.body() == null ? new byte[0] : in.body();
        String body = new String(bytes, StandardCharsets.UTF_8);
        String kw = in.keyword() == null ? "" : in.keyword();
        boolean cs = in.caseSensitive();
        String declared = declaredCharset(in.contentType(), bytes);
        boolean needMore = needsMore(in.count(), in.operator(), in.threshold());
        List<String> hints = new ArrayList<>();
        Map<String, Object> alt = alternatives(body, bytes, kw, cs, declared);

        if (needMore && !kw.isEmpty()) {
            int raw = in.count();
            if (cs && intOf(alt.get("case_insensitive")) > raw) hints.add(CASE_MISMATCH);
            if (intOf(alt.get("normalized")) > raw) hints.add(WHITESPACE_OR_ENTITY);
            if (intOf(alt.get("charset")) > raw) hints.add(CHARSET);
        }
        if (needMore || in.status() >= 400) {
            String head = body.length() > MARKER_CAP ? body.substring(0, MARKER_CAP) : body;
            String lower = head.toLowerCase(Locale.ROOT);
            String title = title(head);
            String titleLower = title == null ? "" : title.toLowerCase(Locale.ROOT);
            String visible = visibleText(body, SCAN_CAP, 4000);
            if (!isTextual(in.contentType(), bytes)) hints.add(NON_TEXT_CONTENT);
            if (isWafPage(lower, titleLower)) hints.add(WAF_OR_BLOCK_PAGE);
            if (isLoginPage(lower, in.finalUri(), in.redirectCount())) hints.add(LOGIN_PAGE);
            if (isJsRendered(lower, visible, title)) hints.add(JS_RENDERED);
            if (redirectedElsewhere(in.originalUrl(), in.finalUri(), in.redirectCount())) hints.add(REDIRECTED_ELSEWHERE);
            if (isMaintenancePage(titleLower, visible.toLowerCase(Locale.ROOT), in.status())) hints.add(MAINTENANCE_PAGE);
            if (in.status() < 400 && isErrorTitle(titleLower)) hints.add(ERROR_PAGE);
        }
        String excerpt = excerpt(body, kw, cs, !needMore && in.count() > 0, in.secrets());
        return new Analysis(List.copyOf(hints), excerpt, alt, declared);
    }

    /** Kural "daha fazla eşleşme" istiyor mu (kelime eksik) — GTE/GT/EQ'da adet eşikten az. */
    public static boolean needsMore(int count, String op, int threshold) {
        String o = op == null ? "GTE" : op;
        return switch (o) {
            case "GT" -> count <= threshold;
            case "EQ" -> count < threshold;
            case "LTE", "LT" -> false;
            default -> count < threshold;   // GTE
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Alternatif aramalar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Aynı gövdede farklı okumalarla adet: {@code raw} (UTF-8, izlemenin kuralı — kontrolün kendisi), {@code
     * case_insensitive}, {@code normalized} (HTML varlıkları çözülmüş + boşluklar indirgenmiş, iki tarafta da),
     * {@code charset} (bildirilen/tahmini kümeyle çözülmüş; {@code charset_name}), {@code visible_text} (yalnız görünür
     * metinde). Hepsi düz {@code indexOf}.
     */
    public static Map<String, Object> alternatives(String body, byte[] bytes, String kw, boolean cs, String declared) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (kw == null || kw.isEmpty()) return m;
        m.put("raw", count(body, kw, cs));
        m.put("case_insensitive", count(body, kw, false));
        String nb = collapseWs(decodeEntities(body));
        String nk = collapseWs(decodeEntities(kw));
        m.put("normalized", nk.isEmpty() ? 0 : count(nb, nk, cs));
        String altName = null;
        int altCount = 0;
        Charset alt = alternateCharset(declared, body);
        if (alt != null && bytes != null) {
            altName = alt.name();
            altCount = count(new String(bytes, alt), kw, cs);
        }
        m.put("charset", altCount);
        m.put("charset_name", altName);
        String visible = visibleText(body, SCAN_CAP, Integer.MAX_VALUE);
        m.put("visible_text", nk.isEmpty() ? 0 : count(visible, nk, cs));
        return m;
    }

    /** UTF-8 dışındaki okunma: bildirilen küme UTF-8 değilse o; yoksa gövde geçersiz UTF-8 ise windows-1254 (Türkçe). */
    static Charset alternateCharset(String declared, String utf8Body) {
        if (declared != null) {
            try {
                Charset c = Charset.forName(declared);
                return c.equals(StandardCharsets.UTF_8) ? null : c;
            } catch (Exception ignore) { return null; }
        }
        if (utf8Body != null && utf8Body.indexOf('\uFFFD') >= 0) {
            try { return Charset.forName("windows-1254"); } catch (Exception ignore) { return StandardCharsets.ISO_8859_1; }
        }
        return null;
    }

    /** Örtüşmesiz adet — kontrolün kendi sayımıyla AYNI kural (duyarsızda iki taraf {@code toLowerCase(ROOT)}). */
    public static int count(String hay, String needle, boolean caseSensitive) {
        if (hay == null || needle == null || needle.isEmpty()) return 0;
        String h = caseSensitive ? hay : hay.toLowerCase(Locale.ROOT);
        String n = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
        int c = 0;
        int from = 0;
        int idx;
        while ((idx = h.indexOf(n, from)) >= 0) {
            c++;
            from = idx + n.length();
        }
        return c;
    }

    /**
     * Eşleşme bağlamları (tanılama): görünür metinde en çok {@code max} eşleşmenin çevresi
     * {@code {before, match, after}} — her biri maskeli. Görünür metinde yoksa ham gövdede aranır ({@code source}).
     */
    public static List<Map<String, Object>> contexts(String body, String kw, boolean cs, int max, int radius,
                                                     Collection<String> secrets) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (body == null || kw == null || kw.isEmpty() || max <= 0) return out;
        String visible = visibleText(body, SCAN_CAP, Integer.MAX_VALUE);
        String nk = collapseWs(decodeEntities(kw));
        String source = "visible";
        String text = visible;
        String needle = nk;
        if (count(visible, nk, cs) == 0) {
            source = "raw";
            text = body.length() > SCAN_CAP ? body.substring(0, SCAN_CAP) : body;
            needle = kw;
        }
        if (needle.isEmpty()) return out;
        String hay = cs ? text : text.toLowerCase(Locale.ROOT);
        String n = cs ? needle : needle.toLowerCase(Locale.ROOT);
        // toLowerCase(ROOT) uzunluğu değiştirebilir (ör. U+0130) — konumlar ancak uzunluk aynıysa ham metne uyar.
        if (hay.length() != text.length()) { hay = text; n = needle; }
        int from = 0;
        int idx;
        while (out.size() < max && (idx = hay.indexOf(n, from)) >= 0) {
            int s = Math.max(0, idx - radius);
            int e = Math.min(text.length(), idx + n.length() + radius);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("before", mask((s > 0 ? "…" : "") + collapseWs(text.substring(s, idx)), secrets));
            c.put("match", text.substring(idx, idx + n.length()));
            c.put("after", mask(collapseWs(text.substring(idx + n.length(), e)) + (e < text.length() ? "…" : ""), secrets));
            c.put("source", source);
            out.add(c);
            from = idx + n.length();
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Alıntı
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Kayda yazılan alıntı (≤ {@value #EXCERPT_MAX}): görünür metin; {@code aroundMatch} ise (yasak kelime bulundu / adet
     * fazla) ilk eşleşmenin çevresi. Sırlar maskeli (JSON/form gizli alanları + izlemenin başlık değerleri).
     */
    public static String excerpt(String body, String kw, boolean cs, boolean aroundMatch, Collection<String> secrets) {
        if (body == null || body.isEmpty()) return null;
        String visible = visibleText(body, SCAN_CAP, aroundMatch ? Integer.MAX_VALUE : EXCERPT_MAX * 2);
        if (visible.isBlank()) {
            // Görünür metin yok (ör. JSON, boş SPA iskeleti) → ham gövdenin başı (boşluk indirgenmiş).
            visible = collapseWs(body.length() > 4000 ? body.substring(0, 4000) : body);
        }
        String text = visible;
        if (aroundMatch && kw != null && !kw.isEmpty()) {
            String hay = cs ? visible : visible.toLowerCase(Locale.ROOT);
            String n = collapseWs(decodeEntities(cs ? kw : kw.toLowerCase(Locale.ROOT)));
            int idx = hay.length() == visible.length() && !n.isEmpty() ? hay.indexOf(n) : -1;
            if (idx >= 0) {
                int s = Math.max(0, idx - EXCERPT_MAX / 3);
                text = (s > 0 ? "…" : "") + visible.substring(s);
            }
        }
        String masked = mask(text, secrets);
        if (masked == null) return null;
        masked = masked.trim();
        if (masked.isEmpty()) return null;
        return masked.length() <= EXCERPT_MAX ? masked : masked.substring(0, EXCERPT_MAX - 1) + "…";
    }

    /** Sır süzgeci: JSON/form gizli alanları ({@link RequestLoggingFilter}) + bilinen sır değerleri. */
    static String mask(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty()) return text;
        String r = RequestLoggingFilter.redactSensitiveFields(text);
        if (secrets != null && !secrets.isEmpty()) r = SecretMask.maskValues(r, secrets);
        return r;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Görünür metin
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** İçeriği hiç görünmeyen öğeler (kapanış etiketine kadar atlanır). {@code noscript} BİLİNÇLİ olarak yok: kontrol
     *  ham HTML'i JavaScript'siz bir tarayıcı gibi okur — o metin tam da "bu sayfa JS istiyor" kanıtıdır. */
    private static final Set<String> SKIP = Set.of("script", "style", "template", "svg", "math", "object", "iframe");
    /** Satır/blok kıran etiketler — metinde boşluğa çevrilir (kelimeler yapışmasın). */
    private static final Set<String> BLOCK = Set.of("p", "div", "br", "li", "ul", "ol", "tr", "td", "th", "table",
            "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "header", "footer", "nav", "main", "aside",
            "title", "option", "label", "button", "form", "dd", "dt", "hr", "blockquote", "pre", "figcaption", "noscript");

    /**
     * Tek geçişli HTML → görünür metin: yorumlar, doctype, betik/stil/şablon içerikleri atlanır; etiketler ayıklanır
     * (blok etiketler boşluk olur); HTML varlıkları çözülür; boşluklar tek boşluğa indirgenir. Düz metin/JSON gövdede
     * metnin kendisi döner (etiket yoksa bir şey ayıklanmaz).
     *
     * @param scanCap taranacak en çok karakter
     * @param outCap  üretilecek en çok karakter
     */
    public static String visibleText(String html, int scanCap, int outCap) {
        if (html == null || html.isEmpty()) return "";
        int n = Math.min(html.length(), Math.max(0, scanCap));
        StringBuilder out = new StringBuilder(Math.min(Math.max(16, outCap), 8192));
        boolean space = true;
        int i = 0;
        while (i < n && out.length() < outCap) {
            char c = html.charAt(i);
            if (c == '<') {
                if (html.startsWith("<!--", i)) {
                    int e = html.indexOf("-->", i + 4);
                    i = e < 0 ? n : e + 3;
                    continue;
                }
                int j = i + 1;
                if (j < n && (html.charAt(j) == '!' || html.charAt(j) == '?')) {
                    int e = html.indexOf('>', j);
                    i = e < 0 ? n : e + 1;
                    continue;
                }
                boolean closing = j < n && html.charAt(j) == '/';
                if (closing) j++;
                int ns = j;
                while (j < n && isTagNameChar(html.charAt(j))) j++;
                if (j == ns || !Character.isLetter(html.charAt(ns))) {   // etiket değil: düz '<' (ör. "a < b")
                    out.append('<');
                    space = false;
                    i++;
                    continue;
                }
                String name = html.substring(ns, j).toLowerCase(Locale.ROOT);
                int gt = tagEnd(html, j, n);
                i = gt < 0 ? n : gt + 1;
                if (!closing && SKIP.contains(name) && !(gt > 0 && html.charAt(gt - 1) == '/')) {
                    int close = findClose(html, i, n, name);
                    i = close < 0 ? n : close;
                    continue;
                }
                if (BLOCK.contains(name) && !space) { out.append(' '); space = true; }
                continue;
            }
            if (c == '&') {
                int semi = entityEnd(html, i, n);
                if (semi > 0) {
                    String dec = decodeEntity(html.substring(i + 1, semi));
                    if (dec != null) {
                        for (int k = 0; k < dec.length(); k++) {
                            char d = dec.charAt(k);
                            if (Character.isWhitespace(d) || d == '\u00A0') {
                                if (!space) { out.append(' '); space = true; }
                            } else { out.append(d); space = false; }
                        }
                        i = semi + 1;
                        continue;
                    }
                }
            }
            if (Character.isWhitespace(c) || c == '\u00A0') {
                if (!space) { out.append(' '); space = true; }
            } else {
                out.append(c);
                space = false;
            }
            i++;
        }
        String s = out.toString().trim();
        return s.length() > outCap ? s.substring(0, outCap) : s;
    }

    private static boolean isTagNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
    }

    /** Etiketin kapanan '>' konumu (tırnak içindeki '>' sayılmaz); yoksa -1. */
    private static int tagEnd(String s, int from, int n) {
        char q = 0;
        for (int k = from; k < n; k++) {
            char c = s.charAt(k);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '>') {
                return k;
            }
        }
        return -1;
    }

    /** {@code </name ...>} kapanışının SONRASI; yoksa -1. Harf duyarsız, doğrusal. */
    private static int findClose(String s, int from, int n, String name) {
        int idx = s.indexOf("</", from);
        while (idx >= 0 && idx < n) {
            if (s.regionMatches(true, idx + 2, name, 0, name.length())) {
                int gt = s.indexOf('>', idx + 2 + name.length());
                return gt < 0 ? n : gt + 1;
            }
            idx = s.indexOf("</", idx + 2);
        }
        return -1;
    }

    /** {@code <title>} metni (varlıklar çözülmüş, boşluk indirgenmiş) ya da null. */
    public static String title(String html) {
        if (html == null) return null;
        int n = Math.min(html.length(), MARKER_CAP);
        int idx = html.indexOf('<');
        while (idx >= 0 && idx < n) {
            if (html.regionMatches(true, idx + 1, "title", 0, 5)
                    && idx + 6 < html.length() && (html.charAt(idx + 6) == '>' || Character.isWhitespace(html.charAt(idx + 6)))) {
                int gt = html.indexOf('>', idx + 6);
                if (gt < 0) return null;
                int close = html.indexOf("</", gt + 1);
                while (close >= 0 && !html.regionMatches(true, close + 2, "title", 0, 5)) close = html.indexOf("</", close + 2);
                String t = close < 0 ? html.substring(gt + 1, Math.min(html.length(), gt + 300)) : html.substring(gt + 1, close);
                String v = collapseWs(decodeEntities(t)).trim();
                return v.isEmpty() ? null : (v.length() > 300 ? v.substring(0, 300) : v);
            }
            idx = html.indexOf('<', idx + 1);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  HTML varlıkları
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final Map<String, String> NAMED = new LinkedHashMap<>();
    static {
        String[][] t = {
                {"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"}, {"nbsp", "\u00A0"},
                {"copy", "©"}, {"reg", "®"}, {"trade", "™"}, {"hellip", "…"}, {"mdash", "—"}, {"ndash", "–"},
                {"lsquo", "‘"}, {"rsquo", "’"}, {"ldquo", "“"}, {"rdquo", "”"}, {"laquo", "«"}, {"raquo", "»"},
                {"euro", "€"}, {"middot", "·"}, {"bull", "•"}, {"deg", "°"}, {"times", "×"}, {"divide", "÷"},
                {"shy", ""}, {"zwnj", ""}, {"zwj", ""},
                {"uuml", "ü"}, {"Uuml", "Ü"}, {"ouml", "ö"}, {"Ouml", "Ö"}, {"ccedil", "ç"}, {"Ccedil", "Ç"},
                {"scedil", "ş"}, {"Scedil", "Ş"}, {"gbreve", "ğ"}, {"Gbreve", "Ğ"}, {"inodot", "ı"}, {"imath", "ı"},
                {"Idot", "İ"}, {"iuml", "ï"}, {"auml", "ä"}, {"Auml", "Ä"}, {"eacute", "é"}, {"Eacute", "É"},
                {"egrave", "è"}, {"aacute", "á"}, {"agrave", "à"}, {"acirc", "â"}, {"ecirc", "ê"}, {"icirc", "î"},
                {"ocirc", "ô"}, {"ucirc", "û"}, {"Acirc", "Â"}, {"Icirc", "Î"}, {"Ucirc", "Û"}, {"szlig", "ß"},
                {"iacute", "í"}, {"oacute", "ó"}, {"uacute", "ú"}, {"ntilde", "ñ"},
        };
        for (String[] e : t) NAMED.put(e[0], e[1]);
    }

    /** {@code &...;} varlığının ';' konumu (en çok 12 karakter) ya da -1. */
    private static int entityEnd(String s, int amp, int n) {
        int lim = Math.min(n, amp + 12);
        for (int k = amp + 1; k < lim; k++) {
            char c = s.charAt(k);
            if (c == ';') return k > amp + 1 ? k : -1;
            if (!(Character.isLetterOrDigit(c) || c == '#')) return -1;
        }
        return -1;
    }

    /** Varlık gövdesi ({@code amp}, {@code #252}, {@code #xFC}) → metin; bilinmiyorsa null. */
    static String decodeEntity(String e) {
        if (e == null || e.isEmpty()) return null;
        if (e.charAt(0) == '#') {
            try {
                int cp = e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X')
                        ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                if (cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return null;
                return new String(Character.toChars(cp));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return NAMED.get(e);
    }

    /** Metindeki tüm (tanınan) HTML varlıklarını çözer. Tanınmayan varlık olduğu gibi kalır. */
    public static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = entityEnd(s, i, n);
                if (semi > 0) {
                    String d = decodeEntity(s.substring(i + 1, semi));
                    if (d != null) {
                        out.append(d);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Boşluk dizilerini (NBSP dâhil) tek boşluğa indirger, uçları kırpar. */
    public static String collapseWs(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u200B') {
                if (!space) { out.append(' '); space = true; }
            } else {
                out.append(c);
                space = false;
            }
        }
        return out.toString().trim();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Karakter kümesi
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final Pattern CT_CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:\\-]{1,40})");
    private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]{0,300}?charset\\s*=\\s*[\"']?([A-Za-z0-9._:\\-]{1,40})");

    /** Bildirilen küme: Content-Type parametresi, yoksa ilk 4 KB'daki {@code <meta charset>}; normalize ad ya da null. */
    public static String declaredCharset(String contentType, byte[] body) {
        String name = null;
        if (contentType != null) {
            Matcher m = CT_CHARSET.matcher(contentType);
            if (m.find()) name = m.group(1);
        }
        if (name == null && body != null && body.length > 0) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher m = META_CHARSET.matcher(head);
            if (m.find()) name = m.group(1);
        }
        if (name == null) return null;
        try {
            return Charset.forName(name.trim()).name();
        } catch (Exception e) {
            return name.trim().length() > 60 ? name.trim().substring(0, 60) : name.trim();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Sayfa türü işaretleri (yalnız ipucu — "olabilir")
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final List<String> WAF_MARKERS = List.of(
            "attention required! | cloudflare", "sorry, you have been blocked", "cf-error-details", "cf-chl-",
            "challenge-platform", "checking your browser", "just a moment...", "ddos protection by",
            "the requested url was rejected", "your support id is", "request rejected",
            "_incapsula_resource", "incapsula incident id", "request unsuccessful. incapsula",
            "errors.edgesuite.net", "reference&#32;&#35;", "you don't have permission to access",
            "web application firewall", "are you a robot", "bot detection",
            "erişim engellendi", "isteğiniz engellendi", "güvenlik duvarı");
    // Bilinçli olarak YOK: düz "captcha" (iletişim formlarındaki reCAPTCHA betiği normal sayfada da geçer) ve gövdede
    // düz "access denied" (yalnız BAŞLIKTA aranır) — ipucu yanlış alarm üretmesin.

    static boolean isWafPage(String lowerHead, String titleLower) {
        for (String mk : WAF_MARKERS) {
            if (lowerHead.contains(mk)) return true;
        }
        return titleLower.contains("access denied") || titleLower.contains("forbidden") || titleLower.contains("blocked");
    }

    private static final Pattern LOGIN_PATH = Pattern.compile("(?i)/(login|log-in|signin|sign-in|sso|oauth2?|adfs|auth|authorize|giris|oturum-?ac)(/|\\.|\\?|$)");

    static boolean isLoginPage(String lowerHead, URI finalUri, int redirects) {
        if (lowerHead.contains("type=\"password\"") || lowerHead.contains("type='password'")
                || lowerHead.contains("type=password")) return true;
        if (redirects > 0 && finalUri != null && finalUri.getRawPath() != null) {
            String p = finalUri.getRawPath() + (finalUri.getRawQuery() != null ? "?" : "");
            return LOGIN_PATH.matcher(p).find();
        }
        return false;
    }

    static boolean isJsRendered(String lowerHead, String visible, String title) {
        if (!lowerHead.contains("<script")) return false;
        int textLen = visible.length() - (title == null ? 0 : Math.min(visible.length(), title.length()));
        if (textLen >= JS_TEXT_MAX) return false;
        return lowerHead.contains("<noscript") || lowerHead.contains("id=\"root\"") || lowerHead.contains("id=\"app\"")
                || lowerHead.contains("id=\"__next\"") || lowerHead.contains("ng-app") || lowerHead.contains("<app-root")
                || lowerHead.contains("data-reactroot") || lowerHead.contains("enable javascript")
                || lowerHead.contains("javascript'i etkinleştir") || lowerHead.contains("javascript gerekli");
    }

    static boolean redirectedElsewhere(String originalUrl, URI finalUri, int redirects) {
        if (redirects <= 0 || finalUri == null || finalUri.getHost() == null || originalUrl == null) return false;
        String origHost;
        try {
            origHost = URI.create(originalUrl.trim().replace("{timestamp}", "0")).getHost();
        } catch (Exception e) {
            return false;
        }
        if (origHost == null) return false;
        return !site(origHost).equals(site(finalUri.getHost()));
    }

    private static String site(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static final List<String> MAINT_MARKERS = List.of(
            "maintenance", "bakım", "bakimda", "under construction", "temporarily unavailable", "be right back",
            "geçici olarak hizmet", "kısa süre içinde geri", "çalışma yapılmaktadır", "yapım aşamasında");

    static boolean isMaintenancePage(String titleLower, String visibleLower, int status) {
        for (String mk : MAINT_MARKERS) {
            if (titleLower.contains(mk)) return true;
        }
        boolean shortPage = visibleLower.length() < 2000;
        if (status == 503 || shortPage) {
            for (String mk : MAINT_MARKERS) {
                if (visibleLower.contains(mk)) return true;
            }
        }
        return false;
    }

    private static final List<String> ERROR_TITLE = List.of(
            "404", "not found", "bulunamadı", "500", "internal server error", "server error", "sunucu hatası",
            "an error occurred", "error occurred", "bir hata oluştu", "exception", "oops", "hata sayfası", "error page",
            "page unavailable", "service unavailable", "bad gateway");

    static boolean isErrorTitle(String titleLower) {
        if (titleLower == null || titleLower.isEmpty()) return false;
        for (String mk : ERROR_TITLE) {
            if (titleLower.contains(mk)) return true;
        }
        return titleLower.equals("error") || titleLower.equals("hata") || titleLower.startsWith("error ") || titleLower.startsWith("hata ");
    }

    /** Metin olarak aranabilir içerik mi? Tür yoksa kaba koklama (NUL / kontrol baytı yoksa metin). */
    public static boolean isTextual(String contentType, byte[] sample) {
        if (contentType != null && !contentType.isBlank()) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            return ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")
                    || ct.contains("html") || ct.contains("x-www-form-urlencoded") || ct.contains("ecmascript")
                    || ct.contains("yaml") || ct.contains("csv");
        }
        if (sample == null || sample.length == 0) return true;
        int n = Math.min(sample.length, 1024);
        for (int i = 0; i < n; i++) {
            int b = sample[i] & 0xff;
            if (b == 0 || (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != 0x0c)) return false;
        }
        return true;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  URL
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Gösterilebilir URL: kullanıcı bilgisi (user:pass@) ATILIR, hassas sorgu değerleri maskelenir. ≤ 2000. */
    public static String displayUrl(URI u) {
        if (u == null) return null;
        try {
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null) return SecretMask.maskUrlQuery(stripUserInfo(u.toString()));
            StringBuilder sb = new StringBuilder(scheme).append("://").append(host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host);
            if (u.getPort() != -1) sb.append(':').append(u.getPort());
            sb.append(u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath());
            if (u.getRawQuery() != null) sb.append('?').append(u.getRawQuery());
            String s = SecretMask.maskUrlQuery(sb.toString());
            return s.length() > 2000 ? s.substring(0, 2000) : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripUserInfo(String s) {
        int scheme = s.indexOf("://");
        if (scheme < 0) return s;
        int at = s.indexOf('@', scheme + 3);
        int slash = s.indexOf('/', scheme + 3);
        if (at > 0 && (slash < 0 || at < slash)) return s.substring(0, scheme + 3) + s.substring(at + 1);
        return s;
    }
}
