package com.sitemonitor.service.keyword;

/**
 * Anahtar kelime eşleşmesinin TEK harf katlama kuralı (kw sözleşmesi, 2026-11-05) — saf, durumsuz, doğrusal.
 *
 * <p>Büyük/küçük harf DUYARSIZ aramada iki taraf da {@link #fold} ile katlanır: İ (U+0130), I, ı (U+0131) ve i → "i"; diğer
 * her karakter {@link Character#toLowerCase(char)} (basit, 1:1). Katlama UZUNLUĞU KORUR (vekil çiftleri olduğu gibi kalır):
 * katlanmış metindeki konum özgün metindeki konumdur. Aksan katlaması YOK ("ş" ≠ "s"). Ön yüz eşi:
 * {@code frontend/src/utils/keywordFold.js} (aynı doğruluk tablosu, {@code KeywordFoldTest.TABLE}).
 */
public final class KeywordFold {

    private KeywordFold() {}

    /** Sayım üst sınırı (TEXT kuralları). */
    public static final int MAX_COUNT = 1_000_000;

    /** Örtüşmesiz eşleşme sonucu: adet + ilk eşleşmenin konumu (yoksa -1). */
    public record Count(int count, int firstIndex) {}

    /** Tek karakter katlama. */
    public static char fold(char c) {
        if (c == 'I' || c == 'i' || c == '\u0130' || c == '\u0131') return 'i';
        return Character.toLowerCase(c);
    }

    /** Metni katlar (uzunluk korunur); null → null. */
    public static String fold(String s) {
        if (s == null) return null;
        char[] out = new char[s.length()];
        for (int i = 0; i < out.length; i++) out[i] = fold(s.charAt(i));
        return new String(out);
    }

    /** Örtüşmesiz sayım; {@code caseSensitive=false} ise iki taraf katlanır. Boş / null iğne → (0, -1). En çok {@code max}. */
    public static Count count(String hay, String needle, boolean caseSensitive, int max) {
        if (hay == null || needle == null || needle.isEmpty() || max < 1) return new Count(0, -1);
        String h = caseSensitive ? hay : fold(hay);
        String n = caseSensitive ? needle : fold(needle);
        int c = 0;
        int first = -1;
        int from = 0;
        int idx;
        while (c < max && (idx = h.indexOf(n, from)) >= 0) {
            if (first < 0) first = idx;
            c++;
            from = idx + n.length();
        }
        return new Count(c, first);
    }
}
