package com.sitemonitor.service.evalstate;

import java.util.Collections;
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

    /** Bir pencere satırı ({@code monitor_last_status}: {@code alert_type, domain, up, network_down, observed_ms}). */
    public record Row(String alertType, String domain, boolean up, boolean networkDown, long observedMs) {}

    /** Örnek: ağ-sınıfı DOWN alan adı sayısı / toplam alan adı. */
    public record Sample(int networkDown, int total) {}

    private EvalWindowSeed() {
    }

    /** Tüm türlerin işaretleri ({@code observed_ms ≥ cutoffMs} satırlardan). Değiştirilemez; null satır / tür / alan adı atlanır. */
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
