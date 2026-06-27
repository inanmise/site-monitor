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
        new Setting("cert.monitor.dns.resolvers",                "monitoring", Type.CSV),
        new Setting("cert.monitor.keyword.alert-enabled",        "monitoring", Type.BOOL),
        new Setting("cert.monitor.ping.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("cert.monitor.expiry.alert-enabled",         "monitoring", Type.BOOL),
        // DNS yavaş/timeout alarmı (DNS_SLOW) tunable'ları — canlı.
        new Setting("cert.monitor.dns.slow-threshold-ms",        "monitoring", Type.INT),
        new Setting("cert.monitor.dns.query-timeout-ms",         "monitoring", Type.INT),
        new Setting("cert.monitor.dns.slow-confirm-attempts",    "monitoring", Type.INT),
        new Setting("cert.monitor.dns.slow-confirm-interval-ms", "monitoring", Type.INT),
        // Kontrol sıklığı + request timeout — per-tip VARSAYILAN (yeni monitör oluştururken kullanılır).
        new Setting("cert.monitor.ping.default-interval-seconds",    "frequency", Type.INT),
        new Setting("cert.monitor.keyword.default-interval-seconds", "frequency", Type.INT),
        new Setting("cert.monitor.port.default-interval-seconds",    "frequency", Type.INT),
        new Setting("cert.monitor.ping.default-timeout-ms",          "frequency", Type.INT),
        new Setting("cert.monitor.keyword.default-timeout-ms",       "frequency", Type.INT),
        new Setting("cert.monitor.port.default-timeout-ms",          "frequency", Type.INT),
        // Haftalık erişilebilirlik e-postası (Pazartesi 10:00) aç/kapa — canlı.
        new Setting("cert.monitor.weekly-availability.enabled",  "monitoring", Type.BOOL),
        // Kurumsal/iç kök+ara CA paketi (PEM) — bu CA ile imzalı host'lar TRUSTED sayılır.
        // TrustEvaluator okuma anında okur (canlı reload). Boş = yalnız public CA'lar (cacerts).
        new Setting("cert.monitor.trust.ca-bundle-pem",          "security",   Type.TEXT),
        new Setting("logging.level.com.certmonitor",             "logging",    Type.ENUM,
                    List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")),
        // Yalnız mail gönderim logger'ı — uygulama geneli TRACE'e geçmeden ekrandan mail
        // TRACE'i aç/kapat. Boş = com.certmonitor (LOG_LEVEL) ile aynı.
        new Setting("logging.level.com.certmonitor.mail",        "logging",    Type.ENUM,
                    List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"))
    );

    public static Setting byKey(String key) {
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }
}
