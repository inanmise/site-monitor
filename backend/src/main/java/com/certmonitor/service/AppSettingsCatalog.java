package com.certmonitor.service;

import java.util.List;

/**
 * Genel Ayarlar sayfasından düzenlenebilir config'lerin küratörlü kataloğu.
 * Serbest key DEĞİL — yalnız buradaki key'ler kaydedilebilir ve tüketici kodda canlı okunur.
 * Varsayılanlar burada GÖMÜLMEZ; AppSettingsService Environment'tan (application.properties) çözer.
 *
 * Yeni bir ayarı canlı yapmak için: (1) buraya satır ekle, (2) tüketici kodu
 * AppSettingsService.getX(key, fallback) ile okusun, (3) i18n etiketini ekle.
 */
public final class AppSettingsCatalog {

    private AppSettingsCatalog() {}

    public enum Type { STRING, INT, BOOL, DOUBLE, CSV, ENUM, TEXT }

    public record Setting(String key, String group, Type type, List<String> enumOptions) {
        public Setting(String key, String group, Type type) {
            this(key, group, type, List.of());
        }
    }

    /** Operasyonel paket — UI'ı olmayan, canlı tuning'e uygun config'ler. */
    public static final List<Setting> ALL = List.of(
        new Setting("cert.monitor.app.base-url",                 "general",    Type.STRING),
        new Setting("cert.monitor.system-admin.email",           "general",    Type.STRING),
        new Setting("cert.monitor.cors.allowed-origins",         "general",    Type.CSV),
        new Setting("cert.monitor.network.error-rate-threshold", "outage",     Type.DOUBLE),
        new Setting("cert.monitor.network.min-errors",           "outage",     Type.INT),
        new Setting("cert.monitor.scheduler.stale-minutes",      "scheduler",  Type.INT),
        new Setting("cert.monitor.uptime.alert-enabled",         "monitoring", Type.BOOL),
        new Setting("cert.monitor.port.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("cert.monitor.dns.alert-enabled",            "monitoring", Type.BOOL),
        // Kurumsal/iç kök+ara CA paketi (PEM) — bu CA ile imzalı host'lar TRUSTED sayılır.
        // TrustEvaluator okuma anında okur (canlı reload). Boş = yalnız public CA'lar (cacerts).
        new Setting("cert.monitor.trust.ca-bundle-pem",          "security",   Type.TEXT),
        new Setting("logging.level.com.certmonitor",             "logging",    Type.ENUM,
                    List.of("DEBUG", "INFO", "WARN", "ERROR"))
    );

    public static Setting byKey(String key) {
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }
}
