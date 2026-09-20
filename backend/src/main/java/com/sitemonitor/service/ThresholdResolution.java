package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.repository.AlertThresholdRepository;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier bazlı alarm eşiği çözümü (2026-09-20).
 *
 * <p>{@code alert_thresholds} tablosunda TEK varsayılan satır ({@code tier = null}) ve isteğe bağlı
 * tier satırları ({@code tier = 1..4}) bulunur. Bir alanın etkin eşiği: envanterdeki tier'ına ait aktif
 * satır varsa o, yoksa varsayılan. Müşteri yüzü (Tier 1) sistemler böylece daha erken uyarılır;
 * tier'sız/dev alanlar varsayılana düşer.
 *
 * <p>Tek okuma: {@link #load} tabloyu bir kez okur; toplu döngülerde (sweep, liste, istatistik) alan
 * başına sorgu atılmaz. Spring bean DEĞİL — çağıran her seferinde taze yükler (tablo küçük, sıcak yollar
 * zaten cache'li) ve mevcut mock tabanlı testlerde repo stub'ları olduğu gibi çalışır.
 */
public final class ThresholdResolution {

    private final AlertThreshold defaultThreshold;
    private final Map<Integer, AlertThreshold> byTier;

    private ThresholdResolution(AlertThreshold defaultThreshold, Map<Integer, AlertThreshold> byTier) {
        this.defaultThreshold = defaultThreshold;
        this.byTier = byTier;
    }

    /** Repo'dan yükler; varsayılan satır yoksa {@code fallback} (null verilirse 30/15/7/24). */
    public static ThresholdResolution load(AlertThresholdRepository repo, AlertThreshold fallback) {
        AlertThreshold def = null;
        Map<Integer, AlertThreshold> tiers = new HashMap<>();
        try {
            def = repo.findFirstByActiveTrue().orElse(null);
            List<AlertThreshold> overrides = repo.findByActiveTrueAndTierIsNotNullOrderByIdAsc();
            if (overrides != null) {
                for (AlertThreshold t : overrides) {
                    if (t != null && t.getTier() != null) tiers.putIfAbsent(t.getTier(), t);   // ilk (en eski) kazanır
                }
            }
        } catch (RuntimeException e) {
            // Eşik okunamazsa alarm üretimi DURMAZ: varsayılana düşülür (safeThreshold deseni).
        }
        if (def == null) def = fallback != null ? fallback : defaults();
        return new ThresholdResolution(def, Collections.unmodifiableMap(tiers));
    }

    /** Sabit eşikle çözüm (testler / eşik önizleme). */
    public static ThresholdResolution fixed(AlertThreshold t) {
        return new ThresholdResolution(t != null ? t : defaults(), Map.of());
    }

    public static AlertThreshold defaults() {
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30);
        t.setHighDays(15);
        t.setCriticalDays(7);
        t.setReAlertIntervalHours(24);
        return t;
    }

    /** Varsayılan (tier'sız) eşik. */
    public AlertThreshold defaultThreshold() { return defaultThreshold; }

    /** Tier için geçerli satır — tier'a özel aktif satır yoksa varsayılan. */
    public AlertThreshold forTier(Integer tier) {
        if (tier == null) return defaultThreshold;
        AlertThreshold t = byTier.get(tier);
        return t != null ? t : defaultThreshold;
    }

    /** Bu tier'ın KENDİ satırı var mı (önizleme: "varsayılanı hangi alanlar kullanır" sorusu için). */
    public boolean hasOverride(Integer tier) { return tier != null && byTier.containsKey(tier); }

    public Map<Integer, AlertThreshold> overrides() { return byTier; }

    /** {kritik, yüksek, uyarı} gün — null alanlar varsayılana çekilir (unboxing NPE yok). */
    public int[] days(Integer tier) {
        AlertThreshold t = forTier(tier);
        return new int[]{
                t.getCriticalDays() != null ? t.getCriticalDays() : 7,
                t.getHighDays()     != null ? t.getHighDays()     : 15,
                t.getWarningDays()  != null ? t.getWarningDays()  : 30,
        };
    }

    public int criticalDays(Integer tier) { return days(tier)[0]; }
    public int highDays(Integer tier)     { return days(tier)[1]; }
    public int warningDays(Integer tier)  { return days(tier)[2]; }

    /** Kalan güne göre seviye: CRITICAL / HIGH / WARNING / null (eşik dışı). Sertifika süre alarmı ile aynı kural. */
    public String levelFor(Integer tier, Integer daysRemaining) {
        if (daysRemaining == null) return null;
        int[] d = days(tier);
        if (daysRemaining <= d[0]) return "CRITICAL";
        if (daysRemaining <= d[1]) return "HIGH";
        if (daysRemaining <= d[2]) return "WARNING";
        return null;
    }
}
