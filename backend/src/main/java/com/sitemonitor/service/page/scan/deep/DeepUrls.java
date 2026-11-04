package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.SecretMask;

import java.net.URI;
import java.util.Locale;

/**
 * Derin analizin URL yardımcıları (2026-11, kaynak analizi algoritma 3) — SAF.
 *
 * <p>Yazılan her URL {@link #mask} ile geçer: sorgu dizesi korunur ama gizli değer ŞÜPHESİ olan parametrenin değeri
 * ({@link SecretMask#maskUrlQuery}) {@code *****} olur. Aynı kural nesne satırında ve bölüm listelerinde uygulanır, eşleşme
 * bozulmaz. Uzunluk sınırlıdır ({@value #MAX_URL_CHARS}); düzenli ifade yalnız bu sınırdan sonra çalışır.
 */
public final class DeepUrls {

    /** Yazılan URL'nin en çok uzunluğu (keşif zaten 4096'dan uzunu atar). */
    public static final int MAX_URL_CHARS = 4096;
    /** Bölüm metin alanlarının tavanı (sözleşme: ≤ 300 karakter). */
    public static final int MAX_TEXT = 300;
    /** Parmak izi (sürümlü dosya adı) sayılan en kısa karma parçası. */
    static final int FINGERPRINT_MIN = 8;

    private DeepUrls() {}

    /** Gizli değer şüphesi olan sorgu parametrelerini maskeler; null → null. */
    public static String mask(String url) {
        if (url == null) return null;
        String u = url.length() > MAX_URL_CHARS ? url.substring(0, MAX_URL_CHARS) : url;
        try {
            return SecretMask.maskUrlQuery(u);
        } catch (RuntimeException e) {
            return u;
        }
    }

    /** Metni {@value #MAX_TEXT} karaktere kırpar (boşlukları sadeleştirmeden). */
    public static String clip(String s) {
        return clip(s, MAX_TEXT);
    }

    public static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Host (küçük harf); çözülemezse null. */
    public static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Şema https mi. */
    public static boolean https(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8);
    }

    /** Şema http mi (karışık içerik denetimi). */
    public static boolean http(String url) {
        return url != null && url.regionMatches(true, 0, "http://", 0, 7);
    }

    /** Port (yoksa şemanın varsayılanı); çözülemezse -1. */
    public static int port(String url) {
        try {
            URI u = URI.create(url);
            if (u.getPort() > 0) return u.getPort();
            return "https".equalsIgnoreCase(u.getScheme()) ? 443 : "http".equalsIgnoreCase(u.getScheme()) ? 80 : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Yol (sorgusuz, parçasız); çözülemezse boş. */
    public static String path(String url) {
        try {
            String p = URI.create(url).getRawPath();
            return p == null ? "" : p;
        } catch (RuntimeException e) {
            int q = indexOfAny(url, '?', '#');
            String head = q < 0 ? url : url.substring(0, q);
            int s = head.indexOf("://");
            int slash = s < 0 ? -1 : head.indexOf('/', s + 3);
            return slash < 0 ? "" : head.substring(slash);
        }
    }

    /** Sorgu dizesi (soru işaretsiz, parçasız); yoksa null. */
    public static String query(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return null;
        int h = url.indexOf('#', q);
        return h < 0 ? url.substring(q + 1) : url.substring(q + 1, h);
    }

    /** Yolun son parçası (dosya adı). */
    public static String fileName(String url) {
        String p = path(url);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /**
     * Sürümlü (parmak izli) adres mi — DOĞRUSAL: dosya adında ≥ {@value #FINGERPRINT_MIN} karakterlik karma parçası
     * ({@code main.3f2a9c1b.js}, {@code app-BkJz8x2Q.js} — harf VE rakam ya da tümü onaltılık) ya da sorguda
     * {@code v= | ver= | version= | hash= | rev=} parametresi. Uzun adresler {@value #MAX_URL_CHARS}'te kesilir.
     */
    public static boolean versioned(String url) {
        if (url == null) return false;
        String u = url.length() > MAX_URL_CHARS ? url.substring(0, MAX_URL_CHARS) : url;
        String q = query(u);
        if (q != null) {
            for (String part : q.split("&", 50)) {
                int eq = part.indexOf('=');
                if (eq <= 0 || eq == part.length() - 1) continue;
                String k = part.substring(0, eq).toLowerCase(Locale.ROOT);
                if (k.equals("v") || k.equals("ver") || k.equals("version") || k.equals("hash") || k.equals("rev")) return true;
            }
        }
        String name = fileName(u);
        int start = 0;
        for (int i = 0; i <= name.length(); i++) {
            char c = i < name.length() ? name.charAt(i) : '.';
            if (c == '.' || c == '-' || c == '_' || c == '~') {
                if (hashLike(name, start, i)) return true;
                start = i + 1;
            }
        }
        return false;
    }

    /**
     * [from, to) parçası karma gibi mi: 8–64 harf / rakam ve en az İKİ rakam ({@code 3f2a9c1b}, {@code BkJz8x2Q},
     * {@code 20231105}); {@code bootstrap4} / {@code es2015} gibi adlar sayılmaz.
     */
    static boolean hashLike(String s, int from, int to) {
        int len = to - from;
        if (len < FINGERPRINT_MIN || len > 64) return false;
        int digits = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return digits >= 2;
    }

    private static int indexOfAny(String s, char a, char b) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == a || s.charAt(i) == b) return i;
        return -1;
    }
}
