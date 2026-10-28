package com.sitemonitor.service.evalstate;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bastırma penceresi TOHUMU — TEK kaynak (2026-10-28, çok pod Sürüm 3-2; tasarım R3-2 §1.3). Hem yükleyici
 * ({@link EvalStateLoader}: devirde MOS'un {@code suppressionWindow}'una konacak işaretler) hem doğrulayıcı
 * ({@link EvalStateVerifier}'ın {@code window_seed} ölçümü; çıktısı 3-1 ile BİREBİR aynı) bu kuralı kullanır — VERIFY'ın
 * ≥ 7 gün ölçtüğü tohum, yüklenenle aynı tohumdur.
 *
 * <p><b>Kural:</b> {@code monitor_last_status}'un her satırı (HER kuşak, HER kaynak — kalem başına tek satır, en yeni
 * (gen, seq) kazanır; yani her satır kalemin bilinen son gözlemidir) alan adı doluysa ve {@code observed_ms ≥ kesim} ise:
 * {@code tür → alan adı → (ağ DOWN |= (!up && network_down), an = max(an, observed_ms))}. Örnek: kesimden yeni işaretlerin
 * (ağ DOWN alan adı sayısı, alan adı sayısı).
 *
 * <p><b>Kesim TÜR BAŞINA</b> (inceleme 2026-10-28, kaydedici M-2): VERIFY türün kendi penceresiyle
 * ({@code şimdi − max(3 × tur aralığı, taban)}, en çok 60 dk) keser; yükleyici de AYNI kuralı kullanır
 * ({@link #loadMarks}). Tek bir 60 dk kesimi, bir alan adının 40 dk önceki ağ-DOWN satırını 1 dk önceki UP satırıyla
 * VEYA'layıp taze bir "ağ DOWN" işaretine çevirirdi — halefin ilk turu VERIFY'ın hiç ölçmediği bir bastırmaya girebilirdi.
 *
 * <p>Kabul edilen şişme (R-5, FX-STATE): elle ({@code M}) ve teyit-UP satırları dahil; {@code observed_ms} gerçek gözlemden
 * ≤ 120 sn ({@code last-status-refresh-ms}) geride olabilir. Saf sınıf — veritabanı yok, durum yok.
 */
public final class EvalWindowSeed {

    /**
     * Yükleyicinin pencere satırı kesimi — {@code MonitoringOutageService.SUPPRESSION_WINDOW_CAP_MS} (60 dk) aynası (paket
     * özel; {@code EvalWindowSeedTest} ikisini karşılaştırır). Türün kendi penceresinden eski işaretleri MOS yok sayar ve
     * sonraki turda budar (canlı işaretler gibi).
     */
    public static final long WINDOW_CAP_MS = 60L * 60 * 1000;

    /** {@link EvalStateSource#windowLengthMsFor} varsayılanının tabanı (MOS {@code suppressionWindowFloorMs} varsayılanı). */
    public static final long DEFAULT_WINDOW_FLOOR_MS = 300_000L;

    /** Bir türün bastırma penceresi uzunluğu, kaydedilmiş tur aralığından ({@link EvalStateSource#windowLengthMsFor}). */
    @FunctionalInterface
    public interface WindowLength {
        long windowMs(String alertType, long roundIntervalMs);
    }

    /**
     * Varsayılan pencere uzunluğu: {@code max(3 × aralık, 5 dk)}, en çok {@link #WINDOW_CAP_MS} — {@link EvalStateSource}
     * varsayılanının aynası ({@code EvalWindowSeedTest} ikisini karşılaştırır). Yalnız kaynak bağlı değilken / testlerde.
     */
    public static final WindowLength DEFAULT_WINDOW_LENGTH = (type, interval) -> Math.min(WINDOW_CAP_MS,
            Math.max(DEFAULT_WINDOW_FLOOR_MS, 3L * Math.max(0L, interval)));

    /** Bir pencere satırı ({@code monitor_last_status}: {@code alert_type, domain, up, network_down, observed_ms}). */
    public record Row(String alertType, String domain, boolean up, boolean networkDown, long observedMs) {}

    /** Örnek: ağ-sınıfı DOWN alan adı sayısı / toplam alan adı. */
    public record Sample(int networkDown, int total) {}

    private EvalWindowSeed() {
    }

    /**
     * Tüm türlerin işaretleri, TEK kesimle ({@code observed_ms ≥ cutoffMs} satırlardan). Değiştirilemez; null satır / tür /
     * alan adı atlanır. Yükleyici bunu DEĞİL {@link #loadMarks}'ı (tür başına kesim) kullanır.
     */
    public static Map<String, Map<String, EvalLoad.SeedMark>> marks(Iterable<Row> rows, long cutoffMs) {
        Map<String, Map<String, EvalLoad.SeedMark>> out = new LinkedHashMap<>();
        if (rows == null) return Map.of();
        for (Row r : rows) {
            if (r == null || r.alertType() == null || r.domain() == null || r.observedMs() < cutoffMs) continue;
            merge(out.computeIfAbsent(r.alertType(), k -> new LinkedHashMap<>()), r);
        }
        if (out.isEmpty()) return Map.of();
        Map<String, Map<String, EvalLoad.SeedMark>> frozen = new LinkedHashMap<>();
        out.forEach((t, m) -> frozen.put(t, Collections.unmodifiableMap(m)));
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * YÜKLEYİCİNİN işaretleri — VERIFY'ın {@code window_seed} kuralıyla AYNI: her tür KENDİ kesimiyle,
     * {@code kesim(tür) = şimdi − pencere(tür, aralık(tür))} ({@code aralık} = önceki kuşağın tür satırındaki tur aralığı,
     * bilinmiyorsa 0 → taban; pencere [0, {@link #WINDOW_CAP_MS}] aralığına sıkıştırılır). {@code loadMarks(…).get(t)} =
     * {@code marksOfType(rows, t, kesim(t))}. Pencere uzunluğu sorulamayan tür tohumlanmaz (o tür için yeniden başlatma
     * semantiği — boş pencere). Değiştirilemez.
     *
     * @param intervals önceki kuşağın tür satırlarından tur aralıkları (null / eksik = 0)
     * @param length    pencere uzunluğu kuralı (null = {@link #DEFAULT_WINDOW_LENGTH})
     */
    public static Map<String, Map<String, EvalLoad.SeedMark>> loadMarks(Iterable<Row> rows, long nowMs,
                                                                       Map<String, Long> intervals, WindowLength length) {
        if (rows == null) return Map.of();
        Map<String, Long> cutoffs = new HashMap<>();
        Map<String, Map<String, EvalLoad.SeedMark>> out = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r == null || r.alertType() == null || r.domain() == null) continue;
            long cutoff = cutoffs.computeIfAbsent(r.alertType(), t -> cutoffOf(t, nowMs, intervals, length));
            if (r.observedMs() < cutoff) continue;
            merge(out.computeIfAbsent(r.alertType(), k -> new LinkedHashMap<>()), r);
        }
        if (out.isEmpty()) return Map.of();
        Map<String, Map<String, EvalLoad.SeedMark>> frozen = new LinkedHashMap<>();
        out.forEach((t, m) -> frozen.put(t, Collections.unmodifiableMap(m)));
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * Bir türün yükleyici kesimi ({@link #loadMarks}): {@code şimdi − pencere}, pencere [0, 60 dk]; kural istisna verirse
     * {@code Long.MAX_VALUE} (hiçbir satır geçmez — tür tohumlanmaz).
     */
    static long cutoffOf(String alertType, long nowMs, Map<String, Long> intervals, WindowLength length) {
        Long iv = intervals == null ? null : intervals.get(alertType);
        long interval = iv == null ? 0L : Math.max(0L, iv);
        long w;
        try {
            w = (length == null ? DEFAULT_WINDOW_LENGTH : length).windowMs(alertType, interval);
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
        w = Math.max(0L, Math.min(WINDOW_CAP_MS, w));
        return nowMs - w;
    }

    /** Tek türün işaretleri ({@code observed_ms ≥ cutoffMs}). Değiştirilemez. */
    public static Map<String, EvalLoad.SeedMark> marksOfType(Iterable<Row> rows, String alertType, long cutoffMs) {
        Map<String, EvalLoad.SeedMark> out = new LinkedHashMap<>();
        if (rows == null || alertType == null) return Map.of();
        for (Row r : rows) {
            if (r == null || !alertType.equals(r.alertType()) || r.domain() == null || r.observedMs() < cutoffMs) continue;
            merge(out, r);
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    /** Kesimden yeni işaretlerin örneği ({@code an ≥ cutoffMs}). */
    public static Sample sample(Map<String, EvalLoad.SeedMark> marks, long cutoffMs) {
        if (marks == null || marks.isEmpty()) return new Sample(0, 0);
        int down = 0, total = 0;
        for (EvalLoad.SeedMark m : marks.values()) {
            if (m == null || m.atMs() < cutoffMs) continue;
            total++;
            if (m.networkDown()) down++;
        }
        return new Sample(down, total);
    }

    private static void merge(Map<String, EvalLoad.SeedMark> byDomain, Row r) {
        boolean netDown = !r.up() && r.networkDown();
        byDomain.merge(r.domain(), new EvalLoad.SeedMark(netDown, r.observedMs()),
                (a, b) -> new EvalLoad.SeedMark(a.networkDown() || b.networkDown(), Math.max(a.atMs(), b.atMs())));
    }
}
