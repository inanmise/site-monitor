package com.sitemonitor.service.keyword.content;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.keyword.KeywordFold;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * İçerik değişimi maskeleri (kw sözleşmesi §7.2, D2) — DOĞRUSAL tarayıcılar, düzenli ifade YOK.
 *
 * <ul>
 *   <li>{@link Mask#DATES}: tarih / saat biçimleri ({@code gg.aa.yyyy}, {@code gg/aa/yyyy}, {@code yyyy-aa-gg} — gün / ay bir ya
 *       da iki hane, yıl dört hane — ve {@code ss:dd}, {@code ss:dd:ss}) ile TR + EN ay / gün adları (katlanmış TAM sözcük;
 *       İngilizce üç harfli kısaltmalar dahil) → {@code @}. Rakamla bitişik parçalar tarih sayılmaz (sınır).</li>
 *   <li>{@link Mask#DIGITS}: kalan her ardışık rakam (0–9) koşusu → {@code #}.</li>
 * </ul>
 * Sıra: önce tarih, sonra rakam (rakam önce maskelense tarih tanınmaz). Doğruluk tablosu {@code ContentFingerprintTest.MASK_TABLE}.
 * Bilinçli bedel: "sat", "sun", "mart", "pazar", "ekim" gibi ad olan sözcükler de maskelenir — maske isteğe bağlıdır ve
 * dinamik sayfada gürültüyü kesmek için vardır.
 */
public final class ContentMasker {

    private ContentMasker() {}

    /** Maske türleri — adlar kalıcıdır ({@code change_mask} CSV). */
    public enum Mask { DIGITS, DATES }

    private static final Set<String> DATE_WORDS = new HashSet<>();

    static {
        String[] words = {
                // TR aylar + günler
                "ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık",
                "pazartesi", "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar",
                // EN aylar + kısaltmalar
                "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
                "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
                // EN günler + kısaltmalar
                "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
                "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun"};
        for (String w : words) DATE_WORDS.add(KeywordFold.fold(w));
    }

    /** Maskeleri uygular (tarih → rakam sırası); boş küme → değişmez. */
    public static String apply(String s, Set<Mask> masks) {
        if (s == null || s.isEmpty() || masks == null || masks.isEmpty()) return s;
        String r = s;
        if (masks.contains(Mask.DATES)) r = maskDates(r);
        if (masks.contains(Mask.DIGITS)) r = maskDigits(r);
        return r;
    }

    /** Her ardışık ASCII rakam koşusu → {@code #}. */
    public static String maskDigits(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (isDigit(c)) {
                while (i < n && isDigit(s.charAt(i))) i++;
                out.append('#');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Tarih / saat biçimleri ve ay / gün adları → {@code @}. Tek geçiş. */
    public static String maskDates(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (isDigit(c) && (i == 0 || !isDigit(s.charAt(i - 1)))) {
                int end = dateEnd(s, i, n);
                if (end > i) {
                    out.append('@');
                    i = end;
                    continue;
                }
                while (i < n && isDigit(s.charAt(i))) out.append(s.charAt(i++));   // tarih değil: rakam koşusu olduğu gibi
                continue;
            }
            if (Character.isLetter(c) && (i == 0 || !Character.isLetter(s.charAt(i - 1)))) {
                int j = i;
                while (j < n && Character.isLetter(s.charAt(j))) j++;
                if (j - i <= 12 && DATE_WORDS.contains(KeywordFold.fold(s.substring(i, j)))) out.append('@');
                else out.append(s, i, j);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** {@code i}'de başlayan tarih / saat parçasının bitiş konumu; parça yoksa {@code i}. */
    static int dateEnd(String s, int i, int n) {
        int d1 = digits(s, i, n);
        int a = i + d1;
        // ss:dd(:ss)
        if ((d1 == 1 || d1 == 2) && a < n && s.charAt(a) == ':') {
            int d2 = digits(s, a + 1, n);
            if (d2 == 2) {
                int b = a + 1 + d2;
                if (b < n && s.charAt(b) == ':' && digits(s, b + 1, n) == 2) {
                    int e = b + 3;
                    return boundary(s, e, n) ? e : i;
                }
                return boundary(s, b, n) ? b : i;
            }
            return i;
        }
        // gg.aa.yyyy / gg/aa/yyyy
        if ((d1 == 1 || d1 == 2) && a < n && (s.charAt(a) == '.' || s.charAt(a) == '/')) {
            char sep = s.charAt(a);
            int d2 = digits(s, a + 1, n);
            int b = a + 1 + d2;
            if ((d2 == 1 || d2 == 2) && b < n && s.charAt(b) == sep && digits(s, b + 1, n) == 4) {
                int e = b + 5;
                return boundary(s, e, n) ? e : i;
            }
            return i;
        }
        // yyyy-aa-gg
        if (d1 == 4 && a < n && s.charAt(a) == '-') {
            int d2 = digits(s, a + 1, n);
            int b = a + 1 + d2;
            if ((d2 == 1 || d2 == 2) && b < n && s.charAt(b) == '-') {
                int d3 = digits(s, b + 1, n);
                if (d3 == 1 || d3 == 2) {
                    int e = b + 1 + d3;
                    return boundary(s, e, n) ? e : i;
                }
            }
            return i;
        }
        return i;
    }

    private static boolean boundary(String s, int e, int n) {
        return e >= n || !isDigit(s.charAt(e));
    }

    /** {@code i}'den başlayan rakam sayısı (en çok 5 — tarih parçaları kısa). */
    private static int digits(String s, int i, int n) {
        int k = 0;
        while (i + k < n && k < 5 && isDigit(s.charAt(i + k))) k++;
        if (k == 5) {   // uzun rakam koşusu tarih parçası değil
            return 5;
        }
        return k;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ── ayar ─────────────────────────────────────────────────────────────────────────────────

    /** Saklı CSV → maske kümesi (tanınmayan parça düşer). */
    public static EnumSet<Mask> parseStored(String csv) {
        EnumSet<Mask> out = EnumSet.noneOf(Mask.class);
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            for (Mask m : Mask.values()) if (m.name().equals(p)) out.add(m);
        }
        return out;
    }

    /**
     * İstek değeri ({@code changeMask}: liste ya da CSV) → kanonik CSV ({@code DATES,DIGITS} sırası enum sırasıdır) ya da
     * {@code null} (boş). Tanınmayan öğe → 400 {@code changeMask}.
     */
    public static String parseRequest(Object raw) {
        if (raw == null) return null;
        Collection<?> items;
        if (raw instanceof Collection<?> c) items = c;
        else items = List.of((Object[]) raw.toString().split(","));
        EnumSet<Mask> set = EnumSet.noneOf(Mask.class);
        for (Object o : items) {
            if (o == null) continue;
            String p = o.toString().trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            Mask hit = null;
            for (Mask m : Mask.values()) if (m.name().equals(p)) hit = m;
            if (hit == null) {
                throw new FieldValidationException("changeMask", Msg.t("Maske DIGITS (rakamlar) ya da DATES (tarih / saat) olmalı.",
                        "The mask must be DIGITS or DATES."));
            }
            set.add(hit);
        }
        return toCsv(set);
    }

    /** Küme → kanonik CSV; boş → {@code null}. */
    public static String toCsv(Set<Mask> masks) {
        if (masks == null || masks.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (Mask m : Mask.values()) if (masks.contains(m)) out.add(m.name());
        return String.join(",", out);
    }

    /** API listesi (kanonik sıra). */
    public static List<String> toList(String csv) {
        List<String> out = new ArrayList<>();
        for (Mask m : parseStored(csv)) out.add(m.name());
        return out;
    }
}
