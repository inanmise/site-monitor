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

    /**
     * Uyum onayı anahtar öneki: {@code site.monitor.retention.approval.<politikaId>}. Değer
     * {@code aktör|ISO|not}; RetentionAdminController yazar/okur. Katalog girdileri
     * {@link #ALL} kurulurken RetentionCatalog'dan ÜRETİLİR — eskiden hiç yoktu ve
     * AppSettingsService.save "Bilinmeyen ayar" ile HER politikanın onayını reddediyordu
     * (2026-09-10, "user push deliveries" bildirimi).
     */
    public static final String RETENTION_APPROVAL_PREFIX = "site.monitor.retention.approval.";

    /** Operasyonel paket — UI'ı olmayan, canlı tuning'e uygun config'ler (elle yazılan kısım). */
    private static final List<Setting> STATIC = List.of(
        new Setting("site.monitor.app.base-url",                 "general",    Type.STRING),
        new Setting("site.monitor.system-admin.email",           "general",    Type.STRING),
        new Setting("site.monitor.cors.allowed-origins",         "general",    Type.CSV),
        new Setting("site.monitor.login-issues.enabled",         "general",    Type.BOOL),
        new Setting("site.monitor.deploy.notify.enabled",        "general",    Type.BOOL),   // E3: dağıtım e-postası/push (opt-in)
        // Ortam adı (2026-09-29, kullanıcı isteği): Spring özelliğiyle AYNI anahtar (${APP_ENVIRONMENT:}) — DB satırı
        // env'i ezer (log seviyesi deseni). Öncelik DB > APP_ENVIRONMENT > otomatik (local/unknown); BuildInfo canlı
        // okur, biçim [a-z0-9-]{1,40} AppSettingsService.validate'te. Sürüm penceresi, dağıtım geçmişi (kayıtlar ortam
        // adına göre tutulur — DeploymentHistoryService.syncEnvironment), Prometheus etiketi. GLOBAL_ONLY (aşağıda).
        // Anahtar LİTERAL yazılır (BuildInfo.ENV_KEY ile aynı): ön yüz etiket/yardım kapıları kataloğu dize olarak tarar.
        new Setting("site.monitor.environment",                  "general",    Type.STRING),
        // ErrorBoundary otomatik çökme bildirimi (kayıt + admin maili) — varsayılan AÇIK.
        new Setting("site.monitor.client-errors.enabled",        "general",    Type.BOOL),
        // Sorun bildirimlerinde tekil admin maili yerine günlük özet — varsayılan KAPALI.
        new Setting("site.monitor.issue-reports.daily-digest",   "general",    Type.BOOL),
        // Login provizyonunda müdürü otomatik MANAGER eskalasyon kontağı yapma — varsayılan KAPALI
        // (kullanıcı kararı 2026-08-03); açılırsa D7+ müdürler otomatik eklenir. Mevcut kayıtlar silinmez.
        new Setting("site.monitor.escalation.auto-add-managers", "general",    Type.BOOL),
        // LDAP takım/müdür eşlemesi (2026-09-26, prod hatası: üye olmayan kullanıcı takımda görünüyordu).
        // Müdür sicilinin okunacağı AD nitelikleri, öncelik sırasıyla (ea4 bayatsa sıra çevrilir).
        new Setting("site.monitor.ldap.manager-attributes",      "general",    Type.CSV),
        // AD artık desteklemediğinde AD'den TÜRETİLMİŞ üyeliği girişte buda (elle eklenen korunur).
        new Setting("site.monitor.ldap.prune-unsupported-teams", "general",    Type.BOOL),
        // Astın girişinde, bu kadar saatten eski müdür kaydını AD'den tazele (0 = kapalı).
        new Setting("site.monitor.ldap.manager-refresh-hours",   "general",    Type.INT),
        // Org geneli envanter görünürlüğü (2026-09-26, kullanıcı kararı) — Domain Envanteri, Tüm Sertifikalar ve
        // Durum İzleme "Tüm takımlar" ile başka takımların kayıtlarını SALT OKUNUR gösterir; yazma kendi takımında
        // kalır. Varsayılan AÇIK; InventoryVisibility canlı okur. GLOBAL_ONLY (aşağıda).
        new Setting("site.monitor.inventory.visible-to-all",     "general",    Type.BOOL),
        // Org geneli Olaylar görünürlüğü (2026-09-28, kullanıcı kararı) — Alarmlar → Olaylar'da "Diğer ekiplerin olayları"
        // ve "Tümü": başka takımların olayları SALT OKUNUR (liste, detay, yorumlar); onay/çözüm/yorum/silme kendi
        // kapsamında kalır. Envanter anahtarından AYRI: olay içeriği (mesaj, yorumlar) alan adı-takım eşlemesinden daha
        // hassas; kurum birini açıp diğerini kapatabilmeli. Varsayılan AÇIK; IncidentsController canlı okur. GLOBAL_ONLY.
        new Setting("site.monitor.incidents.visible-to-all",     "general",    Type.BOOL),
        // Hareketsizlik oturum kapatma. Eskiden YALNIZ derleme zamani (VITE_INACTIVITY_MS)
        // ayarlanabiliyordu: degistirmek icin yeniden derleyip dagitmak gerekiyordu.
        // k6 REST API adresi. Varsayilan 127.0.0.1:0 = efemer port (cakisma imkansiz).
        // BOS birakilirsa bayrak hic eklenmez — beklenmedik bir k6 surumunde ani geri donus.
        new Setting("site.monitor.scripted.k6-api-address",      "monitoring", Type.STRING),

        // ── Kişi-bazlı webhook (push) bildirim kanalı ─────────────────────────────
        // Mail hattından TAMAMEN bağımsız ikinci kanal. Global anahtar vars. KAPALI:
        // açılmadıkça sistemin gözlenen davranışı bugünün birebir aynısıdır.
        new Setting("site.monitor.userpush.enabled",             "userpush",   Type.BOOL),
        new Setting("site.monitor.userpush.url",                 "userpush",   Type.STRING),
        // JSON: [{name, value(SecretCipher ile şifreli), secret}] — değerler API'den asla düz dönmez.
        new Setting("site.monitor.userpush.headers",             "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.pipeline",            "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.title",               "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.timeout-connect-seconds", "userpush", Type.INT),
        new Setting("site.monitor.userpush.timeout-total-seconds",   "userpush", Type.INT),
        new Setting("site.monitor.userpush.retry-max",           "userpush",   Type.INT),
        new Setting("site.monitor.userpush.retry-backoff-seconds",   "userpush", Type.CSV),
        new Setting("site.monitor.userpush.circuit-threshold",   "userpush",   Type.INT),
        new Setting("site.monitor.userpush.circuit-cooldown-seconds","userpush", Type.INT),
        new Setting("site.monitor.userpush.hourly-cap",          "userpush",   Type.INT),
        // JSON: {grup: {enabled, source: orgRole|title, patterns:[..]}} — K2 karma model.
        new Setting("site.monitor.userpush.role-groups",         "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.down",       "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.slow",       "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.expiry",     "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.changed",    "userpush",   Type.STRING),
        // cert/degraded şablonları da yönetilebilir olsun — kardeşleri katalogda, bu ikisi
        // atlandığı için sessizce özelleştirilemez kalıyordu (aynı sınıf boşluk).
        new Setting("site.monitor.userpush.template.cert",       "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.degraded",   "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.resolved",   "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.test",       "userpush",   Type.STRING),
        // K8: mail neyi gönderiyorsa webhook da — günlük re-alert dahil (vars. AÇIK).
        new Setting("site.monitor.userpush.realert-enabled",     "userpush",   Type.BOOL),
        // Haftalık rapor onayı (2026-09-13): takıma (rol grubu kuralıyla) ve müdüre (doğrudan) push — vars. AÇIK.
        new Setting("site.monitor.userpush.weekly.team-enabled",    "userpush",   Type.BOOL),
        new Setting("site.monitor.userpush.weekly.manager-enabled", "userpush",   Type.BOOL),
        // E2 sessiz saatler: pencerede yalnız min seviye ve üstü gider (örn. 22:00-07:00 CRITICAL).
        new Setting("site.monitor.userpush.quiet-start",         "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.quiet-end",           "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.quiet-min-level",     "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.retention-days",      "userpush",   Type.INT),
        // Mesaj uzunlugu: ikisi de GOMULU sabitti, hicbir yerden yonetilemiyordu. reason tavani
        // (eski 120) sessizce kelime ortasindan kesiyordu; max-message urun sozlesmesi (K6, <=200).
        new Setting("site.monitor.userpush.max-message-chars",   "userpush",   Type.INT),
        new Setting("site.monitor.userpush.reason-max-chars",    "userpush",   Type.INT),
        // Saat tavanı özeti (2026-10-04, onaylı öneri 2): tavana takılan push'lar kişi başına TEK özet push'unda toplanır
        // (vars. AÇIK, 15 dk, 5–120); KRİTİK alarm push'larını tavandan muaf tutma (vars. KAPALI = bugünkü davranış).
        new Setting("site.monitor.userpush.overflow-summary-enabled", "userpush", Type.BOOL),
        new Setting("site.monitor.userpush.overflow-summary-minutes", "userpush", Type.INT),
        new Setting("site.monitor.userpush.critical-bypass-cap", "userpush",   Type.BOOL),
        // Zamana bağlı eskalasyon adımı kişiye push da gönderir (2026-10-04, onaylı öneri 6; vars. AÇIK, adım yoksa etkisiz).
        new Setting("site.monitor.escalation.step-push-enabled", "userpush",   Type.BOOL),
        // İngilizce push şablonları + başlık (2026-10-04, onaylı öneri 5): push dili İngilizce olan kişiye bunlar gider;
        // boşsa gömülü İngilizce varsayılan (PushI18n.DEFAULT_TEMPLATES_EN) / Türkçe başlık. Yer tutucular Türkçeyle aynı.
        new Setting("site.monitor.userpush.title.en",            "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.down.en",    "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.slow.en",    "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.expiry.en",  "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.changed.en", "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.cert.en",    "userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.degraded.en","userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.resolved.en","userpush",   Type.STRING),
        new Setting("site.monitor.userpush.template.test.en",    "userpush",   Type.STRING),
        new Setting("site.monitor.ui.inactivity-minutes",         "general",    Type.INT),
        new Setting("site.monitor.ui.inactivity-warn-seconds",    "general",    Type.INT),
        new Setting("site.monitor.network.error-rate-threshold", "outage",     Type.DOUBLE),
        new Setting("site.monitor.network.min-errors",           "outage",     Type.INT),
        new Setting("site.monitor.scheduler.stale-minutes",      "scheduler",  Type.INT),
        // ── Görev havuzu (certCheckExecutor) — 2026-09-10: eskiden yalnız EXECUTOR_* env + restart.
        //    ExecutorTuningService AppSettingsChangedEvent ile CANLI uygular; core<=max ve kuyruk>=1
        //    kuralı save'de (ExecutorTuningService.validate) reddedilir. Boş = env varsayılanına dön.
        new Setting("site.monitor.executor.core-size",           "executor",   Type.INT),
        new Setting("site.monitor.executor.max-size",            "executor",   Type.INT),
        new Setting("site.monitor.executor.queue-capacity",      "executor",   Type.INT),
        // A12: gunluk alan-adi bitis tazelemesi ac/kapa — kodda okunuyordu, katalogda yoktu.
        new Setting("site.monitor.scheduler.domain-expiry-refresh.enabled", "scheduler", Type.BOOL),
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
        new Setting("site.monitor.pagespeed.alert-enabled",           "monitoring", Type.BOOL),
        new Setting("site.monitor.pagespeed.resource-concurrency",    "monitoring", Type.INT),
        new Setting("site.monitor.pagespeed.user-agent",              "monitoring", Type.STRING),
        new Setting("site.monitor.pagespeed.max-check-seconds",       "monitoring", Type.INT),
        new Setting("site.monitor.pagespeed.manual-cooldown-seconds", "monitoring", Type.INT),
        // "Şimdi Dene" beklemesi — kayıtlı ölçümden AYRI ve daha kısa (form doldururken kullanılır).
        new Setting("site.monitor.pagespeed.test-cooldown-seconds",   "monitoring", Type.INT),
        // Bir ölçümde indirilecek TOPLAM bayt tavanı (KB). Kaynak başına tavan tek başına yetmiyor:
        // 500 kaynak × 10 MB teorik olarak 5 GB eder. Tavan dolunca kalan kaynaklar atlanır ve
        // ölçüm "alt sınır" olarak işaretlenir.
        new Setting("site.monitor.pagespeed.max-total-kb",            "monitoring", Type.INT),
        new Setting("site.monitor.metrics.pagespeed.retention-days",           "monitoring", Type.INT),
        new Setting("site.monitor.metrics.pagespeed-resources.retention-days", "monitoring", Type.INT),
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
        // Betikte dosya okuma (2026-10-01, öneri 1): REPORT = uyar + raporla (varsayılan, davranış aynı) · BLOCK = kayıt
        // reddedilir, koşum yürütülmez. Önce /api/admin/scripted/file-read-report ile mevcut betikler denetlenir.
        new Setting("site.monitor.scripted.file-read-policy",        "scripted", Type.ENUM, java.util.List.of("REPORT", "BLOCK")),
        // Koştu ama hiç check() çalıştırmadı: WARN = kayıt hatalı sayılır, alarm YOK (varsayılan) ·
        // FAIL = alarm da üret · PASS = eski davranış (yeniden dağıtım gerektirmeyen geri dönüş).
        new Setting("site.monitor.scripted.no-checks-policy",        "scripted", Type.ENUM, java.util.List.of("WARN", "FAIL", "PASS")),
        // Kaydetme öncesi `k6 archive` ile sözdizimi doğrulaması. WARN = uyar ama kaydet ·
        // BLOCK = kesin hatada (satır/sütun çıkarılabiliyorsa) kaydetmeyi reddet · OFF = kapalı.
        new Setting("site.monitor.scripted.syntax-check-policy",     "scripted", Type.ENUM, java.util.List.of("BLOCK", "WARN", "OFF")),
        new Setting("site.monitor.scripted.validate-timeout-seconds","scripted", Type.INT),
        // ── Kaynak tavanları (L2) — script ÜRETİM sistemlerine istek atıyor ────────────────
        // max-rps: k6 --rps; statik analizin kanıtlayamadığı döngüleri saniyede bu sayıya yayar.
        // max-procs / mem-limit: k6 alt sürecinin GOMAXPROCS / GOMEMLIMIT değerleri (Go runtime).
        // max-requests-per-run: aşılırsa koşum ANOMALİ sayılır ve izleme anında kapatılır.
        new Setting("site.monitor.scripted.max-rps",                 "scripted", Type.INT),
        new Setting("site.monitor.scripted.max-procs",               "scripted", Type.STRING),
        new Setting("site.monitor.scripted.mem-limit",               "scripted", Type.STRING),
        new Setting("site.monitor.scripted.max-requests-per-run",    "scripted", Type.INT),
        // ── Anomali guard'ı (L3) ──────────────────────────────────────────────────────────
        new Setting("site.monitor.scripted.anomaly.enabled",         "scripted", Type.BOOL),
        new Setting("site.monitor.scripted.anomaly.timeout-streak",  "scripted", Type.INT),
        new Setting("site.monitor.scripted.slow-threshold-ms",       "scripted", Type.INT),
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
        // Haftalık rapor SON GİRİŞ zamanı (2026-09-12): sayfa başlığı + hatırlatma maili + hatırlatma günü buradan.
        new Setting("site.monitor.weekly-report.deadline-day",  "monitoring", Type.ENUM,
                java.util.List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")),
        new Setting("site.monitor.weekly-report.deadline-time", "monitoring", Type.STRING),
        // Kullanılabilirlik hedefi (%) — izleme kartlarındaki 30 günlük SLA satırı bununla karşılaştırılır (2026-09-12, #11).
        new Setting("site.monitor.sla.target-pct",             "monitoring", Type.DOUBLE),
        // Kurumsal vekilin CONNECT tüneline izin verdiği portlar (2026-09-24) — Port İzleme formunda kullanıcıya gösterilir,
        // vekil reddinde hata mesajına eklenir. Vekil yöneticisinin politikasını yansıtır; kontrolü ENGELLEMEZ.
        new Setting("site.monitor.proxy.connect-ports",        "monitoring", Type.CSV),
        // Vade takvimi (2026-09-12): yenileme öncesi süre — genel + tier başına (boş/0 = genel)
        new Setting("site.monitor.renewal.lead-days",           "monitoring", Type.INT),
        new Setting("site.monitor.renewal.lead-days-t1",        "monitoring", Type.INT),
        new Setting("site.monitor.renewal.lead-days-t2",        "monitoring", Type.INT),
        new Setting("site.monitor.renewal.lead-days-t3",        "monitoring", Type.INT),
        new Setting("site.monitor.renewal.lead-days-t4",        "monitoring", Type.INT),
        // Birleşik aktivite akışı (Kayıtlar → Aktivite) kayıt saklama süresi — bu günden eski aktiviteler gece temizlenir.
        new Setting("site.monitor.activity.retention-days",           "monitoring", Type.INT),
        // Denetim (audit) kayıt saklama süresi (gün) + silmeden önce JSONL arşiv üretimi (append-only + arşiv).
        new Setting("site.monitor.audit.retention-days",             "monitoring", Type.INT),
        // İzleme YAPILANDIRMASI değişiklik geçmişi (kim/ne zaman/hangi IP/neyi değiştirdi).
        // audit'ten UZUN tutulur (varsayılan 730 gün): ayrı tablo olmasının sebeplerinden biri de budur.
        new Setting("site.monitor.monitoring.change-retention-days", "monitoring", Type.INT),
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
        new Setting("site.monitor.pagespeed.default-interval-seconds", "frequency", Type.INT),
        new Setting("site.monitor.pagespeed.default-timeout-ms",       "frequency", Type.INT),
        // A12: bu iki tur MonitoringController tarafindan `getInt` ile OKUNUYORDU ama katalogda
        // YOKTU. AppSettingsService katalog disi anahtari IllegalArgumentException ile reddettigi
        // icin Genel Ayarlar > Siklik listesinde 8 tur gorunup DNS ile Sentetik hic cikmiyordu:
        // varsayilanlarini degistirmenin tek yolu application.properties duzenleyip yeniden
        // baslatmakti. Kod "canli okunuyor" gibi gorunuyordu, degildi.
        new Setting("site.monitor.dns.default-interval-seconds",      "frequency", Type.INT),
        new Setting("site.monitor.scripted.default-interval-seconds", "frequency", Type.INT),
        // A12 — kodda okunan ama katalogda olmayan anahtarlar (canli duzenlenemiyorlardi):
        // gunluk ikinci kritik-domain kontrolu (SchedulerService.runCriticalDomainChecks),
        // kara liste (DNSBL) sorgusu ve uptime kurtarma zinciri.
        new Setting("site.monitor.domain.critical-check-enabled",         "monitoring", Type.BOOL),
        new Setting("site.monitor.domain.critical-check-threshold-days",  "monitoring", Type.INT),
        new Setting("site.monitor.domain.dnsbl-lists",                    "monitoring", Type.CSV),
        new Setting("site.monitor.domain.dnsbl-max-ips",                  "monitoring", Type.INT),
        new Setting("site.monitor.domain.dnsbl-budget-ms",                "monitoring", Type.INT),
        new Setting("site.monitor.uptime.recovery-checks",                "monitoring", Type.INT),
        new Setting("site.monitor.uptime.recovery-interval-ms",           "monitoring", Type.INT),
        // Haftalık erişilebilirlik e-postası (Pazartesi 10:00) aç/kapa — canlı.
        new Setting("site.monitor.weekly-availability.enabled",  "monitoring", Type.BOOL),
        // Aylık sertifika envanteri raporu (ayın son cuması 10:00) — aç/kapa + alıcılar, canlı.
        new Setting("site.monitor.cert-inventory-report.enabled",    "monitoring", Type.BOOL),
        new Setting("site.monitor.cert-inventory-report.recipients", "monitoring", Type.STRING),
        new Setting("site.monitor.cert-inventory-report.cc",         "monitoring", Type.STRING),
        // Zamanlama CANLI: dinamik tetikleyici her hesaplamada bunu okur (yeniden başlatma yok).
        new Setting("site.monitor.cert-inventory-report.cron",       "monitoring", Type.STRING),
        // Aylık yönetici özeti (2026-10-10) — Raporlar → Yönetici Özeti sayfası yönetir (grup Genel Ayarlar'da GİZLİ,
        // GeneralSettings SKIP_GROUPS). Zamanlanmış gönderim VARSAYILAN KAPALI (opt-in). Hepsi GLOBAL_ONLY (aşağıda).
        new Setting("site.monitor.executive-summary.enabled",               "executive-summary", Type.BOOL),
        new Setting("site.monitor.executive-summary.cron",                  "executive-summary", Type.STRING),
        new Setting("site.monitor.executive-summary.recipients",            "executive-summary", Type.STRING),
        new Setting("site.monitor.executive-summary.include-global-admins", "executive-summary", Type.BOOL),
        new Setting("site.monitor.executive-summary.availability-target",   "executive-summary", Type.DOUBLE),
        new Setting("site.monitor.executive-summary.renewal-target-days",   "executive-summary", Type.INT),
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
        // 2026-09-30: fırtına ömür sınırı — son üye katılımından bu kadar dakika sonra yeni üye gelmediyse fırtına
        // mühürlenir ve kapanır (kalıcı başarısız üyeler fırtınayı süresiz açık tutup yeni alarmları yutmasın).
        new Setting("site.monitor.storm.quiet-minutes",          "storm",      Type.INT),
        // 2026-10-03 (kullanıcı kararı): push fırtınaya devredilmez — fırtına yalnız e-postayı gruplar, kişi push'u alarm başına
        // açılış sırasıyla gider. Varsayılan true; false = 2026-10-02'ye kadarki toplu fırtına push'u. GLOBAL_ONLY değil (sır yok).
        new Setting("site.monitor.storm.push-individual",        "storm",      Type.BOOL),
        // ── Tür bazlı yeniden uyarı sıklığı (2026-10-01, opt-in) — ReAlertIntervals. Dakika; 0 = genel aralık (eşik
        //    tablosundaki reAlertIntervalHours, varsayılan 24 saat — bugünkü davranış); aksi 15–10080. EscalationService
        //    yeniden uyarı kararında CANLI okur. GLOBAL_ONLY değil: sır taşımaz, fırtına / alert-enabled ayarlarıyla aynı sınıf.
        new Setting("site.monitor.realert.cert-minutes",         "realert",    Type.INT),
        new Setting("site.monitor.realert.domain-minutes",       "realert",    Type.INT),
        new Setting("site.monitor.realert.http-minutes",         "realert",    Type.INT),
        new Setting("site.monitor.realert.ping-minutes",         "realert",    Type.INT),
        new Setting("site.monitor.realert.port-minutes",         "realert",    Type.INT),
        new Setting("site.monitor.realert.dns-minutes",          "realert",    Type.INT),
        new Setting("site.monitor.realert.keyword-minutes",      "realert",    Type.INT),
        new Setting("site.monitor.realert.page-minutes",         "realert",    Type.INT),
        new Setting("site.monitor.realert.pagespeed-minutes",    "realert",    Type.INT),
        new Setting("site.monitor.realert.scripted-minutes",     "realert",    Type.INT),
        // Kurumsal/iç kök+ara CA paketi (PEM) — bu CA ile imzalı host'lar TRUSTED sayılır.
        // TrustEvaluator okuma anında okur (canlı reload). Boş = yalnız public CA'lar (cacerts).
        // Yeni cihazdan giriş bilgi e-postası (E1). Varsayılan KAPALI: kurumsal kurulumda
        // posta hacmi bir karardır, yönetici açar. Tespit ham UA değil CİHAZ ÖZETİ ile
        // yapılır — yoksa her tarayıcı güncellemesi yanlış alarm üretirdi.
        new Setting("site.monitor.security.new-device-email",   "security",   Type.BOOL),
        // A12: giris sorunu bildirimi, kullanici "sustur" demis olsa bile gonderilsin mi.
        new Setting("site.monitor.login-issues.force-email",     "security",   Type.BOOL),
        new Setting("site.monitor.trust.ca-bundle-pem",          "security",   Type.TEXT),
        // CA otomatik sabitleme (TOFU) — PKIX hatasında CA sunucudan çekilip host bazında pinlenir,
        // rotasyon/bitişte otomatik yenilenir (CaAutoPinService). Kapsam: HTTP uptime strict + RDAP çıkışı.
        new Setting("site.monitor.trust.auto-pin.enabled",       "security",   Type.BOOL),
        // Güvenlik alarmları (sunulan sertifika bu host için kabul edilebilir mi):
        //  • hostname uyuşmazlığı → varsayılan AÇIK (meşru olarak neredeyse hiç olmaz);
        //  • güvenilmeyen CA → varsayılan KAPALI, çünkü ca-bundle-pem boşken iç host'ların TÜMÜ
        //    UNTRUSTED görünür; açık gelseydi yayın anında alarm seli olurdu.
        new Setting("site.monitor.trust.alert-hostname-mismatch", "security", Type.BOOL),
        new Setting("site.monitor.trust.alert-untrusted",         "security", Type.BOOL),
        // ── Giriş Yöntemleri (2026-10-02, kullanıcı isteği) — Ayarlar → Güvenlik → "Giriş Yöntemleri" sayfası yönetir
        //    (LoginMethodsController; Genel Ayarlar'da GÖSTERİLMEZ — grup GeneralSettings SKIP_GROUPS'ta). Hepsi GLOBAL_ONLY.
        //    ldap-enabled: LDAP hesaplarının PAROLA (AD bind) girişi — mevcut LDAP entegrasyonundan (dizin/eşitleme) BAĞIMSIZ;
        //    yerel hesaplar ve kurulumdaki bootstrap admin bundan etkilenmez. otp.*: push / e-posta ile tek kullanımlık kodla giriş.
        new Setting("site.monitor.login.ldap-enabled",                    "login-methods", Type.BOOL),
        new Setting("site.monitor.login.otp.push.enabled",                "login-methods", Type.BOOL),
        new Setting("site.monitor.login.otp.email.enabled",               "login-methods", Type.BOOL),
        new Setting("site.monitor.login.otp.push.ttl-seconds",            "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.email.ttl-seconds",           "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.max-attempts",                "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.resend-cooldown-seconds",     "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.max-requests-per-user",       "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.max-requests-per-ip",         "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.max-failed-verifications",    "login-methods", Type.INT),
        new Setting("site.monitor.login.otp.allow-global-admins",         "login-methods", Type.BOOL),
        //    2026-10-03 (kullanıcı isteği): kod isteğinde kayıtlı cep telefonu (push) / e-posta (e-posta) da sorulur, kod
        //    yalnız eşleşirse gider; 15 dk'da kullanıcı başına eşleşmeyen deneme sınırı.
        new Setting("site.monitor.login.otp.push.require-phone",          "login-methods", Type.BOOL),
        new Setting("site.monitor.login.otp.email.require-email",         "login-methods", Type.BOOL),
        new Setting("site.monitor.login.otp.max-contact-mismatches",      "login-methods", Type.INT),
        //    2026-10-03 (kullanıcı isteği): kodla giriş push BAŞLIĞI / MESAJI (TR / EN). Boş = yerleşik varsayılan; mesajda
        //    {kod} zorunlu (tam bir kez), {sure} / {saat} isteğe bağlı — doğrulama LoginMethodsController + OtpPushTemplate.
        new Setting("site.monitor.login.otp.push.title-tr",               "login-methods", Type.STRING),
        new Setting("site.monitor.login.otp.push.title-en",               "login-methods", Type.STRING),
        new Setting("site.monitor.login.otp.push.message-tr",             "login-methods", Type.STRING),
        new Setting("site.monitor.login.otp.push.message-en",             "login-methods", Type.STRING),
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
        // Giriş sayfası "Kullanım istatistikleri" (2026-10-04): sağlıklı izleme, 24 sa koşum/alarm, takım, aktif ve
        // çevrimiçi kullanıcı — yalnız toplam sayılar, PUBLIC /api/public-stats. Kapalıyken uç yalnız eski iki rakamı döner.
        // Marka sayfasında (giriş sayfası ayarlarının yanında) düzenlenir; kurulumun DIŞA açık yüzü → GLOBAL_ONLY.
        new Setting("site.monitor.public-stats.usage-enabled",   "branding",   Type.BOOL),
        // ── Görünüm → Temalar (2026-10-05, kullanıcı isteği) — ThemeSettingsController yönetir (Genel Ayarlar'da GÖSTERİLMEZ,
        //    grup GeneralSettings SKIP_GROUPS'ta); /api/branding "themes" alanıyla PUBLIC okunur. Kurallar ThemeCatalog.validate
        //    (bilinen kimlik, en az bir tema, varsayılan açık, "system" Açık + Koyu ister) — AppSettingsService de uygular.
        //    Anahtarlar LİTERAL yazılır (ThemeCatalog.KEY_* ile aynı): ön yüz etiket/yardım kapıları kataloğu dize olarak tarar.
        new Setting("site.monitor.theme.enabled",                "appearance", Type.CSV),
        new Setting("site.monitor.theme.default",                "appearance", Type.ENUM, ThemeCatalog.DEFAULT_OPTIONS),
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
        // site.monitor.inventory.auto-purge-days KALDIRILDI (2026-10-07): envanter silmesi kalıcı, çöp kutusu yok.
        new Setting("site.monitor.rollup.lookback-days",              "retention", Type.INT),
        new Setting("site.monitor.rollup.retention-days",             "retention", Type.INT),
        new Setting("site.monitor.rollup.hourly-retention-days",      "retention", Type.INT),
        new Setting("site.monitor.page-usage.retention-days",         "retention", Type.INT),   // sayfa kullanımı özeti (System Health #1)
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
        new Setting("site.monitor.login.otp.retention-days",          "retention", Type.INT),   // kodla giriş istekleri (2026-10-02)
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

    /** Tam katalog: elle yazılan ayarlar + politika başına üretilen uyum-onayı anahtarları. */
    public static final List<Setting> ALL = java.util.stream.Stream.concat(
            STATIC.stream(),
            RetentionCatalog.ALL.stream()
                    .map(p -> new Setting(RETENTION_APPROVAL_PREFIX + p.id(), "retention", Type.STRING)))
            .toList();

    public static Setting byKey(String key) {
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }

    /**
     * YALNIZ GLOBAL yöneticinin değiştirebileceği anahtarlar. 2026-09-10 ürün kararı: kapsamlı müdür
     * (AD ADMIN, viewTeamIds dolu) Ayarlar'ı görür ve operasyonel ayarları düzenler; ama v20.50.29'un
     * kapattığı sızıntı/RCE vektörleri kapalı kalır. Her satırın gerekçesi:
     * <ul>
     *   <li>SSRF kapıları + DNS çözücü: iç ağa/metadata'ya erişimi açar ya da izleme hedeflerini
     *       saldırganın çözücüsüne yönlendirir.</li>
     *   <li>CA paketi + TOFU: sahte sertifikaya güven → MITM; RDAP taban URL'i dış hedef.</li>
     *   <li>k6 ikili yolu ve API adresi: sunucuda çalıştırılacak yürütülebilir dosyayı seçer (RCE).</li>
     *   <li>Push URL + başlıklar: saklı gizli başlıklar "Test" ile saldırganın sunucusuna gider.</li>
     *   <li>Uygulama taban URL'i + CORS + sistem yöneticisi e-postası: tüm e-posta bağlantılarını
     *       sahte alana çevirme (oltalama) / kimlikli isteklere yabancı origin açma / yönetici
     *       bildirimlerini yönlendirme.</li>
     *   <li>Log seviyeleri: TRACE mail logu içerik/başlık sızdırır.</li>
     *   <li>Org geneli envanter görünürlüğü: TÜM takımların alan adı, sorumlu kişi ve sertifika verisinin
     *       kime açık olduğunu belirleyen kurum politikası — takım kapsamlı bir müdür diğer takımlar adına
     *       karar veremez (2026-09-26). Kardeşi {@code incidents.visible-to-all} (TÜM takımların olay kayıtları ve
     *       yorumları, 2026-09-28) aynı sınıf.</li>
     *   <li>LDAP müdür/üyelik eşlemesi ({@code ldap.manager-attributes}, {@code ldap.prune-unsupported-teams},
     *       {@code ldap.manager-refresh-hours}): müdür bağı bir YETKİLENDİRME girdisidir (managerId → görüş/yönetim
     *       kapsamı) ve budama kimin hangi takımda kalacağını belirler. Müdür sicilinin okunduğu AD niteliğini
     *       değiştiren takım kapsamlı bir müdür kapsamı dolaylı genişletebilirdi (BO6, bug regresyon 2026-09-27;
     *       kardeşi {@code inventory.visible-to-all} ile aynı sınıf).</li>
     *   <li>Veri saklama ({@link #retentionKeys}, 2026-09-28 regresyon taraması): saklama süreleri, yasal saklama
     *       (legal hold), temizlik parti boyutu, uyum onayları ve envanter çöp kutusu otomatik boşaltma BÜTÜN takımların
     *       verisini siler/korur — sistem geneli ayar. Müdür eskiden yasal saklamayı kapatıp süreleri tabana çekerek
     *       her takımın geçmişini kalıcı sildirebiliyordu. Liste {@code RetentionCatalog}'dan TÜRETİLİR: yeni politika
     *       eklenince kapı kendiliğinden kapsar.</li>
     *   <li>Ortam adı ({@link BuildInfo#ENV_KEY}, 2026-09-29): kurulumun TÜMÜNÜ adlandırır — sürüm penceresi, dağıtım
     *       geçmişinin ortam anahtarı (koşan kaydı yeni ada taşır) ve Prometheus/Grafana {@code environment} etiketi.
     *       Takım kapsamlı bir müdür bütün kurulumun kimliğini değiştiremez.</li>
     *   <li>Giriş sayfası kullanım istatistikleri ({@code public-stats.usage-enabled}, 2026-10-04): OTURUMSUZ herkese
     *       açık uçta kurum geneli sayıların (izleme, koşum, alarm, takım, aktif/çevrimiçi kullanıcı) yayınlanması — kurulumun
     *       dışa açık yüzü; takım kapsamlı bir müdür kurum adına açıp kapatamaz.</li>
     *   <li>Temalar ({@code theme.enabled}, {@code theme.default}, 2026-10-05): TÜM kullanıcıların (giriş sayfası dahil)
     *       hangi temaları seçebileceğini ve seçimi olmayanın hangisini göreceğini belirler — kurum geneli görünüm
     *       politikası; takım kapsamlı müdür Ayarlar → Görünüm → Temalar sayfasını SALT OKUNUR görür.</li>
     * </ul>
     * Zorlama TEK yerde: {@link AppSettingsService#save} (hangi denetleyici çağırırsa çağırsın);
     * {@code getCatalogForClient} kalemi {@code global_only}/{@code read_only} ile işaretler, UI kilitler.
     * Kapı: {@code SettingsScopedAdminGateTest}.
     */
    public static final java.util.Set<String> GLOBAL_ONLY = withRetentionKeys(java.util.Set.of(
        "site.monitor.monitoring.allow-internal-targets",
        "site.monitor.monitoring.allow-loopback-targets",
        "site.monitor.dns.resolvers",
        "site.monitor.trust.ca-bundle-pem",
        "site.monitor.trust.auto-pin.enabled",
        "site.monitor.http.rdap-base-url",
        "site.monitor.scripted.k6-bin",
        "site.monitor.scripted.k6-api-address",
        "site.monitor.scripted.hardcoded-secret-policy",
        "site.monitor.scripted.file-read-policy",
        "site.monitor.userpush.url",
        "site.monitor.userpush.headers",
        "site.monitor.app.base-url",
        "site.monitor.cors.allowed-origins",
        "site.monitor.system-admin.email",
        "logging.level.com.sitemonitor",
        "logging.level.com.sitemonitor.mail",
        "site.monitor.inventory.visible-to-all",
        "site.monitor.incidents.visible-to-all",
        "site.monitor.ldap.manager-attributes",
        "site.monitor.ldap.prune-unsupported-teams",
        "site.monitor.ldap.manager-refresh-hours",
        "site.monitor.environment",
        // Giriş yöntemleri (2026-10-02): KİMİN NASIL kimlik doğrulayacağını belirleyen kurum geneli güvenlik politikası —
        // kapsamlı bir müdür LDAP girişini kapatıp herkesi koda yönlendiremez, kod deneme/sınır eşiklerini gevşetemez ya da
        // global yöneticilere kodla girişi açamaz. Liste LoginMethodsService.KEYS ile aynı (SettingsScopedAdminGateTest).
        "site.monitor.login.ldap-enabled",
        "site.monitor.login.otp.push.enabled",
        "site.monitor.login.otp.email.enabled",
        "site.monitor.login.otp.push.ttl-seconds",
        "site.monitor.login.otp.email.ttl-seconds",
        "site.monitor.login.otp.max-attempts",
        "site.monitor.login.otp.resend-cooldown-seconds",
        "site.monitor.login.otp.max-requests-per-user",
        "site.monitor.login.otp.max-requests-per-ip",
        "site.monitor.login.otp.max-failed-verifications",
        "site.monitor.login.otp.allow-global-admins",
        // 2026-10-03: kişi bilgisi doğrulaması — müdür telefon/e-posta sorgusunu kapatıp kodu yalnız kullanıcı adına
        // indiremez ya da eşleşmeme sınırını gevşetemez.
        "site.monitor.login.otp.push.require-phone",
        "site.monitor.login.otp.email.require-email",
        "site.monitor.login.otp.max-contact-mismatches",
        // 2026-10-03: kodla giriş push metni — müdür kurum geneli giriş kodu iletisini değiştiremez (kimlik avı metni
        // yazılamasın, kod kaldırılamasın).
        "site.monitor.login.otp.push.title-tr",
        "site.monitor.login.otp.push.title-en",
        "site.monitor.login.otp.push.message-tr",
        "site.monitor.login.otp.push.message-en",
        // 2026-10-04: giriş sayfası kullanım istatistikleri — OTURUMSUZ herkese açık uçta kurum geneli kullanım
        // rakamlarını yayınlayıp yayınlamama kararı kurulumun tamamını bağlar; takım kapsamlı müdür açıp kapatamaz.
        "site.monitor.public-stats.usage-enabled",
        // 2026-10-05: temalar — kurum geneli görünüm politikası (açık temalar + varsayılan); müdür salt okunur görür.
        "site.monitor.theme.enabled",
        "site.monitor.theme.default",
        // 2026-10-10: aylık yönetici özeti — KURUM GENELİ rapor: kime gideceği (alıcılar + global yöneticiler), ne zaman
        // gideceği ve hangi hedeflerle ölçüleceği global yönetici kararıdır; müdür sayfayı açamaz (uç ayrıca kapılı).
        "site.monitor.executive-summary.enabled",
        "site.monitor.executive-summary.cron",
        "site.monitor.executive-summary.recipients",
        "site.monitor.executive-summary.include-global-admins",
        "site.monitor.executive-summary.availability-target",
        "site.monitor.executive-summary.renewal-target-days"
    ));

    /**
     * Veri saklama anahtarları — {@code RetentionCatalog}'daki her politikanın süre anahtarı + uyum onayı anahtarı,
     * yasal saklama ve parti boyutu. Elle liste DEĞİL (yeni politika sessizce dışarıda kalmasın). Envanter çöp kutusu
     * otomatik boşaltma anahtarı 2026-10-07'de kalktı (silme kalıcı).
     */
    public static java.util.Set<String> retentionKeys() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        out.add(RetentionCatalog.HOLD_KEY);
        out.add(RetentionCatalog.BATCH_KEY);
        for (var p : RetentionCatalog.ALL) {
            if (p.settingKey() != null) out.add(p.settingKey());
            out.add(RETENTION_APPROVAL_PREFIX + p.id());
        }
        return out;
    }

    private static java.util.Set<String> withRetentionKeys(java.util.Set<String> base) {
        java.util.Set<String> all = new java.util.HashSet<>(base);
        all.addAll(retentionKeys());
        return java.util.Set.copyOf(all);
    }

    public static boolean isGlobalOnly(String key) {
        return key != null && GLOBAL_ONLY.contains(key);
    }
}
