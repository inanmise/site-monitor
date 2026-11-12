package com.sitemonitor.service.baseline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Dinamik taban çizgisi (anomali) saf matematiği — dbquery akıllı kuralının
 * ({@link com.sitemonitor.service.dbquery.DbQueryEvaluator#anomalyAgainst} / {@code anomalyBase}) ispatlı algoritmasını
 * izleme türlerine GENELLEŞTİREN bağımsız bir yeniden yazım. Tek skaler ölçü (yanıt süresi ms) üzerinde çalışır; dbquery'nin
 * kolon / aggregate / kural mantığı YOKTUR. Veritabanısız sınanır ({@code BaselineMathTest}); hesap dbquery ile aynı olduğu
 * için aynı davranış kapıyla doğrulanır.
 *
 * <p>Özü: taban = ihlal etmeyen en yeni {@code window} örneğin ortalaması ± {@code k·σ}. Bu kuralı İHLAL EDEN örnekler
 * tabandan DIŞLANIR (kalıcı bir yavaşlama kendi tabanını şişirip alarmı sahte kapatmasın — Y1); ama ardışık ihlal dizisi
 * {@code window} uzunluğa ulaştıysa (ya da geçmişin tamamını kaplayıp ≥ {@code minSamples} ise) yeni düzey olağan sayılır
 * (KONTROLLÜ UYUM). Etkin σ'nın tabanı {@code |ortalama| × }{@value #SIGMA_FLOOR_RATIO} (düz seriler sınırsız sapma
 * üretmesin). Karar yönlüdür: yukarı (yavaş), aşağı (hızlı) ya da iki yön.</p>
 */
public final class BaselineMath {

    /** Etkin σ'nın alt sınırı: ortalamanın mutlak değerinin %2'si (dbquery {@code SIGMA_FLOOR_RATIO} ile BİREBİR). */
    public static final double SIGMA_FLOOR_RATIO = 0.02;

    public static final String LEVEL_NONE = "none", LEVEL_WARNING = "warning", LEVEL_CRITICAL = "critical";

    /** Örnek < {@code minSamples}: taban henüz kuruluyor (yeterince karşılaştırılabilir geçmiş yok). */
    public static final String NOTE_BUILDING_BASELINE = "building_baseline";
    /** Geçmiş yeterli çalıştırma içeriyor ama değeri boş / dışlanan satırlar örnek vermedi (kanıt eksikliği). */
    public static final String NOTE_INSUFFICIENT_SAMPLES = "insufficient_samples";
    /** Karşılaştırılabilir geçmiş kesildi (izlemenin hedefi / tanımı değişti) — taban sıfırlandı. */
    public static final String NOTE_BASELINE_RESET = "baseline_reset";

    /** Sınırsız sapmanın gösterilen değeri (σ = 0 iken). */
    public static final double UNBOUNDED_DEVIATION = 999.0;

    private BaselineMath() {}

    /** Bir geçmiş örneği: ölçü (ms) + bu örneğin tabanı İHLAL edip etmediği (Y1 dışlaması için). */
    public record Sample(double value, boolean breach) {}

    /**
     * Kuralın karşılaştırılabilir geçmişi (örnekler YENİDEN ESKİYE sıralı).
     *
     * @param samples  en yeni → en eski örnekler (ihlal bayrağıyla); mevsimsel okumada hedeflerin en yakın örnekleri
     * @param complete geçmişin TAMAMI okundu (okuma sınırına değilmedi) — kontrollü uyumun "geçmişi kaplıyor" kolu bunu ister
     * @param reset    karşılaştırılabilir geçmiş bir sıfırlanmayla kesildi (örnek yoksa {@link #NOTE_BASELINE_RESET})
     */
    public record History(List<Sample> samples, boolean complete, boolean reset) {
        public History {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        public static final History EMPTY = new History(List.of(), true, false);
    }

    /**
     * Karar. {@code breached} = bu değer yapılandırılan yönde tabanı aştı mı (yeni örnek bu bayrakla saklanır → gelecekte
     * Y1 dışlaması). {@code mean/sigma/low/high/deviation} görüntü + saklama içindir.
     *
     * @param level     {@link #LEVEL_NONE} / {@link #LEVEL_WARNING} / {@link #LEVEL_CRITICAL}
     * @param note      {@link #NOTE_BUILDING_BASELINE} / {@link #NOTE_INSUFFICIENT_SAMPLES} / {@link #NOTE_BASELINE_RESET} ya da null
     * @param samples   tabanda kullanılan örnek sayısı
     * @param deviation z = (değer − ortalama) / σ (6 ondalığa yuvarlı); σ = 0 iken ±{@value #UNBOUNDED_DEVIATION}
     * @param breached  level ≠ none (bu değer taban dışında)
     */
    public record Verdict(String level, String note, int samples, int minSamples, Double mean, Double sigma,
                          Double low, Double high, Double deviation, boolean breached) {

        public boolean evaluated() {
            return mean != null;   // ortalama var = taban kuruldu, gerçek karar verildi
        }
    }

    /** {@link #baseSamples} sonucu: taban örnekleri + yürüme ölçüleri. */
    record Base(List<Double> samples, int seen, int consumed, boolean cut) {}

    /**
     * Etkin σ ("göreli taban", TEK yer): {@code max(σ, |ortalama| × }{@value #SIGMA_FLOOR_RATIO}{@code )}. dbquery
     * {@code effectiveSigma} ile birebir.
     */
    public static double effectiveSigma(double mean, double sigma) {
        double floor = Math.abs(mean) * SIGMA_FLOOR_RATIO;
        double s = Double.isFinite(sigma) ? sigma : 0;
        return Math.max(s, floor);
    }

    /**
     * Taban örneklerinin seçimi (Y1 — ihlal dışlaması + kontrollü uyum). Geçmiş yeniden eskiye yürünür: ihlal etmeyen
     * örnekler tabana girer; ardışık ihlal dizisi {@code window} uzunluğa ulaştıysa — ya da dizi geçmişin tamamını
     * ({@link History#complete}) kaplıyor ve ≥ {@code minSamples} ise — o dizi de tabana girer (yeni düzey olağan). Değeri
     * boş örnek yoktur (yanıt süresi her zaman bir sayıdır); yine de genel kalması için null değer pencereyi doldurur ama
     * örnek sayılmaz. dbquery {@code anomalyBase} ile birebir.
     */
    static Base baseSamples(History history, int window, int minSamples) {
        List<Sample> pts = history == null ? List.of() : history.samples();
        boolean complete = history == null || history.complete();
        List<Double> samples = new ArrayList<>();
        int seen = 0, consumed = 0, i = 0, n = pts.size();
        while (i < n && consumed < window) {
            Sample p = pts.get(i);
            if (!p.breach()) {
                seen++;
                consumed++;
                samples.add(p.value());
                i++;
                continue;
            }
            int j = i, len = 0;   // ardışık ihlal dizisi [i, j)
            while (j < n && pts.get(j).breach()) {
                len++;
                j++;
            }
            boolean accept = len >= window || (j >= n && complete && len >= minSamples);
            for (int k = i; k < j && consumed < window; k++) {
                seen++;
                consumed++;
                if (accept) samples.add(pts.get(k).value());
            }
            i = j;
        }
        return new Base(List.copyOf(samples), seen, consumed, i >= n && !complete);
    }

    /**
     * Karar — değer ({@code v}) ve geçmiş ({@code history}) bilindiğinde. Örnek &lt; {@code minSamples}: geçmiş yeterince
     * çalıştırma içeriyorsa {@link #NOTE_INSUFFICIENT_SAMPLES} (kanıt eksikliği), değilse {@link #NOTE_BUILDING_BASELINE}
     * (taban sıfırlandıysa ve örnek yoksa {@link #NOTE_BASELINE_RESET}) — hiçbiri karar değildir ({@code evaluated=false},
     * level none). Ortalama ve ÖRNEK standart sapması (n − 1); etkin σ {@link #effectiveSigma}; sapma z = (değer − ort) / σ.
     * Yön: iki yön |z|, yukarı z, aşağı −z; kritik katsayısını AŞAN sapma kritik, uyarı katsayısını aşan uyarı (sınırın
     * kendisi olağan). dbquery {@code anomalyAgainst} ile birebir.
     */
    public static Verdict evaluate(double v, History history, BaselineParams rawParams) {
        BaselineParams p = (rawParams == null ? BaselineParams.defaults() : rawParams).normalized();
        Base base = baseSamples(history, p.window(), p.minSamples());
        List<Double> all = base.samples();
        int n = all.size();
        if (n < p.minSamples()) {
            boolean seasonal = p.seasonal();
            boolean insufficient = seasonal ? base.seen() >= p.minSamples() : base.consumed() >= p.window() || base.cut();
            String note = insufficient ? NOTE_INSUFFICIENT_SAMPLES
                    : n == 0 && history != null && history.reset() ? NOTE_BASELINE_RESET : NOTE_BUILDING_BASELINE;
            return new Verdict(LEVEL_NONE, note, n, p.minSamples(), null, null, null, null, null, false);
        }
        double sum = 0, min = Double.POSITIVE_INFINITY;
        for (double x : all) {
            sum += x;
            if (x < min) min = x;
        }
        double mean = sum / n, ss = 0;
        for (double x : all) {
            double d = x - mean;
            ss += d * d;
        }
        double sigma = effectiveSigma(mean, n > 1 ? Math.sqrt(ss / (n - 1)) : 0);
        double dev;
        // İlkel eşitlik (D2): −0.0 == 0.0 — "−0" sonucu sınırsız sapma sayılmaz
        if (sigma == 0) dev = v == mean ? 0 : (v > mean ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        else dev = round((v - mean) / sigma);
        double toward = switch (p.direction()) {
            case BaselineParams.DIRECTION_UP -> dev;
            case BaselineParams.DIRECTION_DOWN -> -dev;
            default -> Math.abs(dev);
        };
        String level = toward > p.kCritical() ? LEVEL_CRITICAL : toward > p.kWarning() ? LEVEL_WARNING : LEVEL_NONE;
        double low = mean - p.kWarning() * sigma, high = mean + p.kWarning() * sigma;
        // Görüntü: geçmişin hepsi ≥ 0 ise (yanıt süreleri) alt uç 0'ın altına yazılmaz — karar ham sapmayla verilir
        if (min >= 0 && low < 0) low = 0;
        double shown = Double.isInfinite(dev) ? Math.copySign(UNBOUNDED_DEVIATION, dev) : dev;
        return new Verdict(level, null, n, p.minSamples(), round(mean), round(sigma), round(low), round(high), shown,
                !LEVEL_NONE.equals(level));
    }

    /** 6 ondalığa yuvarlar (dbquery {@code round} ile birebir). */
    static double round(double v) {
        if (!Double.isFinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
