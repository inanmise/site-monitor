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
    // 2026-11-14 (Sayfa Hızı zenginleştirme madde 11 / 12). Anahtarlar YALNIZ büyük harf: arayüzün delil ayrıştırıcısı
    // ({@code KEY:eşik>ölçülen}) anahtarı [A-Z]+ ile okur; {@code breached_metrics} kolonu 60 karakter — dokuz anahtarın
    // hepsi 49 karakter eder.
    /** Sayfa beklenenden KÜÇÜK (asgari boyut eşiğinin altında). */
    public static final String BREACH_MINSIZE = "MINSIZE";
    public static final String BREACH_JS = "JS";
    public static final String BREACH_IMG = "IMG";
    public static final String BREACH_FONT = "FONT";
    public static final String BREACH_THIRD = "THIRD";

    /** Bütün anahtarlar, eşik tablosu / alarm postası / arayüz sırasıyla. */
    public static final List<String> BREACH_KEYS = List.of(BREACH_LOAD, BREACH_TTFB, BREACH_SIZE, BREACH_REQUESTS,
            BREACH_MINSIZE, BREACH_JS, BREACH_IMG, BREACH_FONT, BREACH_THIRD);

    /**
     * Bir ölçümün eşiklerle karşılaştırılan değerleri (2026-11-14). {@code ttfbMs} GERÇEK kontrol isteğinin ilk baytıdır
     * (izlemenin UA / kimlik / başlık / yönlendirme / vekil kararıyla; yönlendirme zinciri dahil) — madde 2: ayrı faz probu
     * eşiği artık ETKİLEMEZ. {@code lowerBound}: toplam / tür baytları ALT SINIR (bayt ya da süre tavanı, kaynak tavanı);
     * üst eşik aşımı yine geçerli bir ihlaldir ama asgari boyut eşiği ALT SINIRLA değerlendirilmez (gerçek boyut daha
     * büyük olabilir — sahte "sayfa küçük" alarmı olmasın). Tür baytı null = ölçülemedi (ihlal üretmez).
     */
    public record Measurement(Integer loadMs, Integer ttfbMs, Long totalBytes, Integer requestCount,
                              Long jsBytes, Long imgBytes, Long fontBytes, Long thirdPartyBytes, boolean lowerBound) {
        /** Yalnız dört temel metrik (tür kırılımı yok). */
        public static Measurement basic(Integer loadMs, Integer ttfbMs, Long totalBytes, Integer requestCount) {
            return new Measurement(loadMs, ttfbMs, totalBytes, requestCount, null, null, null, null, false);
        }
    }

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
        return evaluate(m, Measurement.basic(loadMs, ttfbMs, totalBytes, requestCount));
    }

    /**
     * Bütün eşikler (2026-11-14). TTFB eşiği {@link Measurement#ttfbMs()}'e — GERÇEK isteğin ilk baytına — bakar (madde 2).
     *
     * <p><b>Eskiden</b> TTFB eşiği ayrı faz probunun "sunucu bekleme" süresine bakıyordu; o prob kendi UA'sıyla, kimlik /
     * özel başlık göndermeden ve yönlendirme İZLEMEDEN tek bir GET atıyordu. Kimlik isteyen sayfada eşik 401'in, yönlenen
     * sayfada 301'in süresini ölçüyordu — sayfanın kendisini değil. Artık eşik, kartta / geçmişte / "Şimdi Dene" önerisinde
     * görünen TTFB ile AYNI sayıdır (tek TTFB). Faz kırılımı (DNS / bağlantı / TLS / sunucu) yalnız bilgi olarak kalır.
     *
     * <p>Kurallar: eşik null ya da ≤ 0 değerlendirilmez; karşılaştırma KESİN (tam eşik ihlal değil); ölçülemeyen (null)
     * metrik ihlal üretmez; KB eşikleri bayta TEK yerde (burada) çevrilir (1 KB = 1024 bayt). Asgari boyut ({@code minPageKb})
     * KESİN küçüktür ve toplam alt sınırsa değerlendirilmez.
     */
    public static List<String> evaluate(PageSpeedMonitor m, Measurement x) {
        List<String> out = new ArrayList<>(4);
        if (m == null || x == null) return out;
        if (exceeds(x.loadMs(), m.getMaxLoadMs())) out.add(BREACH_LOAD);
        if (exceeds(x.ttfbMs(), m.getMaxTtfbMs())) out.add(BREACH_TTFB);
        // Boyut eşiği KB cinsinden girilir, ölçüm bayt — çevrim TEK yerde olsun diye burada.
        if (exceedsKb(x.totalBytes(), m.getMaxPageKb())) out.add(BREACH_SIZE);
        if (exceeds(x.requestCount(), m.getMaxRequests())) out.add(BREACH_REQUESTS);
        if (belowKb(x.totalBytes(), m.getMinPageKb(), x.lowerBound())) out.add(BREACH_MINSIZE);
        if (exceedsKb(x.jsBytes(), m.getMaxJsKb())) out.add(BREACH_JS);
        if (exceedsKb(x.imgBytes(), m.getMaxImgKb())) out.add(BREACH_IMG);
        if (exceedsKb(x.fontBytes(), m.getMaxFontKb())) out.add(BREACH_FONT);
        if (exceedsKb(x.thirdPartyBytes(), m.getMaxThirdPartyKb())) out.add(BREACH_THIRD);
        return out;
    }

    private static boolean exceeds(Integer measured, Integer threshold) {
        return threshold != null && threshold > 0 && measured != null && measured > threshold;
    }

    private static boolean exceedsKb(Long bytes, Integer thresholdKb) {
        return thresholdKb != null && thresholdKb > 0 && bytes != null && bytes > thresholdKb * 1024L;
    }

    /** Asgari boyut: ölçülen KESİN küçükse ihlal; alt sınır olan toplamla karar verilmez (gerçeği daha büyük olabilir). */
    private static boolean belowKb(Long bytes, Integer minKb, boolean lowerBound) {
        return minKb != null && minKb > 0 && bytes != null && !lowerBound && bytes < minKb * 1024L;
    }

    /** Eşiğin (izlemenin şu anki ayarı) değeri — anahtarın birimiyle (ms / KB / adet); tanımlı değilse null. */
    public static Integer thresholdOf(PageSpeedMonitor m, String key) {
        if (m == null || key == null) return null;
        Integer v = switch (key) {
            case BREACH_LOAD -> m.getMaxLoadMs();
            case BREACH_TTFB -> m.getMaxTtfbMs();
            case BREACH_SIZE -> m.getMaxPageKb();
            case BREACH_REQUESTS -> m.getMaxRequests();
            case BREACH_MINSIZE -> m.getMinPageKb();
            case BREACH_JS -> m.getMaxJsKb();
            case BREACH_IMG -> m.getMaxImgKb();
            case BREACH_FONT -> m.getMaxFontKb();
            case BREACH_THIRD -> m.getMaxThirdPartyKb();
            default -> null;
        };
        return v == null || v <= 0 ? null : v;
    }

    /** Ölçülen değer anahtarın birimiyle (ms / KB tam sayı / adet); ölçülemediyse null. */
    public static Long measuredOf(Measurement x, String key) {
        if (x == null || key == null) return null;
        return switch (key) {
            case BREACH_LOAD -> x.loadMs() == null ? null : x.loadMs().longValue();
            case BREACH_TTFB -> x.ttfbMs() == null ? null : x.ttfbMs().longValue();
            case BREACH_SIZE, BREACH_MINSIZE -> kbOf(x.totalBytes());
            case BREACH_REQUESTS -> x.requestCount() == null ? null : x.requestCount().longValue();
            case BREACH_JS -> kbOf(x.jsBytes());
            case BREACH_IMG -> kbOf(x.imgBytes());
            case BREACH_FONT -> kbOf(x.fontBytes());
            case BREACH_THIRD -> kbOf(x.thirdPartyBytes());
            default -> null;
        };
    }

    private static Long kbOf(Long bytes) { return bytes == null ? null : bytes / 1024; }

    /** Anahtarın değeri alt sınır mı (bayt metrikleri, toplam kısmi olduğunda) — postada / arayüzde "≥" ile gösterilir. */
    public static boolean lowerBoundFor(Measurement x, String key) {
        if (x == null || !x.lowerBound() || key == null) return false;
        return switch (key) {
            case BREACH_SIZE, BREACH_MINSIZE, BREACH_JS, BREACH_IMG, BREACH_FONT, BREACH_THIRD -> true;
            default -> false;
        };
    }

    /**
     * Eşik TABLOSU (2026-11-14, madde 1 — alarm postası): TANIMLI her eşik için bir satır, TSV
     * {@code ANAHTAR\teşik\tölçülen\tihlal(1|0)\talt-sınır(1|0)}. Ölçülemeyen değer boş bırakılır. {@code breached}
     * kontrolün KENDİ kararıdır (ihlal delili ile aynı kaynak) — burada yeniden hesaplanmaz. Hiç eşik yoksa null.
     */
    public static String thresholdTable(PageSpeedMonitor m, Measurement x, List<String> breached) {
        if (m == null) return null;
        java.util.Set<String> b = breached == null ? java.util.Set.of() : new java.util.HashSet<>(breached);
        StringBuilder sb = new StringBuilder();
        for (String k : BREACH_KEYS) {
            Integer th = thresholdOf(m, k);
            if (th == null) continue;
            Long v = measuredOf(x, k);
            if (sb.length() > 0) sb.append('\n');
            sb.append(k).append('\t').append(th).append('\t').append(v == null ? "" : v).append('\t')
              .append(b.contains(k) ? '1' : '0').append('\t').append(lowerBoundFor(x, k) ? '1' : '0');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * İhlal DELİLİ: {@code "TTFB:1000>2955,LOAD:8000>15094"} — eşik ve ölçülen, ölçüm anındaki
     * değerlerle. Kullanıcı eşiği sonradan değiştirince geçmiş satır yanlış sayıyla açıklanmasın.
     * Boş liste → {@code null}.
     */
    public static String breachDetail(PageSpeedMonitor m, List<String> breaches, Integer loadMs,
                                      Integer ttfbMs, Long totalBytes, Integer requestCount) {
        return breachDetail(m, breaches, Measurement.basic(loadMs, ttfbMs, totalBytes, requestCount));
    }

    /**
     * İhlal delili, ölçüm anının değerleriyle (2026-11-14: dokuz anahtar). Biçim değişmedi: {@code ANAHTAR:eşik>ölçülen};
     * ">" yalnız ayırıcıdır — asgari boyutta da "eşik → ölçülen" diye okunur ({@code MINSIZE:50>12}). KB anahtarları KB yazılır
     * (bayt yazmak kullanıcıyı her seferinde bölmeye zorlardı).
     */
    public static String breachDetail(PageSpeedMonitor m, List<String> breaches, Measurement x) {
        if (m == null || breaches == null || breaches.isEmpty()) return null;
        List<String> parts = new ArrayList<>(breaches.size());
        for (String b : breaches) {
            if (BREACH_KEYS.contains(b)) parts.add(pair(b, thresholdOf(m, b), measuredOf(x, b)));
            else parts.add(b);
        }
        return String.join(",", parts);
    }

    private static String pair(String key, Integer threshold, Long measured) {
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
