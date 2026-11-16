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

    // 2026-11-15 (Sayfa Hızı 2c, büyüme alarmı — madde 13): önceki haftanın TAM, aynı kipteki zamanlanmış ölçümlerine göre
    // büyüme. Anahtarlar YALNIZ büyük harf (arayüz ayrıştırıcısı [A-Z]+); breached_metrics / undecided_metrics kolonları 120
    // karaktere genişletildi (on iki anahtarın hepsi 75 karakter eder).
    /** Sayfa ağırlığı önceki haftanın medyanından belirgin büyüdü ({@code growthBytesPct}). */
    public static final String BREACH_GROWSIZE = "GROWSIZE";
    /** İstek sayısı önceki haftanın medyanından belirgin arttı ({@code growthRequestsPct}). */
    public static final String BREACH_GROWREQ = "GROWREQ";
    /** Sayfa ağırlığı öğrenilen bandın (ortalama + k·σ) üstünde ({@code growthBytesBand}). */
    public static final String BREACH_SIZEBAND = "SIZEBAND";

    /** Bütün anahtarlar, eşik tablosu / alarm postası / arayüz sırasıyla (sıra KALICI — yalnız SONA eklenir). */
    public static final List<String> BREACH_KEYS = List.of(BREACH_LOAD, BREACH_TTFB, BREACH_SIZE, BREACH_REQUESTS,
            BREACH_MINSIZE, BREACH_JS, BREACH_IMG, BREACH_FONT, BREACH_THIRD, BREACH_GROWSIZE, BREACH_GROWREQ, BREACH_SIZEBAND);

    /** Büyüme anahtarları (eşik birimi yüzde ya da bant; tanılamanın eşik ölçüsüne girmez — tanılama geçmiş okumaz). */
    public static final List<String> GROWTH_KEYS = List.of(BREACH_GROWSIZE, BREACH_GROWREQ, BREACH_SIZEBAND);

    /** Büyüme yüzdesi sınırları (kayıtta doğrulanır; kayıtlı değer değişmediyse yeniden denetlenmez). */
    public static final int GROWTH_PCT_MIN = 1, GROWTH_PCT_MAX = 1000;
    /** Karşılaştırma penceresi: önceki 7 gün. */
    public static final long GROWTH_WINDOW_MS = 7L * 24 * 3600 * 1000;
    /** Pencerede en az bu kadar TAM, aynı kipte zamanlanmış ölçüm yoksa karar verilmez (BİLİNMİYOR, ihlal de sağlık da değil). */
    public static final int GROWTH_MIN_SAMPLES = 5;
    /** Okunan en çok satır (5 dk tabanla 7 gün = 2016 zamanlanmış ölçüm + teyit / elle satırları). */
    public static final int GROWTH_READ_LIMIT = 3000;
    /**
     * Öğrenilen bayt bandının parametreleri: yukarı yön, uyarı 3σ (kritik 4σ — alarm seviyesi izlemenin kendi seviyesidir),
     * en yeni 50 tam ölçüm, en az {@value #GROWTH_MIN_SAMPLES}; etkin σ tabanı ortalamanın %2'si (BaselineMath) → bant en dar
     * ortalama + %6. Bandı aşan örnekler tabandan dışlanır; 50 ardışık aşım yeni düzeyi olağan sayar (kontrollü uyum).
     */
    public static final com.sitemonitor.service.baseline.BaselineParams BAND_PARAMS =
            new com.sitemonitor.service.baseline.BaselineParams(com.sitemonitor.service.baseline.BaselineParams.DIRECTION_UP,
                    3.0, 4.0, 50, GROWTH_MIN_SAMPLES, com.sitemonitor.service.baseline.BaselineParams.SEASON_NONE);

    /**
     * Büyüme karşılaştırmasının referansı (önceki 7 günün TAM, aynı kipteki ZAMANLANMIŞ ölçümleri — ölçümden ÖNCE okunur).
     *
     * @param samples        kullanılabilir örnek sayısı (tam, aynı kip, zamanlanmış)
     * @param medianBytes    toplam baytların medyanı (örnek yoksa null)
     * @param medianRequests istek sayılarının medyanı (örnek yoksa null)
     * @param band           öğrenilen bayt bandının kararı — bant kapalıysa / değer yokken null; kuruluyorsa {@code evaluated()} false
     */
    public record Growth(int samples, Long medianBytes, Integer medianRequests,
                         com.sitemonitor.service.baseline.BaselineMath.Verdict band, boolean fullWindow) {
        public static final Growth NONE = new Growth(0, null, null, null);

        /** Pencerenin tam olup olmadığı bilinmeden (2026-11-17 öncesi imza) — {@code fullWindow} false (referans dolabilir sayılır). */
        public Growth(int samples, Long medianBytes, Integer medianRequests,
                      com.sitemonitor.service.baseline.BaselineMath.Verdict band) {
            this(samples, medianBytes, medianRequests, band, false);
        }

        /** Medyan karşılaştırmasına yetecek örnek var mı. */
        public boolean enough() { return samples >= GROWTH_MIN_SAMPLES; }

        /**
         * Büyüme bu turda KARŞILAŞTIRILAMAZ mı (inceleme 2026-11-17): referans penceresi TAM 7 günü kapsadı (yakın bir tanım
         * değişimiyle kısalmadı, okuma sınıra takılmadı) ve yine de {@value #GROWTH_MIN_SAMPLES}'ten az kullanılabilir (tam,
         * aynı kip, zamanlanmış) ölçüm tuttu. Bu durumda referans KENDİLİĞİNDEN DOLMAZ (seyrek zamanlama — günlerce bir ölçüm —
         * ya da her ölçümü kısmi olan sayfa); büyüme eşikleri ne ihlal ne kararsız sayılır ({@link #undecided} onları
         * listelemez). Eskiden "kararsız" sayılıyordu: kararsız eşik SLOW kalemini kestiği için açık bir PAGESPEED_SLOW alarmı
         * (büyümeyle ya da başka eşikle açılmış) hiçbir zaman kapanamıyordu.
         */
        public boolean notComparable() { return fullWindow && !enough(); }

        /** Aynı referans, bant kararıyla (ölçüm değeri bilindiğinde hesaplanır). */
        public Growth withBand(com.sitemonitor.service.baseline.BaselineMath.Verdict v) {
            return new Growth(samples, medianBytes, medianRequests, v, fullWindow);
        }

        /** Aynı referans, pencerenin tam olup olmadığıyla ({@code SchedulerService.pageSpeedGrowthRead} bilir). */
        public Growth withFullWindow(boolean full) {
            return new Growth(samples, medianBytes, medianRequests, band, full);
        }
    }

    /** Bir geçmiş örneği (yeniden eskiye): toplam bayt, istek sayısı, o ölçümün bandı aşıp aşmadığı. */
    public record GrowthSample(long totalBytes, int requestCount, boolean bandBreach) {}

    /**
     * Geçmişten referans (saf): örnekler YENİDEN ESKİYE. Medyan çift sayıda örnekte alt-orta (tam sayı, kesin — ondalık yok).
     * Bant kararı ölçüm değerine bağlı olduğu için ayrıca ({@link #bandVerdict}) hesaplanır.
     */
    public static Growth growthOf(List<GrowthSample> samples) {
        if (samples == null || samples.isEmpty()) return new Growth(0, null, null, null);
        long[] b = new long[samples.size()];
        int[] r = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) { b[i] = samples.get(i).totalBytes(); r[i] = samples.get(i).requestCount(); }
        java.util.Arrays.sort(b);
        java.util.Arrays.sort(r);
        int mid = (samples.size() - 1) / 2;
        return new Growth(samples.size(), b[mid], r[mid], null);
    }

    /** Öğrenilen bayt bandı, geçmiş tamlığı bilinmeden (kesik sayılır) — {@link #bandVerdict(Long, List, boolean)}. */
    public static com.sitemonitor.service.baseline.BaselineMath.Verdict bandVerdict(Long totalBytes, List<GrowthSample> samples) {
        return bandVerdict(totalBytes, samples, false);
    }

    /**
     * Öğrenilen bayt bandı: {@link #BAND_PARAMS} ile {@code BaselineMath} (saf). Değer ya da örnek yoksa null.
     *
     * <p>{@code complete} (inceleme 2026-11-15): okuma SINIRA takılmadıysa örnekler referans penceresinin TAMAMIdır (7 gün ya
     * da tanım değişiminden beri) — o zaman pencerenin başına dek uzanan ve en az {@code minSamples} uzunluğundaki ardışık
     * aşım dizisi yeni düzey sayılır (kontrollü uyum). Eskiden hep {@code false} geçiliyordu: seyrek izlemede (aralık ≳ 3,4 sa,
     * ör. günlük cron) 7 günde 50 örnek hiç birikmediği için uyum HİÇ olmuyor, bant alarmı haftalarca açık kalıyordu.
     */
    public static com.sitemonitor.service.baseline.BaselineMath.Verdict bandVerdict(Long totalBytes, List<GrowthSample> samples,
                                                                                  boolean complete) {
        if (totalBytes == null || samples == null) return null;
        List<com.sitemonitor.service.baseline.BaselineMath.Sample> pts = new ArrayList<>(samples.size());
        for (GrowthSample s : samples) pts.add(new com.sitemonitor.service.baseline.BaselineMath.Sample(s.totalBytes(), s.bandBreach()));
        return com.sitemonitor.service.baseline.BaselineMath.evaluate(totalBytes,
                new com.sitemonitor.service.baseline.BaselineMath.History(pts, complete, false), BAND_PARAMS);
    }

    /**
     * Bir ölçümün eşiklerle karşılaştırılan değerleri (2026-11-14). {@code ttfbMs} = faz probunun SON adımının SUNUCU
     * fazı: izlemenin UA / kimlik / özel başlık / vekil kararıyla, yönlendirmeler izlenerek, TAZE bir bağlantıda (inceleme
     * B2 — anlam "sunucu süresi" olarak kaldı, koşullar gerçek isteğe eşitlendi); null = ölçülemedi. {@code lowerBound}:
     * toplam / tür baytları ALT SINIR (bayt ya da süre tavanı, kaynak tavanı) — üst eşik aşımı yine KANITTIR ama eşiğin
     * altındaki alt sınır bir şey kanıtlamaz (gerçek değer daha büyük olabilir): {@link #undecided} onu BİLİNMİYOR sayar.
     * Tür baytı null = ölçülemedi (ihlal üretmez, tanımlı eşikte BİLİNMİYOR). {@code growth} (2026-11-15): büyüme
     * karşılaştırmasının referansı; null = okunmadı (büyüme eşiği tanımlıysa BİLİNMİYOR).
     */
    public record Measurement(Integer loadMs, Integer ttfbMs, Long totalBytes, Integer requestCount,
                              Long jsBytes, Long imgBytes, Long fontBytes, Long thirdPartyBytes, boolean lowerBound,
                              Growth growth) {
        /** Büyüme referansı olmadan (2026-11-15 öncesi imza). */
        public Measurement(Integer loadMs, Integer ttfbMs, Long totalBytes, Integer requestCount,
                           Long jsBytes, Long imgBytes, Long fontBytes, Long thirdPartyBytes, boolean lowerBound) {
            this(loadMs, ttfbMs, totalBytes, requestCount, jsBytes, imgBytes, fontBytes, thirdPartyBytes, lowerBound, null);
        }

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
        // Büyüme (2026-11-15): yalnız yeterli referans varken; alt sınır olan değer eşiği aşıyorsa yine kanıtlıdır
        Growth g = x.growth();
        if (g != null && g.enough()) {
            if (grewBeyond(x.totalBytes(), g.medianBytes(), m.getGrowthBytesPct())) out.add(BREACH_GROWSIZE);
            if (grewBeyond(x.requestCount() == null ? null : x.requestCount().longValue(),
                    g.medianRequests() == null ? null : g.medianRequests().longValue(), m.getGrowthRequestsPct())) out.add(BREACH_GROWREQ);
        }
        if (Boolean.TRUE.equals(m.getGrowthBytesBand()) && g != null && g.band() != null && g.band().evaluated()
                && g.band().breached()) out.add(BREACH_SIZEBAND);
        return out;
    }

    /**
     * Değer, medyanın (100 + yüzde)/100 katından KESİN büyük mü — tam sayı aritmetiği (ondalık yuvarlama yok): {@code v·100 >
     * medyan·(100 + yüzde)}. Yüzde tanımsız / ≤ 0, medyan yok / ≤ 0 ya da değer yok → ihlal değil.
     */
    static boolean grewBeyond(Long value, Long median, Integer pct) {
        if (pct == null || pct <= 0 || value == null || median == null || median <= 0) return false;
        return value * 100L > median * (100L + pct);
    }

    /** Medyana göre büyüme yüzdesi (aşağı yuvarlı tam sayı; küçülmede negatif). Hesaplanamıyorsa null. */
    static Long growthPct(Long value, Long median) {
        if (value == null || median == null || median <= 0) return null;
        return Math.floorDiv((value - median) * 100L, median);
    }

    /**
     * Kararı VERİLEMEYEN tanımlı eşikler (2026-11-14, inceleme B1) — {@code breached} içinde olmayan ve ölçümle kanıtlanamayan
     * anahtarlar: değer ölçülemedi (null) ya da ölçüm ALT SINIR ve eşik aşılmadı (gerçek değer daha büyük olabilir); asgari
     * boyutta alt sınır eşiğin ALTINDA (gerçek değer üstünde olabilir). Bu anahtarlar "eşik içinde" SAYILMAZ: ihlal yoksa
     * SLOW kalemi üretilmez (açık alarm sahte kapanmaz), yeniden ölçüm "atlandı" döner, bütçe uyumu bu ölçümü saymaz.
     * Alt sınır olan değer eşiği AŞIYORSA ihlal kanıtlıdır (gerçek değer daha da büyük) — o anahtar burada yer almaz.
     */
    public static List<String> undecided(PageSpeedMonitor m, Measurement x, List<String> breached) {
        List<String> out = new ArrayList<>(2);
        if (m == null || x == null) return out;
        java.util.Set<String> b = breached == null ? java.util.Set.of() : new java.util.HashSet<>(breached);
        for (String k : BREACH_KEYS) {
            Integer th = thresholdOf(m, k);
            if (b.contains(k) || th == null) continue;
            // Büyüme referansı kendiliğinden dolamıyorsa (tam pencere, yetersiz örnek) eşik bu turda karşılaştırılamaz: kararsız
            // SAYILMAZ — açık alarm diğer eşiklerin kanıtıyla kapanabilsin (inceleme 2026-11-17; Growth#notComparable)
            if (GROWTH_KEYS.contains(k) && x.growth() != null && x.growth().notComparable()) continue;
            if (measuredOf(x, k) == null) { out.add(k); continue; }
            if (!lowerBoundFor(x, k)) continue;
            // Asgari boyut: alt sınır eşiğin ÜSTÜNDEYSE gerçek değer de üstündedir (kanıtlı geçti); ALTINDAYSA bilinmiyor.
            if (BREACH_MINSIZE.equals(k)) {
                if (x.totalBytes() < th * 1024L) out.add(k);
            } else {
                out.add(k);   // üst eşik: alt sınır eşiğin altında → gerçek değer üstünde olabilir
            }
        }
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
            case BREACH_GROWSIZE -> m.getGrowthBytesPct();
            case BREACH_GROWREQ -> m.getGrowthRequestsPct();
            // Bant açık/kapalı bir anahtardır; "tanımlı" işareti 1 (birimsiz) — tablo / delil üst sınırı ayrıca yazar
            case BREACH_SIZEBAND -> Boolean.TRUE.equals(m.getGrowthBytesBand()) ? 1 : null;
            default -> null;
        };
        return v == null || v <= 0 ? null : v;
    }

    /**
     * İzlemede en az bir eşik / tür bütçesi / asgari boyut tanımlı mı (inceleme F7). Tanımlı değilse "bütçe uyumu"
     * anlamsızdır: kart / ayrıntı / kutu "bütçe tanımlı değil" der, Grafana bütçe değeri yazmaz, bütçe uyumu null döner.
     */
    public static boolean budgetDefined(PageSpeedMonitor m) {
        if (m == null) return false;
        for (String k : BREACH_KEYS) if (thresholdOf(m, k) != null) return true;
        return false;
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
            // Büyüme (2026-11-15): medyana göre büyüme YÜZDESİ; yeterli referans yoksa null (= BİLİNMİYOR)
            case BREACH_GROWSIZE -> x.growth() == null || !x.growth().enough() ? null
                    : growthPct(x.totalBytes(), x.growth().medianBytes());
            case BREACH_GROWREQ -> x.growth() == null || !x.growth().enough() || x.growth().medianRequests() == null ? null
                    : growthPct(x.requestCount() == null ? null : x.requestCount().longValue(),
                            x.growth().medianRequests().longValue());
            // Bant: bant KURULDUYSA ölçülen KB; kuruluyorsa null (= BİLİNMİYOR)
            case BREACH_SIZEBAND -> x.growth() == null || x.growth().band() == null || !x.growth().band().evaluated() ? null
                    : kbOf(x.totalBytes());
            default -> null;
        };
    }

    private static Long kbOf(Long bytes) { return bytes == null ? null : bytes / 1024; }

    /**
     * Anahtarın değeri alt sınır mı (bayt metrikleri, ölçüm kısmi olduğunda: bayt tavanı, süre tavanı ya da kaynak tavanı) —
     * postada / arayüzde "≥" ile gösterilir; TEK kural (alarm metni {@code SchedulerService.pageSpeedBreachText} de bunu okur).
     */
    public static boolean lowerBoundFor(Measurement x, String key) {
        if (x == null || !x.lowerBound() || key == null) return false;
        return switch (key) {
            case BREACH_SIZE, BREACH_MINSIZE, BREACH_JS, BREACH_IMG, BREACH_FONT, BREACH_THIRD -> true;
            // Büyüme karşılaştırması TAM ölçüm ister: kısmi ölçümde (süre / bayt / kaynak tavanı) eşiği aşmayan değer kanıt değildir
            case BREACH_GROWSIZE, BREACH_GROWREQ, BREACH_SIZEBAND -> true;
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
            Long v = GROWTH_KEYS.contains(k) ? growthCurrent(x, k) : measuredOf(x, k);
            if (sb.length() > 0) sb.append('\n');
            sb.append(k).append('\t').append(GROWTH_KEYS.contains(k) ? growthLimit(x, k, th) : String.valueOf(th))
              .append('\t').append(v == null ? "" : v).append('\t')
              .append(b.contains(k) ? '1' : '0').append('\t').append(lowerBoundFor(x, k) ? '1' : '0');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Büyüme satırının eşik hücresi (alarm postası, 2026-11-15): {@code GROWSIZE} {@code yüzde|medyanKB}, {@code GROWREQ}
     * {@code yüzde|medyanİstek}, {@code SIZEBAND} {@code altKB|üstKB|up} (bant kuruluyorsa {@code ||up}). Yeterli referans yoksa
     * medyan boş bırakılır — posta "karşılaştırılamadı" der.
     */
    static String growthLimit(Measurement x, String k, Integer th) {
        Growth g = x == null ? null : x.growth();
        boolean ok = g != null && g.enough();
        return switch (k) {
            case BREACH_GROWSIZE -> th + "|" + (ok && g.medianBytes() != null ? String.valueOf(g.medianBytes() / 1024) : "");
            case BREACH_GROWREQ -> th + "|" + (ok && g.medianRequests() != null ? String.valueOf(g.medianRequests()) : "");
            default -> {
                com.sitemonitor.service.baseline.BaselineMath.Verdict v = g == null ? null : g.band();
                yield v != null && v.evaluated() && v.low() != null && v.high() != null
                        ? (long) Math.floor(v.low() / 1024) + "|" + (long) Math.floor(v.high() / 1024) + "|up" : "||up";
            }
        };
    }

    /** Büyüme satırının ölçülen hücresi: şimdiki değer (KB / istek) — referans yoksa da yazılır (posta "şimdi" diyebilsin). */
    static Long growthCurrent(Measurement x, String k) {
        if (x == null) return null;
        return BREACH_GROWREQ.equals(k) ? (x.requestCount() == null ? null : x.requestCount().longValue()) : kbOf(x.totalBytes());
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
            if (BREACH_SIZEBAND.equals(b)) parts.add(bandPair(x));
            else if (BREACH_KEYS.contains(b)) parts.add(pair(b, thresholdOf(m, b), measuredOf(x, b)));
            else parts.add(b);
        }
        return String.join(",", parts);
    }

    /** Bant delili: {@code SIZEBAND:üstKB>ölçülenKB} (bandın ölçüm anındaki üst ucu). */
    private static String bandPair(Measurement x) {
        Growth g = x == null ? null : x.growth();
        Double hi = g == null || g.band() == null ? null : g.band().high();
        return BREACH_SIZEBAND + ":" + (hi == null ? "?" : String.valueOf((long) Math.floor(hi / 1024))) + ">"
                + (x == null || x.totalBytes() == null ? "?" : String.valueOf(x.totalBytes() / 1024));
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
