package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.scan.PageResourceDiscovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stil metnindeki {@code @font-face} blokları (2026-11, kaynak analizi algoritma 3) — SAF, DOĞRUSAL (düzenli ifade yok):
 * aile adı, ağırlık, {@code font-display} ve tarayıcının indireceği TEK dosya ({@link PageResourceDiscovery#chooseFont} —
 * keşifle aynı kural, böylece blok ölçülen yazı tipi nesnesiyle eşleşir). En çok {@value #MAX_FACES} blok; metin keşfin
 * karakter tavanıyla kesilir.
 */
public final class FontFaces {

    static final int MAX_FACES = 200;

    /**
     * @param family   aile adı (tırnaksız, ≤ 100 karakter)
     * @param weight   {@code font-weight} değeri (yoksa "400")
     * @param display  {@code font-display} değeri; yoksa null
     * @param url      indirilen dosyanın anahtarı (parçasız, mutlak); bulunamazsa null
     * @param cssUrl   bloğun bulunduğu stil dosyası (satır içi stilde sayfanın adresi)
     */
    public record Face(String family, String weight, String display, String url, String cssUrl) {}

    private FontFaces() {}

    public static List<Face> scan(String css, String baseUrl, String cssUrl) {
        List<Face> out = new ArrayList<>();
        if (css == null || css.isEmpty()) return out;
        String s = PageResourceDiscovery.stripComments(css.length() > PageResourceDiscovery.MAX_CSS_CHARS
                ? css.substring(0, PageResourceDiscovery.MAX_CSS_CHARS) : css);
        String low = s.toLowerCase(Locale.ROOT);
        int from = 0;
        while (out.size() < MAX_FACES) {
            int at = low.indexOf("@font-face", from);
            if (at < 0) break;
            int open = s.indexOf('{', at + 10);
            if (open < 0) break;
            int close = s.indexOf('}', open + 1);
            int end = close < 0 ? s.length() : close;
            String family = unquote(value(s, low, open + 1, end, "font-family"));
            String weight = value(s, low, open + 1, end, "font-weight");
            String display = value(s, low, open + 1, end, "font-display");
            String chosen = PageResourceDiscovery.chooseFont(s, low, open + 1, end);
            String url = null;
            if (chosen != null && !chosen.toLowerCase(Locale.ROOT).startsWith("data:")) {
                String u = PageFetchCore.normalizeUrl(PageFetchCore.resolve(baseUrl, PageFetchCore.normalizeUrl(chosen)));
                if (PageFetchCore.isHttp(u) && u.length() <= DeepUrls.MAX_URL_CHARS) url = PageResourceDiscovery.key(u);
            }
            if (family != null && !family.isBlank()) {
                out.add(new Face(DeepUrls.clip(family, 100), weight == null || weight.isBlank() ? "400" : DeepUrls.clip(weight, 20),
                        display == null || display.isBlank() ? null : DeepUrls.clip(display.toLowerCase(Locale.ROOT), 20), url, cssUrl));
            }
            from = close < 0 ? s.length() : close + 1;
        }
        return out;
    }

    /** Bloğun [from, to) aralığında {@code prop:} bildiriminin değeri (son bildirim kazanır); yoksa null. */
    static String value(String s, String low, int from, int to, String prop) {
        String found = null;
        int p = from;
        while (p < to) {
            int k = low.indexOf(prop, p, to);
            if (k < 0) break;
            char before = k == 0 ? '{' : s.charAt(k - 1);
            int j = k + prop.length();
            while (j < to && Character.isWhitespace(s.charAt(j))) j++;
            if ((before == '{' || before == ';' || Character.isWhitespace(before)) && j < to && s.charAt(j) == ':') {
                int e = j + 1;
                char q = 0;
                while (e < to) {
                    char c = s.charAt(e);
                    if (q != 0) { if (c == q) q = 0; }
                    else if (c == '"' || c == '\'') q = c;
                    else if (c == ';') break;
                    e++;
                }
                found = s.substring(j + 1, e).trim();
                if (found.toLowerCase(Locale.ROOT).endsWith("!important")) {
                    found = found.substring(0, found.length() - 10).trim();
                }
                p = e;
                continue;
            }
            p = k + prop.length();
        }
        return found;
    }

    private static String unquote(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0)) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
