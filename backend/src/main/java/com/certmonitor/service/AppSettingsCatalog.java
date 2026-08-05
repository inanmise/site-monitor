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
        // Login provizyonunda müdürü otomatik MANAGER eskalasyon kontağı yapma — varsayılan KAPALI
        // (kullanıcı kararı 2026-08-03); açılırsa D7+ müdürler otomatik eklenir. Mevcut kayıtlar silinmez.
        new Setting("cert.monitor.escalation.auto-add-managers", "general",    Type.BOOL),
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
        // Sayfa Bütünlüğü (9. tür) — alarm aç/kapa + kaynak-doğrulama eşzamanlılığı (nezaket + tek-pod yük).
        new Setting("cert.monitor.page.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("cert.monitor.page.resource-concurrency",    "monitoring", Type.INT),
        new Setting("cert.monitor.page.user-agent",              "monitoring", Type.STRING),
        new Setting("cert.monitor.page.max-check-seconds",       "monitoring", Type.INT),
        new Setting("cert.monitor.page.manual-cooldown-seconds", "monitoring", Type.INT),
        // Sayfa-bütünlüğü check/issue serisi — gün-bazlı saklama (gece batch-purge).
        new Setting("cert.monitor.metrics.page.retention-days",       "monitoring", Type.INT),
        new Setting("cert.monitor.metrics.page-issues.retention-days","monitoring", Type.INT),
        // Senaryo İzleme (10. tür) — k6 alt süreç havuzu, timeout tavanları, çıktı/güvenlik + saklama.
        new Setting("cert.monitor.scripted.enabled",                 "scripted", Type.BOOL),
        new Setting("cert.monitor.scripted.pool-size",               "scripted", Type.INT),
        new Setting("cert.monitor.scripted.default-timeout-seconds", "scripted", Type.INT),
        new Setting("cert.monitor.scripted.max-timeout-seconds",     "scripted", Type.INT),
        new Setting("cert.monitor.scripted.output-tail-bytes",       "scripted", Type.INT),
        new Setting("cert.monitor.scripted.manual-cooldown-seconds", "scripted", Type.INT),
        new Setting("cert.monitor.scripted.k6-bin",                  "scripted", Type.STRING),
        new Setting("cert.monitor.scripted.hardcoded-secret-policy", "scripted", Type.ENUM, java.util.List.of("WARN", "BLOCK")),
        new Setting("cert.monitor.metrics.scripted.retention-days",  "scripted", Type.INT),
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
        // Birleşik aktivite akışı (Kayıtlar → Aktivite) kayıt saklama süresi — bu günden eski aktiviteler gece temizlenir.
        new Setting("cert.monitor.activity.retention-days",           "monitoring", Type.INT),
        // Denetim (audit) kayıt saklama süresi (gün) + silmeden önce JSONL arşiv üretimi (append-only + arşiv).
        new Setting("cert.monitor.audit.retention-days",             "monitoring", Type.INT),
        new Setting("cert.monitor.audit.archive-enabled",            "monitoring", Type.BOOL),
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
        new Setting("cert.monitor.page.default-interval-seconds",     "frequency", Type.INT),
        new Setting("cert.monitor.page.default-timeout-ms",           "frequency", Type.INT),
        new Setting("cert.monitor.page.default-slow-ms",              "frequency", Type.INT),
        new Setting("cert.monitor.page.default-crawl-depth",          "frequency", Type.INT),
        new Setting("cert.monitor.page.default-crawl-max-pages",      "frequency", Type.INT),
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
        //    Boş değer = varsayılan Site Monitör kimliği. banner-version otomatik yönetilir (UI'da gizli).
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
                    List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")),
        // ── Başarısız-login anomali tespiti + sistem-admin e-posta uyarısı (katmanlı kurallar) ──
        //    Detektör audit_log'u tarar; her kural bağımsız + canlı eşiklerle. (scan-ms başlangıçta
        //    okunur — @Scheduled fixedDelay — bu yüzden katalogda değil, yalnız application.properties.)
        new Setting("cert.monitor.failed-login.enabled",                          "login-anomaly", Type.BOOL),
        new Setting("cert.monitor.failed-login.window-minutes",                   "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.threshold-total",                  "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.threshold-per-account",            "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.threshold-per-ip",                 "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.threshold-distinct-users-per-ip",  "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.threshold-distinct-ips-per-account","login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.relative-multiplier",              "login-anomaly", Type.DOUBLE),
        new Setting("cert.monitor.failed-login.baseline-hours",                   "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.relative-floor",                   "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.catchup-cap-minutes",              "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.cooldown-minutes",                 "login-anomaly", Type.INT),
        new Setting("cert.monitor.failed-login.resolved-email-enabled",           "login-anomaly", Type.BOOL),
        new Setting("cert.monitor.failed-login.alert-recipients",                 "login-anomaly", Type.CSV),
        new Setting("cert.monitor.failed-login.retention-days",                   "login-anomaly", Type.INT),
        // ── DB ölçek/retention (gece temizlik) — büyük tabloda batch'li silme + eksik-tablo retention ──
        new Setting("cert.monitor.retention.purge-batch-size",        "retention", Type.INT),
        new Setting("cert.monitor.network-outage.retention-days",     "retention", Type.INT),
        new Setting("cert.monitor.incident.retention-days",           "retention", Type.INT),
        new Setting("cert.monitor.audit.archive-retention-days",      "retention", Type.INT),
        new Setting("cert.monitor.rollup.lookback-days",              "retention", Type.INT),
        new Setting("cert.monitor.rollup.retention-days",             "retention", Type.INT),
        new Setting("cert.monitor.db.growth-warn-rows",               "retention", Type.INT)
    );

    public static Setting byKey(String key) {
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }
}
