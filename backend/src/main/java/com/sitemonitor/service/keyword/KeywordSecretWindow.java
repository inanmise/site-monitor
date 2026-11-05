package com.sitemonitor.service.keyword;

import com.sitemonitor.config.RequestLoggingFilter;
import com.sitemonitor.service.SecretMask;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sayfa metninden kesilen pencerelerin (kontrol {@code snippet}'i, canlı eşleşme bağlamları) sır maskesi — kw son inceleme D-1 /
 * D-9 (2026-11-05). İki kural:
 * <ol>
 *   <li><b>Pencere sırrı bölmez</b>: pencere kenarına denk gelen sır (izlemenin parolası / başlık değeri ya da kodlanmış hâli)
 *       pencere o sırrı tam kapsayacak kadar genişletilir ({@link #expand}) — yarım bir sır maskeden kaçmasın.</li>
 *   <li><b>Maske birleşik metne uygulanır</b>: önce / eşleşme / sonra parçalarının BİRLEŞİMİ maskelenir; parça parça maske
 *       birleşiminkiyle aynıysa parçalar döner, değilse (sır ya da {@code "password":"…"} kalıbı parça sınırını aşıyordu) tek
 *       maskeli parça döner ({@link Pieces#joined()}) — sınırda bölünen sır asla açık kalmaz.</li>
 * </ol>
 * Maske = {@link RequestLoggingFilter#redactSensitiveFields} (hassas alan adları) + {@link SecretMask#maskValues} (izlemenin sır
 * değerleri; Basic auth'un base64 kimliği dahil — çağıranın listesi). Saf; döngüler sır sayısı × pencere ile sınırlı.
 */
public final class KeywordSecretWindow {

    private KeywordSecretWindow() {}

    /** Genişletmenin tavanı: bundan uzun bir değer sır sayılmaz (pencere sınırsız büyümesin). */
    static final int MAX_SECRET_CHARS = 512;
    /** {@link SecretMask#maskValues} ile aynı alt sınır (daha kısa değer maskelenmez → genişletmeye de girmez). */
    static final int MIN_SECRET_CHARS = 4;
    private static final int MAX_PASSES = 3;

    /** Maskeli parçalar; {@code joined} → sır parça sınırını aşıyordu, birleşik metin {@code before}'da, öteki iki parça boş. */
    public record Pieces(String before, String match, String after, boolean joined) {}

    /** Hassas alan adları + izlemenin sır değerleri maskelenmiş metin. */
    public static String maskText(String s, Collection<String> secrets) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String t = RequestLoggingFilter.redactSensitiveFields(s);
        return secrets == null || secrets.isEmpty() ? t : SecretMask.maskValues(t, secrets);
    }

    /**
     * {@code [b, a)} penceresini, kenarına denk gelen her sır geçişini TAM kapsayacak biçimde genişletir. @return {@code {b, a}}
     */
    public static int[] expand(String text, int b, int a, Collection<String> secrets) {
        if (text == null || secrets == null || secrets.isEmpty()) return new int[] { b, a };
        List<String> variants = variants(secrets);
        int nb = Math.max(0, b), na = Math.min(text.length(), a);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int pb = nb, pa = na;
            for (String v : variants) {
                int len = v.length();
                // Arama yalnız pencere ± sır uzunluğu bölgesinde (tüm sayfada değil — maliyet pencereyle sınırlı)
                int lo = Math.max(0, nb - len + 1);
                int hi = Math.min(text.length(), na + len - 1);
                if (hi - lo < len) continue;
                String region = text.substring(lo, hi);
                int from = 0;
                while (from <= region.length() - len) {
                    int rel = region.indexOf(v, from);
                    if (rel < 0) break;
                    int idx = lo + rel;
                    int end = idx + len;
                    if (idx < nb && end > nb) nb = idx;
                    if (idx < na && end > na) na = end;
                    from = rel + 1;
                }
            }
            if (nb == pb && na == pa) break;
        }
        return new int[] { nb, na };
    }

    /** {@code [b, s)} önce, {@code [s, e)} eşleşme, {@code [e, a)} sonra — birleşik maske (sınıf belgesi kural 2). */
    public static Pieces mask(String text, int b, int s, int e, int a, Collection<String> secrets) {
        String before = text.substring(b, s);
        String match = text.substring(s, e);
        String after = text.substring(e, a);
        String mb = maskText(before, secrets);
        String mm = maskText(match, secrets);
        String ma = maskText(after, secrets);
        String whole = maskText(before + match + after, secrets);
        if (whole.equals(mb + mm + ma)) return new Pieces(mb, mm, ma, false);
        return new Pieces(whole, "", "", true);
    }

    /** Sır değerlerinin pencerede görünebileceği biçimleri ({@link SecretMask#maskValues} ile aynı aile): ham, URL, JSON kaçışlı. */
    static List<String> variants(Collection<String> secrets) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : secrets) {
            if (v == null || v.length() < MIN_SECRET_CHARS || v.length() > MAX_SECRET_CHARS) continue;
            out.add(v);
            try {
                String enc = URLEncoder.encode(v, StandardCharsets.UTF_8);
                out.add(enc);
                out.add(enc.replace("+", "%20"));
            } catch (RuntimeException ignored) { /* ham biçim yine kapsanıyor */ }
            out.add(v.replace("\\", "\\\\").replace("\"", "\\\""));
        }
        return new ArrayList<>(out);
    }
}
