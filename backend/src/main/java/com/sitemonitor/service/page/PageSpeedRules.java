package com.sitemonitor.service.page;

import com.sitemonitor.model.PageSpeedMonitor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Sayfa Hızı ölçümünün SAF kuralları — ağ, veritabanı ve Spring yok.
 *
 * <p>Eşik değerlendirmesi, tracker hariç tutma ve başlık ayrıştırma buraya toplandı çünkü hepsi
 * "yanlış olursa sessizce yanlış" türünden: eşik bir yönde kayarsa ya alarm hiç gelmez ya her
 * kontrolde gelir; tracker eşleşmesi genişse kendi kaynaklarımızı ölçüm dışı bırakır. Saf oldukları
 * için tablo testiyle sınır sınır pinlenebilirler.
 */
public final class PageSpeedRules {

    private PageSpeedRules() {}

    // ── Eşik anahtarları (UI ve e-posta bunları çevirir; serbest metin DEĞİL) ────────────────
    public static final String BREACH_LOAD = "LOAD";
    public static final String BREACH_TTFB = "TTFB";
    public static final String BREACH_SIZE = "SIZE";
    public static final String BREACH_REQUESTS = "REQUESTS";

    /**
     * Hangi eşikler aşıldı. Boş liste = ihlal yok.
     *
     * <p>Kurallar:
     * <ul>
     *   <li>Eşik {@code null} ise o metrik DEĞERLENDİRİLMEZ — kullanıcı yalnız umursadığını bağlar.</li>
     *   <li>Eşik {@code <= 0} ise de değerlendirilmez (0 "sınırsız" anlamına gelir, "her şey ihlal" değil).</li>
     *   <li>Karşılaştırma KESİN büyüktür: tam eşik değeri ihlal SAYILMAZ ("en fazla 3000 ms" demek 3000'in
     *       kendisine izin vermek demektir).</li>
     *   <li>Ölçülemeyen (null) metrik ihlal üretmez — eksik veri alarma dönüşmemeli.</li>
     * </ul>
     */
    public static List<String> evaluate(PageSpeedMonitor m, Integer loadMs, Integer ttfbMs,
                                        Long totalBytes, Integer requestCount) {
        return evaluate(m, loadMs, ttfbMs, null, totalBytes, requestCount);
    }

    /**
     * @param serverMs SUNUCU bekleme süresi (faz kırılımından); {@code null} ise eski tek-parça
     *                 {@code ttfbMs}'e düşülür.
     *
     * <p>TTFB eşiği neden {@code serverMs}'e bakar: eski {@code ttfbMs} DNS + TCP + TLS +
     * yönlendirme zincirini de içeriyordu ve bağlantı havuzu sıcakken 41 ms, soğukken ~3 sn
     * okunuyordu. Eşik o rakama bakınca sunucu hiç yavaşlamamışken alarm üretiyordu — ölçtüğü
     * şey sunucunun değil BİZİM bağlantı kurma maliyetimizdi. Fazlar ölçülemediyse (eski kayıt,
     * prob başarısız) eskiye düşmek zorunlu: eşiği sessizce devre dışı bırakmak, alarmı
     * kaybetmek olurdu.
     */
    public static List<String> evaluate(PageSpeedMonitor m, Integer loadMs, Integer ttfbMs,
                                        Integer serverMs, Long totalBytes, Integer requestCount) {
        List<String> out = new ArrayList<>(4);
        if (m == null) return out;
        if (exceeds(loadMs, m.getMaxLoadMs())) out.add(BREACH_LOAD);
        if (exceeds(serverMs != null ? serverMs : ttfbMs, m.getMaxTtfbMs())) out.add(BREACH_TTFB);
        // Boyut eşiği KB cinsinden girilir, ölçüm bayt — çevrim TEK yerde olsun diye burada.
        if (m.getMaxPageKb() != null && m.getMaxPageKb() > 0 && totalBytes != null
                && totalBytes > m.getMaxPageKb() * 1024L) {
            out.add(BREACH_SIZE);
        }
        if (exceeds(requestCount, m.getMaxRequests())) out.add(BREACH_REQUESTS);
        return out;
    }

    private static boolean exceeds(Integer measured, Integer threshold) {
        return threshold != null && threshold > 0 && measured != null && measured > threshold;
    }

    /**
     * İhlal DELİLİ: {@code "TTFB:1000>2955,LOAD:8000>15094"} — eşik ve ölçülen, ölçüm anındaki
     * değerlerle. Kullanıcı eşiği sonradan değiştirince geçmiş satır yanlış sayıyla açıklanmasın.
     * Boş liste → {@code null}.
     */
    public static String breachDetail(PageSpeedMonitor m, List<String> breaches, Integer loadMs,
                                      Integer ttfbMs, Long totalBytes, Integer requestCount) {
        if (m == null || breaches == null || breaches.isEmpty()) return null;
        List<String> parts = new ArrayList<>(breaches.size());
        for (String b : breaches) {
            switch (b) {
                case BREACH_LOAD     -> parts.add(pair(BREACH_LOAD, m.getMaxLoadMs(), loadMs));
                case BREACH_TTFB     -> parts.add(pair(BREACH_TTFB, m.getMaxTtfbMs(), ttfbMs));
                // Boyut eşiği KB girilir, ölçüm bayt: delil de KB cinsinden yazılır ki eşikle
                // aynı birimde okunsun (bayt yazmak kullanıcıyı her seferinde bölmeye zorlardı).
                case BREACH_SIZE     -> parts.add(pair(BREACH_SIZE, m.getMaxPageKb(),
                        totalBytes == null ? null : (int) (totalBytes / 1024)));
                case BREACH_REQUESTS -> parts.add(pair(BREACH_REQUESTS, m.getMaxRequests(), requestCount));
                default              -> parts.add(b);
            }
        }
        return String.join(",", parts);
    }

    private static String pair(String key, Integer threshold, Integer measured) {
        return key + ":" + (threshold == null ? "?" : threshold) + ">" + (measured == null ? "?" : measured);
    }

    /** {@code evaluate} çıktısını DB kolonuna yazılacak biçime çevirir (boşsa null → "ihlal yok"). */
    public static String joinBreaches(List<String> breaches) {
        return (breaches == null || breaches.isEmpty()) ? null : String.join(",", breaches);
    }

    // ── Tracker hariç tutma ──────────────────────────────────────────────────────────────────

    /**
     * Yerleşik tracker/analytics host listesi. Amaç reklam engelleme değil; "kendi sayfam ne kadar
     * ağır" sorusunun üçüncü-taraf gürültüsünden arınmasıdır. Host parçası olarak eşleşir.
     */
    static final String[] BUILTIN_TRACKERS = {
            "google-analytics.com", "googletagmanager.com", "googlesyndication.com", "doubleclick.net",
            "connect.facebook.net", "facebook.com/tr", "static.hotjar.com", "script.hotjar.com",
            "cdn.segment.com", "api.segment.io", "cdn.mxpnl.com", "js-agent.newrelic.com", "nr-data.net",
            "static.criteo.net", "adnxs.com", "scorecardresearch.com", "mc.yandex.ru",
            "clarity.ms", "matomo.cloud", "bat.bing.com", "analytics.tiktok.com", "snap.licdn.com"
    };

    /**
     * Ölçüm dışı bırakılacak URL yüklemi. Kapalıysa (ya da desen yoksa) HİÇBİR ŞEY hariç tutulmaz.
     *
     * <p>Eşleşme büyük/küçük harf duyarsız SUBSTRING'dir — regex bilinçli kullanılmadı: kullanıcı
     * girdisinden derlenen regex hem ReDoS hem "yanlışlıkla her şeyi eşleştiren desen" riski taşır,
     * ve buradaki hata sessizdir (kendi kaynaklarınız ölçüme girmez, sayfa olduğundan hafif görünür).
     */
    public static Predicate<String> exclusion(boolean excludeTrackers, String userPatterns) {
        List<String> needles = new ArrayList<>();
        if (excludeTrackers) {
            for (String t : BUILTIN_TRACKERS) needles.add(t);
        }
        for (String p : splitPatterns(userPatterns)) needles.add(p);
        if (needles.isEmpty()) return url -> false;
        return url -> {
            if (url == null) return false;
            String low = url.toLowerCase(Locale.ROOT);
            for (String n : needles) if (low.contains(n)) return true;
            return false;
        };
    }

    /** Satır ya da virgül ayrık desenler; boşlar atılır, sayı ve uzunluk caplenir. */
    static List<String> splitPatterns(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("[\\r\\n,]+")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if (p.length() > MAX_PATTERN_LEN) p = p.substring(0, MAX_PATTERN_LEN);
            out.add(p);
            if (out.size() >= MAX_PATTERNS) break;
        }
        return out;
    }

    static final int MAX_PATTERNS = 50;
    static final int MAX_PATTERN_LEN = 200;

    // ── İstek başlıkları ─────────────────────────────────────────────────────────────────────

    static final int MAX_CUSTOM_HEADERS = 20;

    /**
     * {@code Ad: değer} satırlarını başlık haritasına çevirir. Boş satır ve {@code #} yorumları atlanır;
     * iki nokta taşımayan satır sessizce yok sayılır (yarım yapılandırma isteği patlatmasın).
     *
     * <p>Kontrol karakteri (CR/LF) taşıyan satır ATILIR — başlık enjeksiyonu buradan başlar.
     * {@link PageFetchCore} ayrıca kendi ayrılmış başlıklarını korur; iki katman bilinçlidir.
     */
    public static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int c = s.indexOf(':');
            if (c <= 0) continue;
            String k = s.substring(0, c).trim();
            String v = s.substring(c + 1).trim();
            if (k.isEmpty() || PageFetchCore.hasControlChars(k) || PageFetchCore.hasControlChars(v)) continue;
            out.put(k, v);
            if (out.size() >= MAX_CUSTOM_HEADERS) break;
        }
        return out;
    }

    /** HTTP Basic auth başlık değeri; kullanıcı adı boşsa null (parola tek başına anlamsız). */
    public static String basicAuthHeader(String user, String plainPassword) {
        if (user == null || user.isBlank()) return null;
        String raw = user + ":" + (plainPassword == null ? "" : plainPassword);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Yapılandırılmış UA boş/null ise varsayılana düşer. */
    public static String userAgentOr(String configured, String fallback) {
        return (configured == null || configured.isBlank()) ? fallback : configured;
    }

    /** Aralık tabanı sunucu tarafında da uygulanır — form atlanabilir, uç atlanamaz. */
    public static int clampInterval(Integer seconds) {
        int s = seconds == null ? PageSpeedMonitor.MIN_INTERVAL_SECONDS : seconds;
        return Math.max(PageSpeedMonitor.MIN_INTERVAL_SECONDS, s);
    }

    public static int clampConcurrency(Integer c) {
        int v = c == null ? 5 : c;
        return Math.max(1, Math.min(20, v));
    }
}
