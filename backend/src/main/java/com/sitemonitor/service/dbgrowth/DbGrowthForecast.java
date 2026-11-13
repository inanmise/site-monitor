package com.sitemonitor.service.dbgrowth;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Veritabanı büyüme tahmini — SAF hesap (2026-11-13). Durum yok, saat yok, sorgu yok; tablo testli
 * ({@code DbGrowthForecastTest}).
 *
 * <ul>
 *   <li><b>Eğim:</b> son {@value #WINDOW_DAYS} gündeki noktalarda (en az {@value #MIN_POINTS}) Theil–Sen — bütün nokta
 *       çiftlerinin eğimlerinin ortancası. Tek bir sıçrama (toplu içe aktarma, VACUUM FULL) eğimi kaydırmaz. Bant = çift
 *       eğimlerinin 25. ve 75. yüzdelikleri (doğrusal ara değer).</li>
 *   <li><b>Tablo başına doygunluk:</b> yaş kuralı olan tablo henüz dolmadıysa (en eski kayıt saklama süresinden genç)
 *       tahmin = min(doğrusal, dengelenme). Dolmuş tablo doğrusal (ekleme ≈ silme → eğim ≈ 0).</li>
 *   <li><b>Veritabanı:</b> tablo dışı pay (katalog, boş alan) sabit + tablo tahminlerinin toplamı. Tablo verisi yoksa
 *       veritabanının kendi eğimiyle doğrusal.</li>
 *   <li><b>Soğuk başlangıç:</b> eğim yoksa (geçmiş &lt; {@value #MIN_POINTS} gün) tablonun tahmini günlük artışı
 *       ({@link #histogramFractionBefore} ya da tekdüze varsayım) ve ±%50 bant — "tahmin" etiketiyle.</li>
 * </ul>
 */
public final class DbGrowthForecast {

    private DbGrowthForecast() {}

    /** Eğim penceresi (gün). */
    public static final int WINDOW_DAYS = 30;
    /** Eğim için en az nokta. */
    public static final int MIN_POINTS = 7;
    /** Ekrandaki projeksiyon ufukları (gün). */
    public static final int[] HORIZONS = {30, 90, 180};
    /** Dolmaya kalan gün aramasının üst sınırı (gün; ~10 yıl). */
    public static final int MAX_FULL_DAYS = 3650;
    /** Soğuk başlangıç bandı (oran): düşük = hız × 0,5, yüksek = hız × 1,5. */
    public static final double COLD_BAND = 0.5;

    /** Bir gözlem: {@code day} = epoch günü, {@code value} = bayt. */
    public record Point(long day, double value) {}

    /** Theil–Sen sonucu: günlük eğim (bayt/gün), bant ve kullanılan nokta sayısı. */
    public record Fit(double slope, double low, double high, int points) {}

    /** Bir tablonun tahmin girdisi. {@code fit} null → {@code coldRate} (null → büyümüyor sayılır). */
    public record TableInput(String name, double latestBytes, Fit fit, Double coldRate, Double equilibriumBytes) {}

    /** Bir ufuktaki değer ve bant. */
    public record Projection(double value, double low, double high) {}

    /**
     * Theil–Sen. Yalnız en yeni noktadan geriye {@value #WINDOW_DAYS} gün içindeki noktalar; aynı gündeki ikinci nokta
     * yok sayılır. {@value #MIN_POINTS}'den az nokta → null.
     */
    public static Fit fit(List<Point> points) {
        if (points == null || points.isEmpty()) return null;
        List<Point> sorted = new ArrayList<>(points);
        sorted.removeIf(p -> p == null || Double.isNaN(p.value()) || Double.isInfinite(p.value()));
        if (sorted.isEmpty()) return null;
        sorted.sort(Comparator.comparingLong(Point::day));
        long newest = sorted.get(sorted.size() - 1).day();
        List<Point> win = new ArrayList<>();
        long lastDay = Long.MIN_VALUE;
        for (Point p : sorted) {
            if (p.day() < newest - WINDOW_DAYS || p.day() == lastDay) continue;
            win.add(p);
            lastDay = p.day();
        }
        int n = win.size();
        if (n < MIN_POINTS) return null;
        double[] slopes = new double[n * (n - 1) / 2];
        int k = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                slopes[k++] = (win.get(j).value() - win.get(i).value()) / (double) (win.get(j).day() - win.get(i).day());
            }
        }
        Arrays.sort(slopes);
        return new Fit(quantile(slopes, 0.5), quantile(slopes, 0.25), quantile(slopes, 0.75), n);
    }

    /** Sıralı dizinin {@code q} yüzdeliği (doğrusal ara değer, R tip 7). */
    static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        if (sorted.length == 1) return sorted[0];
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        double frac = pos - lo;
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
    }

    /** Tablo tahmini {@code days} gün sonra. Değerler 0'ın altına inmez; doymamış tabloda dengelenmeyle sınırlanır. */
    public static Projection project(TableInput t, int days) {
        double s, lo, hi;
        if (t.fit() != null) {
            s = t.fit().slope();
            lo = t.fit().low();
            hi = t.fit().high();
        } else if (t.coldRate() != null) {
            s = t.coldRate();
            lo = t.coldRate() * (1 - COLD_BAND);
            hi = t.coldRate() * (1 + COLD_BAND);
        } else {
            s = lo = hi = 0;
        }
        double base = Math.max(0, t.latestBytes());
        double v = Math.max(0, base + s * days);
        double l = Math.max(0, base + lo * days);
        double h = Math.max(0, base + hi * days);
        if (t.equilibriumBytes() != null) {
            double cap = Math.max(base, t.equilibriumBytes());
            v = Math.min(v, cap);
            l = Math.min(l, cap);
            h = Math.min(h, cap);
        }
        return new Projection(v, Math.min(l, v), Math.max(h, v));
    }

    /**
     * Veritabanı tahmini {@code days} gün sonra: tablo dışı sabit pay + tablo tahminleri. Tablo listesi boşsa {@code dbFit}
     * ile doğrusal (o da yoksa null).
     */
    public static Projection projectDatabase(double dbLatest, List<TableInput> tables, Fit dbFit, int days) {
        if (tables == null || tables.isEmpty()) {
            if (dbFit == null) return null;
            double v = Math.max(0, dbLatest + dbFit.slope() * days);
            double l = Math.max(0, dbLatest + dbFit.low() * days);
            double h = Math.max(0, dbLatest + dbFit.high() * days);
            return new Projection(v, Math.min(l, v), Math.max(h, v));
        }
        double tablesNow = 0;
        for (TableInput t : tables) tablesNow += Math.max(0, t.latestBytes());
        double fixed = Math.max(0, dbLatest - tablesNow);
        double v = fixed, l = fixed, h = fixed;
        for (TableInput t : tables) {
            Projection p = project(t, days);
            v += p.value();
            l += p.low();
            h += p.high();
        }
        return new Projection(v, l, h);
    }

    /**
     * Merkez tahminin kapasiteye ulaştığı ilk gün (0 = zaten dolu); {@value #MAX_FULL_DAYS} gün içinde ulaşmıyorsa null.
     * Kapasite bilinmiyorsa (≤ 0) null.
     */
    public static Integer daysUntilFull(double capacityBytes, double dbLatest, List<TableInput> tables, Fit dbFit) {
        if (capacityBytes <= 0) return null;
        if (dbLatest >= capacityBytes) return 0;
        // Tahmin günlere göre tekdüze artmayabilir (negatif eğimli tablo) → gün gün tarama; tavan sınırlı
        for (int d = 1; d <= MAX_FULL_DAYS; d++) {
            Projection p = projectDatabase(dbLatest, tables, dbFit, d);
            if (p == null) return null;
            if (p.value() >= capacityBytes) return d;
        }
        return null;
    }

    /** Dolmuş sayılır mı: en eski kaydın yaşı saklama süresinin 1 gün yakınında ya da üstünde. */
    public static boolean saturated(Double oldestAgeDays, int retentionDays) {
        return oldestAgeDays != null && retentionDays > 0 && oldestAgeDays >= retentionDays - 1;
    }

    /**
     * Dengelenme boyutu (doymamış yaş kuralı): kapsanan gün başına bayt × saklama günü. En eski kayıt en az 3 gün eski
     * değilse (tablo yeni) tahmin edilmez (null) — birkaç saatlik veriyle sapma çok büyük olur.
     */
    public static Double equilibrium(double totalBytes, Double oldestAgeDays, int retentionDays) {
        if (oldestAgeDays == null || retentionDays <= 0 || oldestAgeDays < 3) return null;
        if (saturated(oldestAgeDays, retentionDays)) return null;
        return totalBytes * retentionDays / oldestAgeDays;
    }

    // ── Histogram (pg_stats.histogram_bounds) ───────────────────────────────────────────────────────────

    /**
     * Eşit frekanslı histogramda {@code cutoff}'tan ÖNCE kalan satırların oranı (0..1). Sınırlar küçükten büyüğe zaman
     * değerleri (ISO / "yyyy-MM-dd HH:mm:ss" / gün / saat kovası). İki sınırdan az ya da okunamayan sınır → null. Kova içinde
     * zaman üzerinden doğrusal ara değer.
     */
    public static Double histogramFractionBefore(List<String> bounds, Instant cutoff) {
        if (bounds == null || bounds.size() < 2 || cutoff == null) return null;
        long c = cutoff.getEpochSecond();
        long[] t = new long[bounds.size()];
        for (int i = 0; i < t.length; i++) {
            Long v = epochSeconds(bounds.get(i));
            if (v == null) return null;
            t[i] = v;
        }
        int n = t.length;
        if (c <= t[0]) return 0.0;
        if (c >= t[n - 1]) return 1.0;
        for (int i = 1; i < n; i++) {
            if (c < t[i]) {
                long span = t[i] - t[i - 1];
                double within = span <= 0 ? 1.0 : (double) (c - t[i - 1]) / span;
                return ((i - 1) + within) / (n - 1);
            }
        }
        return 1.0;
    }

    /** Sınır metni → epoch saniye (UTC kabul); okunamazsa null. */
    static Long epochSeconds(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        s = s.replace(' ', 'T');
        try {
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            if (s.length() == 13) return LocalDateTime.parse(s + ":00:00").toEpochSecond(ZoneOffset.UTC);
            if (s.length() >= 19) return LocalDateTime.parse(s.substring(0, 19)).toEpochSecond(ZoneOffset.UTC);
            if (s.length() == 16) return LocalDateTime.parse(s + ":00").toEpochSecond(ZoneOffset.UTC);
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code pg_stats.histogram_bounds::text} ayrıştırıcı — {@code {"a","b",c}} biçimi; tırnaklı öğeler virgül içerebilir.
     * Doğrusal tarama; en çok {@code max} öğe.
     */
    public static List<String> parseArray(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String s = text.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length() && out.size() < max; i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '\\' && i + 1 < s.length()) { cur.append(s.charAt(++i)); continue; }
                if (ch == '"') { quoted = false; continue; }
                cur.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0 && out.size() < max) out.add(cur.toString());
        return out;
    }
}
