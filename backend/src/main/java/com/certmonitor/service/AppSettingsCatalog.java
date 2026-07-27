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
        new Setting("cert.monitor.login-issues.enabled",         "general",    Type.BOOL),
        new Setting("cert.monitor.network.error-rate-threshold", "outage",     Type.DOUBLE),
        new Setting("cert.monitor.network.min-errors",           "outage",     Type.INT),
        new Setting("cert.monitor.scheduler.stale-minutes",      "scheduler",  Type.INT),
        new Setting("cert.monitor.uptime.alert-enabled",         "monitoring", Type.BOOL),
        new Setting("cert.monitor.port.alert-enabled",           "monitoring", Type.BOOL),
        // SSRF koruması — giden izleme/tanılama hedefleri (SsrfGuard). Metadata/loopback/link-local her zaman blok.
        new Setting("cert.monitor.monitoring.allow-internal-targets", "monitoring", Type.BOOL),
        new Setting("cert.monitor.monitoring.allow-loopback-targets", "monitoring", Type.BOOL),
        new Setting("cert.monitor.dns.alert-enabled",            "monitoring", Type.BOOL),
        new Setting("cert.monitor.dns.resolvers",                "monitoring", Type.CSV),
        new Setting("cert.monitor.keyword.alert-enabled",        "monitoring", Type.BOOL),
        new Setting("cert.monitor.ping.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("cert.monitor.http.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("cert.monitor.http.rdap-base-url",           "monitoring", Type.STRING),
        // Alan adı (domain) süre-bitişi izleme — RDAP (proxy-aware) + env-gated WHOIS.
        new Setting("cert.monitor.domain.alert-enabled",         "monitoring", Type.BOOL),
        new Setting("cert.monitor.domain.rdap-bootstrap-url",    "monitoring", Type.STRING),
        new Setting("cert.monitor.domain.rdap-fallback-url",     "monitoring", Type.STRING),
        new Setting("cert.monitor.domain.whois-enabled",         "monitoring", Type.BOOL),
        new Setting("cert.monitor.domain.whois-servers",         "monitoring", Type.CSV),
        new Setting("cert.monitor.domain.whois-timeout-ms",      "monitoring", Type.INT),
        // .tr (TRABIS) HTTPS web-whois — port-43 kapalı ortamda .tr süre bitişini almanın tek yolu (proxy'den geçer).
        new Setting("cert.monitor.domain.tr-web-whois-enabled",   "monitoring", Type.BOOL),
        new Setting("cert.monitor.domain.tr-web-whois-providers", "monitoring", Type.CSV),
        new Setting("cert.monitor.domain.isimtescil-whois-url",   "monitoring", Type.STRING),
        new Setting("cert.monitor.domain.trabis-whois-url",       "monitoring", Type.STRING),
        new Setting("cert.monitor.domain.trabis-whois43-host",    "monitoring", Type.STRING),
        new Setting("cert.monitor.domain.rdap-timeout-ms",       "monitoring", Type.INT),
        new Setting("cert.monitor.domain.default-warning-days",  "monitoring", Type.INT),
        new Setting("cert.monitor.domain.default-critical-days", "monitoring", Type.INT),
        new Setting("cert.monitor.domain.default-thresholds",    "monitoring", Type.STRING),
        new Setting("cert.monitor.expiry.alert-enabled",         "monitoring", Type.BOOL),
        // DNS yavaş/timeout alarmı (DNS_SLOW) tunable'ları — canlı.
        new Setting("cert.monitor.dns.slow-threshold-ms",        "monitoring", Type.INT),
        new Setting("cert.monitor.dns.query-timeout-ms",         "monitoring", Type.INT),
        new Setting("cert.monitor.dns.slow-confirm-attempts",    "monitoring", Type.INT),
        new Setting("cert.monitor.dns.slow-confirm-interval-ms", "monitoring", Type.INT),
        // HTTP metrik kalıcı serisi — gün-bazlı saklama (Sistem Sağlığı HTTP paneli + gece temizlik).
        new Setting("cert.monitor.metrics.http.retention-days",  "monitoring", Type.INT),
        // Haftalık rapor görselleri (base64 ≤8MB/satır) — bu günden eski görseller gece temizlenir (rapor metni korunur).
        new Setting("cert.monitor.weekly-report.image-retention-days", "monitoring", Type.INT),
        // Kontrol sıklığı + request timeout — per-tip VARSAYILAN (yeni monitör oluştururken kullanılır).
        new Setting("cert.monitor.ping.default-interval-seconds",    "frequency", Type.INT),
        new Setting("cert.monitor.keyword.default-interval-seconds", "frequency", Type.INT),
        new Setting("cert.monitor.port.default-interval-seconds",    "frequency", Type.INT),
        new Setting("cert.monitor.http.default-interval-seconds",    "frequency", Type.INT),
        new Setting("cert.monitor.domain.default-interval-seconds",  "frequency", Type.INT),
        new Setting("cert.monitor.ping.default-timeout-ms",          "frequency", Type.INT),
        new Setting("cert.monitor.keyword.default-timeout-ms",       "frequency", Type.INT),
        new Setting("cert.monitor.keyword.default-slow-ms",          "frequency", Type.INT),
        new Setting("cert.monitor.port.default-timeout-ms",          "frequency", Type.INT),
        new Setting("cert.monitor.port.default-slow-ms",             "frequency", Type.INT),
        new Setting("cert.monitor.http.default-timeout-ms",          "frequency", Type.INT),
        // Haftalık erişilebilirlik e-postası (Pazartesi 10:00) aç/kapa — canlı.
        new Setting("cert.monitor.weekly-availability.enabled",  "monitoring", Type.BOOL),
        // Haftalık rapor sağlık skoru ağırlıkları (executive özet) — WeeklyScoreCalculator canlı okur.
        new Setting("cert.monitor.weekly.score.weight-critical",  "weekly", Type.DOUBLE),
        new Setting("cert.monitor.weekly.score.weight-expiring",  "weekly", Type.DOUBLE),
        new Setting("cert.monitor.weekly.score.weight-weak-algo", "weekly", Type.DOUBLE),
        new Setting("cert.monitor.weekly.score.weight-uptime",    "weekly", Type.DOUBLE),
        // Alarm fırtınası (alert storm) — çok monitör birden düşünce bireysel alarmları TEK toplu bildirime indirger.
        new Setting("cert.monitor.storm.enabled",                "storm",      Type.BOOL),
        new Setting("cert.monitor.storm.threshold-unit",         "storm",      Type.ENUM, List.of("COUNT", "PERCENT")),
        new Setting("cert.monitor.storm.threshold-value",        "storm",      Type.INT),
        new Setting("cert.monitor.storm.window-minutes",         "storm",      Type.INT),
        new Setting("cert.monitor.storm.per-group",              "storm",      Type.BOOL),
        // Kurumsal/iç kök+ara CA paketi (PEM) — bu CA ile imzalı host'lar TRUSTED sayılır.
        // TrustEvaluator okuma anında okur (canlı reload). Boş = yalnız public CA'lar (cacerts).
        new Setting("cert.monitor.trust.ca-bundle-pem",          "security",   Type.TEXT),
        // CA otomatik sabitleme (TOFU) — PKIX hatasında CA sunucudan çekilip host bazında pinlenir,
        // rotasyon/bitişte otomatik yenilenir (CaAutoPinService). Kapsam: HTTP uptime strict + RDAP çıkışı.
        new Setting("cert.monitor.trust.auto-pin.enabled",       "security",   Type.BOOL),
        // ── Branding (beyaz etiket) — BrandingController üzerinden yönetilir; /api/branding public okur.
        //    Boş değer = varsayılan CertMonitor kimliği. banner-version otomatik yönetilir (UI'da gizli).
        new Setting("cert.monitor.branding.app-name",            "branding",   Type.STRING),
        new Setting("cert.monitor.branding.tab-title",           "branding",   Type.STRING),
        new Setting("cert.monitor.branding.login-title",         "branding",   Type.STRING),
        new Setting("cert.monitor.branding.login-subtitle",      "branding",   Type.STRING),
        new Setting("cert.monitor.branding.signin-label",        "branding",   Type.STRING),
        new Setting("cert.monitor.branding.username-label",      "branding",   Type.STRING),
        new Setting("cert.monitor.branding.footer-text",         "branding",   Type.STRING),
        new Setting("cert.monitor.branding.primary-color",       "branding",   Type.STRING),
        new Setting("cert.monitor.branding.logo-data",           "branding",   Type.TEXT),
        new Setting("cert.monitor.branding.banner-enabled",      "branding",   Type.BOOL),
        new Setting("cert.monitor.branding.banner-text",         "branding",   Type.STRING),
        new Setting("cert.monitor.branding.banner-link",         "branding",   Type.STRING),
        new Setting("cert.monitor.branding.banner-link-label",   "branding",   Type.STRING),
        new Setting("cert.monitor.branding.banner-tone",         "branding",   Type.ENUM, List.of("INFO", "WARNING", "CRITICAL")),
        new Setting("cert.monitor.branding.banner-version",      "branding",   Type.INT),
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
