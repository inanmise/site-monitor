package com.sitemonitor.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sertifika sağlık sayacı (2026-11-13, İstatistikler zenginleştirmesi) — TEK kural, iki tüketici: İstatistik sayfasının
 * yönetici özeti ({@link ExecutiveStatsService}, "filo sağlığı" + takım satırları) ve 90 günlük günlük görüntü
 * ({@code CertHealthDailyService}). Eğri ile kutucuk aynı sayıyı söylesin diye kural burada.
 *
 * <ul>
 *   <li>Son kontrol yok ya da kalan gün bilinmiyor → {@code unchecked}; ama son kontrol HATA ise {@code error}.</li>
 *   <li>Durum {@code error} → {@code error} (kalan gün bilinse de).</li>
 *   <li>Kalan gün &lt; 0 → {@code expired}; ≤ 30 → {@code under30} (≤ 7 ise ayrıca {@code under7}); aksi → {@code ok}.</li>
 * </ul>
 * {@code evaluated} = toplam − kontrol edilmemiş; sağlık % = temiz / değerlendirilen (hiç değerlendirilmeyen → null).
 */
public final class CertHealthCounts {

    /** "30 gün altı" penceresi (yönetici özetinin {@code window_days}'i ile aynı). */
    public static final int WINDOW_DAYS = 30;

    private int total, ok, under30, under7, expired, error, unchecked;

    /** Bir sertifika: {@code hasCheck} son kontrol satırı var mı; {@code status} / {@code days} o satırdan. */
    public void add(boolean hasCheck, String status, Integer days) {
        total++;
        if (!hasCheck || days == null) {
            if (hasCheck && "error".equals(status)) error++; else unchecked++;
            return;
        }
        if ("error".equals(status)) { error++; return; }
        int d = days;
        if (d < 0) expired++;
        else if (d <= WINDOW_DAYS) { under30++; if (d <= 7) under7++; }
        else ok++;
    }

    public int total() { return total; }
    public int ok() { return ok; }
    public int under30() { return under30; }
    public int under7() { return under7; }
    public int expired() { return expired; }
    public int error() { return error; }
    public int unchecked() { return unchecked; }
    public int evaluated() { return total - unchecked; }

    /** Temiz / değerlendirilen, bir ondalık; değerlendirilen yoksa null. */
    public Double healthPct() {
        int ev = evaluated();
        return ev == 0 ? null : Math.round(1000.0 * ok / ev) / 10.0;
    }

    /** Yönetici özeti {@code certs} bloğunun alanları (sıra sabit). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total); m.put("ok", ok); m.put("under30", under30); m.put("under7", under7);
        m.put("expired", expired); m.put("error", error); m.put("unchecked", unchecked);
        m.put("evaluated", evaluated());
        m.put("health_pct", healthPct());
        return m;
    }
}
