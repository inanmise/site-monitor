package com.sitemonitor.util;

import java.util.Locale;
import java.util.Map;

/**
 * Posta KONU şablonu çözücü — yer tutuculu tek satır ({@code {ad} · {seviye}}). Veritabanı sorgu izlemesinin
 * ({@code DbQueryMailSubject}) doğrusal döngüsünden çıkarıldı (kw sözleşmesi §8.3, 2026-11-05) ve anahtar kelime izlemesiyle
 * ({@code KeywordMailSubject}) ORTAK: iki tür aynı kuralı kullanır, çıktı bayt bayt aynıdır.
 *
 * <p>Kurallar: yer tutucu adı süslü parantez içinde, ≤ 12 karakter, büyük/küçük harf duyarsız; takma ad haritası Türkçe adı ve
 * İngilizce eşini aynı değer anahtarına bağlar. Bilinmeyen yer tutucu OLDUĞU GİBİ kalır. Şablon ve değerler kullanıcı verisidir:
 * DÜZENLİ İFADE YOK — doğrusal tarama; sonuç tek satır (satır sonu / denetim karakteri boşluk olur), en çok {@code maxLen}.
 */
public final class SubjectTemplate {

    private SubjectTemplate() {}

    /**
     * Şablonu çözer.
     *
     * @param template şablon (null / boş → null)
     * @param aliases  yer tutucu adı (küçük harf) → değer anahtarı
     * @param values   değer anahtarı → değer (null = boş)
     * @param maxLen   sonuç üst sınırı (aşarsa "…" ile kırpılır)
     * @return tek satırlık konu ya da null (şablon yok / sonuç boş — çağıran standart konuya düşer)
     */
    public static String render(String template, Map<String, String> aliases, Map<String, String> values, int maxLen) {
        if (template == null || template.isBlank()) return null;
        String t = template.length() > 4 * maxLen ? template.substring(0, 4 * maxLen) : template;
        StringBuilder out = new StringBuilder(t.length() + 32);
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '{') {
                int close = t.indexOf('}', i + 1);
                // Yer tutucu adı kısadır (≤ 12); uzak / hiç kapanmayan süslü parantez metin olarak kalır.
                if (close > i + 1 && close - i <= 13) {
                    String key = aliases.get(t.substring(i + 1, close).trim().toLowerCase(Locale.ROOT));
                    if (key != null) {
                        String v = values == null ? null : values.get(key);
                        out.append(v == null ? "" : v);
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        String s = singleLine(out.toString());
        if (s.isEmpty()) return null;
        return s.length() > maxLen ? s.substring(0, maxLen - 1) + "…" : s;
    }

    /** Satır sonu / sekme / denetim karakterleri boşluk olur, ardışık boşluk teke iner, uçlar kırpılır (doğrusal). */
    public static String singleLine(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (!space && b.length() > 0) b.append(' ');
                space = true;
            } else {
                b.append(c);
                space = false;
            }
        }
        int end = b.length();
        while (end > 0 && b.charAt(end - 1) == ' ') end--;
        return b.substring(0, end);
    }
}
