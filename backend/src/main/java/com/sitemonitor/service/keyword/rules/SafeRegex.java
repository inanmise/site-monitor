package com.sitemonitor.service.keyword.rules;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.sitemonitor.service.keyword.KeywordFold;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anahtar kelime izlemesinin DÜZENLİ İFADE kuralları (kw sözleşmesi §6.2, 2026-11-05) — RE2J (doğrusal zamanlı motor).
 * Kullanıcı deseni {@code java.util.regex}'e ASLA verilmez (geri izleme → ReDoS, kesilemez).
 *
 * <p><b>Kayıt doğrulaması</b> ({@link #compile}): boş değil, ≤ {@value #MAX_PATTERN} karakter, satır sonu / denetim karakteri
 * yok; RE2 sözdizimi (geri başvuru ve ileri / geri bakış yok); BOŞ metinle eşleşen desen reddedilir ({@code a*}, {@code \b},
 * {@code x?} — her konumu sayardı); karmaşıklık iki aşamada sınırlı: derlemeden ÖNCE iç içe sayılı yinelemenin ({@code
 * ((a{1000}){1000}){1000}}) program boyutu kestirimi ≤ {@value #MAX_ESTIMATE} (derlemenin kendisi milyonlarca komut ve
 * yüzlerce MB üretirdi), derlemeden SONRA gerçek program boyutu ≤ {@value #MAX_PROGRAM}.
 *
 * <p><b>Neden program boyutu ve zaman bütçesi</b> (ölçüm 2026-11-05, RE2J 1.8): RE2J doğrusaldır ama maliyet metin uzunluğu
 * × ETKİN durum sayısıdır — 741 komutluk bir desen 512 K karakterde tek aramada 2,7 sn sürdü; {@code a[^z]*z|a} gibi bir desen
 * her eşleşmede metnin sonuna kadar tarar, art arda aramalar karesel olur (512 K 'a' üzerinde 200 arama 10 sn). Bu yüzden:
 * program ≤ {@value #MAX_PROGRAM} komut (tek arama ≈ 1 sn altında) ve sayım döngüsü KURAL BAŞINA zaman bütçesiyle
 * ({@link #count(Compiled, String, int, long)}) aramalar ARASINDA kesilir; kesilen sayım alt sınırdır, sonuç
 * {@code scanTruncated} taşır (kural notu {@code SCAN_TRUNCATED}).
 *
 * <p><b>Türkçe harf</b>: büyük/küçük harf duyarsız aramada metin {@link KeywordFold#fold} ile, desen {@link #foldPattern} ile
 * katlanır ve {@link Pattern#CASE_INSENSITIVE} eklenir (İ/I/ı/i → i; diğer harfler RE2'nin basit katlamasıyla eşleşir).
 */
public final class SafeRegex {

    private SafeRegex() {}

    /** Desen uzunluğu üst sınırı. */
    public static final int MAX_PATTERN = 200;
    /** Derlenmiş program boyutu üst sınırı (RE2J {@link Pattern#programSize()}). */
    public static final int MAX_PROGRAM = 300;
    /** Derleme öncesi program boyutu kestirimi üst sınırı (iç içe yinelemeyi derlemeden önce keser). */
    public static final int MAX_ESTIMATE = 5_000;
    /** Aranan metin üst sınırı (karakter) — ötesi aranmaz ({@code SCAN_TRUNCATED}). */
    public static final int REGEX_SCAN_CAP = 512 * 1024;
    /** Sayım üst sınırı. */
    public static final int MAX_COUNT = 10_000;
    /** Kural başına varsayılan sayım zaman bütçesi (ms) — aramalar arasında denetlenir. */
    public static final long DEFAULT_BUDGET_MS = 1_000;

    /** Boş eşleşme yoklaması: bunlardan birinde sıfır uzunluklu eşleşme bulan desen reddedilir. */
    private static final String[] EMPTY_PROBES = {"", "a", " ", "a b", "ab\n12 xy", "İı", "<p>x</p>", "-_."};
    /** Derleme önbelleği (kontrol başına yeniden derleme olmasın); dolunca boşaltılır (sınırlı bellek). */
    private static final int CACHE_MAX = 512;
    private static final ConcurrentHashMap<String, Compiled> CACHE = new ConcurrentHashMap<>();

    /** Derlenmiş desen. */
    public record Compiled(String source, Pattern pattern, boolean caseSensitive) {}

    /** Sayım sonucu: adet, ilk eşleşmenin [başlangıç, bitiş) konumu (yoksa -1), tavana takıldı mı, tarama kesildi mi. */
    public record Count(int count, int firstStart, int firstEnd, boolean capped, boolean scanTruncated, boolean timedOut) {
        static final Count EMPTY = new Count(0, -1, -1, false, false, false);
    }

    /** Kayıt doğrulama hatası — iki dilde neden + (varsa) sorunlu parça. */
    public static final class RegexError extends IllegalArgumentException {
        private final String reasonTr;
        private final String reasonEn;

        RegexError(String reasonTr, String reasonEn) {
            super(reasonTr);
            this.reasonTr = reasonTr;
            this.reasonEn = reasonEn;
        }

        public String reasonTr() { return reasonTr; }
        public String reasonEn() { return reasonEn; }
    }

    // ── Derleme ──────────────────────────────────────────────────────────────────────────────

    /**
     * Kayıt anı derlemesi — geçersizse {@link RegexError}. Başarılı sonuç önbelleğe alınır (çalışma anı aynı deseni
     * yeniden derlemez).
     */
    public static Compiled compile(String pattern, boolean caseSensitive) {
        if (pattern == null || pattern.isBlank()) {
            throw new RegexError("Düzenli ifade boş olamaz.", "The regular expression cannot be empty.");
        }
        if (pattern.length() > MAX_PATTERN) {
            throw new RegexError("Düzenli ifade en fazla " + MAX_PATTERN + " karakter olabilir.",
                    "The regular expression can be at most " + MAX_PATTERN + " characters.");
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\r' || c == '\n' || (Character.isISOControl(c) && c != '\t')) {
                throw new RegexError("Düzenli ifade satır sonu ya da denetim karakteri içeremez (" + (i + 1) + ". karakter).",
                        "The regular expression cannot contain a line break or control character (character " + (i + 1) + ").");
            }
        }
        String key = (caseSensitive ? "1:" : "0:") + pattern;
        Compiled cached = CACHE.get(key);
        if (cached != null) return cached;

        if (estimate(pattern) > MAX_ESTIMATE) {
            throw tooComplex();
        }
        Pattern p = compileRaw(pattern, caseSensitive);
        if (p.programSize() > MAX_PROGRAM) {
            throw tooComplex();
        }
        rejectEmptyMatch(p, caseSensitive);
        Compiled c = new Compiled(pattern, p, caseSensitive);
        if (CACHE.size() >= CACHE_MAX) CACHE.clear();
        CACHE.put(key, c);
        return c;
    }

    /** Saklı desen için hoşgörülü derleme: geçersizse {@code null} (çağıran kuralı "sayılamadı" sayar). Asla fırlatmaz. */
    public static Compiled compileStored(String pattern, boolean caseSensitive) {
        try {
            return compile(pattern, caseSensitive);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Doğrulama: geçerliyse {@code null}, değilse {@link RegexError} (alan hatası iletisi çağıranda kurulur). */
    public static RegexError validate(String pattern, boolean caseSensitive) {
        try {
            compile(pattern, caseSensitive);
            return null;
        } catch (RegexError e) {
            return e;
        }
    }

    private static RegexError tooComplex() {
        return new RegexError("Düzenli ifade çok karmaşık (iç içe ya da çok büyük yineleme sayısı). Deseni sadeleştirin — "
                + "ör. {1000} yerine daha küçük bir sayı, iç içe {n} yinelemesi yerine tek yineleme.",
                "The regular expression is too complex (nested or very large repetition counts). Simplify it — e.g. a "
                + "smaller number than {1000}, a single repetition instead of nested {n} repetitions.");
    }

    private static Pattern compileRaw(String pattern, boolean caseSensitive) {
        if (caseSensitive) return compileOrThrow(pattern, 0);
        String folded = foldPattern(pattern);
        try {
            return Pattern.compile(folded, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException foldFailed) {
            // Katlama bir karakter sınıfı aralığını bozduysa (beklenmez — aralık uçları katlanmaz) özgün desen kullanılır.
            return compileOrThrow(pattern, Pattern.CASE_INSENSITIVE);
        }
    }

    private static Pattern compileOrThrow(String pattern, int flags) {
        try {
            return Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            String frag = e.getPattern();
            int at = frag == null || frag.isEmpty() ? -1 : pattern.indexOf(frag);
            String where = at >= 0 ? " (" + (at + 1) + ". karakter yakınında: «" + clip(frag) + "»)" : "";
            String whereEn = at >= 0 ? " (near character " + (at + 1) + ": «" + clip(frag) + "»)" : "";
            String desc = e.getDescription() == null ? "" : e.getDescription();
            throw new RegexError("Düzenli ifade geçersiz" + where + ": " + desc + ". RE2 sözdizimi kullanılır — geri başvuru "
                    + "(\\1) ve ileri / geri bakış ((?=…), (?<=…)) desteklenmez.",
                    "Invalid regular expression" + whereEn + ": " + desc + ". RE2 syntax is used — back-references (\\1) "
                    + "and look-arounds ((?=…), (?<=…)) are not supported.");
        } catch (RuntimeException e) {
            throw new RegexError("Düzenli ifade derlenemedi.", "The regular expression could not be compiled.");
        }
    }

    private static String clip(String s) {
        return s.length() > 20 ? s.substring(0, 20) + "…" : s;
    }

    /** Desen boş metinle (sıfır uzunlukta) eşleşebiliyor mu — yoklama metinleri üzerinde, sınırlı arama sayısıyla. */
    private static void rejectEmptyMatch(Pattern p, boolean caseSensitive) {
        for (String probe : EMPTY_PROBES) {
            String hay = caseSensitive ? probe : KeywordFold.fold(probe);
            Matcher m = p.matcher(hay);
            int pos = 0;
            for (int guard = 0; guard <= hay.length() + 1 && pos <= hay.length(); guard++) {
                if (!m.find(pos)) break;
                if (m.end() == m.start()) {
                    throw new RegexError("Düzenli ifade boş metinle eşleşebiliyor (ör. a*, x?, \\b, ^) — her konumu sayardı. "
                            + "En az bir karakter tüketen bir desen yazın (ör. a+).",
                            "The regular expression can match empty text (e.g. a*, x?, \\b, ^) — it would count every position. "
                            + "Write a pattern that consumes at least one character (e.g. a+).");
                }
                pos = m.end();
            }
        }
    }

    // ── Türkçe katlama ───────────────────────────────────────────────────────────────────────

    private static boolean foldLetter(char c) {
        return c == 'I' || c == 'i' || c == 'İ' || c == 'ı';
    }

    /**
     * Desendeki DÜZ İ / I / ı / i harflerini "i" yapar; kaçış dizileri ({@code \…}), {@code \p{…}} / {@code \P{…}} /
     * {@code \x{…}}, {@code \Q…\E}, grup bayrak / ad önekleri ({@code (?i)}, {@code (?P<ad>}) ve karakter sınıfı ARALIK
     * uçları ({@code [I-K]}) olduğu gibi kalır. Doğrusal, tek geçiş. Doğruluk tablosu {@code SafeRegexTest.FOLD_TABLE}.
     */
    public static String foldPattern(String p) {
        if (p == null || p.isEmpty()) return p;
        int n = p.length();
        StringBuilder out = new StringBuilder(n);
        boolean inClass = false;
        int classBody = -1;   // sınıf gövdesinin ilk karakterinin konumu
        int i = 0;
        while (i < n) {
            char c = p.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) { out.append(c); i++; continue; }
                char d = p.charAt(i + 1);
                int end;
                if (d == 'Q') {
                    int e = p.indexOf("\\E", i + 2);
                    end = e < 0 ? n : e + 2;
                } else if ((d == 'p' || d == 'P' || d == 'x') && i + 2 < n && p.charAt(i + 2) == '{') {
                    int close = p.indexOf('}', i + 3);
                    end = close < 0 ? n : close + 1;
                } else {
                    end = i + 2;
                }
                out.append(p, i, end);
                i = end;
                continue;
            }
            if (!inClass && c == '[') {
                inClass = true;
                out.append(c);
                i++;
                if (i < n && p.charAt(i) == '^') { out.append('^'); i++; }
                classBody = i;
                if (i < n && p.charAt(i) == ']') { out.append(']'); i++; }   // baştaki ']' sınıfın bir üyesidir
                continue;
            }
            if (inClass) {
                if (c == '[' && i + 1 < n && p.charAt(i + 1) == ':') {   // POSIX sınıfı [:alpha:] — olduğu gibi
                    int close = p.indexOf(":]", i + 2);
                    int end = close < 0 ? n : close + 2;
                    out.append(p, i, end);
                    i = end;
                    continue;
                }
                if (c == ']') { inClass = false; out.append(c); i++; continue; }
                if (foldLetter(c)) {
                    boolean rangeStart = i + 2 < n && p.charAt(i + 1) == '-' && p.charAt(i + 2) != ']';
                    boolean rangeEnd = i - 1 > classBody && p.charAt(i - 1) == '-' && p.charAt(i - 2) != '\\';
                    out.append(rangeStart || rangeEnd ? c : 'i');
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }
            if (c == '(' && i + 1 < n && p.charAt(i + 1) == '?') {
                out.append("(?");
                i += 2;
                while (i < n && (Character.isLetter(p.charAt(i)) && p.charAt(i) != 'P' || p.charAt(i) == '-')) {
                    out.append(p.charAt(i));   // bayrak harfleri (i, m, s, U) — katlanmaz
                    i++;
                }
                if (i < n && (p.charAt(i) == '<' || (p.charAt(i) == 'P' && i + 1 < n && p.charAt(i + 1) == '<'))) {
                    int close = p.indexOf('>', i);
                    int end = close < 0 ? n : close + 1;
                    out.append(p, i, end);   // grup adı — katlanmaz
                    i = end;
                }
                continue;
            }
            out.append(foldLetter(c) ? 'i' : c);
            i++;
        }
        return out.toString();
    }

    // ── Karmaşıklık kestirimi ────────────────────────────────────────────────────────────────

    /**
     * Derlemeden ÖNCE kaba program boyutu kestirimi (doğrusal tarayıcı): her atom 1, grup = seçeneklerin toplamı, sayılı
     * yineleme {@code {n,m}} çarpar ({@code {n,}} → n+1). İç içe yinelemenin çarpımını yakalamak için yeterli; sözdizimi
     * hatalarını RE2J'ye bırakır. Doygun aritmetik.
     */
    static long estimate(String p) {
        if (p == null) return 0;
        final long cap = Long.MAX_VALUE / 8;
        Deque<long[]> stack = new ArrayDeque<>();
        long[] f = new long[3];   // [seçenek toplamı, dizi toplamı, son atom]
        int n = p.length();
        int i = 0;
        while (i < n) {
            char c = p.charAt(i);
            switch (c) {
                case '\\' -> {
                    int end;
                    long cost = 1;
                    if (i + 1 < n && p.charAt(i + 1) == 'Q') {
                        int e = p.indexOf("\\E", i + 2);
                        end = e < 0 ? n : e + 2;
                        cost = Math.max(1, (e < 0 ? n : e) - (i + 2));
                    } else if (i + 2 < n && (p.charAt(i + 1) == 'p' || p.charAt(i + 1) == 'P' || p.charAt(i + 1) == 'x')
                            && p.charAt(i + 2) == '{') {
                        int close = p.indexOf('}', i + 3);
                        end = close < 0 ? n : close + 1;
                    } else {
                        end = Math.min(n, i + 2);
                    }
                    f[1] = sat(f[1] + f[2], cap);
                    f[2] = cost;
                    i = end;
                }
                case '[' -> {
                    int j = i + 1;
                    if (j < n && p.charAt(j) == '^') j++;
                    if (j < n && p.charAt(j) == ']') j++;
                    while (j < n && p.charAt(j) != ']') {
                        if (p.charAt(j) == '\\') j++;
                        else if (p.charAt(j) == '[' && j + 1 < n && p.charAt(j + 1) == ':') {
                            int close = p.indexOf(":]", j + 2);
                            j = close < 0 ? n : close + 1;
                        }
                        j++;
                    }
                    f[1] = sat(f[1] + f[2], cap);
                    f[2] = 1;
                    i = Math.min(n, j + 1);
                }
                case '(' -> {
                    int j = i + 1;
                    boolean flagOnly = false;
                    if (j < n && p.charAt(j) == '?') {
                        j++;
                        while (j < n && (Character.isLetter(p.charAt(j)) && p.charAt(j) != 'P' || p.charAt(j) == '-')) j++;
                        if (j < n && p.charAt(j) == ')') flagOnly = true;   // (?i) — grup değil
                        else if (j < n && p.charAt(j) == ':') j++;
                        else if (j < n && (p.charAt(j) == '<' || p.charAt(j) == 'P')) {
                            int close = p.indexOf('>', j);
                            j = close < 0 ? n : close + 1;
                        }
                    }
                    if (flagOnly) { i = j + 1; break; }
                    stack.push(f);
                    f = new long[3];
                    i = j;
                }
                case ')' -> {
                    if (stack.isEmpty()) {   // dengesiz — RE2J hatasını bildirir; burada atom say
                        f[1] = sat(f[1] + f[2], cap);
                        f[2] = 1;
                    } else {
                        long group = sat(sat(f[0] + f[1], cap) + f[2] + 2, cap);
                        f = stack.pop();
                        f[1] = sat(f[1] + f[2], cap);
                        f[2] = group;
                    }
                    i++;
                }
                case '|' -> {
                    f[0] = sat(sat(f[0] + f[1], cap) + f[2], cap);
                    f[1] = 0;
                    f[2] = 0;
                    i++;
                }
                case '*', '+', '?' -> {
                    f[2] = sat(f[2] + 1, cap);
                    i++;
                    if (i < n && p.charAt(i) == '?') i++;   // tembel niceleyici
                }
                case '{' -> {
                    int j = i + 1;
                    long lo = 0, hi = -1;
                    int s = j;
                    while (j < n && j - s < 7 && Character.isDigit(p.charAt(j))) j++;
                    boolean ok = j > s;
                    if (ok) lo = Long.parseLong(p.substring(s, j));
                    boolean open = false;
                    if (ok && j < n && p.charAt(j) == ',') {
                        j++;
                        int s2 = j;
                        while (j < n && j - s2 < 7 && Character.isDigit(p.charAt(j))) j++;
                        if (j > s2) hi = Long.parseLong(p.substring(s2, j));
                        else open = true;
                    }
                    if (ok && j < n && p.charAt(j) == '}') {
                        long mult = hi >= 0 ? Math.max(lo, hi) : (open ? lo + 1 : lo);
                        f[2] = sat(sat(Math.max(1, f[2]) * Math.max(1, mult), cap) + 1, cap);
                        i = j + 1;
                        if (i < n && p.charAt(i) == '?') i++;
                    } else {   // niceleyici değil — düz '{'
                        f[1] = sat(f[1] + f[2], cap);
                        f[2] = 1;
                        i++;
                    }
                }
                default -> {
                    f[1] = sat(f[1] + f[2], cap);
                    f[2] = 1;
                    i += Character.isHighSurrogate(c) && i + 1 < n ? 2 : 1;
                }
            }
        }
        long total = sat(sat(f[0] + f[1], cap) + f[2], cap);
        while (!stack.isEmpty()) {   // kapanmamış gruplar — RE2J reddeder; kestirime katılır
            long[] g = stack.pop();
            total = sat(sat(sat(g[0] + g[1], cap) + g[2], cap) + total, cap);
        }
        return total;
    }

    private static long sat(long v, long cap) {
        return v < 0 || v > cap ? cap : v;
    }

    // ── Sayım ────────────────────────────────────────────────────────────────────────────────

    /** Varsayılan bütçeyle sayım ({@link #DEFAULT_BUDGET_MS}, tavan {@link #MAX_COUNT}). */
    public static Count count(Compiled c, String text) {
        return count(c, text, MAX_COUNT, DEFAULT_BUDGET_MS * 1_000_000L);
    }

    /**
     * Örtüşmesiz eşleşme sayımı: metin ilk {@value #REGEX_SCAN_CAP} karakter (duyarsızda katlanmış), en çok {@code max}
     * eşleşme, sıfır uzunluklu eşleşme sayılmaz (bir konum ilerlenir), zaman bütçesi ({@code budgetNanos}) aramalar
     * ARASINDA denetlenir — en az bir arama her zaman yapılır. Asla fırlatmaz.
     */
    public static Count count(Compiled c, String text, int max, long budgetNanos) {
        if (c == null || text == null || text.isEmpty() || max < 1) return Count.EMPTY;
        boolean truncated = text.length() > REGEX_SCAN_CAP;
        String hay = text;
        if (truncated) {
            int cut = REGEX_SCAN_CAP;
            if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;   // vekil çiftini bölme
            hay = text.substring(0, cut);
        }
        if (!c.caseSensitive()) hay = KeywordFold.fold(hay);
        try {
            Matcher m = c.pattern().matcher(hay);
            long deadline = System.nanoTime() + Math.max(0, budgetNanos);
            int count = 0;
            int first = -1;
            int firstEnd = -1;
            int pos = 0;
            int len = hay.length();
            boolean timedOut = false;
            while (count < max && pos <= len) {
                if (!m.find(pos)) break;
                int s = m.start();
                int e = m.end();
                if (e == s) {
                    pos = s + 1;
                } else {
                    if (first < 0) { first = s; firstEnd = e; }
                    count++;
                    pos = e;
                }
                if (count < max && pos <= len && System.nanoTime() > deadline) {
                    timedOut = true;
                    break;
                }
            }
            return new Count(count, first, firstEnd, count >= max, truncated || timedOut, timedOut);
        } catch (RuntimeException e) {
            return new Count(0, -1, -1, false, true, false);
        }
    }
}
