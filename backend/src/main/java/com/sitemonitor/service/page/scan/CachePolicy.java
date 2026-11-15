package com.sitemonitor.service.page.scan;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * biçimi) çıplak yönerge DEĞİLDİR; tırnaklı değerdeki ters bölüyle kaçırılmış tırnak ({@code \"}) tırnağı kapatmaz.
 * {@code Pragma: no-cache} yanıtta {@code Cache-Control} olsa da (ör. {@code max-age=3600} ile) {@code no-cache} gibi sayılır —
 * Chromium (kurumda yaygın Chrome / Edge) böyle davranır (RFC 9111 §5.4 yanıttaki Pragma'yı tanımlamaz; 2026-11-15 kararı).
 * {@code max-age} birden çok kez verilmişse İLKİ geçerlidir (RFC 9111 §4.2.1); sayısal olmayan {@code max-age} bayat sayılır
 * (ömür 0). {@code max-age}, {@code Expires}'ın önüne geçer. Bozuk {@code Expires} ("0", "-1") süresi geçmiş sayılır.
 * Tarihler RFC 9110 §5.6.7'nin üç biçiminde okunur (IMF-fixdate, RFC 850 — iki haneli yıl 50 yıl kuralıyla —, asctime); haftanın
 * günü yok sayılır ({@link #parseHttpDate}). {@code Last-Modified} okunamıyorsa yalnız doğrulayıcıdır → {@link #REVALIDATE}.
 * Doğrulayıcı = BOŞ OLMAYAN {@code ETag} ya da {@code Last-Modified} ({@link #hasValidator} — tarama ve kayıttan geri kurma
 * aynı kuralı kullanır).
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
    /** Okunan en uzun tarih metni (daha uzunu tarih sayılmaz). */
    static final int MAX_DATE_CHARS = 64;
    /** Tarihte en çok parça (boşluk / virgülle ayrılan; fazlası tarih sayılmaz). */
    static final int MAX_DATE_TOKENS = 8;
    /** RFC 850 iki haneli yılı: şimdiden bu kadar yıldan fazla ilerideyse bir yüzyıl önceki yıl sayılır (RFC 9110 §5.6.7). */
    static final int TWO_DIGIT_YEAR_WINDOW = 50;
    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /**
     * @param cacheClass sınıf ({@link #CLASSES})
     * @param ttlS       eski {@code cache_ttl_s} anlamıyla ömür: {@code no-store} / çıplak {@code no-cache} / {@code Pragma: no-cache}
     *                   → -1, {@code max-age} / {@code Expires − Date} → saniye (≥ 0), ömür bilgisi yoksa null
     * @param heuristicS {@link #HEURISTIC} sınıfında tarayıcının tahmini ömrü (sn, yalnız gösterim); diğerlerinde null
     * @param validator  boş olmayan {@code ETag} ya da {@code Last-Modified} var mı ({@link #hasValidator})
     */
    public record Policy(String cacheClass, Long ttlS, Long heuristicS, boolean validator) {}

    private CachePolicy() {}

    /**
     * Yanıt başlıklarından sınıf. Tüm parametreler null olabilir; {@code date} yoksa / bozuksa {@code nowMs} referanstır.
     */
    public static Policy classify(String cacheControl, String pragma, String expires, String date, String etag,
                                  String lastModified, long nowMs) {
        boolean hasLm = present(lastModified);
        boolean validator = hasValidator(etag, lastModified);

        boolean noStore = false, noCache = false;
        Long maxAge = null;
        if (present(cacheControl)) {
            for (String[] d : directives(cacheControl)) {
                String name = d[0];
                if (name.equals("no-store")) noStore = true;
                else if (name.equals("no-cache") && d[1] == null) noCache = true;   // no-cache="alan" çıplak değil
                else if (name.equals("max-age") && maxAge == null) maxAge = deltaSeconds(d[1]);
            }
        }
        // Chromium kuralı: yanıttaki Pragma: no-cache, Cache-Control'de ömür olsa da no-cache sayılır (sınıf açıklaması)
        if (present(pragma)) {
            for (String[] d : directives(pragma)) {
                if (d[0].equals("no-cache") && d[1] == null) noCache = true;
            }
        }

        if (noStore) return new Policy(NO_STORE, -1L, null, validator);
        if (noCache) return new Policy(validator ? REVALIDATE : NONE, -1L, null, validator);

        long ref = parseHttpDate(date, nowMs, nowMs);
        Long ttl = maxAge;
        if (ttl == null && present(expires)) {
            long exp = parseHttpDate(expires, ref, Long.MIN_VALUE);   // iki haneli yılın yüzyılı Date'e göre
            ttl = exp == Long.MIN_VALUE ? 0L : Math.max(0L, (exp - ref) / 1000L);
        }
        if (ttl != null) {
            if (ttl >= STORED_MIN_S) return new Policy(STORED, ttl, null, validator);
            if (ttl > 0) return new Policy(SHORT, ttl, null, validator);
            return new Policy(validator ? REVALIDATE : NONE, ttl, null, validator);
        }
        // Ömür bilgisi hiç yok
        if (hasLm) {
            long lm = parseHttpDate(lastModified, ref, Long.MIN_VALUE);
            if (lm != Long.MIN_VALUE) {
                long heuristic = Math.max(0L, (ref - lm) / 1000L / HEURISTIC_DIVISOR);
                return new Policy(HEURISTIC, null, heuristic, true);
            }
        }
        return new Policy(validator ? REVALIDATE : NONE, null, null, validator);
    }

    /**
     * Doğrulayıcı var mı: BOŞ OLMAYAN {@code ETag} ya da {@code Last-Modified} (tek kural — tarama, sınıf ve kayıttan geri kurma;
     * boş {@code ETag:} satırı doğrulayıcı değildir).
     */
    public static boolean hasValidator(String etag, String lastModified) {
        return present(etag) || present(lastModified);
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
     * Virgülle ayrılmış yönergeler {ad (küçük harf), değer (tırnaksız; değer yoksa null)} — tırnak içindeki virgül ayırmaz;
     * tırnak içinde ters bölü sonraki karakteri kaçırır (RFC 9110 §5.6.4 quoted-pair: {@code \"} tırnağı kapatmaz). İlk
     * {@value #MAX_HEADER_CHARS} karakter, en çok {@value #MAX_DIRECTIVES} yönerge; her karakter en çok bir kez okunur.
     */
    static List<String[]> directives(String header) {
        List<String[]> out = new ArrayList<>();
        if (header == null) return out;
        int n = Math.min(header.length(), MAX_HEADER_CHARS);
        int start = 0;
        boolean quoted = false;
        for (int k = 0; k <= n && out.size() < MAX_DIRECTIVES; k++) {
            char c = k < n ? header.charAt(k) : ',';
            if (quoted && c == '\\' && k + 1 < n) k++;   // kaçırılan karakteri atla (yalnız ileri — sınırlı)
            else if (c == '"') quoted = !quoted;
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

    /**
     * HTTP tarihi (epoch ms) — RFC 9110 §5.6.7'nin alıcının KABUL ETMESİ GEREKEN üç biçimi, hoşgörülü:
     * <ul>
     *   <li>IMF-fixdate {@code Sun, 06 Nov 1994 08:49:37 GMT} (gün adı isteğe bağlı);</li>
     *   <li>RFC 850 {@code Sunday, 06-Nov-94 08:49:37 GMT} — iki haneli yıl, şimdiden {@value #TWO_DIGIT_YEAR_WINDOW} yıldan
     *       fazla ilerideyse bir önceki yüzyıl (dört haneli yıl da kabul);</li>
     *   <li>asctime {@code Sun Nov  6 08:49:37 1994} (saat dilimi yok → UTC).</li>
     * </ul>
     * Haftanın günü DOĞRULANMAZ (yanlış gün adı tarihi bozmaz). Bölge {@code GMT} / {@code UTC} / {@code UT} / {@code Z} ya da
     * {@code ±HHMM}; yoksa UTC. Saniye 60 (artık saniye) 59 sayılır. Düzenli ifade yok: en çok {@value #MAX_DATE_CHARS} karakter,
     * {@value #MAX_DATE_TOKENS} parça, her karakter bir kez okunur. Okunamazsa {@code fallback}.
     *
     * @param nowMs iki haneli yılın yüzyılını seçmek için referans (yanıtın {@code Date} başlığı; o yoksa / okunamazsa şimdi)
     */
    static long parseHttpDate(String s, long nowMs, long fallback) {
        if (!present(s)) return fallback;
        String t = s.trim();
        if (t.length() > MAX_DATE_CHARS) return fallback;
        List<String> tok = new ArrayList<>(MAX_DATE_TOKENS);
        int from = -1;
        for (int k = 0; k <= t.length(); k++) {
            char c = k < t.length() ? t.charAt(k) : ' ';
            boolean sep = c == ' ' || c == ',' || c == '\t';
            if (sep) {
                if (from >= 0) {
                    if (tok.size() >= MAX_DATE_TOKENS) return fallback;
                    tok.add(t.substring(from, k));
                    from = -1;
                }
            } else if (from < 0) {
                from = k;
            }
        }
        if (tok.isEmpty()) return fallback;
        int i = 0;
        // Haftanın günü (harflerden oluşup ay adı olmayan ilk parça) yok sayılır
        if (letters(tok.get(0)) && month(tok.get(0)) < 0) i++;
        if (i >= tok.size()) return fallback;
        String first = tok.get(i);
        int day, mon, year;
        String time, zone;
        if (first.indexOf('-') > 0) {                                    // RFC 850: 06-Nov-94 08:49:37 GMT
            int a = first.indexOf('-'), b = first.indexOf('-', a + 1);
            if (b < 0 || first.indexOf('-', b + 1) >= 0) return fallback;
            day = digits(first.substring(0, a), 2);
            mon = month(first.substring(a + 1, b));
            year = year(first.substring(b + 1), nowMs);
            time = at(tok, i + 1);
            zone = at(tok, i + 2);
            if (tok.size() > i + 3) return fallback;
        } else if (month(first) >= 0) {                                   // asctime: Nov 6 08:49:37 1994
            mon = month(first);
            day = digits(at(tok, i + 1), 2);
            time = at(tok, i + 2);
            year = year(at(tok, i + 3), nowMs);
            zone = at(tok, i + 4);
            if (tok.size() > i + 5) return fallback;
        } else {                                                          // IMF-fixdate: 06 Nov 1994 08:49:37 GMT
            day = digits(first, 2);
            mon = month(at(tok, i + 1));
            year = year(at(tok, i + 2), nowMs);
            time = at(tok, i + 3);
            zone = at(tok, i + 4);
            if (tok.size() > i + 5) return fallback;
        }
        if (day < 0 || mon < 0 || year < 0 || time == null) return fallback;
        int c1 = time.indexOf(':'), c2 = c1 < 0 ? -1 : time.indexOf(':', c1 + 1);
        if (c1 < 0 || c2 < 0 || time.indexOf(':', c2 + 1) >= 0) return fallback;
        int hh = digits(time.substring(0, c1), 2), mm = digits(time.substring(c1 + 1, c2), 2), ss = digits(time.substring(c2 + 1), 2);
        if (hh < 0 || hh > 23 || mm < 0 || mm > 59 || ss < 0 || ss > 60) return fallback;
        Integer offset = zoneOffsetSeconds(zone);
        if (offset == null) return fallback;
        try {
            // Geçersiz gün (31 Şubat) LocalDateTime.of'ta istisnadır
            long epochS = LocalDateTime.of(year, mon + 1, day, hh, mm, Math.min(ss, 59)).toEpochSecond(ZoneOffset.UTC) - offset;
            return epochS * 1000L;
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    private static String at(List<String> tok, int i) {
        return i < tok.size() ? tok.get(i) : null;
    }

    private static boolean letters(String s) {
        if (s.isEmpty()) return false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }

    /** Üç harfli İngilizce ay adı (büyük / küçük harf fark etmez) → 0..11, değilse -1. */
    private static int month(String s) {
        if (s == null || s.length() != 3) return -1;
        return MONTHS.indexOf(s.toLowerCase(Locale.ROOT));
    }

    /** 1..{@code max} haneli sayı, değilse -1. */
    private static int digits(String s, int max) {
        if (s == null || s.isEmpty() || s.length() > max) return -1;
        int out = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < '0' || c > '9') return -1;
            out = out * 10 + (c - '0');
        }
        return out;
    }

    /** Dört haneli yıl olduğu gibi; iki haneli yıl RFC 9110 §5.6.7 50 yıl kuralıyla; başka uzunluk -1. */
    private static int year(String s, long nowMs) {
        if (s == null) return -1;
        if (s.length() == 4) return digits(s, 4);
        if (s.length() != 2) return -1;
        int yy = digits(s, 2);
        if (yy < 0) return -1;
        int nowYear = LocalDateTime.ofEpochSecond(Math.floorDiv(nowMs, 1000L), 0, ZoneOffset.UTC).getYear();
        int y = nowYear - Math.floorMod(nowYear, 100) + yy;
        if (y > nowYear + TWO_DIGIT_YEAR_WINDOW) y -= 100;
        return y;
    }

    /** Bölge → saniye cinsinden ofset ({@code GMT} / {@code UTC} / {@code UT} / {@code Z} / yok → 0, {@code ±HHMM}); bilinmeyen → null. */
    private static Integer zoneOffsetSeconds(String z) {
        if (z == null) return 0;
        String u = z.toUpperCase(Locale.ROOT);
        if (u.equals("GMT") || u.equals("UTC") || u.equals("UT") || u.equals("Z")) return 0;
        if (u.length() == 5 && (u.charAt(0) == '+' || u.charAt(0) == '-')) {
            int h = digits(u.substring(1, 3), 2), m = digits(u.substring(3, 5), 2);
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            int sign = u.charAt(0) == '-' ? -1 : 1;
            return sign * (h * 3600 + m * 60);
        }
        return null;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
