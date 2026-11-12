package com.sitemonitor.service.baseline;

import com.sitemonitor.service.dbquery.DbQueryRules;

/**
 * Dinamik yanıt-süresi taban çizgisi (anomali) parametreleri — izleme türlerine genelleştirilen dbquery akıllı kuralı
 * ({@link DbQueryRules.Anomaly}) ile AYNI matematiği kullanır (ortalama ± k·σ, mevsimsellik, kontrollü uyum). dbquery'den
 * farkı: tek skaler ölçü (yanıt süresi ms) ve varsayılan yön YUKARI ("normalin dışında YAVAŞ" — site yavaşlamasını proaktif
 * yakalamak). Yavaşlama izlemesi için {@code direction} genelde {@link #DIRECTION_UP}.
 *
 * <p>Sabitler ve sınırlar dbquery ile BİREBİR aynıdır (sürüklenmesin diye {@code BaselineParamsTest} kapısı
 * {@link DbQueryRules} ile eşitler). Alt altyapı türden bağımsızdır; dbquery'nin kolon/aggregate mantığı yoktur.</p>
 *
 * @param direction  {@link #DIRECTION_UP} (yavaş), {@link #DIRECTION_DOWN} (hızlı — nadir), {@link #DIRECTION_BOTH}
 * @param kWarning   uyarı eşiği (σ katsayısı); {@code kWarning ≤ kCritical}
 * @param kCritical  kritik eşiği (σ katsayısı)
 * @param window     olağan aralığın hesaplandığı en yeni çalıştırma sayısı (10–200)
 * @param minSamples olağan aralık için gereken en az örnek (5 … window)
 * @param season     {@link #SEASON_NONE} / {@link #SEASON_DAILY} / {@link #SEASON_WEEKLY}
 */
public record BaselineParams(String direction, double kWarning, double kCritical, int window, int minSamples,
                             String season) {

    public static final String DIRECTION_UP = DbQueryRules.DIRECTION_UP,
            DIRECTION_DOWN = DbQueryRules.DIRECTION_DOWN,
            DIRECTION_BOTH = DbQueryRules.DIRECTION_BOTH;
    public static final String SEASON_NONE = DbQueryRules.SEASON_NONE,
            SEASON_DAILY = DbQueryRules.SEASON_DAILY,
            SEASON_WEEKLY = DbQueryRules.SEASON_WEEKLY;

    public static final double K_MIN = DbQueryRules.K_MIN, K_MAX = DbQueryRules.K_MAX,
            K_WARNING_DEFAULT = DbQueryRules.K_WARNING_DEFAULT, K_CRITICAL_DEFAULT = DbQueryRules.K_CRITICAL_DEFAULT;
    public static final int WINDOW_MIN = DbQueryRules.WINDOW_MIN, WINDOW_MAX = DbQueryRules.WINDOW_MAX,
            WINDOW_DEFAULT = DbQueryRules.WINDOW_DEFAULT, MIN_SAMPLES_MIN = DbQueryRules.MIN_SAMPLES_MIN,
            MIN_SAMPLES_DEFAULT = DbQueryRules.MIN_SAMPLES_DEFAULT;

    /** Yavaşlama izlemesinin öntanımlısı: YUKARI yön, k 2,5 / 3,5, pencere 30, en az 10 örnek, mevsimsiz. */
    public static BaselineParams defaults() {
        return new BaselineParams(DIRECTION_UP, K_WARNING_DEFAULT, K_CRITICAL_DEFAULT, WINDOW_DEFAULT,
                MIN_SAMPLES_DEFAULT, SEASON_NONE);
    }

    /** Geçersiz / sınır dışı değerleri öntanımlıya kırpar (lenient okuma — bozuk ayar asla patlamaz). */
    public BaselineParams normalized() {
        String dir = DIRECTION_UP.equals(direction) || DIRECTION_DOWN.equals(direction) || DIRECTION_BOTH.equals(direction)
                ? direction : DIRECTION_UP;
        String se = SEASON_NONE.equals(season) || SEASON_DAILY.equals(season) || SEASON_WEEKLY.equals(season)
                ? season : SEASON_NONE;
        double kw = clampD(kWarning, K_MIN, K_MAX, K_WARNING_DEFAULT);
        double kc = clampD(kCritical, K_MIN, K_MAX, K_CRITICAL_DEFAULT);
        if (kc < kw) kc = Math.max(kw, K_CRITICAL_DEFAULT);
        int w = clampI(window, WINDOW_MIN, WINDOW_MAX, WINDOW_DEFAULT);
        int ms = clampI(minSamples, MIN_SAMPLES_MIN, w, Math.min(MIN_SAMPLES_DEFAULT, w));
        return new BaselineParams(dir, kw, kc, w, ms, se);
    }

    /** dbquery Anomaly'ye köprü (aynı matematik; geçmiş okuma planı bunu kullanır). */
    public DbQueryRules.Anomaly toAnomaly() {
        BaselineParams n = normalized();
        return new DbQueryRules.Anomaly(n.direction(), n.kWarning(), n.kCritical(), n.window(), n.minSamples(), n.season());
    }

    public boolean seasonal() {
        return !SEASON_NONE.equals(season);
    }

    /** Sonlu değilse öntanımlı, aksi hâlde EN YAKIN sınıra kırpar (dbquery lenient {@code coerce} ile aynı). */
    private static double clampD(double v, double lo, double hi, double def) {
        if (!Double.isFinite(v)) return def;
        return Math.max(lo, Math.min(hi, v));
    }

    /** En yakın sınıra kırpar ({@code lo > hi} ise {@code lo}). */
    private static int clampI(int v, int lo, int hi, int def) {
        if (lo > hi) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
