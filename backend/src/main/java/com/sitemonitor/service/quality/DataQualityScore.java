package com.sitemonitor.service.quality;

import java.util.Map;

/**
 * Veri kalitesi PUAN FORMÜLÜ — saf, durumsuz (birim testi doğruluk tablosuyla pinler).
 *
 * <h2>Formül</h2>
 * Bir kova (takım, Sahipsiz ya da kurum) için her kural {@code r}'nin uygun öğe sayısı {@code e_r} (kuralın baktığı
 * öğeler) ve kusurlu öğe sayısı {@code f_r} vardır. Uygun öğesi olmayan kural ({@code e_r = 0}) o kovada
 * <b>uygulanmaz</b> (ne puan verir ne puan alır). Uygulanan kurallar üzerinde:
 * <pre>
 *   sağlık_r = 1 − f_r / e_r                       (0..1 — kuralın sağlıklı öğe ORANI)
 *   puan     = round( 100 × Σ w_r · sağlık_r / Σ w_r )
 * </pre>
 * Oran kullanıldığı için büyük takım boyutundan dolayı cezalanmaz: 1000 kaydın 10'unda tier eksik olan takım bu
 * kuralda 0,99, 10 kaydın 1'inde eksik olan 0,90 alır. Takım kuralları (üye, müdür, adres, eskalasyon) takım başına
 * tek öğedir → 0 ya da 1.
 *
 * <p><b>Kaybedilen puan</b> (arayüzdeki "−N puan"): {@code 100 × w_r · (f_r/e_r) / Σ w_r} — o kural tümüyle
 * düzeltilirse puanın ne kadar artacağı. Kayıpların toplamı {@code 100 − puan}'a eşittir (yuvarlama hariç).
 *
 * <h2>Bantlar</h2>
 * {@code ≥ 90} Mükemmel · {@code ≥ 75} İyi · {@code ≥ 50} İyileştirilmeli · {@code < 50} Zayıf. Hiç kural
 * uygulanmıyorsa puan YOKTUR ({@code null}, bant {@code NO_DATA}) — boş takım "100" görünüp sıralamanın tepesine
 * çıkmasın.
 */
public final class DataQualityScore {

    private DataQualityScore() {}

    public static final int EXCELLENT_MIN = 90;
    public static final int GOOD_MIN = 75;
    public static final int FAIR_MIN = 50;

    /** Bant kodları — arayüz {@code dq.band.<KOD>} ile çevirir. */
    public enum Band { EXCELLENT, GOOD, NEEDS_ATTENTION, POOR, NO_DATA }

    /** Kural başına sayaç: uygun ({@code eligible}) ve kusurlu ({@code failing}) öğe sayısı. */
    public record Count(int eligible, int failing) {
        public Count {
            if (eligible < 0 || failing < 0) throw new IllegalArgumentException("negatif sayaç");
            if (failing > eligible) throw new IllegalArgumentException("kusurlu > uygun");
        }

        public boolean applies() { return eligible > 0; }

        /** Sağlıklı öğe oranı (0..1); uygulanmayan kuralda 1. */
        public double health() { return eligible == 0 ? 1.0 : 1.0 - (double) failing / eligible; }
    }

    /** Puan (0..100) ya da hiç kural uygulanmıyorsa {@code null}. */
    public static Integer score(Map<DataQualityRule, Count> counts) {
        double num = 0, den = 0;
        for (Map.Entry<DataQualityRule, Count> e : counts.entrySet()) {
            Count c = e.getValue();
            if (c == null || !c.applies()) continue;
            int w = e.getKey().weight();
            num += w * c.health();
            den += w;
        }
        if (den == 0) return null;
        return (int) Math.round(100.0 * num / den);
    }

    /** Kuralın tümüyle düzeltilmesiyle kazanılacak puan (0..100, bir ondalık); uygulanmıyorsa 0. */
    public static double pointsLost(Map<DataQualityRule, Count> counts, DataQualityRule rule) {
        Count c = counts.get(rule);
        if (c == null || !c.applies() || c.failing() == 0) return 0;
        double den = 0;
        for (Map.Entry<DataQualityRule, Count> e : counts.entrySet()) {
            if (e.getValue() != null && e.getValue().applies()) den += e.getKey().weight();
        }
        if (den == 0) return 0;
        double lost = 100.0 * rule.weight() * ((double) c.failing() / c.eligible()) / den;
        return Math.round(lost * 10.0) / 10.0;
    }

    public static Band band(Integer score) {
        if (score == null) return Band.NO_DATA;
        if (score >= EXCELLENT_MIN) return Band.EXCELLENT;
        if (score >= GOOD_MIN) return Band.GOOD;
        if (score >= FAIR_MIN) return Band.NEEDS_ATTENTION;
        return Band.POOR;
    }
}
