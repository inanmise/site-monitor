package com.sitemonitor.service.metrics;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.util.Msg;

import java.util.List;

/**
 * İzleme ölçümleri aktarıcısının ayarları (2026-10-18, Ayarlar → Platform → Grafana panosu). Anahtarlar
 * {@code AppSettingsCatalog}'da grup {@code grafana}, GLOBAL_ONLY; varsayılanlar {@code application.properties}'te
 * ({@code METRICS_EXPORT_*}). {@link MonitoringMetricsExporter} her turda CANLI okur (yeniden başlatma yok).
 */
public final class MetricsExportSettings {

    private MetricsExportSettings() {}

    public static final String KEY_ENABLED = "site.monitor.metrics.export.enabled";
    public static final String KEY_PER_MONITOR = "site.monitor.metrics.export.per-monitor";
    public static final String KEY_MAX_ENTITIES = "site.monitor.metrics.export.max-entities";
    public static final String KEY_REFRESH_SECONDS = "site.monitor.metrics.export.refresh-seconds";

    public static final List<String> KEYS = List.of(KEY_ENABLED, KEY_PER_MONITOR, KEY_MAX_ENTITIES, KEY_REFRESH_SECONDS);

    /** Aile başına (izlemeler, sertifikalar, alan adları, alarmlar) en çok kayıt — seri patlamasına karşı tavan. */
    public static final int MAX_ENTITIES_DEFAULT = 5000;
    public static final int MAX_ENTITIES_MIN = 100;
    public static final int MAX_ENTITIES_MAX = 50_000;
    /** Tazeleme aralığı (sn). */
    public static final int REFRESH_DEFAULT = 60;
    public static final int REFRESH_MIN = 30;
    public static final int REFRESH_MAX = 600;

    public static boolean isKey(String key) {
        return key != null && KEYS.contains(key);
    }

    public static boolean enabled(AppSettingsService s) {
        return s == null || s.getBoolean(KEY_ENABLED, true);
    }

    public static boolean perMonitor(AppSettingsService s) {
        return s == null || s.getBoolean(KEY_PER_MONITOR, true);
    }

    public static int maxEntities(AppSettingsService s) {
        int v = s == null ? MAX_ENTITIES_DEFAULT : s.getInt(KEY_MAX_ENTITIES, MAX_ENTITIES_DEFAULT);
        return Math.max(MAX_ENTITIES_MIN, Math.min(MAX_ENTITIES_MAX, v));
    }

    public static int refreshSeconds(AppSettingsService s) {
        int v = s == null ? REFRESH_DEFAULT : s.getInt(KEY_REFRESH_SECONDS, REFRESH_DEFAULT);
        return Math.max(REFRESH_MIN, Math.min(REFRESH_MAX, v));
    }

    /** Kayıt doğrulaması ({@code AppSettingsService.validate} çağırır) — boş değer "varsayılana dön", burada gelmez. */
    public static void validate(String key, String val) {
        if (KEY_MAX_ENTITIES.equals(key)) range(val, MAX_ENTITIES_MIN, MAX_ENTITIES_MAX);
        if (KEY_REFRESH_SECONDS.equals(key)) range(val, REFRESH_MIN, REFRESH_MAX);
    }

    /** Aralık dışı / sayı olmayan → anlaşılır hata (alan adı denetleyicide eklenir). */
    public static String rangeProblem(String val, int min, int max) {
        try {
            int v = Integer.parseInt(val.trim());
            if (v >= min && v <= max) return null;
        } catch (Exception ignored) { /* aşağıda */ }
        return Msg.t(min + " ile " + max + " arasında bir tam sayı girin.", "Enter a whole number between " + min + " and " + max + ".");
    }

    private static void range(String val, int min, int max) {
        String p = rangeProblem(val, min, max);
        if (p != null) throw new IllegalArgumentException(p);
    }
}
