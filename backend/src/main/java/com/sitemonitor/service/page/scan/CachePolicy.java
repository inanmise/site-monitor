package com.sitemonitor.service.page.scan;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bir yanıtın tarayıcı önbelleği davranışı (2026-11-15, kaynak analizi algoritma 4 — "önbelleğe alınmıyor" bulgusunun
 * doğruluğu). SAF: ağ / veritabanı yok, tablo testleriyle sabitlenir. Başlık değerleri DOĞRUSAL taranır (düzenli ifade yok;
 * tırnak içindeki virgül ayırmaz), yönerge sayısı {@value #MAX_DIRECTIVES} ve okunan karakter {@value #MAX_HEADER_CHARS} ile
 * sınırlı.
 *
 * <p><b>Sınıflar</b> ({@code cache_class}, adlar KALICI):
 * <ul>
 *   <li>{@link #NO_STORE}: {@code Cache-Control}'ün HERHANGİ bir yerinde {@code no-store} — tarayıcı hiç saklamaz.</li>
 *   <li>{@link #REVALIDATE}: saklanır ama her ziyarette sunucuya sorulur — çıplak {@code no-cache}, {@code max-age=0} ya da süresi
 *       geçmiş {@code Expires} VE bir doğrulayıcı ({@code ETag} / {@code Last-Modified}); yanıt ucuz bir 304'tür. Ömür bilgisi
 *       hiç yokken yalnız {@code ETag} varsa da bu sınıftır (tarayıcı her seferinde koşullu sorar).</li>
 *   <li>{@link #HEURISTIC}: ömür bilgisi yok ({@code max-age} / {@code Expires} / {@code no-cache} hiçbiri), ama
 *       {@code Last-Modified} okunabiliyor — tarayıcı ömrü kendisi tahmin eder (RFC 9111 §4.2.2: son değişiklikten bu yana geçen
 *       sürenin yaklaşık %10'u; {@code heuristicS} yalnız gösterim içindir).</li>
 *   <li>{@link #NONE}: ne ömür ne doğrulayıcı (başlıksız), ya da doğrulayıcısız {@code no-cache} / {@code max-age=0} / süresi
 *       geçmiş {@code Expires} — her ziyarette tamamı yeniden iner.</li>
 *   <li>{@link #SHORT}: 0 &lt; ömür &lt; {@value #STORED_MIN_S} sn.</li>
 *   <li>{@link #STORED}: ömür ≥ {@value #STORED_MIN_S} sn.</li>
 * </ul>
 *
 * <p><b>Kurallar:</b> {@code s-maxage} paylaşılan önbellek içindir, tarayıcı için yok sayılır. {@code no-cache="alan"} (alan
 * biçimi) çıplak yönerge DEĞİLDİR. {@code Pragma: no-cache} YALNIZ {@code Cache-Control} yokken (ya da boşken) sayılır.
 * {@code max-age} birden çok kez verilmişse İLKİ geçerlidir (RFC 9111 §4.2.1); sayısal olmayan {@code max-age} bayat sayılır
 * (ömür 0). {@code max-age}, {@code Expires}'ın önüne geçer. Bozuk {@code Expires} ("0", "-1") süresi geçmiş sayılır.
 * {@code Last-Modified} okunamıyorsa (RFC 1123 dışı) yalnız doğrulayıcıdır → {@link #REVALIDATE}.
 *
 * <p><b>Önbellek puanına katkı</b> ({@link #credit}): {@code stored} ≥ 1 gün 1,0, ≥ 1 sa 0,5; {@code revalidate} 0,5;
 * {@code heuristic} 0,5; {@code short} doğrulayıcı varsa 0,5 (süresi dolunca ucuz 304 — {@code max-age=0}'dan kötü
 * puanlanamaz), yoksa 0; {@code none} / {@code no_store} 0.
 *
 * <p><b>İkinci ziyaret</b> ({@link #repeatVisit}): {@code stored} istek yok, gövde yok; {@code heuristic} tahmini ömür ≥ 1 sa
 * ise istek yok, değilse koşullu istek (gövde yok); {@code revalidate} koşullu istek, gövde yok; {@code short} istek, gövde
 * yalnız doğrulayıcı yoksa; {@code none} / {@code no_store} istek + tüm gövde.
 */
public final class CachePolicy {

    public static final String STORED = "stored", SHORT = "short", REVALIDATE = "revalidate", HEURISTIC = "heuristic",
            NONE = "none", NO_STORE = "no_store";
    /** Sınıflar (gösterim sırası: iyiden kötüye). */
    public static final List<String> CLASSES = List.of(STORED, SHORT, REVALIDATE, HEURISTIC, NONE, NO_STORE);

    /** {@link #STORED} alt sınırı (sn) — {@code PageResourceAnalyzer.CACHE_OK_S} ile aynı. */
    public static final long STORED_MIN_S = 3_600;
    /** Tam puan alt sınırı (sn) — {@code PageResourceAnalyzer.CACHE_GOOD_S} ile aynı. */
    public static final long LONG_S = 86_400;
    /** RFC 9111 §1.2.2: daha büyük delta-seconds bu değere indirilir. */
    static final long MAX_DELTA_S = 2_147_483_648L;
    /** Sezgisel ömür payı (son değişiklikten bu yana geçen sürenin %10'u). */
    static final long HEURISTIC_DIVISOR = 10;
    /** Başlık başına okunan en çok karakter ve yönerge sayısı (doğrusal tarama; fazlası okunmaz). */
    static final int MAX_HEADER_CHARS = 2048, MAX_DIRECTIVES = 64;

    /**
     * @param cacheClass sınıf ({@link #CLASSES})
     * @param ttlS       eski {@code cache_ttl_s} anlamıyla ömür: {@code no-store} / çıplak {@code no-cache} (ve {@code Cache-Control}
     *                   yokken {@code Pragma: no-cache}) → -1, {@code max-age} / {@code Expires − Date} → saniye (≥ 0), ömür bilgisi
     *                   yoksa null
     * @param heuristicS {@link #HEURISTIC} sınıfında tarayıcının tahmini ömrü (sn, yalnız gösterim); diğerlerinde null
     * @param validator  {@code ETag} ya da {@code Last-Modified} var mı
     */
    public record Policy(String cacheClass, Long ttlS, Long heuristicS, boolean validator) {}

    private CachePolicy() {}

    /**
     * Yanıt başlıklarından sınıf. Tüm parametreler null olabilir; {@code date} yoksa / bozuksa {@code nowMs} referanstır.
     */
    public static Policy classify(String cacheControl, String pragma, String expires, String date, String etag,
                                  String lastModified, long nowMs) {
        boolean hasEtag = present(etag);
        boolean hasLm = present(lastModified);
        boolean validator = hasEtag || hasLm;

        boolean noStore = false, noCache = false;
        Long maxAge = null;
        boolean ccPresent = present(cacheControl);
        if (ccPresent) {
            for (String[] d : directives(cacheControl)) {
                String name = d[0];
                if (name.equals("no-store")) noStore = true;
                else if (name.equals("no-cache") && d[1] == null) noCache = true;   // no-cache="alan" çıplak değil
                else if (name.equals("max-age") && maxAge == null) maxAge = deltaSeconds(d[1]);
            }
        } else if (present(pragma)) {
            for (String[] d : directives(pragma)) {
                if (d[0].equals("no-cache") && d[1] == null) noCache = true;
            }
        }

        if (noStore) return new Policy(NO_STORE, -1L, null, validator);
        if (noCache) return new Policy(validator ? REVALIDATE : NONE, -1L, null, validator);

        long ref = parseDate(date, nowMs);
        Long ttl = maxAge;
        if (ttl == null && present(expires)) {
            long exp = parseDate(expires, Long.MIN_VALUE);
            ttl = exp == Long.MIN_VALUE ? 0L : Math.max(0L, (exp - ref) / 1000L);
        }
        if (ttl != null) {
            if (ttl >= STORED_MIN_S) return new Policy(STORED, ttl, null, validator);
            if (ttl > 0) return new Policy(SHORT, ttl, null, validator);
            return new Policy(validator ? REVALIDATE : NONE, ttl, null, validator);
        }
        // Ömür bilgisi hiç yok
        if (hasLm) {
            long lm = parseDate(lastModified, Long.MIN_VALUE);
            if (lm != Long.MIN_VALUE) {
                long heuristic = Math.max(0L, (ref - lm) / 1000L / HEURISTIC_DIVISOR);
                return new Policy(HEURISTIC, null, heuristic, true);
            }
        }
        return new Policy(validator ? REVALIDATE : NONE, null, null, validator);
    }

    /** Yalnız eski {@code cache_ttl_s} değeri (bkz. {@link Policy#ttlS()}). */
    public static Long ttl(String cacheControl, String pragma, String expires, String date, long nowMs) {
        return classify(cacheControl, pragma, expires, date, null, null, nowMs).ttlS();
    }

    /** Puan katkısı (0 / 0,5 / 1). Bilinmeyen sınıf → 0. */
    public static double credit(String cacheClass, Long ttlS, boolean validator) {
        if (cacheClass == null) return 0;
        return switch (cacheClass) {
            case STORED -> ttlS != null && ttlS >= LONG_S ? 1.0 : 0.5;
            case REVALIDATE, HEURISTIC -> 0.5;
            case SHORT -> validator ? 0.5 : 0;
            default -> 0;
        };
    }

    /**
     * Nesnenin sınıfı: kaydedilmişse o; yoksa (eski kayıt / sınıfsız test nesnesi) yalnız ömürden ESKİ kural — null / ≤ 0 →
     * {@link #NONE}, &lt; 1 sa → {@link #SHORT}, aksi {@link #STORED} (doğrulayıcı bilinmez sayılır).
     */
    public static String classOf(ScanItem i) {
        if (i.cacheClass != null) return i.cacheClass;
        Long ttl = i.cacheTtlS;
        if (ttl == null || ttl <= 0) return NONE;
        return ttl < STORED_MIN_S ? SHORT : STORED;
    }

    /** Nesnenin puan katkısı — sınıfsız nesnede eski ömür kuralıyla birebir (doğrulayıcı yok sayılır). */
    public static double creditOf(ScanItem i) {
        return credit(classOf(i), i.cacheTtlS, i.cacheClass != null && i.validator);
    }

    /** İkinci ziyarette: istek gerekir mi, tüm gövde yeniden iner mi. */
    public record Repeat(boolean request, boolean body) {}

    /** İkinci ziyaret kuralı (sınıf açıklamasına bakın); sınıfsız nesnede eski kural: ≥ 1 sa taze ya da doğrulayıcılı → gövde yok. */
    public static Repeat repeatVisit(ScanItem i) {
        if (i.cacheClass == null) {
            boolean fresh = i.cacheTtlS != null && i.cacheTtlS >= STORED_MIN_S;
            return new Repeat(!fresh, !fresh && !i.validator);
        }
        return switch (i.cacheClass) {
            case STORED -> new Repeat(false, false);
            case HEURISTIC -> new Repeat(i.cacheHeuristicS == null || i.cacheHeuristicS < STORED_MIN_S, false);
            case REVALIDATE -> new Repeat(true, false);
            case SHORT -> new Repeat(true, !i.validator);
            default -> new Repeat(true, true);
        };
    }

    // ── Ayrıştırma (doğrusal) ───────────────────────────────────────────────────────────

    /**
     * Virgülle ayrılmış yönergeler {ad (küçük harf), değer (tırnaksız; değer yoksa null)} — tırnak içindeki virgül ayırmaz.
     * İlk {@value #MAX_HEADER_CHARS} karakter, en çok {@value #MAX_DIRECTIVES} yönerge.
     */
    static List<String[]> directives(String header) {
        List<String[]> out = new ArrayList<>();
        if (header == null) return out;
        int n = Math.min(header.length(), MAX_HEADER_CHARS);
        int start = 0;
        boolean quoted = false;
        for (int k = 0; k <= n && out.size() < MAX_DIRECTIVES; k++) {
            char c = k < n ? header.charAt(k) : ',';
            if (c == '"') quoted = !quoted;
            else if (c == ',' && (!quoted || k == n)) {
                String[] d = directive(header, start, k);
                if (d != null) out.add(d);
                start = k + 1;
                quoted = false;
            }
        }
        return out;
    }

    private static String[] directive(String s, int from, int to) {
        int eq = -1;
        for (int k = from; k < to; k++) {
            if (s.charAt(k) == '=') { eq = k; break; }
        }
        String name = s.substring(from, eq < 0 ? to : eq).trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return null;
        String value = null;
        if (eq >= 0) {
            String v = s.substring(eq + 1, to).trim();
            if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') v = v.substring(1, v.length() - 1);
            else if (!v.isEmpty() && v.charAt(0) == '"') v = v.substring(1);
            value = v;
        }
        return new String[]{name, value};
    }

    /** {@code max-age} değeri: yalnız rakamlar (≤ 19), {@value #MAX_DELTA_S} üstü ona iner; değer yok / sayısal değil → 0 (bayat). */
    static long deltaSeconds(String v) {
        if (v == null || v.isEmpty()) return 0L;
        long out = 0;
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c < '0' || c > '9') return 0L;
            out = out * 10 + (c - '0');
            if (out >= MAX_DELTA_S) return MAX_DELTA_S;
        }
        return out;
    }

    private static long parseDate(String s, long fallback) {
        if (!present(s)) return fallback;
        String t = s.trim();
        if (t.length() > 64) return fallback;
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
