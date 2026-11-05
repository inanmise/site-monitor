package com.sitemonitor.service.keyword;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Anahtar kelime kontrolünün METİN katmanı (kw sözleşmesi §4.2–4.3, 2026-11-05) — saf, durumsuz, doğrusal.
 *
 * <ul>
 *   <li><b>Çözme</b> ({@link #decode}): gövde baytları sayfanın BİLDİRDİĞİ karakter kümesiyle okunur. Öncelik: BOM (UTF-8,
 *       UTF-16BE/LE) &gt; {@code Content-Type} {@code charset=} &gt; ilk 4 KB'taki {@code <meta charset>} /
 *       {@code <meta http-equiv … charset=>} &gt; UTF-8. Etiketler WHATWG kuralıyla eşlenir: {@code iso-8859-9}, {@code latin5}
 *       … → {@code windows-1254}; {@code iso-8859-1}, {@code us-ascii} … → {@code windows-1252}. Desteklenmeyen / bozuk ad →
 *       UTF-8 ({@code source="fallback"}). Bildirim YOKKEN geçersiz UTF-8 yine UTF-8 okunur (sezgisel windows-1254 YOK —
 *       {@code CHARSET} ipucu bu durumu söylemeye devam eder). Eskiden gövde HER ZAMAN UTF-8 okunuyordu: windows-1254 bir
 *       sayfada "Giriş" hiç bulunmuyordu (inceleme B4).</li>
 *   <li><b>Görünüm</b> ({@link #view}): eşleşme kapsamı {@code RAW} (ham metin; isteğe bağlı boşluk / varlık normalleştirme)
 *       ya da {@code VISIBLE} (görünür metin — {@link KeywordBodyAnalyzer#visibleText}, her zaman normalize).</li>
 *   <li><b>İğne</b> ({@link #needle}): aranan metin, görünümle aynı normalleştirmeden geçer.</li>
 * </ul>
 * Düzenli ifade YOK: meta koklaması ilk 4 KB'ta düz tarama.
 */
public final class KeywordText {

    private KeywordText() {}

    /** Eşleşme kapsamı: ham metin (bugünkü, NULL ile aynı). */
    public static final String SCOPE_RAW = "RAW";
    /** Eşleşme kapsamı: görünür metin (etiket / betik / stil ayıklanmış, varlık çözülmüş, boşluk indirgenmiş). */
    public static final String SCOPE_VISIBLE = "VISIBLE";
    /** Kontrol yolunun görünür metin tavanı (karakter) — gövde tavanıyla aynı; analiz ipuçlarının 512 K sınırı AYRI. */
    public static final int VISIBLE_CAP = 2_000_000;
    /** {@code <meta>} koklamasının bakıldığı baş kısım (bayt). */
    static final int META_SNIFF = 4096;

    /** Çözme kaynağı. */
    public static final String SRC_BOM = "bom";
    public static final String SRC_HEADER = "header";
    public static final String SRC_META = "meta";
    public static final String SRC_DEFAULT = "default";
    public static final String SRC_FALLBACK = "fallback";

    /** Çözme sonucu: metin + kullanılan küme (kanonik ad) + kaynak ({@code bom|header|meta|default|fallback}). */
    public record Decoded(String text, String charset, String source) {}

    /** WHATWG Encoding Standard etiket eşlemesinin bizi ilgilendiren kısmı (küçük harf etiket → kanonik ad). */
    private static final Map<String, String> WHATWG = Map.ofEntries(
            Map.entry("iso-8859-9", "windows-1254"), Map.entry("iso8859-9", "windows-1254"), Map.entry("iso_8859-9", "windows-1254"),
            Map.entry("iso-ir-148", "windows-1254"), Map.entry("latin5", "windows-1254"), Map.entry("l5", "windows-1254"),
            Map.entry("csisolatin5", "windows-1254"), Map.entry("windows-1254", "windows-1254"), Map.entry("cp1254", "windows-1254"),
            Map.entry("x-cp1254", "windows-1254"), Map.entry("iso_8859-9:1989", "windows-1254"), Map.entry("iso88599", "windows-1254"),
            Map.entry("iso-8859-1", "windows-1252"), Map.entry("iso8859-1", "windows-1252"), Map.entry("iso_8859-1", "windows-1252"),
            Map.entry("latin1", "windows-1252"), Map.entry("l1", "windows-1252"), Map.entry("us-ascii", "windows-1252"),
            Map.entry("ascii", "windows-1252"), Map.entry("cp1252", "windows-1252"), Map.entry("windows-1252", "windows-1252"),
            Map.entry("x-cp1252", "windows-1252"), Map.entry("cp819", "windows-1252"), Map.entry("ibm819", "windows-1252"),
            Map.entry("csisolatin1", "windows-1252"), Map.entry("iso-ir-100", "windows-1252"), Map.entry("iso88591", "windows-1252"),
            Map.entry("ansi_x3.4-1968", "windows-1252"),
            Map.entry("utf8", "UTF-8"), Map.entry("unicode-1-1-utf-8", "UTF-8"), Map.entry("utf-8", "UTF-8"));

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Çözme
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Gövdeyi bildirilen kümeyle çözer; asla fırlatmaz (null gövde → boş metin, UTF-8). */
    public static Decoded decode(byte[] body, String contentType) {
        byte[] b = body == null ? new byte[0] : body;
        // 1) BOM — en güçlü bildirim (WHATWG: BOM, başlığı da ezer).
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) {
            return new Decoded(new String(b, 3, b.length - 3, StandardCharsets.UTF_8), "UTF-8", SRC_BOM);
        }
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) {
            return new Decoded(new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE), "UTF-16BE", SRC_BOM);
        }
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) {
            return new Decoded(new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE), "UTF-16LE", SRC_BOM);
        }
        // 2) Content-Type başlığı.
        String headerLabel = charsetParam(contentType);
        if (headerLabel != null) {
            Charset cs = resolve(headerLabel, false);
            if (cs != null) return new Decoded(new String(b, cs), cs.name(), SRC_HEADER);
            return new Decoded(new String(b, StandardCharsets.UTF_8), "UTF-8", SRC_FALLBACK);
        }
        // 3) <meta charset> / <meta http-equiv … charset=> — ilk 4 KB.
        String metaLabel = metaCharset(b);
        if (metaLabel != null) {
            Charset cs = resolve(metaLabel, true);
            if (cs != null) return new Decoded(new String(b, cs), cs.name(), SRC_META);
            return new Decoded(new String(b, StandardCharsets.UTF_8), "UTF-8", SRC_FALLBACK);
        }
        // 4) Bildirim yok → UTF-8 (geçersiz UTF-8 bile — sezgisel tahmin YOK).
        return new Decoded(new String(b, StandardCharsets.UTF_8), "UTF-8", SRC_DEFAULT);
    }

    /**
     * Etiket → küme (WHATWG eşlemesi, sonra {@link Charset#forName}). {@code fromMeta}: meta etiketinde UTF-16 ailesi UTF-8
     * sayılır (WHATWG ön taraması — ASCII uyumlu bir belge kendini UTF-16 ilan edemez). Desteklenmeyen / bozuk → null.
     */
    public static Charset resolve(String label, boolean fromMeta) {
        if (label == null) return null;
        String l = label.trim().toLowerCase(Locale.ROOT);
        if (l.isEmpty() || l.length() > 40) return null;
        String mapped = WHATWG.get(l);
        String name = mapped != null ? mapped : l;
        if (fromMeta && name.startsWith("utf-16")) return StandardCharsets.UTF_8;
        Charset cs = forName(name);
        if (cs == null && "windows-1254".equals(name)) cs = forName("ISO-8859-9");   // jdk.charsets yoksa en yakın eş
        if (cs == null && "windows-1252".equals(name)) cs = StandardCharsets.ISO_8859_1;
        return cs;
    }

    private static Charset forName(String name) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (Exception e) {
            return null;   // IllegalCharsetNameException vb.
        }
    }

    /** {@code Content-Type} içindeki {@code charset=} değeri (tırnaklar ayıklanmış) ya da null — doğrusal. */
    static String charsetParam(String contentType) {
        if (contentType == null || contentType.isEmpty()) return null;
        String ct = contentType.length() > 300 ? contentType.substring(0, 300) : contentType;
        String lower = ct.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset");
        while (idx >= 0) {
            int k = idx + 7;
            while (k < ct.length() && (ct.charAt(k) == ' ' || ct.charAt(k) == '\t')) k++;
            if (k < ct.length() && ct.charAt(k) == '=') {
                return labelAt(ct, k + 1);
            }
            idx = lower.indexOf("charset", idx + 7);
        }
        return null;
    }

    /** İlk {@value #META_SNIFF} baytta {@code <meta …charset=…>} — etiket içinde aranır (ISO-8859-1 okuma: bayt = karakter). */
    static String metaCharset(byte[] body) {
        if (body == null || body.length == 0) return null;
        String head = new String(body, 0, Math.min(body.length, META_SNIFF), StandardCharsets.ISO_8859_1);
        String lower = head.toLowerCase(Locale.ROOT);
        int m = lower.indexOf("<meta");
        while (m >= 0) {
            int end = lower.indexOf('>', m + 5);
            if (end < 0) end = lower.length();
            int lim = Math.min(end, m + 400);
            int c = lower.indexOf("charset", m + 5);
            while (c >= 0 && c < lim) {
                int k = c + 7;
                while (k < lim && (head.charAt(k) == ' ' || head.charAt(k) == '\t')) k++;
                if (k < lim && head.charAt(k) == '=') {
                    String v = labelAt(head, k + 1);
                    if (v != null) return v;
                }
                c = lower.indexOf("charset", c + 7);
            }
            m = lower.indexOf("<meta", m + 5);
        }
        return null;
    }

    /** {@code from} konumundan (boşluk / tırnak atlanarak) etiket karakterleri {@code [A-Za-z0-9._:-]} — en çok 40. */
    private static String labelAt(String s, int from) {
        int k = from;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t' || s.charAt(k) == '"' || s.charAt(k) == '\'')) k++;
        int st = k;
        while (k < s.length() && k - st < 40) {
            char ch = s.charAt(k);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == ':' || ch == '-') k++;
            else break;
        }
        return k > st ? s.substring(st, k) : null;
    }

    /**
     * Alternatif okuma — {@code CHARSET} ipucu için: kullanılan küme UTF-8 değilse UTF-8; UTF-8 ise ve metinde geçersiz bayt
     * izi (U+FFFD) varsa windows-1254 (Türkçe eski sayfalar). Aksi hâlde null (alternatif yok).
     */
    public static Charset alternate(String usedCharset, String text) {
        Charset used = usedCharset == null ? StandardCharsets.UTF_8 : forName(usedCharset);
        if (used != null && !used.equals(StandardCharsets.UTF_8)) return StandardCharsets.UTF_8;
        if (text != null && text.indexOf('�') >= 0) {
            Charset w = forName("windows-1254");
            return w != null ? w : forName("ISO-8859-9");
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Görünüm + iğne
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Kapsam değerini kanonik hâle getirir: {@code VISIBLE} ya da {@code RAW} (null / bilinmeyen → RAW). */
    public static String scope(String raw) {
        return raw != null && SCOPE_VISIBLE.equalsIgnoreCase(raw.trim()) ? SCOPE_VISIBLE : SCOPE_RAW;
    }

    /** HTML varlıkları çözülür + boşluklar indirgenir ({@code normalize_ws} ve görünür metin kuralı). */
    public static String normalize(String s) {
        return KeywordBodyAnalyzer.collapseWs(KeywordBodyAnalyzer.decodeEntities(s));
    }

    /**
     * Aranan metin: {@code RAW} → çözülmüş ham metin ({@code normalizeWs} ise varlık + boşluk normalleştirilmiş);
     * {@code VISIBLE} → görünür metin (tam gövde, {@value #VISIBLE_CAP} karakter).
     */
    public static String view(String text, String scope, boolean normalizeWs) {
        if (text == null) return "";
        if (SCOPE_VISIBLE.equals(scope(scope))) return KeywordBodyAnalyzer.visibleText(text, VISIBLE_CAP, VISIBLE_CAP);
        return normalizeWs ? normalize(text) : text;
    }

    /** İğne görünümle AYNI normalleştirmeden geçer (ham + normalleştirmesiz → olduğu gibi). */
    public static String needle(String keyword, String scope, boolean normalizeWs) {
        if (keyword == null) return "";
        if (SCOPE_VISIBLE.equals(scope(scope)) || normalizeWs) return normalize(keyword);
        return keyword;
    }
}
