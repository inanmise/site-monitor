package com.sitemonitor.service.page.scan.deep;

import java.util.Locale;

/**
 * Betik / stil ÖRNEĞİ üzerinde sezgisel denetim (2026-11, kaynak analizi algoritma 3) — SAF, doğrusal, düzenli ifade yok.
 *
 * <p>Örnek gövdenin çözülmüş ilk ≤ {@value #HEAD_CHARS} karakteri + son ≤ {@value #TAIL_CHARS} karakteridir (bellekte, saklanmaz).
 * Üç şey çıkar: (1) küçültülmüş mü (satır uzunluğu + boşluk oranı — eşikler aşağıda, tek yerde), (2) {@code sourceMappingURL}
 * (dosyanın sonundaki yorum), (3) kütüphane başlığı ({@link JsLibraryCatalog#detect}). Küçük dosya ya da belirsiz oran →
 * {@code minified = null} ("bilinmiyor"), asla tahmin edilmiş bir "evet".
 */
public final class AssetSample {

    public static final int HEAD_CHARS = 64 * 1024, TAIL_CHARS = 4 * 1024;
    /** Bundan küçük örnek değerlendirilmez (küçültmenin kazancı önemsiz, oran gürültülü). */
    static final int MIN_CHARS = 2 * 1024;
    /** Ortalama satır uzunluğu bu ve üstü → küçültülmüş. */
    static final double MINIFIED_LINE = 200;
    /** Boşluk oranı bunun altı → küçültülmüş. */
    static final double MINIFIED_WS = 0.08;
    /** Küçültülmemiş: ortalama satır kısa VE boşluk oranı yüksek VE yeterince satır. */
    static final double UNMINIFIED_LINE = 100, UNMINIFIED_WS = 0.15;
    static final int UNMINIFIED_MIN_LINES = 20;
    /** Küçültmenin tahmini kazancı (tel baytına oran — sıkıştırılmış gövdede kazanç açılmış metindekinden azdır). */
    public static final double SAVE_JS = 0.25, SAVE_CSS = 0.15;

    /**
     * @param minified     true / false / null (bilinmiyor)
     * @param sourceMap    {@code sourceMappingURL} değeri (çözülmemiş, {@code data:} hariç); yoksa null
     * @param library      tespit edilen kütüphane (yalnız betik); yoksa null
     */
    public record Result(Boolean minified, String sourceMap, JsLibraryCatalog.Library library) {
        public static final Result NONE = new Result(null, null, null);
    }

    private AssetSample() {}

    /**
     * @param head  çözülmüş ilk karakterler (null = okunamadı)
     * @param tail  çözülmüş son karakterler (null = okunamadı ya da baş zaten tamamı)
     * @param js    betik mi (stil değilse kütüphane aranmaz)
     */
    public static Result inspect(String url, String head, String tail, boolean js) {
        if (head == null) return Result.NONE;
        String h = head.length() > HEAD_CHARS ? head.substring(0, HEAD_CHARS) : head;
        String t = tail == null ? null : (tail.length() > TAIL_CHARS ? tail.substring(tail.length() - TAIL_CHARS) : tail);
        Boolean min = minified(h);
        String map = sourceMapping(t != null ? t : lastChars(h, TAIL_CHARS));
        JsLibraryCatalog.Library lib = js ? JsLibraryCatalog.detect(url, t == null ? h : h + "\n" + t) : null;
        return new Result(min, map, lib);
    }

    /** Küçültme kararı; örnek < {@value #MIN_CHARS} ya da arada kalan oran → null. */
    static Boolean minified(String s) {
        if (s == null || s.length() < MIN_CHARS) return null;
        int lines = 1, ws = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') lines++;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') ws++;
        }
        double avgLine = (double) s.length() / lines;
        double wsRatio = (double) ws / s.length();
        if (avgLine >= MINIFIED_LINE || wsRatio < MINIFIED_WS) return true;
        if (avgLine < UNMINIFIED_LINE && wsRatio > UNMINIFIED_WS && lines >= UNMINIFIED_MIN_LINES) return false;
        return null;
    }

    /**
     * Son {@code sourceMappingURL=} değeri ({@code //# sourceMappingURL=x.map} ya da {@code /*# sourceMappingURL=x.map *\/});
     * {@code data:} (satır içi harita) ve boş değer → null. Değer ≤ 2048 karakter.
     */
    static String sourceMapping(String tail) {
        if (tail == null) return null;
        int i = tail.lastIndexOf("sourceMappingURL=");
        if (i < 0) return null;
        // Yorum içinde mi: önünde "//#" / "//@" / "/*#" / "/*@"
        int p = i - 1;
        while (p >= 0 && (tail.charAt(p) == ' ' || tail.charAt(p) == '\t')) p--;
        if (p < 2) return null;
        char m = tail.charAt(p);
        if (m != '#' && m != '@') return null;
        String opener = tail.substring(p - 2, p);
        if (!opener.equals("//") && !opener.equals("/*")) return null;
        int s = i + "sourceMappingURL=".length();
        int e = s;
        while (e < tail.length() && e - s < 2048) {
            char c = tail.charAt(e);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '"' || c == '\'') break;
            if (c == '*' && e + 1 < tail.length() && tail.charAt(e + 1) == '/') break;
            e++;
        }
        String v = tail.substring(s, e).trim();
        if (v.isEmpty() || v.toLowerCase(Locale.ROOT).startsWith("data:")) return null;
        return v;
    }

    private static String lastChars(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /** Tahmini küçültme kazancı (tel baytı üzerinden). */
    public static long estimatedSaving(long bytes, boolean js) {
        return Math.round(Math.max(0, bytes) * (js ? SAVE_JS : SAVE_CSS));
    }
}
