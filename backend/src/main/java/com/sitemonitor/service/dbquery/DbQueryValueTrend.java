package com.sitemonitor.service.dbquery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kartın değer eğilimi (2026-11-05, dbq2 C1) — saf hesap. Son 24 saatin ZAMANLANMIŞ ve sorgusu çalışmış çalıştırmalarının
 * {@code metric_value}'su saatlik kovalarda ortalanır (UTC saat — kontrol satırının {@code checked_at}'i UTC ISO): 24 nokta,
 * en eskiden en yeniye; değeri olmayan saat {@code null} (asla 0 değil). Değer hiç yoksa eğilim YOK (alan yazılmaz).
 *
 * <p>{@code delta_pct}: ilk değerli noktadan son değerli noktaya yüzde değişim (1 ondalık) — en az iki değerli nokta ister;
 * ilk değer 0 ise yüzde tanımsızdır ({@code null}) ve yön işaretten okunur. {@code direction}: {@code up} | {@code down} |
 * {@code flat} (|değişim| &lt; {@value #FLAT_PCT} %) | {@code null}. Yön iyi / kötü SÖYLEMEZ (renk nötr — ihlal rozette).
 */
public final class DbQueryValueTrend {

    public static final int HOURS = 24;
    /** Bu yüzdenin altındaki değişim "düz" sayılır. */
    public static final double FLAT_PCT = 1.0;
    public static final String UP = "up", DOWN = "down", FLAT = "flat";

    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private DbQueryValueTrend() {}

    /** İlk kovanın başlangıcı: şimdiki saatin başından 23 saat önce. */
    public static Instant firstHour(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS).minus(HOURS - 1L, ChronoUnit.HOURS);
    }

    /** Sorgunun alt sınırı ({@code checked_at >= from}) — UTC ISO. */
    public static String fromIso(Instant now) {
        return ISO.format(firstHour(now));
    }

    /** Kova anahtarı ({@code checked_at}'in ilk 13 karakteri). */
    public static String hourKey(Instant hour) {
        return HOUR_KEY.format(hour);
    }

    /**
     * @param byHour saat anahtarı ({@code yyyy-MM-ddTHH}) → ortalama değer; null / boş → eğilim yok
     * @return {@code {points, delta_pct, direction}} ya da hiç değer yoksa null
     */
    public static Map<String, Object> build(Map<String, Double> byHour, Instant now) {
        if (byHour == null || byHour.isEmpty()) return null;
        Instant first = firstHour(now);
        List<Double> points = new ArrayList<>(HOURS);
        Double firstVal = null, lastVal = null;
        int firstIdx = -1, lastIdx = -1;
        for (int i = 0; i < HOURS; i++) {
            Double v = byHour.get(hourKey(first.plus(i, ChronoUnit.HOURS)));
            Double r = round(v);
            points.add(r);
            if (r != null) {
                if (firstVal == null) { firstVal = r; firstIdx = i; }
                lastVal = r;
                lastIdx = i;
            }
        }
        if (firstVal == null) return null;
        Double delta = null;
        String direction = null;
        if (lastIdx > firstIdx) {
            double diff = lastVal - firstVal;
            if (firstVal == 0d) {
                direction = diff > 0 ? UP : diff < 0 ? DOWN : FLAT;
            } else {
                double pct = diff / Math.abs(firstVal) * 100d;
                delta = BigDecimal.valueOf(pct).setScale(1, RoundingMode.HALF_UP).doubleValue();
                direction = Math.abs(pct) < FLAT_PCT ? FLAT : pct > 0 ? UP : DOWN;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", Collections.unmodifiableList(points));
        out.put("delta_pct", delta);
        out.put("direction", direction);
        return out;
    }

    /** 4 ondalık (taşmasız); NaN / sonsuz → null. */
    static Double round(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) return null;
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
