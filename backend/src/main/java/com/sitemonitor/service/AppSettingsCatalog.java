package com.sitemonitor.service;

import com.sitemonitor.service.retention.RetentionCatalog;

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
        new Setting("site.monitor.app.base-url",                 "general",    Type.STRING),
        new Setting("site.monitor.system-admin.email",           "general",    Type.STRING),
        new Setting("site.monitor.cors.allowed-origins",         "general",    Type.CSV),
        new Setting("site.monitor.login-issues.enabled",         "general",    Type.BOOL),
        // ErrorBoundary otomatik çökme bildirimi (kayıt + admin maili) — varsayılan AÇIK.
        new Setting("site.monitor.client-errors.enabled",        "general",    Type.BOOL),
        // Sorun bildirimlerinde tekil admin maili yerine günlük özet — varsayılan KAPALI.
        new Setting("site.monitor.issue-reports.daily-digest",   "general",    Type.BOOL),
        // Login provizyonunda müdürü otomatik MANAGER eskalasyon kontağı yapma — varsayılan KAPALI
        // (kullanıcı kararı 2026-08-03); açılırsa D7+ müdürler otomatik eklenir. Mevcut kayıtlar silinmez.
        new Setting("site.monitor.escalation.auto-add-managers", "general",    Type.BOOL),
        new Setting("site.monitor.network.error-rate-threshold", "outage",     Type.DOUBLE),
        new Setting("site.monitor.network.min-errors",           "outage",     Type.INT),
        new Setting("site.monitor.scheduler.stale-minutes",      "scheduler",  Type.INT),
        new Setting("site.monitor.uptime.alert-enabled",         "monitoring", Type.BOOL),
        new Setting("site.monitor.port.alert-enabled",           "monitoring", Type.BOOL),
        // SSRF koruması — giden izleme/tanılama hedefleri (SsrfGuard). Metadata/loopback/link-local her zaman blok.
        new Setting("site.monitor.monitoring.allow-internal-targets", "monitoring", Type.BOOL),
        new Setting("site.monitor.monitoring.allow-loopback-targets", "monitoring", Type.BOOL),
        new Setting("site.monitor.dns.alert-enabled",            "monitoring", Type.BOOL),
        new Setting("site.monitor.dns.resolvers",                "monitoring", Type.CSV),
        new Setting("site.monitor.keyword.alert-enabled",        "monitoring", Type.BOOL),
        new Setting("site.monitor.ping.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("site.monitor.http.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("site.monitor.http.rdap-base-url",           "monitoring", Type.STRING),
        // Sayfa Bütünlüğü (9. tür) — alarm aç/kapa + kaynak-doğrulama eşzamanlılığı (nezaket + tek-pod yük).
        new Setting("site.monitor.page.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("site.monitor.page.resource-concurrency",    "monitoring", Type.INT),
        new Setting("site.monitor.page.user-agent",              "monitoring", Type.STRING),
        new Setting("site.monitor.page.max-check-seconds",       "monitoring", Type.INT),
        new Setting("site.monitor.page.manual-cooldown-seconds", "monitoring", Type.INT),
        // Sayfa-bütünlüğü check/issue serisi — gün-bazlı saklama (gece batch-purge).
        new Setting("site.monitor.metrics.page.retention-days",       "monitoring", Type.INT),
        new Setting("site.monitor.metrics.page-issues.retention-days","monitoring", Type.INT),
        // Senaryo İzleme (10. tür) — k6 alt süreç havuzu, timeout tavanları, çıktı/güvenlik + saklama.
        new Setting("site.monitor.scripted.enabled",                 "scripted", Type.BOOL),
        new Setting("site.monitor.scripted.alert-enabled",           "scripted", Type.BOOL),
        new Setting("site.monitor.scripted.pool-size",               "scripted", Type.INT),
        new Setting("site.monitor.scripted.default-timeout-seconds", "scripted", Type.INT),
        new Setting("site.monitor.scripted.max-timeout-seconds",     "scripted", Type.INT),
        new Setting("site.monitor.scripted.output-tail-bytes",       "scripted", Type.INT),
        new Setting("site.monitor.scripted.manual-cooldown-seconds", "scripted", Type.INT),
        new Setting("site.monitor.scripted.manual-wait-seconds", "scripted", Type.INT),
        new Setting("site.monitor.scripted.k6-bin",                  "scripted", Type.STRING),
        new Setting("site.monitor.scripted.hardcoded-secret-policy", "scripted", Type.ENUM, java.util.List.of("WARN", "BLOCK")),
        // Koştu ama hiç check() çalıştırmadı: WARN = kayıt hatalı sayılır, alarm YOK (varsayılan) ·
        // FAIL = alarm da üret · PASS = eski davranış (yeniden dağıtım gerektirmeyen geri dönüş).
        new Setting("site.monitor.scripted.no-checks-policy",        "scripted", Type.ENUM, java.util.List.of("WARN", "FAIL", "PASS")),
        // Kaydetme öncesi `k6 archive` ile sözdizimi doğrulaması. WARN = uyar ama kaydet ·
        // BLOCK = kesin hatada (satır/sütun çıkarılabiliyorsa) kaydetmeyi reddet · OFF = kapalı.
        new Setting("site.monitor.scripted.syntax-check-policy",     "scripted", Type.ENUM, java.util.List.of("BLOCK", "WARN", "OFF")),
        new Setting("site.monitor.scripted.validate-timeout-seconds","scripted", Type.INT),
        new Setting("site.monitor.metrics.scripted.retention-days",  "scripted", Type.INT),
        // Alan adı (domain) süre-bitişi izleme — RDAP (proxy-aware) + env-gated WHOIS.
        new Setting("site.monitor.domain.alert-enabled",         "monitoring", Type.BOOL),
        new Setting("site.monitor.domain.rdap-bootstrap-url",    "monitoring", Type.STRING),
        new Setting("site.monitor.domain.rdap-fallback-url",     "monitoring", Type.STRING),
        new Setting("site.monitor.domain.whois-enabled",         "monitoring", Type.BOOL),
        new Setting("site.monitor.domain.whois-servers",         "monitoring", Type.CSV),
        new Setting("site.monitor.domain.whois-timeout-ms",      "monitoring", Type.INT),
        // .tr (TRABIS) HTTPS web-whois — port-43 kapalı ortamda .tr süre bitişini almanın tek yolu (proxy'den geçer).
        new Setting("site.monitor.domain.tr-web-whois-enabled",   "monitoring", Type.BOOL),
        new Setting("site.monitor.domain.tr-web-whois-providers", "monitoring", Type.CSV),
        new Setting("site.monitor.domain.isimtescil-whois-url",   "monitoring", Type.STRING),
        new Setting("site.monitor.domain.trabis-whois-url",       "monitoring", Type.STRING),
        new Setting("site.monitor.domain.trabis-whois43-host",    "monitoring", Type.STRING),
        new Setting("site.monitor.domain.rdap-timeout-ms",       "monitoring", Type.INT),
        new Setting("site.monitor.domain.default-warning-days",  "monitoring", Type.INT),
        new Setting("site.monitor.domain.default-critical-days", "monitoring", Type.INT),
        new Setting("site.monitor.domain.default-thresholds",    "monitoring", Type.STRING),
        new Setting("site.monitor.expiry.alert-enabled",         "monitoring", Type.BOOL),
        // DNS yavaş/timeout alarmı (DNS_SLOW) tunable'ları — canlı.
        new Setting("site.monitor.dns.slow-threshold-ms",        "monitoring", Type.INT),
        new Setting("site.monitor.dns.query-timeout-ms",         "monitoring", Type.INT),
        new Setting("site.monitor.dns.slow-confirm-attempts",    "monitoring", Type.INT),
        new Setting("site.monitor.dns.slow-confirm-interval-ms", "monitoring", Type.INT),
        // HTTP metrik kalıcı serisi — gün-bazlı saklama (Sistem Sağlığı HTTP paneli + gece temizlik).
        new Setting("site.monitor.metrics.http.retention-days",  "monitoring", Type.INT),
        // Haftalık rapor görselleri (base64 ≤8MB/satır) — bu günden eski görseller gece temizlenir (rapor metni korunur).
        new Setting("site.monitor.weekly-report.image-retention-days", "monitoring", Type.INT),
        // Birleşik aktivite akışı (Kayıtlar → Aktivite) kayıt saklama süresi — bu günden eski aktiviteler gece temizlenir.
        new Setting("site.monitor.activity.retention-days",           "monitoring", Type.INT),
        // Denetim (audit) kayıt saklama süresi (gün) + silmeden önce JSONL arşiv üretimi (append-only + arşiv).
        new Setting("site.monitor.audit.retention-days",             "monitoring", Type.INT),
        new Setting("site.monitor.audit.archive-enabled",            "monitoring", Type.BOOL),
        // Kontrol sıklığı + request timeout — per-tip VARSAYILAN (yeni monitör oluştururken kullanılır).
        new Setting("site.monitor.ping.default-interval-seconds",    "frequency", Type.INT),
        new Setting("site.monitor.keyword.default-interval-seconds", "frequency", Type.INT),
        new Setting("site.monitor.port.default-interval-seconds",    "frequency", Type.INT),
        new Setting("site.monitor.http.default-interval-seconds",    "frequency", Type.INT),
        new Setting("site.monitor.domain.default-interval-seconds",  "frequency", Type.INT),
        new Setting("site.monitor.ping.default-timeout-ms",          "frequency", Type.INT),
        new Setting("site.monitor.keyword.default-timeout-ms",       "frequency", Type.INT),
        new Setting("site.monitor.keyword.default-slow-ms",          "frequency", Type.INT),
        new Setting("site.monitor.port.default-timeout-ms",          "frequency", Type.INT),
        new Setting("site.monitor.port.default-slow-ms",             "frequency", Type.INT),
        new Setting("site.monitor.http.default-timeout-ms",          "frequency", Type.INT),
        new Setting("site.monitor.page.default-interval-seconds",     "frequency", Type.INT),
        new Setting("site.monitor.page.default-timeout-ms",           "frequency", Type.INT),
        new Setting("site.monitor.page.default-slow-ms",              "frequency", Type.INT),
        new Setting("site.monitor.page.default-crawl-depth",          "frequency", Type.INT),
        new Setting("site.monitor.page.default-crawl-max-pages",      "frequency", Type.INT),
        // Haftalık erişilebilirlik e-postası (Pazartesi 10:00) aç/kapa — canlı.
        new Setting("site.monitor.weekly-availability.enabled",  "monitoring", Type.BOOL),
        // Aylık sertifika envanteri raporu (ayın son cuması 10:00) — aç/kapa + alıcılar, canlı.
        new Setting("site.monitor.cert-inventory-report.enabled",    "monitoring", Type.BOOL),
        new Setting("site.monitor.cert-inventory-report.recipients", "monitoring", Type.STRING),
        new Setting("site.monitor.cert-inventory-report.cc",         "monitoring", Type.STRING),
        // Zamanlama CANLI: dinamik tetikleyici her hesaplamada bunu okur (yeniden başlatma yok).
        new Setting("site.monitor.cert-inventory-report.cron",       "monitoring", Type.STRING),
        // Haftalık rapor sağlık skoru ağırlıkları (executive özet) — WeeklyScoreCalculator canlı okur.
        new Setting("site.monitor.weekly.score.weight-critical",  "weekly", Type.DOUBLE),
        new Setting("site.monitor.weekly.score.weight-expiring",  "weekly", Type.DOUBLE),
        new Setting("site.monitor.weekly.score.weight-weak-algo", "weekly", Type.DOUBLE),
        new Setting("site.monitor.weekly.score.weight-uptime",    "weekly", Type.DOUBLE),
        // Alarm fırtınası (alert storm) — çok monitör birden düşünce bireysel alarmları TEK toplu bildirime indirger.
        new Setting("site.monitor.storm.enabled",                "storm",      Type.BOOL),
        new Setting("site.monitor.storm.threshold-unit",         "storm",      Type.ENUM, List.of("COUNT", "PERCENT")),
        new Setting("site.monitor.storm.threshold-value",        "storm",      Type.INT),
        new Setting("site.monitor.storm.window-minutes",         "storm",      Type.INT),
        new Setting("site.monitor.storm.per-group",              "storm",      Type.BOOL),
        // Kurumsal/iç kök+ara CA paketi (PEM) — bu CA ile imzalı host'lar TRUSTED sayılır.
        // TrustEvaluator okuma anında okur (canlı reload). Boş = yalnız public CA'lar (cacerts).
        new Setting("site.monitor.trust.ca-bundle-pem",          "security",   Type.TEXT),
        // CA otomatik sabitleme (TOFU) — PKIX hatasında CA sunucudan çekilip host bazında pinlenir,
        // rotasyon/bitişte otomatik yenilenir (CaAutoPinService). Kapsam: HTTP uptime strict + RDAP çıkışı.
        new Setting("site.monitor.trust.auto-pin.enabled",       "security",   Type.BOOL),
        // ── Branding (beyaz etiket) — BrandingController üzerinden yönetilir; /api/branding public okur.
        //    Boş değer = varsayılan SiteMonitor kimliği. banner-version otomatik yönetilir (UI'da gizli).
        new Setting("site.monitor.branding.app-name",            "branding",   Type.STRING),
        new Setting("site.monitor.branding.tab-title",           "branding",   Type.STRING),
        new Setting("site.monitor.branding.login-title",         "branding",   Type.STRING),
        new Setting("site.monitor.branding.login-subtitle",      "branding",   Type.STRING),
        new Setting("site.monitor.branding.signin-label",        "branding",   Type.STRING),
        new Setting("site.monitor.branding.username-label",      "branding",   Type.STRING),
        new Setting("site.monitor.branding.footer-text",         "branding",   Type.STRING),
        new Setting("site.monitor.branding.primary-color",       "branding",   Type.STRING),
        new Setting("site.monitor.branding.logo-data",           "branding",   Type.TEXT),
        new Setting("site.monitor.branding.banner-enabled",      "branding",   Type.BOOL),
        new Setting("site.monitor.branding.banner-text",         "branding",   Type.STRING),
        new Setting("site.monitor.branding.banner-link",         "branding",   Type.STRING),
        new Setting("site.monitor.branding.banner-link-label",   "branding",   Type.STRING),
        new Setting("site.monitor.branding.banner-tone",         "branding",   Type.ENUM, List.of("INFO", "WARNING", "CRITICAL")),
        new Setting("site.monitor.branding.banner-version",      "branding",   Type.INT),
        new Setting("logging.level.com.sitemonitor",             "logging",    Type.ENUM,
                    List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")),
        // Yalnız mail gönderim logger'ı — uygulama geneli TRACE'e geçmeden ekrandan mail
        // TRACE'i aç/kapat. Boş = com.sitemonitor (LOG_LEVEL) ile aynı.
        new Setting("logging.level.com.sitemonitor.mail",        "logging",    Type.ENUM,
                    List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")),
        // ── Başarısız-login anomali tespiti + sistem-admin e-posta uyarısı (katmanlı kurallar) ──
        //    Detektör audit_log'u tarar; her kural bağımsız + canlı eşiklerle. (scan-ms başlangıçta
        //    okunur — @Scheduled fixedDelay — bu yüzden katalogda değil, yalnız application.properties.)
        new Setting("site.monitor.failed-login.enabled",                          "login-anomaly", Type.BOOL),
        new Setting("site.monitor.failed-login.window-minutes",                   "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.threshold-total",                  "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.threshold-per-account",            "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.threshold-per-ip",                 "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.threshold-distinct-users-per-ip",  "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.threshold-distinct-ips-per-account","login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.relative-multiplier",              "login-anomaly", Type.DOUBLE),
        new Setting("site.monitor.failed-login.baseline-hours",                   "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.relative-floor",                   "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.catchup-cap-minutes",              "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.cooldown-minutes",                 "login-anomaly", Type.INT),
        new Setting("site.monitor.failed-login.resolved-email-enabled",           "login-anomaly", Type.BOOL),
        new Setting("site.monitor.failed-login.alert-recipients",                 "login-anomaly", Type.CSV),
        new Setting("site.monitor.failed-login.retention-days",                   "login-anomaly", Type.INT),
        // ── DB ölçek/retention (gece temizlik) — büyük tabloda batch'li silme + eksik-tablo retention ──
        new Setting("site.monitor.retention.purge-batch-size",        "retention", Type.INT),
        new Setting("site.monitor.network-outage.retention-days",     "retention", Type.INT),
        new Setting("site.monitor.incident.retention-days",           "retention", Type.INT),
        new Setting("site.monitor.audit.archive-retention-days",      "retention", Type.INT),
        new Setting("site.monitor.rollup.lookback-days",              "retention", Type.INT),
        new Setting("site.monitor.rollup.retention-days",             "retention", Type.INT),
        new Setting("site.monitor.rollup.hourly-retention-days",      "retention", Type.INT),
        new Setting("site.monitor.cert-inventory-report.retention-days", "retention", Type.INT),
        new Setting("site.monitor.db.growth-warn-rows",               "retention", Type.INT),
        // ── 2026-08: eskiden koda GÖMÜLÜ olan kesimler artık ayar (RetentionCatalog ile senkron;
        //    RetentionSettingsSyncTest kilitler). Her birinin kodda bir minDays tabanı vardır. ──
        new Setting(RetentionCatalog.HOLD_KEY,                        "retention", Type.BOOL),
        new Setting("site.monitor.notification.retention-days",       "retention", Type.INT),
        new Setting("site.monitor.sql-history.retention-days",        "retention", Type.INT),
        new Setting("site.monitor.heartbeat.retention-days",          "retention", Type.INT),
        new Setting("site.monitor.diagnostics.retention-days",        "retention", Type.INT),
        new Setting("site.monitor.alert.retention-days",              "retention", Type.INT),
        new Setting("site.monitor.login-issue.retention-days",        "retention", Type.INT),
        new Setting("site.monitor.storm.retention-days",              "retention", Type.INT),
        // Ham izleme serileri — tür bazında (hepsi 180g varsayılan; eski tek tsCutoff davranışı)
        new Setting("site.monitor.series.uptime.retention-days",      "retention", Type.INT),
        new Setting("site.monitor.series.certificate.retention-days", "retention", Type.INT),
        new Setting("site.monitor.series.port.retention-days",        "retention", Type.INT),
        new Setting("site.monitor.series.keyword.retention-days",     "retention", Type.INT),
        new Setting("site.monitor.series.ping.retention-days",        "retention", Type.INT),
        new Setting("site.monitor.series.dns.retention-days",         "retention", Type.INT),
        new Setting("site.monitor.series.http.retention-days",        "retention", Type.INT),
        new Setting("site.monitor.series.domain.retention-days",      "retention", Type.INT),
        // Daha önce HİÇ temizlenmeyen tablolar
        new Setting("site.monitor.incident.image-retention-days",     "retention", Type.INT),
        new Setting("site.monitor.incident.draft-image-retention-days", "retention", Type.INT),
        new Setting("site.monitor.weekly-report.mail-retention-days", "retention", Type.INT),
        new Setting("site.monitor.weekly-availability.retention-days", "retention", Type.INT),
        new Setting("site.monitor.cert-note-revision.retention-days", "retention", Type.INT),
        new Setting("site.monitor.scripted.draft-retention-days", "retention", Type.INT),
        new Setting("site.monitor.retention.run-history-retention-days", "retention", Type.INT)
    );

    public static Setting byKey(String key) {
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }
}
