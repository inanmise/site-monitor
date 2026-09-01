package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.model.NetworkOutageEvent;
import com.sitemonitor.model.PageCheck;
import com.sitemonitor.model.PageResourceIssue;
import com.sitemonitor.model.PageSpeedCheck;
import com.sitemonitor.model.PageSpeedResource;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.model.PingCheck;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final CertificateCheckerService checkerService;
    private final CertificateService certService;
    private final EmailNotificationService emailService;
    private final EscalationService escalationService;
    private final MaintenanceService maintenanceService;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;
    private final PermissionService permissionService;
    /** Tek seferlik geçmiş geri doldurma (K5) — açılışta koşar, nişanı kendi tablosunda tutar. */
    private final MonitorHistoryBackfillService historyBackfill;
    private final DataSource dataSource;
    private final ApplicationEventPublisher eventPublisher;   // readiness gating (REFUSING/ACCEPTING_TRAFFIC)

    private final PortCheckerService portCheckerService;
    private final PortMonitorRepository portMonitorRepo;
    private final PortCheckRepository portCheckRepo;

    private final DnsCheckerService dnsCheckerService;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final DnsRecordRepository dnsRecordRepo;

    private final UptimeHttpCheckerService uptimeHttpCheckerService;
    private final UptimeCheckRepository uptimeCheckRepo;
    private final MonitoringOutageService monitoringOutageService;

    private final KeywordCheckerService keywordCheckerService;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final KeywordResultRepository keywordResultRepo;

    private final PingCheckerService pingCheckerService;
    private final PingMonitorRepository pingMonitorRepo;
    private final PingCheckRepository pingCheckRepo;

    private final HttpCheckerService httpCheckerService;
    private final HttpMonitorRepository httpMonitorRepo;
    private final HttpCheckRepository httpCheckRepo;
    private final RdapDomainExpiryService rdapDomainExpiryService;
    private final ActivityLogService activityLog;   // birleşik aktivite akışı (best-effort)
    private final AuditService auditService;         // sistem olayları (schema-patch, retention-purge)
    private final FailedLoginAnomalyIncidentService failedLoginAnomalyIncidentService;   // başarısız-login anomali taraması

    private final DomainMonitorRepository domainMonitorRepo;
    private final DomainCheckRepository domainCheckRepo;
    private final DomainCheckerService domainCheckerService;

    private final NetworkOutageEventRepository networkOutageRepo;

    private final WeeklyReportReminderService weeklyReportReminderService;
    private final WeeklyAvailabilityReportService weeklyAvailabilityReportService;
    private final com.sitemonitor.service.report.CertificateInventoryReportService certificateInventoryReportService;

    private final IncidentService incidentService;

    private final AppSettingsService appSettings;

    /** Gece temizliği artık bildirimsel RetentionCatalog üzerinden koşar (2026-08). */
    private final RetentionService retentionService;

    /** Per-monitör SONRAKİ VADE zamanı (epoch ms), key "type:id" — GRID semantiği (2026-08-03; ad tarihsel,
     *  testler reflection ile bağlı). In-memory → restart'ta sıfırlanır (ilk sweep'te hepsi due). Gerçek
     *  "check frequency": vade sabit interval adımlarıyla ilerler; sweep gecikmesi kadansa taşınmaz. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastMonitorCheckAt = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    @Qualifier("certCheckExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor certCheckExecutor;

    /** Alan enjeksiyonu bilinçli: constructor'ı (ve SchedulerServiceTest kurulumunu) büyütmemek için
     *  certCheckExecutor ile aynı desen. */
    @Autowired
    private CaAutoPinService caAutoPinService;

    /** Aynı gerekçeyle alan enjeksiyonu — envanter domain-expiry cache'ini günlük tazeleyen servis. */
    @Autowired
    private DomainExpiryRefreshService domainExpiryRefreshService;

    /** Sayfa-bütünlüğü (9. tür) — constructor'ı/testini büyütmemek için alan enjeksiyonu (aynı desen). */
    @Autowired
    private PageCheckerService pageCheckerService;
    @Autowired
    private com.sitemonitor.repository.PageMonitorRepository pageMonitorRepo;
    @Autowired
    private com.sitemonitor.repository.PageCheckRepository pageCheckRepo;
    @Autowired
    private com.sitemonitor.repository.PageResourceIssueRepository pageResourceIssueRepo;

    /** Sayfa Hızı — alan enjeksiyonu (aynı desen). */
    @Autowired
    private PageSpeedCheckerService pageSpeedCheckerService;
    @Autowired
    private com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @Autowired
    private com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    @Autowired
    private com.sitemonitor.repository.PageSpeedResourceRepository pageSpeedResourceRepo;

    /** Senaryo İzleme — alan enjeksiyonu (aynı desen). */
    @Autowired
    private ScriptedCheckerService scriptedCheckerService;
    @Autowired
    private com.sitemonitor.repository.ScriptedMonitorRepository scriptedMonitorRepo;
    @Autowired
    private com.sitemonitor.repository.ScriptedCheckRepository scriptedCheckRepo;
    /** Anomali guard'ı (L3) — koşum kaydedildikten sonra izlemeyi kapatabilir. */
    @Autowired
    private ScriptedAnomalyGuard scriptedAnomalyGuard;

    @Value("${site.monitor.username:user}")
    private String adminUsername;

    @Value("${site.monitor.password:password}")
    private String adminPassword;

    /** Stale threshold: domain not checked within this many minutes is considered stale. */
    /** Sertifika süpürme cron'u — "sonraki kontrol" hesabı da bunu okur (tek kaynak). */
    @Value("${site.monitor.scheduler.cron:0 0 * * * *}")
    private String sweepCron;

    @Value("${site.monitor.scheduler.stale-minutes:65}")
    private int staleMinutes;

    /** Lock TTL: must be > the longest possible check run but short enough that a crashed
     *  instance doesn't block the cluster for too long. */
    @Value("${site.monitor.scheduler.lock-ttl-minutes:10}")
    private int lockTtlMinutes;

    /** Sweep-level distributed lock TTL. Kısa tutulur (2 dk) → crash sonrası kilit hızlı
     *  self-heal olur; startup temizliği (clearStaleLocksForThisHost) de ayrıca siler.
     *  İzleme sweep'leri hızlıdır; çok sayıda monitörde bir sweep bu süreyi aşarsa 2+ pod'da
     *  ikinci pod aynı turu başlatabilir → o zaman artırılır (env: SWEEP_LOCK_TTL_MINUTES). */
    @Value("${site.monitor.scheduler.sweep-lock-ttl-minutes:2}")
    private int sweepLockTtlMinutes;

    /** Açılışta ağır iş (catch-up + tam tarama) bu kadar ms geciktirilir — login/BCrypt
     *  ilk dakikada CPU'yu kapışmasın. 0 = anında (eski davranış). */
    @Value("${site.monitor.scheduler.startup-check-delay-ms:60000}")
    private long startupCheckDelayMs;

    @Value("${site.monitor.alert.default-warning-days:30}")
    private int defaultWarningDays;

    @Value("${site.monitor.alert.default-high-days:15}")
    private int defaultHighDays;

    @Value("${site.monitor.alert.default-critical-days:7}")
    private int defaultCriticalDays;

    @Value("${site.monitor.alert.default-realert-hours:24}")
    private int defaultReAlertHours;

    /** Bulk network failure threshold — when the network-class error rate in a single
     *  run reaches or exceeds this value (AND minNetworkErrors), the run is treated as
     *  a suspected outage and downstream alarm processing is suppressed. */
    @Value("${site.monitor.network.error-rate-threshold:0.50}")
    private double networkErrorRateThreshold;

    @Value("${site.monitor.network.min-errors:3}")
    private int networkMinErrors;

    @Value("${site.monitor.system-admin.email:erdi.inanmis@gmail.com}")
    private String systemAdminEmail;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Envanteri pasifleşip sweep'ten düşen DNS monitörünün açık alarmları (kontrol durduğu için
     *  recovery hiç gelmez → sessizce kapatılır). MonitoringController.DNS_ALERT_TYPES ile aynı küme. */
    private static final Set<String> DNS_SKIP_ALERT_TYPES = Set.of(
            EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_DNS_CHANGED, EscalationService.TYPE_DNS_SLOW,
            EscalationService.TYPE_DNS_UNEXPECTED, EscalationService.TYPE_DNS_INCONSISTENT);

    /** Hostname portion of the instance ID — used to clear stale locks left by previous
     *  instances on the same machine (crash/kill without running the finally block). */
    private static final String HOSTNAME   = resolveHostname();
    private static final String INSTANCE_ID = HOSTNAME + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    // In-process guard — prevents same JVM from running two checks concurrently
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<LocalDateTime> lastRun = new AtomicReference<>();
    private final AtomicReference<String> currentRunId = new AtomicReference<>("");
    private final AtomicReference<String> lastRunId    = new AtomicReference<>("");

    // Scan statistics — updated at end of each successful scan
    private final AtomicLong    lastRunDurationMs = new AtomicLong(0);
    private final AtomicInteger lastRunTotal      = new AtomicInteger(0);
    private final AtomicInteger lastRunWarnings   = new AtomicInteger(0);
    private final AtomicInteger lastRunErrors     = new AtomicInteger(0);
    /** Son sweep BAŞARISIZ bittiyse özet (aksi halde null). Sağlık ekranında görünür ki tur
     *  sessizce yanmasın — eskiden istisna yalnız Spring'in generic log satırına düşüyordu. */
    private final AtomicReference<String> lastRunFailure = new AtomicReference<>(null);

    // Network bulk-failure state (in-memory; resets on restart)
    private final AtomicBoolean   networkOutageActive       = new AtomicBoolean(false);
    private final AtomicReference<String> networkOutageDetectedAt = new AtomicReference<>(null);
    private final AtomicReference<String> networkOutageResolvedAt = new AtomicReference<>(null);
    private final AtomicReference<Double> networkLastErrorRate    = new AtomicReference<>(0.0);
    private final AtomicInteger   networkLastNetworkErrors  = new AtomicInteger(0);
    private final AtomicInteger   networkLastTotal          = new AtomicInteger(0);
    /** Set when admin alert email could not be sent (likely because the same outage blocked SMTP).
     *  Retried on every scheduler tick until cleared by a successful alert OR resolved email. */
    private final AtomicBoolean   pendingAdminAlertEmail    = new AtomicBoolean(false);
    /** Set when admin resolved email could not be sent. */
    private final AtomicBoolean   pendingAdminResolvedEmail = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        log.info("Application started [instance={}] — bootstrapping...", INSTANCE_ID);
        // Senkron bootstrap boyunca trafiği REDDET: /health/readiness 503 döner → kubelet pod'u Service
        // endpoint'lerine EKLEMEZ. Böylece soğuk-JVM + şema patch + seeding sürerken kullanıcı isteği
        // bu pod'a yönlenmez (ilk tıklama askıda kalmaz). Bitince finally'de ACCEPTING → sıcak servis.
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        try {
            applySchemaPatches();
            auditService.recordSystemEvent("SCHEMA_PATCH", "SYSTEM", "db", "başlangıç şema yamaları uygulandı");
            userService.ensureBootstrapped(adminUsername, adminPassword);
            // In-memory oturumlar restart'ta silinir ama DB'deki activeSessionId kalır → aksi halde
            // "Aktif Oturum" sayımı şişer ve restart sonrası ilk login'de gerçekte canlı oturum
            // olmasa da "başka yerde aktif oturum" onayı çıkar. Açılışta stale işaretleri temizle.
            try {
                int cleared = userService.clearAllActiveSessions();
                if (cleared > 0) log.info("Cleared {} stale active-session marker(s) on startup", cleared);
            } catch (Exception e) {
                log.warn("Startup active-session cleanup failed: {}", e.getMessage());
            }
            // Geçmiş tablosu ŞEMA YAMALARINDAN SONRA doldurulur (tablo/kolonlar hazır olmalı).
            // Hata yutulur: geri doldurma açılışı düşürmez, bir sonraki başlangıç yeniden dener.
            try {
                int moved = historyBackfill.runOnce();
                if (moved > 0) log.info("İzleme değişiklik geçmişi denetimden dolduruldu: {} satır", moved);
            } catch (Exception e) {
                log.warn("İzleme geçmişi geri doldurma başarısız: {}", e.getMessage());
            }
            permissionService.seedDefaultsIfEmpty();
            permissionService.seedMissingDefaults(); // katalogda yeni eklenen modüllerin grant'lerini backfill et
            // Politika yükseltmeleri: seedMissingDefaults yalnız EKSİK satırı ekler, var olan
            // allowed=false satırını çevirmez — sonradan gevşetilen bir varsayılan mevcut
            // kurulumlarda ancak bununla geçerli olur (yalnız insan eli değmemiş satırlar).
            permissionService.applyPolicyUpgrades();
            try { incidentService.seedOptions(); } // olay modülü varsayılan kanalları (idempotent)
            catch (Exception e) { log.warn("Incident option seed failed: {}", e.getMessage()); }
            ensureDefaultThreshold();
            warnOnOrphanedRecords();
            clearStaleLocksForThisHost();
            restoreOutageStateFromDb();
        } finally {
            // Senkron bootstrap bitti (başarılı ya da değil — mevcut davranış: app yine de çalışmaya
            // devam eder) → trafiğe HAZIR. Gecikmeli ağır tarama aşağıda arka planda kalır.
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
            log.info("Bootstrap complete — readiness=ACCEPTING_TRAFFIC [instance={}]", INSTANCE_ID);
        }
        // Ağır açılış işi (escalation catch-up + tam sertifika taraması) ANINDA
        // çalışırsa, CPU-sınırlı pod'da JVM ısınması + bu işin TLS/OCSP/CRL kriptosu
        // ilk interaktif isteği (login → BCrypt) aç bırakır → login ~1dk askıda kalır.
        // Bu yüzden tek daemon thread'e alıp startupCheckDelayMs kadar geciktiriyoruz;
        // pod Ready olur olmaz login CPU'yu serbest bulur, ağır iş JVM ısınınca başlar.
        Thread t = new Thread(() -> {
            try {
                if (startupCheckDelayMs > 0) Thread.sleep(startupCheckDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try { escalationService.catchUpMissedDailyAlerts(); }
            catch (Exception e) { log.warn("Startup catch-up (daily) failed: {}", e.getMessage()); }
            try { escalationService.catchUpAlertsOnDeletedDomains(); }
            catch (Exception e) { log.warn("Startup catch-up (deleted) failed: {}", e.getMessage()); }
            runCheck();
        }, "startup-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Removes any scheduler lock left by a previous instance on THIS machine that
     * crashed or was killed before the finally block ran.
     * Locks from other hosts (different hostname prefix) are intentionally preserved
     * so rolling restarts in HA clusters don't cancel a running check on another node.
     */
    private void clearStaleLocksForThisHost() {
        try {
            int deleted = jdbcTemplate.update(
                "DELETE FROM scheduler_lock WHERE name = ? AND locked_by LIKE ?",
                "cert-check", HOSTNAME + "-%");
            // İzleme alarm pipeline'ının tip+domain bazlı kilitleri (TTL zaten
            // kendini temizler; bu sadece crash sonrası toparlanmayı hızlandırır)
            deleted += jdbcTemplate.update(
                "DELETE FROM scheduler_lock WHERE name LIKE 'mon-alert:%' AND locked_by LIKE ?",
                HOSTNAME + "-%");
            // İzleme sweep kilitleri (uptime/port/keyword/ping/dns '-sweep'). Crash sonrası
            // bu host'un bıraktığı satırı anında sil → sweep'ler TTL (2 dk) dolana kadar susmasın.
            deleted += jdbcTemplate.update(
                "DELETE FROM scheduler_lock WHERE name LIKE '%-sweep' AND locked_by LIKE ?",
                HOSTNAME + "-%");
            if (deleted > 0) {
                log.info("Cleared {} stale scheduler lock(s) from previous instance(s) on this host", deleted);
            }
        } catch (Exception e) {
            log.warn("Could not clear stale scheduler lock: {}", e.getMessage());
        }
    }

    /** Idempotent DDL patches for columns that ddl-auto=update may miss on existing tables. */
    /**
     * Kişi-webhook izleme bayrağı (notifyWebhook=false) sweep bağlamına damgalanır: alarm hunisi
     * (EscalationService.sendCombinedAlert → UserPushService.enqueueAlert) monitör nesnesini hiç
     * görmez, kararı buradan okur (SKIPPED_MONITOR_OFF). Bayrak açıkken ctx AYNEN döner — mail
     * yolunun bağlamına hiçbir şey eklenmez.
     */
    static Map<String, Object> pushCtx(Map<String, Object> ctx, Boolean notifyWebhook) {
        if (!Boolean.FALSE.equals(notifyWebhook)) return ctx;
        Map<String, Object> out = new LinkedHashMap<>(ctx == null ? Map.of() : ctx);
        out.put("push_disabled", true);
        return out;
    }

    /**
     * {@code pushCtx}'in E-POSTA eşleniği: izlemenin {@code notifyEmail} bayrağı kapalıysa ctx'e
     * {@code mail_disabled} damgası basar; {@code EscalationService.sendCombinedAlert} bunu okuyup
     * maili atlar (webhook BAĞIMSIZ koşmaya devam eder).
     *
     * <p>Bayrak bugüne kadar backend'de HİÇBİR yerde okunmuyordu — formdaki "E-mail" kutusu süstü,
     * işaretini kaldırmak mail gönderimini durdurmuyordu. {@code Boolean.FALSE.equals} null-güvenli:
     * kolonu sonradan eklenen ESKİ satırlar {@code null} taşır ve mail almaya devam eder; yalnız
     * kullanıcının BİLEREK kapattığı izlemeler susar.
     */
    static Map<String, Object> mailCtx(Map<String, Object> ctx, Boolean notifyEmail) {
        if (!Boolean.FALSE.equals(notifyEmail)) return ctx;
        Map<String, Object> out = new LinkedHashMap<>(ctx == null ? Map.of() : ctx);
        out.put("mail_disabled", true);
        return out;
    }

    /**
     * Per-monitor TEYİT/KURTARMA ayarlarını ctx'e basar — {@code MonitoringOutageService} bunları
     * okuyup alarmı N denemeden önce AÇMAZ, kurtarmayı M başarılı kontrolden önce KAPATMAZ.
     *
     * <p>DNS ve Domain türlerinde bu anahtarlar HİÇ basılmıyordu; o iki tür GLOBAL varsayılana
     * sabitliydi ("kaç kez doğrulayayım?" sorusu formda hiç sorulmuyordu). Artık izleme bazında
     * ayarlanabiliyor. null değerler yine global varsayılana düşer — mevcut izlemelerin
     * DOĞRULAMA davranışı değişmez. Tek fark KURTARMA tarafında: global varsayılan 1'dir
     * (ilk başarılı kontrolde kapat), izleme varsayılanı ise diğer yedi türle hizalı 3'tür.
     */
    private static Map<String, Object> confirmCtx(Map<String, Object> ctx, Integer attempts,
                                                  Integer attemptSec, Integer recChecks, Integer recSec) {
        Map<String, Object> out = ctx == null ? new LinkedHashMap<>() : ctx;
        out.put("monitor_confirm_attempts", attempts);
        out.put("monitor_confirm_interval_ms", attemptSec != null ? attemptSec * 1000L : null);
        out.put("monitor_recovery_checks", recChecks);
        out.put("monitor_recovery_interval_ms", recSec != null ? recSec * 1000L : null);
        return out;
    }

    /** İki kanal bayrağını birlikte uygular — çağrı yerlerinde tek sarmalayıcı kalsın. */
    static Map<String, Object> chanCtx(Map<String, Object> ctx, Boolean notifyEmail, Boolean notifyWebhook) {
        return mailCtx(pushCtx(ctx, notifyWebhook), notifyEmail);
    }

    private void applySchemaPatches() {
        // Kişi-webhook kanalı (2026-08): izleme-bazlı bayrak + kişi opt-out. Dolu tablolara
        // NOT NULL eklenmez (Hibernate yutar, kolon oluşmaz) — nullable + kod tarafında vars. true.
        patch("ALTER TABLE http_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE port_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE dns_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE keyword_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE ping_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE page_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE pagespeed_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE scripted_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        patch("ALTER TABLE domain_monitors ADD COLUMN notify_webhook BOOLEAN DEFAULT true");
        // E-posta kanal bayragi: bu uc turde HIC yoktu (form da gostermiyordu). Dolu tabloya
        // NOT NULL eklenmez — nullable + kodda vars. true; eski satirlar null kalir ve
        // null-guvenli okuma sayesinde mail almaya DEVAM eder.
        patch("ALTER TABLE dns_monitors ADD COLUMN notify_email BOOLEAN DEFAULT true");
        patch("ALTER TABLE ping_monitors ADD COLUMN notify_email BOOLEAN DEFAULT true");
        // Ping yavaşlık alarmı (göreli eşik) — üçü de nullable + DEFAULT'lu: dolu tabloda
        // ddl-auto NOT NULL kolonu SESSİZCE eklemiyor, açık patch şart (bkz. sınıf başı notu).
        patch("ALTER TABLE ping_monitors ADD COLUMN slow_response_enabled BOOLEAN DEFAULT false");
        patch("ALTER TABLE ping_monitors ADD COLUMN slow_baseline_window_minutes INTEGER DEFAULT 10");
        patch("ALTER TABLE ping_monitors ADD COLUMN slow_threshold_percent INTEGER DEFAULT 20");
        patch("ALTER TABLE domain_monitors ADD COLUMN notify_email BOOLEAN DEFAULT true");
        // Dogrulama/kurtarma alanlari DNS ve Domain turlerinde HIC yoktu: tek anlik hata
        // dogrudan alarm aciyordu, digerlerinde ise N deneme bekleniyordu. Ayni soru her
        // turde ayni sekilde sorulmali. Nullable + kodda varsayilan (3/30/3/30).
        for (String tbl : new String[]{"dns_monitors", "domain_monitors"}) {
            patch("ALTER TABLE " + tbl + " ADD COLUMN confirm_attempts INTEGER DEFAULT 3");
            patch("ALTER TABLE " + tbl + " ADD COLUMN confirm_interval_seconds INTEGER DEFAULT 30");
            patch("ALTER TABLE " + tbl + " ADD COLUMN recovery_checks INTEGER DEFAULT 3");
            patch("ALTER TABLE " + tbl + " ADD COLUMN recovery_interval_seconds INTEGER DEFAULT 30");
        }
        patch("ALTER TABLE app_users ADD COLUMN push_opt_out BOOLEAN DEFAULT false");
        // Kişi-webhook ANTI-LOOP garantisi DB seviyesindedir; ddl-auto'ya bırakılamaz. Prod
        // açılışında Hibernate 'constraint "ux_push_event_phase_user" ... does not exist, skipping'
        // yazdı: ddl-auto=update MEVCUT tabloya unique kısıt eklemeyi güvenilir biçimde yapmaz.
        // Kısıt olmazsa aynı olayın aynı fazı aynı kişiye iki kez yazılabilirdi (kod tarafındaki
        // dedupe kontrolü yarışta son sözü söyleyemez). İdempotent index — TEST satırlarında
        // alert_event_id NULL'dır ve Postgres'te NULL'lar çakışmaz, yani test tekrarı serbest kalır.
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_push_event_phase_user "
                + "ON user_push_deliveries(alert_event_id, dedupe_key, username)");
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_push_scope ON user_push_scopes(scope_type, scope_key)");
        patch("ALTER TABLE certificate_checks ADD COLUMN run_id TEXT");
        patch("ALTER TABLE uptime_checks ADD COLUMN maintenance BOOLEAN DEFAULT false");
        patch("ALTER TABLE certificate_checks ADD COLUMN maintenance BOOLEAN DEFAULT false");
        // Sertifika kontrol süresi (elapsed_ms) — ölçülüyordu ama saklanmıyordu; yanıt süresi grafiği
        // bu kolonla besleniyor. Geriye dönük veri üretilemez, mevcut satırlar NULL kalır (istatistiğe girmez).
        patch("ALTER TABLE certificate_checks ADD COLUMN response_ms INTEGER");
        patch("ALTER TABLE alert_events ADD COLUMN resolved_by TEXT");
        // Manuel onay/çözüm gerekçesi — "kim ve ne zaman"ın yanına "NEDEN". Eski satırlarda NULL
        // kalır (geriye dönük üretilemez); gösterim tarafı null'ı boş blok çizmeden geçmeli.
        patch("ALTER TABLE alert_events ADD COLUMN acknowledged_note TEXT");
        patch("ALTER TABLE alert_events ADD COLUMN resolved_note TEXT");
        // Alarm fırtınası (alert storm) bağı + per-group scoping (ddl-auto zaten ekler — güvenlik ağı).
        patch("ALTER TABLE alert_events ADD COLUMN storm_id BIGINT");
        patch("ALTER TABLE alert_events ADD COLUMN group_name TEXT");
        patch("ALTER TABLE notification_logs ADD COLUMN message TEXT");
        patch("ALTER TABLE certificate_inventory ADD COLUMN team_id INTEGER");
        patch("ALTER TABLE certificate_inventory ADD COLUMN use_proxy BOOLEAN DEFAULT false");
        patch("ALTER TABLE certificate_inventory ADD COLUMN timeout_seconds INTEGER");
        patch("ALTER TABLE certificate_inventory ADD COLUMN tls_mode TEXT");
        // Alan adı (registrar) süre bitişi — Alan Adı Tanılama aracı eşleşen envanter satırlarına yazar (TLS sertifika bitişinden ayrı).
        patch("ALTER TABLE certificate_inventory ADD COLUMN domain_expiry TEXT");
        patch("ALTER TABLE certificate_inventory ADD COLUMN domain_registrar TEXT");
        patch("ALTER TABLE certificate_inventory ADD COLUMN domain_expiry_checked_at TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN via TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN tls_mode_used TEXT");

        // Sertifika sağlık kontrol listesi (v20.29): anlaşılan protokol/cipher artık KALICI —
        // checker bunları üretiyordu ama saklanmıyordu, süpürme verisinden sağlık okunamıyordu.
        patch("ALTER TABLE latest_checks ADD COLUMN tls_version TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN cipher_suite TEXT");
        patch("ALTER TABLE certificate_checks ADD COLUMN tls_version TEXT");
        patch("ALTER TABLE certificate_checks ADD COLUMN cipher_suite TEXT");
        // Uygulama katmanı satırları (istemli koşar, sonucu tarihiyle saklanır).
        patch("ALTER TABLE latest_checks ADD COLUMN mixed_content_status TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN mixed_content_at TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN hsts_status TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN hsts_at TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN hsts_note TEXT");
        // D1: tablo adı yanlıştı (page_speed_checks — fazladan alt çizgi); patch() istisnayı yuttuğu
        // için 6 satır SESSİZ no-op idi. Entity tablosu pagespeed_checks; ddl-auto maskeliyordu
        // ama güvenlik ağı kırıktı. PatchTableNamesTest tekrarını engelliyor.
        patch("ALTER TABLE pagespeed_checks ADD COLUMN dns_ms INTEGER");
        patch("ALTER TABLE pagespeed_checks ADD COLUMN connect_ms INTEGER");
        patch("ALTER TABLE pagespeed_checks ADD COLUMN tls_ms INTEGER");
        patch("ALTER TABLE pagespeed_checks ADD COLUMN server_ms INTEGER");
        patch("ALTER TABLE pagespeed_checks ADD COLUMN skipped_lazy INTEGER");
        patch("ALTER TABLE pagespeed_checks ADD COLUMN breach_detail TEXT");
        // Otomatik parmak izi pini (TOFU) — sertifikanın sessizce değişmesini görünür kılar.
        patch("ALTER TABLE latest_checks ADD COLUMN pinned_fingerprint TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN pinned_at TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN previous_fingerprint TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN fingerprint_changed_at TEXT");
        patch("ALTER TABLE app_users ADD COLUMN role_locked BOOLEAN DEFAULT false");
        patch("ALTER TABLE app_users ADD COLUMN org_role_locked BOOLEAN DEFAULT false");
        // Giriş damgaları — kullanıcının kendi güvenlik özeti ("önceki girişiniz / son başarısız
        // deneme") + admin listesi. Mevcut satırlarda NULL/0 kalır; ilk girişte dolar.
        patch("ALTER TABLE app_users ADD COLUMN last_login_at VARCHAR(30)");
        patch("ALTER TABLE app_users ADD COLUMN last_login_ip VARCHAR(64)");
        patch("ALTER TABLE app_users ADD COLUMN last_login_method VARCHAR(16)");
        patch("ALTER TABLE app_users ADD COLUMN prev_login_at VARCHAR(30)");
        patch("ALTER TABLE app_users ADD COLUMN prev_login_ip VARCHAR(64)");
        patch("ALTER TABLE app_users ADD COLUMN prev_login_method VARCHAR(16)");
        patch("ALTER TABLE app_users ADD COLUMN last_failed_login_at VARCHAR(30)");
        patch("ALTER TABLE app_users ADD COLUMN last_failed_login_ip VARCHAR(64)");
        patch("ALTER TABLE app_users ADD COLUMN last_failed_login_reason VARCHAR(40)");
        patch("ALTER TABLE app_users ADD COLUMN failed_since_login INTEGER DEFAULT 0");
        patch("ALTER TABLE app_users ADD COLUMN failed_before_login INTEGER DEFAULT 0");
        // DEFAULT yalnız YENİ satırlara uygulanır; mevcut satırlar NULL kalır ve `COALESCE`suz
        // bir artış NULL'a düşerdi. Sayaçları bir kez sıfırla (idempotent).
        patch("UPDATE app_users SET failed_since_login = 0 WHERE failed_since_login IS NULL");
        patch("UPDATE app_users SET failed_before_login = 0 WHERE failed_before_login IS NULL");
        // Sentetik koşumun kurumsal çıkış vekilini kullanıp kullanmayacağı (AUTO/ON/OFF) ve
        // koşumun gerçekten vekilden geçip geçmediği. Mevcut satırlarda NULL = AUTO.
        patch("ALTER TABLE scripted_monitors ADD COLUMN use_proxy VARCHAR(10)");
        patch("ALTER TABLE scripted_checks ADD COLUMN via_proxy BOOLEAN");
        // İsteğin faz kırılımı: k6 bunları hep üretiyordu, okunmuyordu — "request timeout"un
        // DNS mi TCP mi TLS mi TTFB mi olduğu kayıttan cevaplanamıyordu. NULL = o faza girilmedi.
        patch("ALTER TABLE scripted_checks ADD COLUMN req_blocked_ms BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN req_connecting_ms BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN req_tls_ms BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN req_sending_ms BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN req_waiting_ms BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN req_receiving_ms BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN data_sent BIGINT");
        patch("ALTER TABLE scripted_checks ADD COLUMN data_received BIGINT");
        // Koşumun HANGİ script sürümüyle yapıldığı — sürüm↔arıza korelasyonu bu alan olmadan
        // kurulamıyordu (Sürümler sekmesi ile Kontrol Geçmişi ayrı ayrı bakılıyordu).
        patch("ALTER TABLE scripted_checks ADD COLUMN script_version VARCHAR(20)");
        // Script'in güncel sürüm etiketi; geçmiş ayrı tabloda (scripted_script_versions, entity'den doğar).
        patch("ALTER TABLE scripted_monitors ADD COLUMN script_version VARCHAR(20)");
        // SCRIPTED_SLOW (opt-in yavaş koşum alarmı) — port/keyword'deki alan adlarıyla aynı.
        patch("ALTER TABLE scripted_monitors ADD COLUMN slow_response_enabled BOOLEAN DEFAULT FALSE");
        patch("ALTER TABLE scripted_monitors ADD COLUMN slow_threshold_ms INTEGER");
        // Anomali guard'ı (L3): izleme SİSTEM tarafından kapatıldıysa sebebi burada durur ve
        // ekranda kalıcı uyarı olarak görünür. Kullanıcının kendi kapattığı izlemeden ayırır.
        patch("ALTER TABLE scripted_monitors ADD COLUMN disabled_reason TEXT");
        patch("ALTER TABLE scripted_monitors ADD COLUMN disabled_at VARCHAR(40)");
        // Şablon kütüphanesi ağaç görünümünün dalı — sabit anahtar kümesi (ScriptedTemplateCategories).
        patch("ALTER TABLE scripted_templates ADD COLUMN category VARCHAR(40)");
        patch("CREATE INDEX IF NOT EXISTS idx_stpl_category ON scripted_templates(category)");

        // ── İzleme değişiklik geçmişi (ürün-görünür; audit_log AYRI ve dokunulmadan kalır) ──
        patch("""
              CREATE TABLE IF NOT EXISTS monitor_change_log(
                id BIGSERIAL PRIMARY KEY,
                resource_kind VARCHAR(20) NOT NULL,
                resource_id BIGINT NOT NULL,
                resource_name VARCHAR(255),
                seq INTEGER NOT NULL,
                event_type VARCHAR(30) NOT NULL,
                team_id BIGINT,
                actor VARCHAR(100),
                actor_id BIGINT,
                actor_name VARCHAR(255),
                ip_address VARCHAR(50),
                user_agent TEXT,
                changes TEXT,
                snapshot TEXT,
                note TEXT,
                created_at VARCHAR(30) NOT NULL)""");
        patch("CREATE INDEX IF NOT EXISTS idx_mchg_resource ON monitor_change_log(resource_kind, resource_id)");
        patch("CREATE INDEX IF NOT EXISTS idx_mchg_team ON monitor_change_log(team_id)");
        patch("CREATE INDEX IF NOT EXISTS idx_mchg_created ON monitor_change_log(created_at)");

        // Kimlik kolonları: "kim oluşturdu" sorusu geçmiş tablosuna bakmadan da cevaplanabilsin
        // (kart künyesi bunu okur). Geçmişten BAĞIMSIZ tutulur — biri silinse diğeri ayakta kalır.
        for (String tbl : new String[] {
                "port_monitors", "dns_monitors", "keyword_monitors", "http_monitors",
                "page_monitors", "scripted_monitors", "domain_monitors", "ping_monitors",
                "certificate_inventory" }) {
            patch("ALTER TABLE " + tbl + " ADD COLUMN created_by VARCHAR(100)");
            patch("ALTER TABLE " + tbl + " ADD COLUMN created_by_name VARCHAR(255)");
            patch("ALTER TABLE " + tbl + " ADD COLUMN created_ip VARCHAR(50)");
            patch("ALTER TABLE " + tbl + " ADD COLUMN updated_by VARCHAR(100)");
            patch("ALTER TABLE " + tbl + " ADD COLUMN updated_by_name VARCHAR(255)");
        }
        // Taslak kullanıcı+monitör başına TEK satır. monitor_id yerine metin anahtar kullanılıyor:
        // PostgreSQL unique index'te NULL'ları birbirinden farklı sayar, "yeni monitör" taslakları çoğalırdı.
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_scripted_draft_owner_key ON scripted_drafts(owner, monitor_key)");
        // ── Şablon kütüphanesi (Genel / Takım) ──────────────────────────────────────────────
        // Tablolar ddl-auto=update ile de doğar; bu patch'ler ESKİ kurulumların yükseltmesi için
        // (CLAUDE.md kuralı). team_id NULL = GENEL şablon; builtin_key benzersiz, çünkü seeder'ın
        // idempotensi ve eski `tpl:<id>` çözümü ona dayanıyor (PostgreSQL NULL'ları çakıştırmaz).
        patch("""
            CREATE TABLE IF NOT EXISTS scripted_templates (
              id BIGSERIAL PRIMARY KEY,
              name TEXT NOT NULL, name_en TEXT,
              description TEXT, description_en TEXT,
              when_to_use TEXT, when_to_use_en TEXT,
              script TEXT NOT NULL, env_json TEXT, tags TEXT,
              team_id BIGINT, source_team_name TEXT,
              promoted_at TEXT, promoted_by TEXT,
              builtin_key VARCHAR(64), builtin_seed_version INTEGER,
              active BOOLEAN NOT NULL DEFAULT TRUE,
              deleted_at TEXT, deleted_by TEXT,
              current_version VARCHAR(20),
              created_at TEXT NOT NULL, created_by TEXT NOT NULL, created_by_name TEXT,
              updated_at TEXT, updated_by TEXT, updated_by_name TEXT)""");
        patch("CREATE INDEX IF NOT EXISTS idx_stpl_team_active ON scripted_templates(team_id, active)");
        patch("CREATE UNIQUE INDEX IF NOT EXISTS uq_stpl_builtin_key ON scripted_templates(builtin_key)");
        patch("CREATE INDEX IF NOT EXISTS idx_stpl_name ON scripted_templates(name)");
        // Sürüm geçmişi: append-only, tam snapshot. team_id o ANDAKİ kapsamı taşır ki
        // PROMOTE/DEMOTE satırı join'siz okunabilsin.
        patch("""
            CREATE TABLE IF NOT EXISTS scripted_template_versions (
              id BIGSERIAL PRIMARY KEY,
              template_id BIGINT NOT NULL,
              sequence_no INTEGER NOT NULL,
              version VARCHAR(20) NOT NULL,
              event_type VARCHAR(16) NOT NULL,
              script TEXT, env_json TEXT, team_id BIGINT, note TEXT,
              created_at TEXT NOT NULL, created_by TEXT NOT NULL, created_by_name TEXT)""");
        patch("CREATE INDEX IF NOT EXISTS idx_stv_template_seq ON scripted_template_versions(template_id, sequence_no)");
        patch("CREATE INDEX IF NOT EXISTS idx_stv_created ON scripted_template_versions(created_at)");
        // Takım başına haftalık e-posta anahtarları (Cuma hatırlatması + Pazartesi erişilebilirlik raporu).
        // YENİ takım kapalı doğar (entity başlatıcısı false), ama MEVCUT takımlar TRUE'ya çekilir: kolon
        // eklendiğinde satırlar NULL kalır ve NULL'ı "kapalı" saymak bugün e-posta alan tüm takımları
        // yayınla birlikte sessizce susturur. Bu iki UPDATE yalnız NULL'a dokunur; kullanıcı anahtarı
        // kapattığında değer FALSE olur (NULL değil), o yüzden her açılışta koşması güvenlidir.
        patch("ALTER TABLE teams ADD COLUMN weekly_reminder_enabled BOOLEAN");
        patch("ALTER TABLE teams ADD COLUMN weekly_availability_enabled BOOLEAN");
        patch("UPDATE teams SET weekly_reminder_enabled = TRUE WHERE weekly_reminder_enabled IS NULL");
        patch("UPDATE teams SET weekly_availability_enabled = TRUE WHERE weekly_availability_enabled IS NULL");
        // Haftalık raporlar — tablolar ddl-auto=update ile oluşur; unique index güvenlik ağı
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_weekly_report_team_week ON weekly_reports(team_id, report_year, week_no)");
        // Eş zamanlı düzenleme: sürüm sayacı + yumuşak düzenleme kilidi alanları
        patch("ALTER TABLE weekly_reports ADD COLUMN version INTEGER DEFAULT 0");
        patch("ALTER TABLE weekly_reports ADD COLUMN editing_by TEXT");
        patch("ALTER TABLE weekly_reports ADD COLUMN editing_user_id BIGINT");
        patch("ALTER TABLE weekly_reports ADD COLUMN editing_heartbeat TEXT");
        // Görsel takım izolasyonu — açık team_id + mevcut satırlar için backfill
        patch("ALTER TABLE weekly_report_images ADD COLUMN team_id BIGINT");
        patch("UPDATE weekly_report_images i SET team_id = "
                + "(SELECT r.team_id FROM weekly_reports r WHERE r.id = i.report_id) "
                + "WHERE i.team_id IS NULL");
        patch("ALTER TABLE escalation_contacts ADD COLUMN team_id INTEGER");
        // LDAP users carry no app password → password_hash must allow NULL on existing tables.
        patch("ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL");
        // Username'leri tek-bicim BUYUK harfe cek (case tutarsizligi -> ayni kullanici tek kimlik; aktif-oturum
        // sayimi/audit dogru). DB UPPER, idempotent (yalniz farkli satirlar). remember_me_tokens da hizalanir.
        patch("UPDATE app_users SET username = UPPER(username) WHERE username <> UPPER(username)");
        patch("UPDATE remember_me_tokens SET username = UPPER(username) WHERE username <> UPPER(username)");
        // Cihaz Geçmişi ekranı — remember-me token'ına cihaz meta'sı. HEPSİ NULLABLE:
        // dolu tabloya NOT NULL kolon eklemek Postgres'te reddedilir, Hibernate yutar, kolon
        // HİÇ oluşmaz ve o tabloya giden her sorgu 500 verir (projede yaşandı).
        patch("ALTER TABLE remember_me_tokens ADD COLUMN created_at TEXT");
        patch("ALTER TABLE remember_me_tokens ADD COLUMN last_used_at TEXT");
        patch("ALTER TABLE remember_me_tokens ADD COLUMN ip_address VARCHAR(64)");
        patch("ALTER TABLE remember_me_tokens ADD COLUMN ua_summary VARCHAR(128)");
        patch("ALTER TABLE remember_me_tokens ADD COLUMN ip_city VARCHAR(128)");
        patch("ALTER TABLE remember_me_tokens ADD COLUMN ip_country VARCHAR(64)");
        // Cihaz listesi username'e göre okunur; tabloda satır az ama sorgu her panel açılışında.
        patch("CREATE INDEX IF NOT EXISTS idx_rmt_username ON remember_me_tokens(username)");
        // ── Takım Bildirim Grupları ──────────────────────────────────────────────
        // Alarm e-postaları bugüne dek TEK adrese (Team.email) gidiyordu; grup o adresin
        // yerine geçen adlandırılmış alıcı listesidir. Kolonların HEPSİ NULLABLE: null =
        // "zincirin kalanı" (takım varsayılanı → Team.email), yani MEVCUT DAVRANIŞ.
        // Ayrıca dolu tabloya NOT NULL eklemek Postgres'te reddedilir, Hibernate yutar ve
        // kolon HİÇ oluşmaz (projede yaşanmış tuzak).
        for (String t : new String[]{
                "dns_monitors", "domain_monitors", "http_monitors", "keyword_monitors",
                "page_monitors", "pagespeed_monitors", "ping_monitors", "port_monitors",
                "scripted_monitors", "certificate_inventory", "alert_events" }) {
            patch("ALTER TABLE " + t + " ADD COLUMN notification_group_id BIGINT");
        }
        patch("CREATE INDEX IF NOT EXISTS idx_ng_team ON notification_groups(team_id)");
        patch("CREATE INDEX IF NOT EXISTS idx_ng_team_default ON notification_groups(team_id, is_default)");

        // Sorumlu Ekipler — sertifikayı kimin yenileyeceğini gösteren dört serbest metin alanı.
        // Yönlendirmeye GİRMEZ, yalnız uyarı e-postasında ve envanter detayında gösterilir.
        // Nullable: dolu tabloya NOT NULL eklemek Postgres'te reddedilir, Hibernate yutar ve
        // kolon HİÇ oluşmaz — sonra her sorgu 500 verir (projede yaşanmış tuzak).
        for (String c : new String[]{
                "svc_mgmt_contact", "app_dev_contact", "iis_admin_contact", "waf_admin_contact" }) {
            patch("ALTER TABLE certificate_inventory ADD COLUMN " + c + " VARCHAR(300)");
        }
        // Widen varchar(255) columns to TEXT — markdown editor / long descriptions can overflow
        patch("ALTER TABLE certificate_inventory ALTER COLUMN change_description TYPE TEXT");
        patch("ALTER TABLE certificate_inventory ALTER COLUMN description TYPE TEXT");
        patch("ALTER TABLE certificate_inventory ALTER COLUMN owner TYPE TEXT");
        patch("ALTER TABLE certificate_inventory ALTER COLUMN expected_subject TYPE TEXT");
        // Distributed scheduler lock table (HA: prevents duplicate runs across instances)
        patch("""
            CREATE TABLE IF NOT EXISTS scheduler_lock(
                name TEXT NOT NULL PRIMARY KEY,
                locked_by TEXT NOT NULL,
                locked_until TEXT NOT NULL
            )
            """);
        // Port monitoring tables
        patch("CREATE TABLE IF NOT EXISTS port_monitors (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, protocol TEXT NOT NULL DEFAULT 'TCP', active INTEGER NOT NULL DEFAULT 1, interval_seconds INTEGER NOT NULL DEFAULT 60, timeout_ms INTEGER NOT NULL DEFAULT 5000, created_at TEXT, updated_at TEXT)");
        patch("CREATE TABLE IF NOT EXISTS port_checks (id INTEGER PRIMARY KEY AUTOINCREMENT, monitor_id INTEGER NOT NULL, open INTEGER NOT NULL DEFAULT 0, response_ms INTEGER, checked_at TEXT, error TEXT)");
        // ip_version (Port task, PortMonitor @Column nullable=false) — ddl-auto NOT NULL kolonu MEVCUT satırlara
        // ekleyemez ("contains null values") → kolon hiç oluşmaz, port sweep SELECT'i kırılır. DEFAULT 'auto' ile
        // idempotent güvenlik ağı (mevcut satırlar dolar; diğer eklenen kolonların deseni). Port task'te atlanmıştı.
        patch("ALTER TABLE port_monitors ADD COLUMN ip_version TEXT NOT NULL DEFAULT 'auto'");
        // DNS monitoring tables
        patch("CREATE TABLE IF NOT EXISTS dns_monitors (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, domain TEXT NOT NULL, record_type TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, interval_seconds INTEGER NOT NULL DEFAULT 300, created_at TEXT, updated_at TEXT)");
        patch("CREATE TABLE IF NOT EXISTS dns_records (id INTEGER PRIMARY KEY AUTOINCREMENT, monitor_id INTEGER NOT NULL, record_type TEXT, value TEXT, changed INTEGER NOT NULL DEFAULT 0, previous_value TEXT, checked_at TEXT)");
        patch("ALTER TABLE dns_monitors ADD COLUMN slow_threshold_ms INTEGER");   // per-monitor DNS_SLOW eşiği; null=global (ddl-auto zaten ekler — güvenlik ağı)
        patch("ALTER TABLE dns_monitors ADD COLUMN dns_change_alert_enabled BOOLEAN"); // DNS_CHANGED alarmı aç/kapa; null=açık (ddl-auto zaten ekler — güvenlik ağı)
        patch("ALTER TABLE page_checks ADD COLUMN timeout_count INTEGER");             // TIMEOUT sayacı kırıktan ayrıldı 2026-08-04 (ddl-auto zaten ekler — güvenlik ağı)
        patch("ALTER TABLE domain_monitors ADD COLUMN check_timeout_ms INTEGER"); // per-monitor RDAP timeout (ms); null=global (ddl-auto zaten ekler — güvenlik ağı)
        // Domain Kaydı (registration) — RDAP/WHOIS'ten ek alanlar (ddl-auto eski DB'yi backfill etmez → güvenlik ağı).
        patch("ALTER TABLE domain_checks ADD COLUMN registrar_iana_id TEXT");
        patch("ALTER TABLE domain_checks ADD COLUMN dnssec TEXT");
        patch("ALTER TABLE domain_checks ADD COLUMN resolved_ips TEXT");
        patch("ALTER TABLE domain_checks ADD COLUMN hostnames TEXT");
        patch("ALTER TABLE domain_monitors ADD COLUMN transfer_lock_alert BOOLEAN DEFAULT true");
        patch("ALTER TABLE domain_monitors ADD COLUMN blacklist_enabled BOOLEAN DEFAULT false");
        patch("ALTER TABLE domain_monitors ADD COLUMN change_alert BOOLEAN DEFAULT true");
        patch("ALTER TABLE domain_checks ADD COLUMN transfer_lock VARCHAR(20)");
        patch("ALTER TABLE domain_checks ADD COLUMN blacklist_status VARCHAR(20)");
        patch("ALTER TABLE domain_checks ADD COLUMN blacklist_detail TEXT");
        patch("ALTER TABLE domain_checks ADD COLUMN change_detail TEXT");
        // Sorun bildirimleri genelleştirmesi (2026-08): login_issue_reports artık üç kaynağı taşır
        // (LOGIN | CLIENT_ERROR | USER_REPORT) + otomatik bağlam alanları. Eski satırlar LOGIN'e backfill edilir.
        patch("ALTER TABLE login_issue_reports ADD COLUMN source TEXT");
        patch("UPDATE login_issue_reports SET source = 'LOGIN' WHERE source IS NULL");
        patch("ALTER TABLE login_issue_reports ADD COLUMN category TEXT");
        patch("ALTER TABLE login_issue_reports ADD COLUMN app_version TEXT");
        patch("ALTER TABLE login_issue_reports ADD COLUMN screen_size TEXT");
        patch("ALTER TABLE login_issue_reports ADD COLUMN tab_key TEXT");
        patch("ALTER TABLE login_issue_reports ADD COLUMN auto_context_json TEXT");
        patch("ALTER TABLE login_issue_reports ADD COLUMN linked_reference TEXT");
        patch("CREATE INDEX IF NOT EXISTS idx_lir_source ON login_issue_reports(source)");
        // MonitoringGroupService.typeOf'ta Page/Scripted eksikti → grupları type='' ile kaydolmuştu;
        // takım+ad eşleşmesiyle doğru türe backfill (idempotent; boş-tür satır kalmayana dek zararsız).
        patch("UPDATE monitoring_groups g SET type='scripted' WHERE (g.type='' OR g.type IS NULL) AND EXISTS "
                + "(SELECT 1 FROM scripted_monitors s WHERE s.group_name = g.name AND s.team_id = g.team_id)");
        patch("UPDATE monitoring_groups g SET type='page' WHERE (g.type='' OR g.type IS NULL) AND EXISTS "
                + "(SELECT 1 FROM page_monitors p WHERE p.group_name = g.name AND p.team_id = g.team_id)");

        // ── Performans index'leri (sıcak sorgu yolları) — idempotent, PG IF NOT EXISTS ──
        // Tablolar bu noktada Hibernate ddl-auto=update ile oluşmuş durumda.
        patch("CREATE INDEX IF NOT EXISTS idx_nl_alert_event_id ON notification_logs(alert_event_id)");
        patch("CREATE INDEX IF NOT EXISTS idx_nl_sent_at ON notification_logs(sent_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_nl_email_status ON notification_logs(email_status)");
        patch("CREATE INDEX IF NOT EXISTS idx_ec_team_role_active ON escalation_contacts(team_id, role, active)");
        patch("CREATE INDEX IF NOT EXISTS idx_ec_active ON escalation_contacts(active)");
        patch("CREATE INDEX IF NOT EXISTS idx_ci_team_active ON certificate_inventory(team_id, active)");
        patch("CREATE INDEX IF NOT EXISTS idx_ci_deleted_at ON certificate_inventory(deleted_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_dnsr_monitor_changed ON dns_records(monitor_id, changed)");
        patch("CREATE INDEX IF NOT EXISTS idx_wrm_report_id ON weekly_report_mails(report_id)");
        // Olay & Hata Geçmişi: tekil index'ler entity @Index'ten gelir; trend/filtre için bileşik.
        patch("CREATE INDEX IF NOT EXISTS idx_inc_occurred_severity ON incident_records(occurred_at, severity)");
        // Kanal kolonu + index (ddl-auto=update yeni kolonu ekler; güvenlik ağı + filtre index'i).
        patch("ALTER TABLE incident_records ADD COLUMN channel TEXT");
        patch("CREATE INDEX IF NOT EXISTS idx_inc_channel ON incident_records(channel)");
        patch("ALTER TABLE incident_records ADD COLUMN team_id BIGINT");
        patch("ALTER TABLE incident_records ADD COLUMN team_name TEXT");
        patch("CREATE INDEX IF NOT EXISTS idx_inc_team ON incident_records(team_id)");
        // Olay görseli taslak yüklemesi: yeni olay henüz kaydedilmeden görsel eklenir (incident_id=null,
        // kaydedince linkImages bağlar). Eski NOT NULL kısıtı taslakları reddediyordu → kaldır (idempotent).
        patch("ALTER TABLE incident_images ALTER COLUMN incident_id DROP NOT NULL");
        // Yönetilen seçenekler artık TAKIMA ÖZEL (team_id, ddl-auto ekler) → eski (type,opt_value) tekil
        // kısıtı kaldırılır ki aynı değer farklı takımlarda bulunabilsin (tekrarlar ensureOption kapsam
        // kontrolüyle önlenir). Constraint VE standalone index formu denenir (idempotent; hata yutulur).
        patch("ALTER TABLE incident_options DROP CONSTRAINT IF EXISTS uk_inc_opt_type_value");
        patch("DROP INDEX IF EXISTS uk_inc_opt_type_value");
        patch("CREATE INDEX IF NOT EXISTS idx_inc_opt_type ON incident_options(type)");
        // ── DB performans index'leri (hot-path sorgular; entity @Index dışında kalan eksikler) ──
        // uptime_checks: haftalık erişilebilirlik raporu domain+port+tarih-aralığı tarar (en hızlı büyüyen tablo).
        patch("CREATE INDEX IF NOT EXISTS idx_uc_domain_port_checked ON uptime_checks(domain, port, checked_at)");
        // dns_monitors: findChangedByDomain alt-sorgusu + findFirstByDomain (entity'de hiç index yok).
        patch("CREATE INDEX IF NOT EXISTS idx_dnsm_domain ON dns_monitors(domain)");
        patch("CREATE INDEX IF NOT EXISTS idx_dnsm_active ON dns_monitors(active)");
        // certificate_inventory: team_id zaten idx_ci_team_active'te; ikinci takım (UG) kapalı değil.
        patch("CREATE INDEX IF NOT EXISTS idx_ci_ugteam_active ON certificate_inventory(ug_team_id, active)");
        // port_monitors: aktif sweep taraması (findByActiveTrue).
        patch("CREATE INDEX IF NOT EXISTS idx_pm_active ON port_monitors(active)");
        // network_outage_events.source — izleme sweep'lerinin bastirma olaylari da bu tabloya
        // yaziliyor; kaynak ayrilmazsa sertifika kesintisiyle karisir (ddl-auto zaten ekler,
        // bu guvenlik agi).
        patch("ALTER TABLE network_outage_events ADD COLUMN source VARCHAR(32)");
        dedupeAndLockMonitorKeys();
        // keyword_results / ping_checks: süre-grafiği aralık taraması (monitor_id + checked_at) — tekil
        // index'ler entity'de var; composite range sorgusunu (responseSeriesRaw) hızlandırır.
        patch("CREATE INDEX IF NOT EXISTS idx_kwr_monitor_checked ON keyword_results(monitor_id, checked_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_pingc_monitor_checked ON ping_checks(monitor_id, checked_at)");
        // Haftalık izleme göstergeleri (MonitoringWeeklyStatsService) — pencere-içi gruplu COUNT için (monitor_id, checked_at).
        patch("CREATE INDEX IF NOT EXISTS idx_portc_monitor_checked ON port_checks(monitor_id, checked_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_dnsr_monitor_checked ON dns_records(monitor_id, checked_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_dc_monitor_checked ON domain_checks(monitor_id, checked_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_hc_monitor_checked ON http_checks(monitor_id, checked_at)");
        // remember_me_tokens: saatlik expired-token temizliği (DELETE WHERE expires_at < ?).
        patch("CREATE INDEX IF NOT EXISTS idx_rmt_expires ON remember_me_tokens(expires_at)");
        // Genel Ayarlar (runtime config override'ları) — tablo ddl-auto ile oluşur; unique key güvenlik ağı.
        patch("""
            CREATE TABLE IF NOT EXISTS app_settings(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                setting_key VARCHAR(150) NOT NULL,
                setting_value TEXT,
                updated_at VARCHAR(40),
                updated_by VARCHAR(120)
            )
            """);
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_app_settings_key ON app_settings(setting_key)");
        // ── Rename VERİ göçü (CertMonitor → SiteMonitor, geriye-uyum): app_settings anahtar önekleri ──
        // AppSettingsService.resolve() exact-match okur; bu göç olmadan tüm admin override'ları
        // (Genel Ayarlar ekranından yapılmış her değişiklik) yeni anahtar adlarında bulunamaz ve
        // sessizce varsayılana dönerdi. Idempotent: WHERE yalnız eski öneki bulur. Çakışma guard'ı:
        // yeni ad zaten varsa (yarım göç / elle eklenmiş) eski satıra dokunulmaz — ux_app_settings_key
        // ihlali yerine satır atlanır; kalanlar yine göçer.
        patch("UPDATE app_settings SET setting_key = 'site.monitor.' || substring(setting_key, 14) "
                + "WHERE setting_key LIKE 'cert.monitor.%' "                                            // geriye-uyum: eski anahtar önekinden göç
                + "AND NOT EXISTS (SELECT 1 FROM app_settings s2 "
                + "WHERE s2.setting_key = 'site.monitor.' || substring(app_settings.setting_key, 14))"); // geriye-uyum: çakışma guard'ı
        patch("UPDATE app_settings SET setting_key = 'logging.level.com.sitemonitor' || substring(setting_key, 30) "
                + "WHERE setting_key LIKE 'logging.level.com.certmonitor%' "                             // geriye-uyum: eski logger anahtarından göç
                + "AND NOT EXISTS (SELECT 1 FROM app_settings s2 "
                + "WHERE s2.setting_key = 'logging.level.com.sitemonitor' || substring(app_settings.setting_key, 30))"); // geriye-uyum: çakışma guard'ı
        // ── Alarm fırtınası (alert storm) — çok monitör birden düşünce bireysel alarmları TEK toplu bildirime indirger ──
        patch("""
            CREATE TABLE IF NOT EXISTS alert_storms(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                scope_key VARCHAR(200) NOT NULL,
                scope_type VARCHAR(16),
                resolved BOOLEAN DEFAULT false,
                member_count INTEGER,
                root_cause VARCHAR(64),
                notified_teams TEXT,
                created_at VARCHAR(40),
                resolved_at VARCHAR(40),
                last_re_alert_at VARCHAR(40)
            )
            """);
        // Scope-başına-tek-aktif storm — atomik terfi (INSERT … ON CONFLICT DO NOTHING) bu kısmi UNIQUE indekse dayanır.
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_alert_storms_active ON alert_storms(scope_key) WHERE resolved = false");
        patch("CREATE INDEX IF NOT EXISTS idx_as_resolved ON alert_storms(resolved)");
        // Pencere-içi açık DOWN eş sayımı (StormService.evaluate) — resolved + alert_type + created_at aralığı.
        patch("CREATE INDEX IF NOT EXISTS idx_ae_storm_scan ON alert_events(resolved, alert_type, created_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_ae_storm_id ON alert_events(storm_id)");
        // ── Büyüme/performans index'leri (DB ölçek pass 2026-07) ──
        // Haftalık KPI + incident kapanış aralığı: resolved_at range (countByLevelResolvedBetween).
        patch("CREATE INDEX IF NOT EXISTS idx_ae_resolved_at ON alert_events(resolved_at)");
        // Denetim konsolu: event_type filtresi + event_time sıralı/range (findAdvanced) — tek-kolon yerine bileşik.
        patch("CREATE INDEX IF NOT EXISTS idx_audit_type_time ON audit_log(event_type, event_time)");
        // Sayfa-bütünlüğü (9. tür) — LATERAL en-güncel + sorun listesi + purge güvenlik-ağı index'leri (ddl-auto ile de gelir).
        patch("CREATE INDEX IF NOT EXISTS idx_pc_monitor_checked ON page_checks(monitor_id, checked_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_pri_monitor_checked ON page_resource_issues(monitor_id, checked_at)");
        patch("CREATE INDEX IF NOT EXISTS idx_pri_check ON page_resource_issues(check_id)");
        patch("CREATE INDEX IF NOT EXISTS idx_sc_monitor_checked ON scripted_checks(monitor_id, checked_at)");
        // ── Yüksek-yazımlı tablolarda daha AGRESİF autovacuum — büyük tabloda varsayılan %20 ölü-tuple
        //    eşiği çok seyrek vacuum + şişme (bloat) demek; %2 scale + sabit eşikle sık, küçük vacuum/analyze.
        //    Postgres'e özgü; H2'de patch() sessiz atlar. Dış prod DB'de de çalışır (ALTER TABLE). Idempotent. ──
        // ── Sayfa Hızı: SONRADAN eklenen kolonlar ────────────────────────────────────────────
        // ddl-auto=update bunları YAPAMAZ: dolu bir tabloya "ADD COLUMN … NOT NULL" varsayılansız
        // gelince Postgres reddeder, Hibernate hatayı yutar ve kolon HİÇ oluşmaz. Sonuç 2026-08-23'te
        // yaşandı: her INSERT ve listeleme sorgusu "column bytes_truncated does not exist" ile düştü,
        // ekran "Sunucu hatası" verdi. DEFAULT ile gelen ALTER mevcut satırları da doldurur.
        patch("ALTER TABLE pagespeed_checks ADD COLUMN bytes_truncated BOOLEAN DEFAULT FALSE");
        patch("UPDATE pagespeed_checks SET bytes_truncated = FALSE WHERE bytes_truncated IS NULL");
        patch("ALTER TABLE pagespeed_resources ADD COLUMN truncated BOOLEAN DEFAULT FALSE");
        patch("UPDATE pagespeed_resources SET truncated = FALSE WHERE truncated IS NULL");

        for (String t : new String[]{"port_checks", "ping_checks", "keyword_results", "http_checks",
                "uptime_checks", "dns_records", "certificate_checks", "activity_log", "audit_log", "notification_logs",
                "page_checks", "page_resource_issues", "scripted_checks",
                // Sayfa Hızı: kırılım tablosu her kontrolde LATEST satırlarını silip yeniden yazar →
                // ölü-tuple üretimi yüksek, agresif autovacuum şart (yoksa tablo şişer).
                "pagespeed_checks", "pagespeed_resources"}) {
            patch("ALTER TABLE " + t + " SET (autovacuum_vacuum_scale_factor = 0.02, autovacuum_vacuum_threshold = 5000, "
                + "autovacuum_analyze_scale_factor = 0.02, autovacuum_analyze_threshold = 5000)");
        }
        // USER artık kendi takımı için olay girer/düzenler — eski sistem-default'u (false) güncelle.
        // Yalnız sistem tarafından tohumlanmış (admin'in elle kapatmadığı) satırı çevirir; silme (incidents.delete)
        // TEAM_ADMIN/ADMIN'de kalır (seedMissingDefaults yeni resource'u doğru tohumlar).
        patch("UPDATE permission_grants SET allowed = TRUE WHERE role = 'USER' "
                + "AND resource_key = 'incidents.manage' AND action = 'edit' "
                + "AND updated_by = 'system' AND allowed = FALSE");
        // USER + TEAM_ADMIN artık kendi takımı için keyword/ping izleme oluşturur/düzenler/çalıştırır.
        // Eski sistem-default'larını (false) çevir. Silme (canManage) ve Port/DNS yazma (requireAdmin)
        // controller'da admin/takım-yöneticisinde kalır; bu yalnız rol-kapısını açar.
        patch("UPDATE permission_grants SET allowed = TRUE WHERE role IN ('USER','TEAM_ADMIN') "
                + "AND resource_key = 'monitoring.crud' AND action = 'edit' "
                + "AND updated_by = 'system' AND allowed = FALSE");
        patch("UPDATE permission_grants SET allowed = TRUE WHERE role IN ('USER','TEAM_ADMIN') "
                + "AND resource_key = 'monitoring.trigger' AND action = 'execute' "
                + "AND updated_by = 'system' AND allowed = FALSE");
        // Keyword adet/operatör koşulu — mevcut binary alert_condition → operatör+eşik backfill (idempotent).
        patch("UPDATE keyword_monitors SET match_operator='GTE', match_count=1 "
                + "WHERE match_operator IS NULL AND (alert_condition='NOT_CONTAINS' OR alert_condition IS NULL)");
        patch("UPDATE keyword_monitors SET match_operator='LTE', match_count=0 "
                + "WHERE match_operator IS NULL AND alert_condition='CONTAINS'");
        // Şemasız URL backfill (2026-08-04): "www.axess.com.tr" gibi kayıtlar kontrol edilemiyor
        // (URI host=null) ve eskiden sahte "kesinti" alarmı üretiyordu. Girişte artık normalize ediliyor;
        // burada mevcut kayıtlar bir kez düzeltilir. İdempotent: NOT LIKE '%://%' ikinci boot'ta 0 satır.
        // (a) normalize hâli aynı takımda zaten varsa dokunma, (b) aynı şemasız url'den birden fazlaysa
        // yalnız en küçük id normalize edilsin → mükerrer üretilmez.
        for (String tbl : new String[]{"page_monitors", "http_monitors"}) {
            patch("UPDATE " + tbl + " p SET url = 'https://' || p.url "
                    + "WHERE p.url IS NOT NULL AND p.url <> '' AND p.url NOT LIKE '%://%' "
                    + "AND NOT EXISTS (SELECT 1 FROM " + tbl + " b WHERE LOWER(b.url) = LOWER('https://' || p.url) "
                    + "                AND ((b.team_id IS NULL AND p.team_id IS NULL) OR b.team_id = p.team_id)) "
                    + "AND NOT EXISTS (SELECT 1 FROM " + tbl + " c WHERE c.id < p.id AND LOWER(c.url) = LOWER(p.url) "
                    + "                AND ((c.team_id IS NULL AND p.team_id IS NULL) OR c.team_id = p.team_id))");
        }
        // Keyword'de aynılık anahtarı url + keyword + takım.
        patch("UPDATE keyword_monitors p SET url = 'https://' || p.url "
                + "WHERE p.url IS NOT NULL AND p.url <> '' AND p.url NOT LIKE '%://%' "
                + "AND NOT EXISTS (SELECT 1 FROM keyword_monitors b WHERE LOWER(b.url) = LOWER('https://' || p.url) "
                + "                AND LOWER(b.keyword) = LOWER(p.keyword) "
                + "                AND ((b.team_id IS NULL AND p.team_id IS NULL) OR b.team_id = p.team_id)) "
                + "AND NOT EXISTS (SELECT 1 FROM keyword_monitors c WHERE c.id < p.id AND LOWER(c.url) = LOWER(p.url) "
                + "                AND LOWER(c.keyword) = LOWER(p.keyword) "
                + "                AND ((c.team_id IS NULL AND p.team_id IS NULL) OR c.team_id = p.team_id))");
        // Notlar/rehberler URL ile eşleşiyor (MonitorNotes target = monitör URL'i) → URL değişince öksüz
        // kalmasınlar: yalnız gerçekten normalize edilmiş bir monitöre karşılık gelen hedefler güncellenir.
        for (String tbl : new String[]{"monitor_notes", "monitor_guide"}) {
            for (String[] kind : new String[][]{{"PAGE", "page_monitors"}, {"HTTP", "http_monitors"}, {"KEYWORD", "keyword_monitors"}}) {
                patch("UPDATE " + tbl + " n SET target = 'https://' || n.target "
                        + "WHERE n.target IS NOT NULL AND n.target NOT LIKE '%://%' AND n.monitor_type = '" + kind[0] + "' "
                        + "AND EXISTS (SELECT 1 FROM " + kind[1] + " m WHERE LOWER(m.url) = LOWER('https://' || n.target)) "
                        // monitor_guide'da (monitor_type,target) UNIQUE — hedef zaten varsa dokunma (kısıt ihlali olmasın)
                        + "AND NOT EXISTS (SELECT 1 FROM " + tbl + " x WHERE x.monitor_type = n.monitor_type "
                        + "                AND LOWER(x.target) = LOWER('https://' || n.target))");
            }
        }
        // Çoklu takım üyeliği (app_user_teams): tablo @ElementCollection + ddl-auto ile oluşur.
        // Join kolonu AppUser'da pinli (user_id). Mevcut tek-takımlı kullanıcıların team_id'sini
        // üyelik tablosuna backfill et (idempotent) — yoksa eski kullanıcılar üyeliksiz kalır.
        patch("INSERT INTO app_user_teams(user_id, team_id) "
                + "SELECT id, team_id FROM app_users a WHERE a.team_id IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM app_user_teams t "
                + "                WHERE t.user_id = a.id AND t.team_id = a.team_id)");
        patch("CREATE INDEX IF NOT EXISTS idx_aut_team ON app_user_teams(team_id)");
        // Haftalık erişilebilirlik e-postası idempotency (team+yıl+hafta) — ddl-auto entity'yi de
        // oluşturur; bu güvenlik ağı + mevcut DB'lerde tablo/uniq garanti.
        patch("""
            CREATE TABLE IF NOT EXISTS weekly_availability_log(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                team_id BIGINT NOT NULL,
                report_year INTEGER NOT NULL,
                week_no INTEGER NOT NULL,
                status VARCHAR(24),
                sent_at VARCHAR(40),
                created_at VARCHAR(40)
            )
            """);
        patch("CREATE UNIQUE INDEX IF NOT EXISTS uk_wal_team_week ON weekly_availability_log(team_id, report_year, week_no)");

        // ── Başarısız-login anomali incident'i (mail bombardımanını önleyen durum kaydı) + tarama state'i ──
        //    ddl-auto entity'yi de oluşturur; bu güvenlik ağı + JdbcTemplate ile okunan state tablosu.
        patch("""
            CREATE TABLE IF NOT EXISTS login_anomaly_incident(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                opened_at VARCHAR(40),
                last_alert_at VARCHAR(40),
                realert_count INTEGER DEFAULT 0,
                resolved BOOLEAN DEFAULT false,
                resolved_at VARCHAR(40),
                peak_total BIGINT DEFAULT 0,
                rules_signature VARCHAR(300),
                rule_count INTEGER DEFAULT 0,
                last_window_start VARCHAR(40),
                last_window_end VARCHAR(40),
                summary TEXT
            )
            """);
        patch("CREATE INDEX IF NOT EXISTS idx_lai_resolved ON login_anomaly_incident(resolved)");
        patch("CREATE INDEX IF NOT EXISTS idx_lai_opened ON login_anomaly_incident(opened_at)");
        patch("""
            CREATE TABLE IF NOT EXISTS login_anomaly_state(
                id INTEGER PRIMARY KEY,
                last_scan_at VARCHAR(40)
            )
            """);

        // ── Faz 5 (DB ölçek): günlük ROLLUP tablosu — ham kontrol serileri kısa retention'la silinse de
        //    uzun-dönem trend (günlük up/total + yanıt süresi) burada kalır. Birleşik: monitor_key =
        //    monitor_id (port/ping/keyword/http) veya domain (uptime). JdbcTemplate ile yazılır/okunur. ──
        patch("""
            CREATE TABLE IF NOT EXISTS monitor_check_daily(
                monitor_type VARCHAR(16) NOT NULL,
                monitor_key VARCHAR(255) NOT NULL,
                day VARCHAR(10) NOT NULL,
                total_checks BIGINT DEFAULT 0,
                up_checks BIGINT DEFAULT 0,
                avg_response_ms INTEGER,
                max_response_ms INTEGER,
                PRIMARY KEY (monitor_type, monitor_key, day)
            )
            """);
        patch("CREATE INDEX IF NOT EXISTS idx_mcd_type_key_day ON monitor_check_daily(monitor_type, monitor_key, day)");

        // ── SAATLİK rollup (2026-08): ham seri kısaltıldığında günlük özet olayın SAATİNİ kaybeder.
        //    Aynı şema + 13 karakterlik kova (YYYY-MM-DDTHH). Maliyeti ham serinin ~%1,7'si. ──
        patch("""
            CREATE TABLE IF NOT EXISTS monitor_check_hourly(
                monitor_type VARCHAR(16) NOT NULL,
                monitor_key VARCHAR(255) NOT NULL,
                hour_bucket VARCHAR(13) NOT NULL,
                total_checks BIGINT DEFAULT 0,
                up_checks BIGINT DEFAULT 0,
                avg_response_ms INTEGER,
                max_response_ms INTEGER,
                PRIMARY KEY (monitor_type, monitor_key, hour_bucket)
            )
            """);
        patch("CREATE INDEX IF NOT EXISTS idx_mch_type_key_hour ON monitor_check_hourly(monitor_type, monitor_key, hour_bucket)");

        // İzleme grubu registry'si (takım + izleme TÜRÜ bazlı grup adları) — ddl-auto entity'yi de oluşturur; bu
        // güvenlik ağı + case-insensitive UNIQUE(team_id, type, name_lower) hem PG hem H2'de (name_lower app'te lower).
        patch("""
            CREATE TABLE IF NOT EXISTS monitoring_groups(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                team_id BIGINT NOT NULL,
                type VARCHAR(16) NOT NULL,
                name VARCHAR(255) NOT NULL,
                name_lower VARCHAR(255) NOT NULL,
                created_by VARCHAR(120),
                created_at VARCHAR(40)
            )
            """);
        patch("CREATE UNIQUE INDEX IF NOT EXISTS ux_mon_groups_team_type_lname ON monitoring_groups(team_id, type, name_lower)");

        cleanupFalseDnsChangeFlags();
        cleanupInterceptedCertPins();
    }

    /**
     * GECMISE DONUK VERI DUZELTMESI — sahte DNS "DEGISTI" damgalari.
     *
     * <p><b>Neden.</b> Cozumleme bir tur basarisiz oldugunda o satirin degeri {@code ""} yaziliyor.
     * Degisiklik tespiti bos kume ile dolu kumeyi AYRIK gorup CHANGED damgasi basiyordu; oysa
     * kayit degismemis, sadece OKUNAMAMISTI. Tek bir gecici hata iki sahte damga birakiyordu
     * (dususte ve kurtulusta). Kok neden kapatildi -- ama gecmis satirlar damgayi tasimaya devam
     * ediyor ve "Degisenler" filtresi/sayaci onlari saymaya devam ederdi.
     *
     * <p><b>Kapsam DAR ve kanitlanabilir:</b> yalnizca taraflardan biri BOS olan satirlar. Yeni
     * {@code detectChange} bu satirlar icin zaten NONE donuyor, yani duzeltme gecmisi GUNCEL
     * mantikla tutarli hale getirir -- hukum degistirmez. Gercek bir degisiklik iki DOLU kume
     * gerektirdiginden bu WHERE hicbir gercek degisikligi silemez.
     *
     * <p>{@code rotated} DOKUNULMAZ: bos tarafla kesisim daima bos olur, yani bos taraf ROTATED
     * uretemez -- temizlenecek bir sey yok.
     *
     * <p>Idempotent: ilk kosudan sonra hicbir satir eslesmez. Alarm tarafina dokunmaz; manuel
     * tetikleme yolu DNS_CHANGED alarmi uretmiyordu (evaluateDnsNow yalniz DNS_FAILURE isler),
     * dolayisiyla temizlenecek sahte alarm YOK.
     */
    private void cleanupFalseDnsChangeFlags() {
        try {
            int fixed = jdbcTemplate.update(
                    "UPDATE dns_records SET changed = FALSE "
                  + "WHERE changed = TRUE AND (COALESCE(previous_value, '') = '' OR COALESCE(value, '') = '')");
            if (fixed > 0) {
                log.info("Sahte DNS 'değişti' damgası temizlendi: {} satır (bir tarafı boş karşılaştırma)", fixed);
            } else {
                log.debug("Sahte DNS 'değişti' damgası bulunamadı — temiz.");
            }
        } catch (Exception e) {
            // Acilis bu yuzden DURMAZ: kozmetik bir gecmis duzeltmesi, izlemenin kendisi degil.
            log.warn("Sahte DNS 'değişti' temizliği atlandı: {}", e.getMessage());
        }
    }

    /**
     * GEÇMİŞE DÖNÜK VERİ DÜZELTMESİ — araya girmeden doğan sahte sertifika "değişti" damgaları
     * ve kirlenmiş pinler.
     *
     * <p><b>Neden.</b> Otomatik pin (TOFU) farklı bir parmak izi görünce güveni sormadan yeniden
     * sabitliyordu. Bir alan adı geçici olarak yerel modeme çözüldüğünde ({@code CN=192.168.1.1},
     * kendinden imzalı) sistem bunu "sertifika değişti" saydı, modemin parmak izini SABİTLEDİ ve
     * gerçek sertifika döndüğünde İKİNCİ bir sahte damga daha bastı. Kök neden
     * {@code CertificateService.applyAutoPin} güven kapısıyla kapatıldı — ama geçmiş satırlar
     * damgayı taşımaya devam eder ve sağlık listesi kırmızı kalırdı.
     *
     * <p><b>Kapsam DAR ve kanıtlanabilir:</b> yalnız ilgili parmak izinin o domain'de
     * {@code UNTRUSTED} gözlendiği satırlar. Yeni kapı bu gözlemi zaten sabitlemezdi, yani
     * düzeltme geçmişi GÜNCEL mantıkla tutarlı hâle getirir — hüküm değiştirmez. Gerçek bir
     * yenileme iki güvenilir gözlem gerektirdiğinden bu WHERE hiçbir gerçek değişimi silemez.
     *
     * <p>İki ayrı cümle, çünkü iki ayrı hasar var: (1) pin'in KENDİSİ araya giren sertifikadan
     * geliyorsa pin tamamen temizlenir ve bir sonraki temiz kontrolde TOFU yeniden sabitler;
     * (2) yalnız ÖNCEKİ taraf kirliyse pin doğrudur, sahte damga silinir.
     *
     * <p>Idempotent: alanlar NULL'a çekildiği için ilk koşudan sonra hiçbir satır eşleşmez.
     * Hata AÇILIŞI DURDURMAZ — kozmetik bir geçmiş düzeltmesi, izlemenin kendisi değil.
     */
    private void cleanupInterceptedCertPins() {
        try {
            int poisoned = jdbcTemplate.update(
                    "UPDATE latest_checks lc SET pinned_fingerprint = NULL, pinned_at = NULL, "
                  + "previous_fingerprint = NULL, fingerprint_changed_at = NULL "
                  + "WHERE lc.pinned_fingerprint IS NOT NULL AND EXISTS ("
                  + "SELECT 1 FROM certificate_checks cc WHERE cc.domain = lc.domain "
                  + "AND cc.fingerprint = lc.pinned_fingerprint AND cc.trust_status = 'UNTRUSTED')");
            int stamps = jdbcTemplate.update(
                    "UPDATE latest_checks lc SET previous_fingerprint = NULL, fingerprint_changed_at = NULL "
                  + "WHERE lc.fingerprint_changed_at IS NOT NULL AND EXISTS ("
                  + "SELECT 1 FROM certificate_checks cc WHERE cc.domain = lc.domain "
                  + "AND cc.fingerprint = lc.previous_fingerprint AND cc.trust_status = 'UNTRUSTED')");
            if (poisoned > 0 || stamps > 0) {
                log.info("Araya girmeden doğan pin kirliliği temizlendi: {} pin sıfırlandı, {} sahte 'değişti' damgası silindi",
                        poisoned, stamps);
            } else {
                log.debug("Araya girmeden doğan sahte sertifika damgası bulunamadı — temiz.");
            }
        } catch (Exception e) {
            log.warn("Sahte sertifika 'değişti' temizliği atlandı: {}", e.getMessage());
        }
    }

    /**
     * Takımsız (team_id IS NULL) sertifika/kontak varsa yalnız UYARIR — bunlar takım-kapsamlı ekranlarda
     * görünmez. Eskiden bu kayıtlar rastgele "ilk" takıma OTOMATİK atanıyordu (özel bir "varsayılan takım"
     * yoktu; yanlış takıma atama + her açılışta yanıltıcı log riski). Artık atama YOK — yalnız görünürlük
     * uyarısı; öksüz kaydı admin bilinçli olarak elle atar. Salt-okunur (mutasyon yapmaz).
     */
    private void warnOnOrphanedRecords() {
        try {
            Long certs = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM certificate_inventory WHERE team_id IS NULL", Long.class);
            Long contacts = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM escalation_contacts WHERE team_id IS NULL", Long.class);
            long c = certs != null ? certs : 0L;
            long k = contacts != null ? contacts : 0L;
            if (c + k > 0) {
                log.warn("{} takımsız sertifika + {} takımsız kontak var — takım-kapsamlı ekranlarda görünmezler; "
                        + "elle takım atayın.", c, k);
            } else {
                log.debug("Öksüz (takımsız) kayıt yok.");
            }
        } catch (Exception e) {
            log.warn("warnOnOrphanedRecords failed: {}", e.getMessage());
        }
    }

    private static final java.util.regex.Pattern ADD_COL_RE =
            java.util.regex.Pattern.compile("(?i)ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)");
    private static final java.util.regex.Pattern CREATE_TBL_RE =
            java.util.regex.Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)");

    /**
     * Idempotent şema/veri yaması. INFO "applied" YALNIZ gerçekten bir değişiklik olduğunda yazılır;
     * her boot'ta tekrar eden no-op'lar (zaten var olan kolon/tablo, 0 satır etkileyen UPDATE,
     * CREATE ... IF NOT EXISTS index'ler) DEBUG'a iner → log gürültüsü gider, davranış aynı kalır.
     */
    /**
     * Envanter-türevi port/dns monitörlerinde MÜKERRER kaydı temizler ve tekrarını DB düzeyinde
     * imkânsız kılar.
     *
     * <p>Yarış: {@code GET /monitoring/port} ve {@code /dns} eksik monitörleri istek anında
     * "lazy-provision" ediyor — check-then-act. Tabloda benzersizlik kısıtı olmadığı için 100
     * kullanıcı ekranı aynı anda açtığında aynı domain için N satır oluşabiliyordu; sonrasında o
     * domain N kat kontrol trafiği üretir ve raporlarda çift sayılır.
     *
     * <p>Sıra ÖNEMLİ: önce birleştir, sonra index. Mükerrer varken index kurulmaya çalışılsaydı
     * {@link #patch} istisnayı yutup {@code debug} loglardı (açılış patlamaz ama kısıt da hiç
     * kurulmazdı — sessiz başarısızlık). Bu yüzden sonunda kısıtın gerçekten var olduğu doğrulanır.
     *
     * <p>{@code standalone} monitörler KAPSAM DIŞI: onları kullanıcı elle ekliyor ve aynı hedefi
     * bilinçli olarak farklı ayarlarla iki kez izlemek meşru.
     */
    private void dedupeAndLockMonitorKeys() {
        // port: doğal anahtar (host, port). dns: (domain) — envanter-türevi kayıtlar daima 'A' tipli.
        mergeDuplicates("port_monitors", "port_checks", "x.host = m.host AND x.port = m.port", "host, port");
        patch("CREATE UNIQUE INDEX IF NOT EXISTS uq_pm_host_port ON port_monitors(host, port) "
            + "WHERE standalone IS NOT TRUE");

        mergeDuplicates("dns_monitors", "dns_records", "x.domain = m.domain", "domain");
        patch("CREATE UNIQUE INDEX IF NOT EXISTS uq_dnsm_domain ON dns_monitors(domain) "
            + "WHERE standalone IS NOT TRUE");

        warnIfIndexMissing("uq_pm_host_port");
        warnIfIndexMissing("uq_dnsm_domain");
    }

    /**
     * Bir monitör tablosundaki mükerrerleri EN KÜÇÜK id'de birleştirir; çocuk satırlar korunur
     * (kontrol geçmişi silinmez, tutulan monitöre taşınır).
     *
     * @param table       monitör tablosu (dış takma ad daima {@code m})
     * @param childTable  {@code monitor_id} ile bağlı kontrol/kayıt tablosu
     * @param keyMatch    alt sorgu ({@code x}) ile dış satır ({@code m}) arasındaki anahtar eşitliği
     * @param keyCols     doğal anahtar sütunları — yalnız log metni için
     */
    private void mergeDuplicates(String table, String childTable, String keyMatch, String keyCols) {
        // Bu satırın "tutulacak" eşi: aynı anahtardaki en küçük id.
        String keepId = "SELECT min(x.id) FROM " + table + " x WHERE x.standalone IS NOT TRUE AND " + keyMatch;
        String dupFilter = "m.standalone IS NOT TRUE AND m.id > (" + keepId + ")";
        try {
            Integer dupes = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " m WHERE " + dupFilter, Integer.class);
            if (dupes == null || dupes == 0) {
                log.debug("{}: mükerrer kayıt yok ({})", table, keyCols);
                return;
            }
            // Kaç satırın etkileneceği SİLMEDEN ÖNCE loglanır — prod verisine dokunuyoruz.
            log.warn("{}: {} mükerrer kayıt bulundu ({}) — en küçük id'de birleştiriliyor", table, dupes, keyCols);

            int moved = jdbcTemplate.update(
                    "UPDATE " + childTable + " c SET monitor_id = k.keep_id FROM ("
                  + "  SELECT m.id AS dup_id, (" + keepId + ") AS keep_id"
                  + "    FROM " + table + " m WHERE " + dupFilter
                  + ") k WHERE c.monitor_id = k.dup_id");

            int deleted = jdbcTemplate.update("DELETE FROM " + table + " m WHERE " + dupFilter);
            log.warn("{}: {} çocuk satır taşındı, {} mükerrer monitör silindi", table, moved, deleted);
        } catch (Exception e) {
            // Birleştirme başarısızsa index de kurulamaz; warnIfIndexMissing bunu görünür kılar.
            log.warn("{}: mükerrer birleştirme başarısız ({}) — benzersizlik kısıtı KURULAMAYABİLİR",
                    table, e.getMessage());
        }
    }

    /** Kısıt gerçekten kuruldu mu? {@link #patch} istisnayı yuttuğu için sessiz başarısızlık olmasın. */
    private void warnIfIndexMissing(String indexName) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE lower(indexname) = lower(?)", Integer.class, indexName);
            if (n == null || n == 0) {
                log.warn("⚠ Benzersizlik kısıtı {} KURULAMADI — mükerrer monitör yarışı hâlâ mümkün", indexName);
            }
        } catch (Exception e) {
            log.debug("{} kontrolü yapılamadı (PostgreSQL değil?): {}", indexName, e.getMessage());
        }
    }

    private void patch(String ddl) {
        String shortDdl = ddl.length() > 60 ? ddl.substring(0, 60) + "…" : ddl;
        String head = ddl.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            if (head.startsWith("ALTER TABLE") && head.contains(" ADD COLUMN ")) {
                var m = ADD_COL_RE.matcher(ddl);
                if (m.find() && columnExists(m.group(1), m.group(2))) {     // kolon zaten var → hiç çalıştırma (hata gürültüsü de biter)
                    log.debug("Schema patch noop (column exists): {}", shortDdl); return;
                }
                jdbcTemplate.execute(ddl);
                log.info("Schema patch applied (column added): {}", shortDdl);
            } else if (head.startsWith("CREATE TABLE")) {
                var m = CREATE_TBL_RE.matcher(ddl);
                if (m.find() && tableExists(m.group(1))) {                  // tablo zaten var → atla
                    log.debug("Schema patch noop (table exists): {}", shortDdl); return;
                }
                jdbcTemplate.execute(ddl);
                log.info("Schema patch applied (table created): {}", shortDdl);
            } else if (head.startsWith("UPDATE") || head.startsWith("INSERT") || head.startsWith("DELETE")) {
                int rows = jdbcTemplate.update(ddl);                        // gerçek değişiklik = etkilenen satır > 0
                if (rows > 0) log.info("Schema patch applied ({} row(s)): {}", rows, shortDdl);
                else          log.debug("Schema patch noop (0 rows): {}", shortDdl);
            } else {
                jdbcTemplate.execute(ddl);   // CREATE [UNIQUE] INDEX IF NOT EXISTS, DROP ..., ALTER ... TYPE — idempotent, sessiz
                log.debug("Schema patch ran: {}", shortDdl);
            }
        } catch (Exception e) {
            log.debug("Schema patch skipped: {}", e.getMessage());
        }
    }

    private boolean columnExists(String table, String col) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) AND lower(column_name)=lower(?)",
                Integer.class, table, col);
        return n != null && n > 0;
    }

    private boolean tableExists(String table) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                Integer.class, table);
        return n != null && n > 0;
    }

    /** Full sweep: runs at the top of every hour (configurable via site.monitor.scheduler.cron). */
    @Scheduled(cron = "${site.monitor.scheduler.cron:0 0 * * * *}")
    public void scheduledHourlyCheck() {
        log.info("Hourly scheduled check triggered [instance={}]", INSTANCE_ID);
        runCheck();
    }

    /** Stale sweep: checks domains not checked within stale-minutes (configurable). */
    @Scheduled(fixedDelayString = "${site.monitor.scheduler.stale-check-interval-ms:300000}",
               initialDelayString = "${site.monitor.scheduler.stale-check-interval-ms:300000}")
    public void checkStaleInventory() {
        int staleMin = appSettings.getInt("site.monitor.scheduler.stale-minutes", staleMinutes);
        String cutoff = ISO.format(Instant.now().minus(staleMin, ChronoUnit.MINUTES));
        Set<String> freshDomains = latestCheckRepo.findByCheckedAtGreaterThanEqual(cutoff).stream()
                .map(lc -> lc.getDomain())
                .collect(Collectors.toSet());

        List<Map<String, Object>> staleDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .filter(item -> !freshDomains.contains(item.getDomain()))
                .map(item -> Map.<String, Object>of(
                        "domain",    item.getDomain(),
                        "port",      item.getPort(),
                        "use_proxy", Boolean.TRUE.equals(item.getUseProxy()),
                        "tls_mode",  item.getTlsMode() == null ? "" : item.getTlsMode()))
                .toList();

        if (staleDomains.isEmpty()) {
            log.debug("Stale sweep: all active domains are fresh");
            return;
        }
        log.info("Stale sweep: {} domain(s) not checked in {} min", staleDomains.size(), staleMin);
        runCheckForDomains(staleDomains, false);   // stale alt-küme → evict etme, 300 sn TTL tazelesin
    }

    /**
     * Gece 03:30 (Europe/Istanbul implicit — backend ISO timestamp'i UTC tutuyor
     * ama cron Spring TaskScheduler'a göre çalışır) eski log/geçmiş kayıtlarını siler.
     * Bellek/disk şişmesini önlemek için:
     *   - audit_log         → 180 gün üstü
     *   - notification_logs → 90 gün üstü
     *   - sql_query_history → 30 gün üstü
     *   - uptime_checks / certificate_checks / port_checks → 180 gün üstü (yüksek hacimli
     *     zaman serisi; daha önce HİÇ temizlenmiyordu — sınırsız büyüyordu)
     *   - dns_records → 180 gün üstü, ANCAK her monitör için en yeni (MAX(id)) satır korunur;
     *     yoksa baseline silinince sonraki kontrol sahte "değişti" üretir.
     * Tüm zaman serisi tablolarında checked_at index'li → DELETE verimli.
     * Bulk DELETE → tek transaction, kısa süreli.
     */
    /**
     * Gece 03:20 — bitişine ≤7 gün kalan (veya geçmiş) otomatik pinlenmiş CA'ları sunucudan yeniden
     * çekip gerekiyorsa döndürür ({@link CaAutoPinService#refreshExpiringPins}). PKIX trust anchor
     * geçerliliğini kontrol etmediğinden süresi dolan pin handshake'i düşürmeyebilir — proaktif
     * yenileme asıl mekanizmadır; kontrol-anı lazy re-pin yedektir.
     */
    @Scheduled(cron = "${site.monitor.trust.auto-pin.refresh-cron:0 20 3 * * *}", zone = "Europe/Istanbul")
    public void runCaPinRefresh() {
        if (!caAutoPinService.isEnabled()) return;
        if (!tryAcquireSchedulerLock("ca-pin-refresh", sweepLockTtlMinutes)) {
            log.debug("CA pin refresh skipped — lock held by another instance");
            return;
        }
        try {
            int rotated = caAutoPinService.refreshExpiringPins();
            if (rotated > 0) log.info("CA pin refresh: {} pin yenilendi", rotated);
        } catch (Exception e) {
            log.warn("CA pin refresh failed: {}", e.getMessage());
        } finally {
            releaseSchedulerLock("ca-pin-refresh");
        }
    }

    /**
     * Günlük 04:15 IST — envanterin {@code domain_expiry} cache'ini RDAP/WHOIS'ten tazeler
     * ({@link DomainExpiryRefreshService#refreshAll}). Bu kolon eskiden yalnız elle "Alan Adı Tanılama"
     * ile yazılıyordu; haftalık rapor onu okuduğundan domain yenilenince bayat kalıyordu. Cuma ~09:00
     * raporundan önce çalışır → rapor güncel gün sayısını gösterir. HA: yalnız bir pod çalıştırır.
     */
    @Scheduled(cron = "${site.monitor.scheduler.domain-expiry-refresh-cron:0 15 4 * * *}", zone = "Europe/Istanbul")
    public void runDomainExpiryRefresh() {
        if (!appSettings.getBoolean("site.monitor.scheduler.domain-expiry-refresh.enabled", true)) return;
        if (!tryAcquireSchedulerLock("domain-expiry-refresh", sweepLockTtlMinutes)) {
            log.debug("Domain-expiry refresh skipped — lock held by another instance");
            return;
        }
        try {
            int n = domainExpiryRefreshService.refreshAll();
            if (n > 0) log.info("Domain-expiry refresh: {} registrable domain tazelendi", n);
        } catch (Exception e) {
            log.warn("Domain-expiry refresh failed: {}", e.getMessage());
        } finally {
            releaseSchedulerLock("domain-expiry-refresh");
        }
    }

    // zone ZORUNLU: konteynerde JVM saat dilimi GMT. Zone'suz bırakıldığında bu iş 03:30 GMT'de,
    // yani 06:30 İstanbul'da — mesai başlangıcında — koşuyordu; oysa niyet sessiz gece penceresi.
    // Kardeş günlük işlerin (auto-pin 03:20, domain-expiry 04:15, digest 09:00 …) hepsi zaten
    // Europe/Istanbul'a sabitli; bu tek istisnaydı. İfade RetentionCatalog'dan gelir (tek kaynak).
    @Scheduled(cron = "${" + RetentionCatalog.CLEANUP_CRON_KEY + ":" + RetentionCatalog.CLEANUP_CRON_DEFAULT + "}",
               zone = RetentionCatalog.CLEANUP_ZONE)
    public void cleanupOldLogs() {
        // HA: prod çok-replikalı (master overlay 3 pod). Kilit olmadan 3 pod aynı anda batch-DELETE +
        // rollup ON CONFLICT koşar → kilit çekişmesi/deadlock riski + 3× boşa iş. Yalnız BİR pod çalışsın.
        // TTL 60dk: batch silme (tablo başına 10dk cap) tüm tabloları için normal gecede fazlasıyla yeter.
        if (!tryAcquireSchedulerLock("nightly-cleanup", 60)) {
            log.debug("Gece temizlik/rollup — lock başka pod'da, atlanıyor");
            return;
        }
        try {
            // ÖNCE rollup (ham kontrol serilerini özete al) — SONRA purge. Böylece ham kısa
            // retention'la silinse de uzun-dönem trend monitor_check_daily'de, olayın SAATİ ise
            // monitor_check_hourly'de korunur.
            rollupDailyStats();
            rollupHourlyStats();

            // Denetim arşivi: audit_log SİLİNMEDEN ÖNCE JSONL'e yazılmalı (append-only + arşiv).
            // Bu adım retention politikasının parçası değil, ön koşuludur → burada kalır.
            boolean hold = retentionService.holdActive();
            int archived = 0;
            if (!hold) {
                var auditPolicy = RetentionCatalog.byId("audit-log").orElseThrow();
                archived = archiveAuditBeforePurge(retentionService.cutoffFor(auditPolicy));
            }

            // ── Asıl temizlik: bildirimsel RetentionCatalog üzerinden ──────────────────────────
            RetentionService.RunResult run = retentionService.runCleanup();

            if (run.holdActive()) {
                auditService.recordSystemEvent("RETENTION_HOLD_ACTIVE", "RETENTION", "cleanup",
                        "{\"skipped_policies\":" + run.items().size() + "}");
            } else {
                int auditDeleted = run.items().stream()
                        .filter(i -> "audit-log".equals(i.policyId())).mapToInt(RetentionService.ItemResult::rows)
                        .findFirst().orElse(0);
                // Retention/purge job'ın kendisi de denetlenir (silme sonrası → yeni zincir ucuna yazılır).
                auditService.recordSystemEvent("AUDIT_RETENTION_PURGE", "AUDIT_LOG", "cleanup",
                        "{\"deleted\":" + auditDeleted + ",\"archived\":" + archived
                                + ",\"total_rows\":" + run.totalRows() + ",\"failed\":" + run.failedCount() + "}");
            }

            // Denetim JSONL arşiv dosyaları (logs/audit-archive/*.jsonl) — dosya sistemi, tablo değil.
            int arcRetDays = Math.max(30, appSettings.getInt("site.monitor.audit.archive-retention-days", 365));
            int arc = hold ? 0 : rotateAuditArchive(arcRetDays);
            // In-memory: silinen monitörlerin checkDue anahtarları birikmesin (uzun uptime sızıntısı).
            int pruned = pruneMonitorCheckState(collectLiveMonitorKeys());
            log.info("Gece temizliği: {} politika, {} satır, {} hata, {} ms · arşivlenen={}, arşiv dosyası silinen={}, "
                    + "checkState anahtarı={}{}",
                    run.items().size(), run.totalRows(), run.failedCount(), run.durationMs(),
                    archived, arc, pruned, run.holdActive() ? " · LEGAL HOLD AKTİF" : "");
        } catch (Exception e) {
            log.warn("Nightly cleanup failed: {}", e.getMessage());
        } finally {
            releaseSchedulerLock("nightly-cleanup");
        }
    }

    // NOT: safeDelete / safeDeleteBatched buradan KALDIRILDI (2026-08). Silme mantığı artık
    // RetentionService içinde, RetentionCatalog'daki bildirimsel politikalardan üretiliyor.

    /** Denetim arşiv dosyalarını (logs/audit-archive/*.jsonl) N günden eskiyse sil — bugün disk sınırsız. */
    private int rotateAuditArchive(int retentionDays) {
        try {
            String dir = appSettings.getString("site.monitor.audit.archive-dir", "logs/audit-archive");
            java.nio.file.Path p = java.nio.file.Path.of(dir);
            if (!java.nio.file.Files.isDirectory(p)) return 0;
            long cutoffMs = System.currentTimeMillis() - retentionDays * 86_400_000L;
            int deleted = 0;
            try (var stream = java.nio.file.Files.list(p)) {
                for (java.nio.file.Path f : stream.filter(x -> x.toString().endsWith(".jsonl")).toList()) {
                    if (java.nio.file.Files.getLastModifiedTime(f).toMillis() < cutoffMs) {
                        java.nio.file.Files.deleteIfExists(f);
                        deleted++;
                    }
                }
            }
            return deleted;
        } catch (Exception e) {
            log.warn("Audit arşiv rotasyonu başarısız: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Günlük ROLLUP: son {@code lookback-days} TAMAMLANMIŞ günü ham kontrol serilerinden
     * {@code monitor_check_daily}'ye aggregate eder (idempotent upsert → gün tekrar hesaplanınca üzerine
     * yazar; kısa app-kapalılığı boşluklarını da self-heal eder). Purge'den ÖNCE çağrılır → ham silinse
     * de trend kalır. Postgres native (ON CONFLICT); H2'de patch/test bunu çalıştırmaz.
     */
    void rollupDailyStats() {
        try {
            int lookback = Math.max(1, appSettings.getInt("site.monitor.rollup.lookback-days", 3));
            Instant todayStartI = Instant.now().truncatedTo(ChronoUnit.DAYS);
            String to   = ISO.format(todayStartI);                                  // bugünün başı (dahil değil)
            String from = ISO.format(todayStartI.minus(lookback, ChronoUnit.DAYS)); // son N tam gün
            int p  = rollupUpsert("PORT",    "port_checks",     "open", "response_ms", from, to);
            int pg = rollupUpsert("PING",    "ping_checks",     "up",   "rtt_ms",      from, to);
            int k  = rollupUpsert("KEYWORD", "keyword_results", "ok",   "response_ms", from, to);
            int h  = rollupUpsert("HTTP",    "http_checks",     "ok",   "response_ms", from, to);
            int pi = rollupUpsert("PAGE",    "page_checks",     "ok",   "response_ms", from, to);
            int sc = rollupUpsert("SCRIPTED", "scripted_checks", "ok",  "duration_ms", from, to);
            int ps = rollupUpsert("PAGESPEED", "pagespeed_checks", "ok", "response_ms", from, to);
            int u  = rollupUptime(from, to);
            log.info("Daily rollup: port={}, ping={}, keyword={}, http={}, page={}, scripted={}, pagespeed={}, uptime={} ({} → {})",
                    p, pg, k, h, pi, sc, ps, u, from, to);
        } catch (Exception e) {
            log.warn("Daily rollup failed: {}", e.getMessage());
        }
    }

    /**
     * SAATLİK rollup: aynı huni, kova genişliği 13 karakter ({@code YYYY-MM-DDTHH}).
     *
     * <p>Neden gerekli: ham seri kısaltıldığında (ör. 180g → 90g) günlük özet "o gün %97,4"
     * demeye devam eder ama kesintinin SAATİNİ kaybeder. Saatlik kova bu boşluğu doldurur ve
     * maliyeti ihmal edilebilir (100 monitör × 24 satır/gün ≈ ham serinin %1,7'si).
     * Kısaltma, bu katman doğrulanmadan YAPILMAZ ({@code RollupConsistencyTest}).
     */
    void rollupHourlyStats() {
        try {
            int lookback = Math.max(1, appSettings.getInt("site.monitor.rollup.lookback-days", 3));
            Instant todayStartI = Instant.now().truncatedTo(ChronoUnit.DAYS);
            String to   = ISO.format(todayStartI.plus(1, ChronoUnit.DAYS));            // bugün dahil
            String from = ISO.format(todayStartI.minus(lookback, ChronoUnit.DAYS));
            int n = rollupAllTypes(HOURLY_TABLE, HOURLY_COL, 13, from, to);
            log.info("Saatlik rollup: {} kova ({} → {})", n, from, to);
        } catch (Exception e) {
            log.warn("Saatlik rollup başarısız: {}", e.getMessage());
        }
    }

    /**
     * Saatlik kovaları GERİYE DÖNÜK doldurur — ham seri hâlâ elde olduğu için tüm saklama
     * penceresi tek seferde kurtarılabilir. Bu sayede "saatlik veri birikene kadar bekle"
     * ön koşulu ortadan kalkar: kısaltmadan önce çözünürlük zaten yedeklenmiş olur.
     * Upsert idempotenttir → tekrar çalıştırmak güvenlidir.
     *
     * @param days kaç gün geriye gidilecek
     * @return yazılan/güncellenen kova sayısı
     */
    public int backfillHourlyRollup(int days) {
        int d = Math.max(1, Math.min(days, 3650));
        String to   = ISO.format(Instant.now().plus(1, ChronoUnit.DAYS));
        String from = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
        int n = rollupAllTypes(HOURLY_TABLE, HOURLY_COL, 13, from, to);
        log.info("Saatlik rollup geriye-doldurma: {} kova ({} gün: {} → {})", n, d, from, to);
        return n;
    }

    static final String HOURLY_TABLE = "monitor_check_hourly";
    static final String HOURLY_COL = "hour_bucket";

    /** 7 izleme türünü tek kova genişliğiyle hedef tabloya yazar; toplam satır döner. */
    private int rollupAllTypes(String target, String bucketCol, int len, String from, String to) {
        int total = 0;
        total += Math.max(0, rollupInto(target, bucketCol, len, "PORT",     "port_checks",     "open", "response_ms", from, to));
        total += Math.max(0, rollupInto(target, bucketCol, len, "PING",     "ping_checks",     "up",   "rtt_ms",      from, to));
        total += Math.max(0, rollupInto(target, bucketCol, len, "KEYWORD",  "keyword_results", "ok",   "response_ms", from, to));
        total += Math.max(0, rollupInto(target, bucketCol, len, "HTTP",     "http_checks",     "ok",   "response_ms", from, to));
        total += Math.max(0, rollupInto(target, bucketCol, len, "PAGE",     "page_checks",     "ok",   "response_ms", from, to));
        total += Math.max(0, rollupInto(target, bucketCol, len, "SCRIPTED", "scripted_checks", "ok",   "duration_ms", from, to));
        total += Math.max(0, rollupInto(target, bucketCol, len, "PAGESPEED", "pagespeed_checks", "ok", "response_ms", from, to));
        total += Math.max(0, rollupUptimeInto(target, bucketCol, len, from, to));
        return total;
    }

    // monitor_id-anahtarlı tipler (boolean up-kolonu). table/upCol/respCol yalnız KOD-kontrollü (injection yok).
    private int rollupUpsert(String type, String table, String upCol, String respCol, String from, String to) {
        return rollupInto("monitor_check_daily", "day", 10, type, table, upCol, respCol, from, to);
    }

    /** Ortak upsert hunisi — yalnız hedef tablo ve kova genişliği değişir (günlük 10, saatlik 13). */
    private int rollupInto(String target, String bucketCol, int len, String type, String table,
                           String upCol, String respCol, String from, String to) {
        String sql = upsertSql(target, bucketCol, len, type, table, upCol, respCol);
        try { return jdbcTemplate.update(sql, from, to); }
        catch (Exception e) { log.warn("Rollup {} ({}) failed: {}", type, target, e.getMessage()); return -1; }
    }

    /** SQL üretimi ayrı: günlük ve saatlik yolun BİRE BİR aynı olduğunu test doğrudan kanıtlayabilsin
     *  (tek fark hedef tablo, kova kolonu ve kova genişliği). Tablo/kolon adları yalnız KOD-kontrollü. */
    static String upsertSql(String target, String bucketCol, int len, String type, String table,
                            String upCol, String respCol) {
        return "INSERT INTO " + target + " (monitor_type, monitor_key, " + bucketCol + ", total_checks, up_checks, avg_response_ms, max_response_ms) "
            + "SELECT '" + type + "', CAST(monitor_id AS varchar), substr(checked_at,1," + len + "), "
            + "count(*), sum(CASE WHEN " + upCol + " THEN 1 ELSE 0 END), "
            + "CAST(round(avg(" + respCol + ")) AS int), max(" + respCol + ") "
            + "FROM " + table + " WHERE checked_at >= ? AND checked_at < ? "
            + "GROUP BY monitor_id, substr(checked_at,1," + len + ") "
            + "ON CONFLICT (monitor_type, monitor_key, " + bucketCol + ") DO UPDATE SET "
            + "total_checks = EXCLUDED.total_checks, up_checks = EXCLUDED.up_checks, "
            + "avg_response_ms = EXCLUDED.avg_response_ms, max_response_ms = EXCLUDED.max_response_ms";
    }

    // Uptime: domain-anahtarlı, up = status='up', maintenance hariç.
    private int rollupUptime(String from, String to) {
        return rollupUptimeInto("monitor_check_daily", "day", 10, from, to);
    }

    private int rollupUptimeInto(String target, String bucketCol, int len, String from, String to) {
        String sql = uptimeUpsertSql(target, bucketCol, len);
        try { return jdbcTemplate.update(sql, from, to); }
        catch (Exception e) { log.warn("Rollup UPTIME ({}) failed: {}", target, e.getMessage()); return -1; }
    }

    static String uptimeUpsertSql(String target, String bucketCol, int len) {
        return "INSERT INTO " + target + " (monitor_type, monitor_key, " + bucketCol + ", total_checks, up_checks, avg_response_ms, max_response_ms) "
            + "SELECT 'UPTIME', domain, substr(checked_at,1," + len + "), "
            + "count(*), sum(CASE WHEN status = 'up' THEN 1 ELSE 0 END), "
            + "CAST(round(avg(response_ms)) AS int), max(response_ms) "
            + "FROM uptime_checks WHERE checked_at >= ? AND checked_at < ? AND (maintenance = false OR maintenance IS NULL) "
            + "GROUP BY domain, substr(checked_at,1," + len + ") "
            + "ON CONFLICT (monitor_type, monitor_key, " + bucketCol + ") DO UPDATE SET "
            + "total_checks = EXCLUDED.total_checks, up_checks = EXCLUDED.up_checks, "
            + "avg_response_ms = EXCLUDED.avg_response_ms, max_response_ms = EXCLUDED.max_response_ms";
    }

    /** Denetim kayıtlarını silmeden ÖNCE tarihli JSONL arşive yazar (append-only + arşiv gerekliliği).
     *  archive-enabled kapalıysa/hata olursa 0 döner (silme yine de yapılır; en kötü ihtimalle arşivsiz). */
    /** Arşiv okuma sayfası. Tüm tabloyu tek List'e almak yerine keyset ile ilerlenir (aşağıya bakın). */
    static final int AUDIT_ARCHIVE_BATCH = 5_000;

    private int archiveAuditBeforePurge(String cutoff) {
        try {
            if (!appSettings.getBoolean("site.monitor.audit.archive-enabled", true)) return 0;
            String dir = appSettings.getString("site.monitor.audit.archive-dir", "logs/audit-archive");
            java.nio.file.Path p = java.nio.file.Path.of(dir, "audit-" + cutoff.substring(0, 10) + ".jsonl");
            if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());

            // KEYSET SAYFALAMA + akan yazım. Eskiden "SELECT *" LIMIT'siz tek List'e alınıyor, sonra
            // TAMAMI tek StringBuilder + tek String'e kopyalanıyordu (bellekte ~3 kat). Retention ilk kez
            // devreye girdiğinde veya legal-hold kalktığında bu milyonlarca satır olabilir → gece 03:30'da
            // OOM → pod restart (üstelik catch(Exception) OutOfMemoryError'ı YAKALAMAZ). Silme tarafı
            // zaten batch'liydi; ön adım da artık öyle.
            long lastSeq = Long.MIN_VALUE;
            int total = 0;
            try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(p,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                while (true) {
                    List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                            "SELECT * FROM audit_log WHERE event_time < ? AND seq > ? ORDER BY seq ASC LIMIT " + AUDIT_ARCHIVE_BATCH,
                            cutoff, lastSeq);
                    if (rows.isEmpty()) break;
                    for (java.util.Map<String, Object> r : rows) {
                        StringBuilder sb = new StringBuilder(256);
                        sb.append('{');
                        boolean first = true;
                        for (var e : r.entrySet()) {
                            if (!first) sb.append(',');
                            first = false;
                            sb.append('"').append(e.getKey()).append("\":");
                            Object v = e.getValue();
                            if (v == null) sb.append("null");
                            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                            else sb.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                                    .replace("\n", " ").replace("\r", " ")).append('"');
                        }
                        sb.append('}');
                        w.write(sb.toString());
                        w.newLine();
                        Object seq = r.get("seq");
                        if (seq instanceof Number n) lastSeq = n.longValue();
                    }
                    total += rows.size();
                    if (rows.size() < AUDIT_ARCHIVE_BATCH) break;
                }
            }
            return total;
        } catch (Exception e) {
            log.warn("Audit arşivleme başarısız (silme yine yapılacak): {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Her Cuma 09:00 Europe/Istanbul — o anki ISO haftası raporunu henüz onaya
     * göndermemiş aktif SY takımlarına hatırlatma maili. HA: scheduler_lock ile
     * yalnız bir pod gönderir (TTL = cert-check ile aynı lock-ttl).
     */
    @Scheduled(cron = "${site.monitor.weekly-report.reminder-cron:0 0 9 ? * FRI}", zone = "Europe/Istanbul")
    public void scheduledWeeklyReportReminder() {
        if (!tryAcquireSchedulerLock("weekly-report-reminder", lockTtlMinutes)) {
            log.debug("Haftalık rapor hatırlatması — lock başka pod'da, atlanıyor");
            return;
        }
        try {
            weeklyReportReminderService.sendFridayReminders();
        } catch (Exception e) {
            log.error("Haftalık rapor cuma hatırlatması başarısız: {}", e.getMessage(), e);
        } finally {
            releaseSchedulerLock("weekly-report-reminder");
        }
    }

    // Sorun bildirimleri günlük özeti — ALAN-enjeksiyon (bilinçli): SchedulerServiceTest manuel
    // kurucuyu kullanır; kurucu imzasını değiştirmemek için. Cron null-guard'lıdır.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LoginIssueMailService loginIssueMailService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.LoginIssueReportRepository loginIssueReportRepo;

    /**
     * Her gün 09:00 Europe/Istanbul — {@code site.monitor.issue-reports.daily-digest} AÇIKKEN
     * son 24 saatin USER_REPORT sorun bildirimlerini tek özet mailde Sistem Yöneticisi'ne gönderir
     * (tekil mailler o modda bilinçli atlanır — IssueReportController). HA: scheduler_lock.
     */
    @Scheduled(cron = "${site.monitor.issue-reports.digest-cron:0 0 9 * * *}", zone = "Europe/Istanbul")
    public void scheduledIssueReportDigest() {
        if (!appSettings.getBoolean("site.monitor.issue-reports.daily-digest", false)) return;
        if (loginIssueMailService == null || loginIssueReportRepo == null) return;   // manuel-kurulum güvenlik ağı
        if (!tryAcquireSchedulerLock("issue-report-digest", lockTtlMinutes)) {
            log.debug("Sorun bildirimi özeti — lock başka pod'da, atlanıyor");
            return;
        }
        try {
            String adminEmail = appSettings.getString("site.monitor.system-admin.email", "");
            if (adminEmail == null || adminEmail.isBlank()) return;
            String since = ISO.format(Instant.now().minus(24, ChronoUnit.HOURS));
            var reports = loginIssueReportRepo
                    .findBySourceAndReportedAtGreaterThanEqualOrderByReportedAtDesc("USER_REPORT", since);
            if (reports.isEmpty()) return;
            List<Map<String, String>> items = new ArrayList<>();
            for (var r : reports) {
                String msg = r.getMessage() != null ? r.getMessage().strip().replaceAll("\\s+", " ") : "";
                items.add(Map.of(
                        "refCode", LoginIssueService.refCode(r),
                        "username", r.getUsername() != null ? r.getUsername() : "—",
                        "summary", msg.length() > 80 ? msg.substring(0, 80) + "…" : msg));
            }
            loginIssueMailService.dispatchDigest(adminEmail, items, "Son 24 saat");
        } catch (Exception e) {
            log.error("Sorun bildirimi günlük özeti başarısız: {}", e.getMessage(), e);
        } finally {
            releaseSchedulerLock("issue-report-digest");
        }
    }

    /**
     * Her Pazartesi 10:00 Europe/Istanbul — sertifika sahibi her aktif takıma, sahip olduğu
     * domainlerin geçen tam haftaya (Pzt–Paz) ait erişilebilirlik (availability) özeti maili.
     * Kesinti olsun olmasın gider. HA: scheduler_lock ile yalnız bir pod gönderir.
     */
    @Scheduled(cron = "${site.monitor.weekly-availability.cron:0 0 10 ? * MON}", zone = "Europe/Istanbul")
    public void scheduledWeeklyAvailabilityReport() {
        if (!tryAcquireSchedulerLock("weekly-availability", lockTtlMinutes)) {
            log.debug("Haftalık erişilebilirlik raporu — lock başka pod'da, atlanıyor");
            return;
        }
        try {
            weeklyAvailabilityReportService.sendWeeklyReports(false);
        } catch (Exception e) {
            log.error("Haftalık erişilebilirlik raporu başarısız: {}", e.getMessage(), e);
        } finally {
            releaseSchedulerLock("weekly-availability");
        }
    }

    /**
     * Dağıtık kilit altında iş çalıştırır — cron'u {@code @Scheduled} yerine DİNAMİK tetikleyiciyle
     * kurulan görevler için ({@code CertInventoryReportScheduling}). Kilit başka pod'daysa iş atlanır.
     */
    public void runWithSchedulerLock(String lockName, Runnable task) {
        if (!tryAcquireSchedulerLock(lockName, lockTtlMinutes)) {
            log.debug("{} — lock başka pod'da, atlanıyor", lockName);
            return;
        }
        try {
            task.run();
        } catch (Exception e) {
            log.error("{} başarısız: {}", lockName, e.getMessage(), e);
        } finally {
            releaseSchedulerLock(lockName);
        }
    }

    /**
     * Başarısız-login anomali taraması — 10 dk'da bir (fixedDelay). Prod çok-replikalı olduğundan
     * dağıtık kilit ZORUNLU (aksi halde her pod ayrı mail atar). Görev exception fırlatsa da scheduler
     * ölmez (try/catch); pencere-kaçırmama + cooldown/incident orkestrasyonu servis içindedir.
     */
    @Scheduled(fixedDelayString = "${site.monitor.failed-login.scan-ms:600000}",
               initialDelayString = "${site.monitor.failed-login.scan-ms:600000}")
    public void scheduledFailedLoginAnomalyScan() {
        if (!tryAcquireSchedulerLock("failed-login-anomaly", lockTtlMinutes)) {
            log.debug("Başarısız-login anomali taraması — lock başka pod'da, atlanıyor");
            return;
        }
        try {
            failedLoginAnomalyIncidentService.scan();
        } catch (Exception e) {
            log.error("Başarısız-login anomali taraması hatası (scheduler devam ediyor): {}", e.getMessage(), e);
        } finally {
            releaseSchedulerLock("failed-login-anomaly");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** DB-free snapshot — safe to call during JVM shutdown when JPA may be unavailable. */
    public Map<String, Object> getShutdownSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance",  INSTANCE_ID);
        m.put("running",   running.get());
        m.put("run_id",    currentRunId.get());
        m.put("last_run",  lastRun.get() != null ? lastRun.get().toString() : "never");
        return m;
    }

    public void runCheck() {
        List<Map<String, Object>> domains = loadDomainsFromInventory();
        if (domains.isEmpty()) {
            log.warn("No active domains in inventory — skipping check");
            return;
        }
        runCheckForDomains(domains, true);   // tam sweep → cache evict
    }

    /**
     * @deprecated Bu metod artık scheduler turunda çağrılmıyor. Domain rename
     * sırasında {@code latest_checks}'te kalan eski domain'i boş metadata
     * (tier/ug/sy null) ile re-create ediyordu — auto-sync bug'ı.
     * Envanter UI ile yönetiliyor; çağrı kaldırıldı.
     */
    @Deprecated
    private void syncLatestChecksToInventory() {
        String now = ISO.format(Instant.now());
        latestCheckRepo.findAll().forEach(lc -> {
            if (!inventoryRepo.existsByDomain(lc.getDomain())) {
                CertificateInventory inv = new CertificateInventory();
                inv.setDomain(lc.getDomain());
                inv.setPort(443);
                inv.setActive(true);
                inv.setCreatedAt(now);
                inv.setUpdatedAt(now);
                inventoryRepo.save(inv);
                log.info("Synced {} → inventory", lc.getDomain());
            }
        });
    }

    private void runCheckForDomains(List<Map<String, Object>> domains, boolean evictCaches) {
        // 1. In-process guard (fast fail for same JVM)
        if (!running.compareAndSet(false, true)) {
            log.warn("Check already in progress (runId={}) — skipping duplicate trigger", currentRunId.get());
            return;
        }

        // 2. Distributed DB lock (HA: prevents duplicate across multiple instances)
        if (!tryAcquireSchedulerLock("cert-check", lockTtlMinutes)) {
            running.set(false);
            log.info("Scheduler lock held by another instance [{}], skipping", INSTANCE_ID);
            return;
        }

        // syncLatestChecksToInventory() artık çağrılmıyor — domain rename
        // sırasında eski domain'i boş metadata ile re-create ediyordu.
        // Envanter UI ile yönetiliyor; yetim latest_checks kayıtları artık
        // otomatik envantere dönmesin. Metod gövdesi @Deprecated olarak duruyor.

        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        currentRunId.set(runId);
        long startMs = System.currentTimeMillis();
        log.info("Certificate check started — runId={}, {} domain(s) [instance={}]",
                runId, domains.size(), INSTANCE_ID);

        try {
            List<CompletableFuture<Map<String, Object>>> futures = domains.stream()
                    .map(d -> checkerService.checkAsync(
                            (String) d.get("domain"),
                            (int) d.get("port"),
                            Boolean.TRUE.equals(d.get("use_proxy")),
                            blankToNull((String) d.get("tls_mode")),
                            (Integer) d.get("timeout_seconds")))
                    .toList();

            List<Map<String, Object>> results = futures.stream()
                    .map(CompletableFuture::join)
                    .map(r -> { Map<String, Object> m = new LinkedHashMap<>(r); m.put("run_id", runId); return m; })
                    .toList();

            results.forEach(certService::saveResult);
            // Cache evict YALNIZ veri-değiştiren tam/manuel sweep sonrası (evictCaches=true).
            // 5 dk'lık stale sweep (alt-küme, zaten 65+ dk bayat domainler) evict ETMEZ → cert
            // dashboard cache'i 300 sn TTL ile tazelenir, thundering-herd + gereksiz global evict biter.
            if (evictCaches) certService.evictAllCaches();

            long errors   = results.stream().filter(r -> "error".equals(r.get("status"))).count();
            long warnings = results.stream().filter(r -> Boolean.TRUE.equals(r.get("warning"))).count();
            long networkErrors = results.stream()
                    .filter(r -> "error".equals(r.get("status")))
                    .map(r -> (String) r.get("error_class"))
                    .filter(c -> "DNS".equals(c) || "NETWORK".equals(c))
                    .count();
            double networkErrorRate = results.isEmpty() ? 0.0 : (double) networkErrors / results.size();
            log.info("Check complete — runId={}, Total={}, Warning={}, Error={}, NetworkErrors={} (rate={}) [instance={}]",
                    runId, results.size(), warnings, errors, networkErrors,
                    String.format("%.2f", networkErrorRate), INSTANCE_ID);

            lastRun.set(LocalDateTime.now(ZoneOffset.UTC));
            lastRunDurationMs.set(System.currentTimeMillis() - startMs);
            lastRunTotal.set(results.size());
            lastRunFailure.set(null);   // temiz tur: önceki hata kaydı düşer
            lastRunErrors.set((int) errors);
            lastRunWarnings.set((int) warnings);

            int    minErrors = appSettings.getInt("site.monitor.network.min-errors", networkMinErrors);
            double rateThreshold = appSettings.getDouble("site.monitor.network.error-rate-threshold", networkErrorRateThreshold);
            boolean suspectedOutage = networkErrors >= minErrors
                                   && networkErrorRate >= rateThreshold;

            if (suspectedOutage) {
                networkLastErrorRate.set(networkErrorRate);
                networkLastNetworkErrors.set((int) networkErrors);
                networkLastTotal.set(results.size());
                if (networkOutageActive.compareAndSet(false, true)) {
                    String detectedAt = ISO.format(Instant.now());
                    networkOutageDetectedAt.set(detectedAt);
                    networkOutageResolvedAt.set(null);
                    log.warn("⚠ Suspected network outage detected: {}/{} domains failed with network-class errors " +
                             "(rate={}, threshold={}) — alarm processing SKIPPED for this run",
                            networkErrors, results.size(),
                            String.format("%.2f", networkErrorRate), rateThreshold);
                    persistOutageDetected(detectedAt, (int) networkErrors, results.size(), networkErrorRate);
                    trySendAdminAlert();
                } else {
                    log.warn("⚠ Suspected network outage still ongoing: {}/{} domains failed with network-class errors " +
                             "(rate={}) — alarm processing remains SKIPPED",
                            networkErrors, results.size(), String.format("%.2f", networkErrorRate));
                    if (pendingAdminAlertEmail.get()) trySendAdminAlert();
                }
            } else {
                if (networkOutageActive.compareAndSet(true, false)) {
                    String resolvedAt = ISO.format(Instant.now());
                    networkOutageResolvedAt.set(resolvedAt);
                    log.info("✓ Network outage cleared — alarm processing resumed");
                    persistOutageResolved(resolvedAt);
                    trySendAdminResolved();
                }
                if (pendingAdminResolvedEmail.get()) trySendAdminResolved();
                // Cert/domain expiry izleme kapalıysa cert alarmları işlenmez (SSL kontrol + durum güncellemesi sürer).
                if (appSettings.getBoolean("site.monitor.expiry.alert-enabled", true)) {
                    escalationService.processResults(results);
                }
            }

        } catch (Exception e) {
            // Eskiden yalnız finally vardı: join()/saveResult/processResults'tan çıkan beklenmedik bir
            // RuntimeException (ör. eşik alanında unboxing NPE'si) turu sessizce yakıyordu — sonuçlar
            // kaydedilmiyor, alarm işleme atlanıyor, lastRun güncellenmiyordu ve tek iz Spring'in generic
            // "Unexpected error occurred in scheduled task" satırıydı. Artık hata sahiplenilip sayılıyor;
            // scan_alarm de bu sayaç üzerinden anlamlı kalıyor.
            lastRunFailure.set(ISO.format(Instant.now()) + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Sertifika sweep'i BAŞARISIZ (runId={}): sonuçlar kaydedilemedi/alarm işlenemedi — {}",
                    runId, e.toString(), e);
        } finally {
            running.set(false);
            lastRunId.set(currentRunId.get());
            currentRunId.set("");
            releaseSchedulerLock("cert-check");
        }
    }

    // ── Distributed lock helpers ──────────────────────────────────────────────

    /**
     * Tries to acquire a named DB lock with a TTL.
     * Returns {@code true} if the lock was acquired by this instance,
     * {@code false} if another instance holds an unexpired lock.
     * Gracefully degrades to {@code true} (allow) if the lock table is unavailable.
     */
    private boolean tryAcquireSchedulerLock(String lockName, int ttlMinutes) {
        try {
            String now   = ISO.format(Instant.now());
            String until = ISO.format(Instant.now().plusSeconds(ttlMinutes * 60L));
            // Remove expired lock (safe even if already gone)
            jdbcTemplate.update(
                "DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?", lockName, now);
            // Try to insert — fails with unique-constraint violation if lock already held
            jdbcTemplate.update(
                "INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                lockName, INSTANCE_ID, until);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;   // kilit başka pod'da — tipli yakalama, mesaj metnine bağımlılık yok
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // Kilit TABLOSU yok (ör. ilk açılışta ddl-auto henüz koşmadı) — bilinçli tek-pod
            // geri düşüşü: kilitsiz çalışmak, hiç çalışmamaktan iyi.
            log.warn("Distributed lock table unavailable (HA degraded): {}", e.getMessage());
            return true;
        } catch (Exception e) {
            // D2: eskiden buradaki HER istisna (deadlock, statement-timeout, bağlantı kopması)
            // fail-open'dı → çok-pod'da geçici DB hatasında ÇİFT sweep. Mesaj-metni unique
            // kontrolü de sürücüye/dile bağımlıydı. Artık güvenli taraf: bu turu ATLA — sweep
            // periyodik, bir sonraki tur telafi eder; çift koşmanın bedeli (çift alarm/mail)
            // bir turu kaçırmaktan büyüktür.
            log.warn("Scheduler lock acquire failed — bu tur atlanıyor (güvenli taraf): {}", e.getMessage());
            return false;
        }
    }

    private void releaseSchedulerLock(String lockName) {
        try {
            jdbcTemplate.update(
                "DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?", lockName, INSTANCE_ID);
        } catch (Exception e) {
            log.warn("Failed to release scheduler lock '{}': {}", lockName, e.getMessage());
        }
    }

    // ── Status & helpers ──────────────────────────────────────────────────────

    /**
     * Sertifika süpürmesinin SIRADAKİ çalışma zamanı (UTC ISO) — "Sonraki kontrol" künyesinin
     * tek kaynağı.
     *
     * <p>Cron ifadesi yapılandırmadan okunur, böylece saatlik varsayılan değiştirilirse ekran da
     * onunla birlikte değişir. Ayrıştırılamayan ifade ekranı DÜŞÜRMEZ, null döner ve arayüz
     * alanı hiç çizmez. Not: {@code @Scheduled} bu ifadeyi sunucunun varsayılan saat dilimiyle
     * yorumluyor (zone verilmemiş), hesap da öyle yapılır — aksi halde gösterilen saat gerçek
     * çalışma anından kayardı.
     */
    public String nextCertificateSweepAt() {
        try {
            String cron = (sweepCron == null || sweepCron.isBlank()) ? "0 0 * * * *" : sweepCron;
            var next = org.springframework.scheduling.support.CronExpression.parse(cron)
                    .next(java.time.ZonedDateTime.now());
            return next == null ? null
                    : next.withZoneSameInstant(java.time.ZoneOffset.UTC)
                          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        } catch (Exception e) {
            log.debug("Sıradaki süpürme zamanı hesaplanamadı: {}", e.toString());
            return null;
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_run",       lastRun.get() != null ? lastRun.get().toString() : "Not yet run");
        m.put("next_run",       nextCertificateSweepAt());
        m.put("active_domains", inventoryRepo.countByActiveTrue());
        m.put("schedule",       "Hourly (top of every hour) + stale sweep every 5 minutes");
        m.put("running",        running.get());
        m.put("current_run_id", currentRunId.get());
        m.put("last_run_id",    lastRunId.get());
        m.put("instance_id",    INSTANCE_ID);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> h = new LinkedHashMap<>();

        // Scheduler state
        Map<String, Object> schedulerMap = new LinkedHashMap<>();
        schedulerMap.put("running",        running.get());
        schedulerMap.put("current_run_id", currentRunId.get());
        schedulerMap.put("last_run_id",    lastRunId.get());
        schedulerMap.put("last_run",       lastRun.get() != null ? lastRun.get().toString() : null);
        // Eskiden "sonraki saat başı" varsayılıyordu; cron yapılandırılabilir olduğu için
        // (ör. yarım saatte bir) bu yanlış zaman gösterebiliyordu — artık ifadeden hesaplanıyor.
        schedulerMap.put("next_run",       nextCertificateSweepAt());
        schedulerMap.put("instance_id",    INSTANCE_ID);
        schedulerMap.put("active_domains", inventoryRepo.countByActiveTrue());
        h.put("scheduler", schedulerMap);

        // Distributed lock state
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT locked_by, locked_until FROM scheduler_lock WHERE name = ?", "cert-check");
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                Map<String, Object> lockMap = new LinkedHashMap<>();
                lockMap.put("held",        true);
                lockMap.put("locked_by",   row.get("locked_by"));
                lockMap.put("locked_until",row.get("locked_until"));
                lockMap.put("held_by_me",  INSTANCE_ID.equals(row.get("locked_by")));
                h.put("lock", lockMap);
            } else {
                h.put("lock", Map.of("held", false));
            }
        } catch (Exception e) {
            h.put("lock", Map.of("held", false, "error", e.getMessage()));
        }

        // Haftalık erişilebilirlik scheduler kartı — açık/duraklatılmış, cron, sıradaki/son çalışma + kilit (şu an çalışıyor mu)
        try {
            Map<String, Object> wa = new LinkedHashMap<>(weeklyAvailabilityReportService.schedulerHealth());
            boolean lockHeld = false;
            try {
                List<Map<String, Object>> waRows = jdbcTemplate.queryForList(
                    "SELECT locked_by FROM scheduler_lock WHERE name = ?", "weekly-availability");
                lockHeld = !waRows.isEmpty();
            } catch (Exception ignore) { /* lock tablosu yoksa/erişilemezse running=false varsay */ }
            wa.put("running", lockHeld);
            h.put("weekly_availability", wa);
        } catch (Exception e) {
            h.put("weekly_availability", Map.of("error", String.valueOf(e.getMessage())));
        }

        // HikariCP pool stats
        try {
            if (dataSource instanceof HikariDataSource hds) {
                var pool = hds.getHikariPoolMXBean();
                Map<String, Object> poolMap = new LinkedHashMap<>();
                poolMap.put("active",   pool.getActiveConnections());
                poolMap.put("idle",     pool.getIdleConnections());
                poolMap.put("total",    pool.getTotalConnections());
                poolMap.put("waiting",  pool.getThreadsAwaitingConnection());
                poolMap.put("max_size", hds.getMaximumPoolSize());
                h.put("pool", poolMap);
            }
        } catch (Exception e) {
            h.put("pool", Map.of("error", e.getMessage()));
        }

        // certCheckExecutor task queue stats
        try {
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("queue_size",      certCheckExecutor.getQueueSize());
            ex.put("queue_capacity",  certCheckExecutor.getQueueCapacity());
            ex.put("active_count",    certCheckExecutor.getActiveCount());
            ex.put("pool_size",       certCheckExecutor.getPoolSize());
            ex.put("core_pool_size",  certCheckExecutor.getCorePoolSize());
            ex.put("max_pool_size",   certCheckExecutor.getMaxPoolSize());
            ex.put("completed_tasks", certCheckExecutor.getThreadPoolExecutor().getCompletedTaskCount());
            ex.put("jvm_start_time", ISO.format(Instant.ofEpochMilli(
                    ManagementFactory.getRuntimeMXBean().getStartTime())));
            h.put("executor_pool", ex);
        } catch (Exception e) {
            h.put("executor_pool", Map.of("error", e.getMessage()));
        }

        // JVM memory
        Runtime rt = Runtime.getRuntime();
        long maxMem   = rt.maxMemory();
        long totalMem = rt.totalMemory();
        long freeMem  = rt.freeMemory();
        long usedMem  = totalMem - freeMem;
        Map<String, Object> memMap = new LinkedHashMap<>();
        memMap.put("used_mb",  usedMem  / (1024 * 1024));
        memMap.put("free_mb",  freeMem  / (1024 * 1024));
        memMap.put("total_mb", totalMem / (1024 * 1024));
        memMap.put("max_mb",   maxMem   / (1024 * 1024));
        memMap.put("used_pct", maxMem > 0 ? (int)(usedMem * 100L / maxMem) : 0);
        h.put("memory", memMap);

        // Scan statistics
        LocalDateTime lr = lastRun.get();
        boolean scanAlarm = !running.get() && lr != null
                && ChronoUnit.HOURS.between(lr, LocalDateTime.now(ZoneOffset.UTC)) >= 2;
        Map<String, Object> scanMap = new LinkedHashMap<>();
        scanMap.put("last_run",    lr != null ? lr.toString() : null);
        scanMap.put("duration_ms", lastRunDurationMs.get());
        scanMap.put("total",       lastRunTotal.get());
        scanMap.put("warnings",    lastRunWarnings.get());
        scanMap.put("errors",      lastRunErrors.get());
        scanMap.put("last_failure", lastRunFailure.get());   // null = son tur temiz bitti
        h.put("scan",       scanMap);
        h.put("scan_alarm", scanAlarm);

        // Denetim izi boşluğu: DB'ye yazılamayıp fallback dosyasına düşen kayıtlar. Bu dosyayı
        // GERİ OKUYAN hiçbir kod yok; sayaç olmadan kayıp tamamen görünmezdi (>0 = elle inceleme).
        try {
            h.put("audit_fallback_pending", auditService.pendingFallbackAuditCount());
        } catch (Exception e) {
            h.put("audit_fallback_pending", -1L);   // bilinmiyor ≠ temiz
        }

        h.put("timestamp", ISO.format(Instant.now()));
        return h;
    }

    /** Force-releases the scheduler lock and resets the in-process guard. ADMIN only. */
    public void forceReleaseLock() {
        try {
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ?", "cert-check");
        } catch (Exception e) {
            log.warn("forceReleaseLock: could not delete lock row: {}", e.getMessage());
        }
        running.set(false);
        currentRunId.set("");
        log.warn("Scheduler lock force-released by admin [instance={}]", INSTANCE_ID);
    }

    private List<Map<String, Object>> loadDomainsFromInventory() {
        List<CertificateInventory> items = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory item : items) {
            // Map.of null DEĞER KABUL ETMEZ; zaman aşımı çoğu kayıtta boştur (global ayar
            // kullanılır) — o yüzden değiştirilebilir harita ve yalnız DOLU ise konur.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain",    item.getDomain());
            row.put("port",      item.getPort());
            row.put("use_proxy", Boolean.TRUE.equals(item.getUseProxy()));
            row.put("tls_mode",  item.getTlsMode() == null ? "" : item.getTlsMode());
            if (item.getTimeoutSeconds() != null) row.put("timeout_seconds", item.getTimeoutSeconds());
            result.add(row);
        }
        log.info("Loaded {} active domains from inventory", result.size());
        return result;
    }

    /** Map.of rejects nulls, so inventory maps carry "" for unset tls_mode. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private void ensureDefaultThreshold() {
        if (thresholdRepo.count() == 0) {
            AlertThreshold t = new AlertThreshold();
            t.setName("default");
            t.setWarningDays(defaultWarningDays);
            t.setHighDays(defaultHighDays);
            t.setCriticalDays(defaultCriticalDays);
            t.setReAlertIntervalHours(defaultReAlertHours);
            t.setActive(true);
            thresholdRepo.save(t);
            log.info("Default alert threshold created (warning={}d, high={}d, critical={}d, reAlert={}h)",
                    defaultWarningDays, defaultHighDays, defaultCriticalDays, defaultReAlertHours);
        }
    }

    /** Per-monitör kontrol sıklığı kapısı: monitör son kontrolünden bu yana intervalSeconds dolmadıysa bu
     *  sweep'te ATLA (gerçek "check frequency"). True dönerse "şimdi kontrol edildi" olarak işaretler. */
    /** checkDue() durum map'inden canlı monitör setinde OLMAYAN anahtarları atar; atılan sayıyı döner.
     *  Silinen monitörlerin "type:id" anahtarları aksi halde süresiz birikirdi (uzun uptime sızıntısı). */
    int pruneMonitorCheckState(java.util.Set<String> liveKeys) {
        int before = lastMonitorCheckAt.size();
        lastMonitorCheckAt.keySet().retainAll(liveKeys);
        return before - lastMonitorCheckAt.size();
    }

    /** Tüm monitör tiplerinin canlı "type:id" anahtarları (checkDue ile aynı format). Gecelik maliyet önemsiz. */
    private java.util.Set<String> collectLiveMonitorKeys() {
        java.util.Set<String> live = new java.util.HashSet<>();
        portMonitorRepo.findAll().forEach(m -> live.add("port:" + m.getId()));
        keywordMonitorRepo.findAll().forEach(m -> live.add("keyword:" + m.getId()));
        httpMonitorRepo.findAll().forEach(m -> live.add("http:" + m.getId()));
        domainMonitorRepo.findAll().forEach(m -> live.add("domain:" + m.getId()));
        pingMonitorRepo.findAll().forEach(m -> live.add("ping:" + m.getId()));
        dnsMonitorRepo.findAll().forEach(m -> live.add("dns:" + m.getId()));
        pageMonitorRepo.findAll().forEach(m -> live.add("page:" + m.getId()));
        pageSpeedMonitorRepo.findAll().forEach(m -> live.add("pagespeed:" + m.getId()));
        scriptedMonitorRepo.findAll().forEach(m -> live.add("scripted:" + m.getId()));
        return live;
    }

    /** Ağ kontrolünü certCheckExecutor'a (20/50, kuyruk 1000) gönderir; dönen Supplier.get() join eder
     *  ve CompletionException'ı soyar → mevcut per-monitör catch blokları orijinal hatayı aynen görür.
     *  4-thread'lik scheduling pool'u monitör sayısıyla büyüyen bloklu ağ I/O'suyla doymasın (F1, CPU
     *  denetimi). Kuyruk dolarsa CallerRunsPolicy task'ı sweep thread'inde koşturur → sweep bugünkü
     *  sıralı davranışa kendiliğinden geri düşer (backpressure); RejectedExecutionException imkânsız. */
    private <R> java.util.function.Supplier<R> startNetworkCheck(java.util.function.Supplier<R> task) {
        if (certCheckExecutor == null) return task;   // savunma (test/bootstrap)
        java.util.concurrent.CompletableFuture<R> f =
                java.util.concurrent.CompletableFuture.supplyAsync(task, certCheckExecutor);
        return () -> {
            try {
                return f.join();
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable c = ce.getCause() != null ? ce.getCause() : ce;
                if (c instanceof RuntimeException re) throw re;
                if (c instanceof Error err) throw err;
                throw new RuntimeException(c);
            }
        };
    }

    /** Öksüz-alarm temizliği (monitör findAll + TÜM açık alarm taraması) her 30 sn'lik sweep'te
     *  koşmasın: amacı rename/silme sonrası takılı alarmları kapatmak — 5 dk kadans fazlasıyla
     *  yeterli (F5). İlk çağrı her zaman due (restart sonrası hemen temizlik). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastOrphanCleanupAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${site.monitor.orphan-cleanup-interval-ms:300000}")
    private long orphanCleanupIntervalMs;

    private boolean orphanCleanupDue(String type) {
        long now = System.currentTimeMillis();
        Long last = lastOrphanCleanupAt.get(type);
        if (last != null && now - last < orphanCleanupIntervalMs) return false;
        lastOrphanCleanupAt.put(type, now);
        return true;
    }

    /** Manuel tetik (UI "şimdi kontrol et"): raw Thread yerine certCheckExecutor'a atılır (F4);
     *  runCheck içindeki scheduler kilidi mükerrer tetikleri no-op'lar. */
    public void triggerManualCheck() {
        certCheckExecutor.execute(this::runCheck);
    }

    /**
     * Yeni envanter eklenince ANINDA tek-domain sertifika kontrolü (async, certCheckExecutor).
     * latest_checks satırı saniyeler içinde oluşur → kalem Genel Bakış'ta gecikmeden görünür ve
     * sonraki kontrollere dahil olur (aksi halde yalnız 5-dk stale sweep / saatlik sweep yakalıyordu).
     * Hata yutulur — envanter ekleme akışını asla kırmaz.
     */
    public void checkSingleDomainAsync(String domain, int port, boolean forceProxy, String tlsMode) {
        if (certCheckExecutor == null || domain == null || domain.isBlank()) return;
        int p = port > 0 ? port : 443;
        certCheckExecutor.execute(() -> {
            try {
                Map<String, Object> result = new java.util.LinkedHashMap<>(checkerService.check(domain, p, forceProxy, tlsMode));
                result.put("run_id", "inventory-add");
                certService.saveResult(result);
                certService.evictAllCaches();
                log.info("Yeni envanter anında kontrol edildi: {}:{}", domain, p);
            } catch (Exception e) {
                log.warn("Yeni envanter anında kontrol başarısız {}: {}", domain, e.getMessage());
            }
        });
    }

    /** GRID: nextDue'yu now'u geçene dek interval adımlarıyla ilerletir (catch-up clamp — kapalılık
     *  sonrası burst yok, tek çalıştırma + gelecekteki ilk grid noktası). Saf/statik → birim test edilir. */
    static long nextDueAfter(long nextDue, long now, long intervalMs) {
        while (nextDue <= now) nextDue += intervalMs;
        return nextDue;
    }

    /** Per-monitör kontrol sıklığı kapısı — GRID semantiği (2026-08-03): map SONRAKİ VADEYİ tutar ve vade
     *  hep sabit interval adımlarıyla ilerler. Eski davranış son FİİLÎ çalıştırmayı damgalıyordu; sweep
     *  periyodu (fixedDelay + en yavaş kontrol) 60sn'yi aşınca 5 dk'lık monitör ~6+ dk'da bir koşuyordu.
     *  Grid'de gecikme bir sonraki vadeye taşınmaz → uzun vadeli ortalama tam olarak intervalSeconds olur. */
    private boolean checkDue(String type, Long id, Integer intervalSeconds) {
        if (id == null) return true;
        int sec = intervalSeconds != null && intervalSeconds > 0 ? intervalSeconds : 60;
        long intervalMs = sec * 1000L;
        long nowMs = System.currentTimeMillis();
        String key = type + ":" + id;
        Long nextDue = lastMonitorCheckAt.get(key);
        if (nextDue == null) {                                   // ilk görüş: hemen çalıştır, grid'i başlat
            lastMonitorCheckAt.put(key, nextDueAfter(nowMs, nowMs, intervalMs));
            return true;
        }
        if (nowMs < nextDue) return false;                       // vade dolmadı → bu sweep'te atla
        lastMonitorCheckAt.put(key, nextDueAfter(nextDue, nowMs, intervalMs));
        return true;
    }

    // ── Port / DNS / Uptime periodic checks ──────────────────────────────────

    @Scheduled(fixedDelayString = "${site.monitor.uptime.interval-ms:300000}", initialDelayString = "60000")
    public void runUptimeChecks() {
        if (!appSettings.getBoolean("site.monitor.uptime.alert-enabled", true)) return;   // izleme duraklatıldı → kontrol+alarm yok
        // HA: tüm-tur dağıtık kilit — 2+ pod'da bir turu yalnız bir pod çalıştırır (mükerrer probe/geçmiş kaydı önlenir).
        if (!tryAcquireSchedulerLock("uptime-sweep", sweepLockTtlMinutes)) {
            log.debug("Uptime sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runUptimeChecksLocked();
        } finally {
            releaseSchedulerLock("uptime-sweep");
        }
    }

    /** Tüm-tur kilit içinde çalışan gerçek uptime sweep gövdesi (bkz. runUptimeChecks). */
    private void runUptimeChecksLocked() {
        List<CertificateInventory> active = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        if (active.isEmpty()) return;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        // Faz 1: ağ kontrolleri certCheckExecutor'da paralel başlar (F1); Faz 2: sonuçlar sıralı işlenir.
        List<Map.Entry<CertificateInventory, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (CertificateInventory inv : active) {
            int port = inv.getPort() != null ? inv.getPort() : 443;
            started.add(Map.entry(inv, startNetworkCheck(() -> recheckUptime(inv.getDomain(), port))));
        }
        for (var entry : started) {
            CertificateInventory inv = entry.getKey();
            int port = inv.getPort() != null ? inv.getPort() : 443;
            try {
                Map<String, Object> r = entry.getValue().get();
                activityLog.recordCheck(ActivityLogService.UPTIME, null, inv.getDomain(),
                        inv.getDomain() + ":" + port, inv.getTeamId(), false, "scheduler", r);
                sweep.add(new MonitoringOutageService.SweepItem(
                        EscalationService.TYPE_ACCESSIBILITY, inv.getDomain(), String.valueOf(port),
                        "up".equals(r.get("status")), (String) r.get("error"),
                        Map.of("port", port),
                        () -> recheckUptime(inv.getDomain(), port)));
            } catch (Exception e) {
                log.warn("Uptime check failed for {}:{}: {}", inv.getDomain(), port, e.getMessage());
            }
        }
        // Erişilebilirlik alarm pipeline'ı — hatası sweep'i asla kırmasın
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, sweep);
        } catch (Exception e) {
            log.warn("Uptime outage processing failed: {}", e.getMessage(), e);
        }
        log.debug("Uptime HTTP checks complete: {} domains", active.size());
    }

    /** Uptime check + uptime_checks persist'i — hem sweep hem teyit re-check'leri
     *  bu yoldan geçer (teyit izi Durum izleme geçmişinde görünür). */
    private Map<String, Object> recheckUptime(String domain, int port) {
        Map<String, Object> r = uptimeHttpCheckerService.check(domain, port, 10000);
        try {
            UptimeCheck check = new UptimeCheck();
            check.setDomain(domain);
            check.setPort(port);
            check.setStatus((String) r.getOrDefault("status", "down"));
            check.setResponseMs(r.get("response_ms") != null
                    ? ((Number) r.get("response_ms")).longValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(ISO.format(Instant.now()));
            check.setMaintenance(maintenanceService.isUnderMaintenance(domain));   // bakımdaysa uptime %'den hariç
            uptimeCheckRepo.save(check);
        } catch (Exception e) {
            log.warn("Uptime kaydı yazılamadı: {}:{} — {}", domain, port, e.getMessage());
        }
        return r;
    }

    @Scheduled(fixedDelayString = "${site.monitor.port.interval-ms:30000}", initialDelayString = "45000")
    public void runPortChecks() {
        if (!appSettings.getBoolean("site.monitor.port.alert-enabled", true)) return;   // izleme duraklatıldı → kontrol+alarm yok
        // HA: tüm-tur dağıtık kilit — 2+ pod'da bir turu yalnız bir pod çalıştırır.
        if (!tryAcquireSchedulerLock("port-sweep", sweepLockTtlMinutes)) {
            log.debug("Port sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runPortChecksLocked();
        } finally {
            releaseSchedulerLock("port-sweep");
        }
    }

    /** Tüm-tur kilit içinde çalışan gerçek port sweep gövdesi (bkz. runPortChecks). */
    private void runPortChecksLocked() {
        List<PortMonitor> monitors = portMonitorRepo.findByActiveTrue();
        boolean cleanupDue = orphanCleanupDue("port");
        // Öksüz port alarmı temizliği: host rename/silme sonrası recovery'nin kapatamadığı açık PORT_DOWN alarmı (ping ile paritede).
        if (cleanupDue) try {
            java.util.Set<String> existingHosts = portMonitorRepo.findAll().stream()
                    .map(PortMonitor::getHost).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedPortAlerts(existingHosts);
        } catch (Exception e) {
            log.warn("Öksüz port alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        // Skip monitors whose host is no longer in active inventory (soft-deleted / inactive)
        Set<String> activeDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .map(CertificateInventory::getDomain).collect(Collectors.toSet());
        int checked = 0, skipped = 0;
        // Envanteri pasifleşen monitör hiç kontrol edilmiyor → recovery de gelmiyor. Açık alarmı sessizce
        // kapatılmazsa SONSUZA KADAR açık kalır (öksüz temizliği monitör satırı durduğu için dokunmaz).
        Set<String> skippedHosts = new LinkedHashSet<>();
        Set<String> checkableHosts = new HashSet<>();
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slowSweep = new ArrayList<>();
        // Faz 1: gating sweep thread'inde; ağ kontrolü certCheckExecutor'da paralel başlar (F1).
        List<Map.Entry<PortMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (PortMonitor m : monitors) {
            if (!Boolean.TRUE.equals(m.getStandalone()) && !activeDomains.contains(m.getHost())) {   // standalone → envanter-skip baypas
                skipped++;
                if (m.getHost() != null) skippedHosts.add(m.getHost());
                continue;
            }
            if (m.getHost() != null) checkableHosts.add(m.getHost());   // checkDue ile atlanan da KONTROL EDİLEBİLİR sayılır
            if (!checkDue("port", m.getId(), m.getIntervalSeconds())) continue;   // aralığı dolmadı → bu sweep'te atla
            started.add(Map.entry(m, startNetworkCheck(() -> recheckPort(m))));
        }
        skippedHosts.removeAll(checkableHosts);   // aynı host'u izleyen aktif monitör varsa alarmı bırak
        if (cleanupDue && !skippedHosts.isEmpty()) {
            log.info("Port izlemesi envanterde aktif olmadığı için atlandı ({}): {}", skippedHosts.size(), skippedHosts);
            for (String host : skippedHosts) {
                escalationService.resolveOpenAlertsSilently(host,
                        Set.of(EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW),
                        "Sistem (envanterde aktif değil — izleme durdu)");
            }
        }
        // Faz 2: sonuçlar sweep thread'inde SIRALI işlenir — item/alarm semantiği birebir korunur.
        for (var entry : started) {
            PortMonitor m = entry.getKey();
            try {
                Map<String, Object> r = entry.getValue().get();
                // ctxExtra: port/protocol + per-monitor teyit/recovery override'ları (ping/keyword ile aynı → tunable + aktif recovery)
                // Hata fırlatan monitör item üretmez — yanlış all-up resolve olmaz
                sweep.add(portSweepItem(m, r));
                // PORT_SLOW: yanıt süresi eşiği (opsiyonel; kapalı/ölçülemedi/erişim-hatası → sentetik up = lingering kurtar)
                Map<String, Object> slowCtx = new LinkedHashMap<>();
                slowCtx.put("port", m.getPort());
                slowCtx.put("protocol", m.getProtocol() != null ? m.getProtocol() : "TCP");
                slowCtx.put("monitor_id", m.getId());
                slowCtx.put("monitor_confirm_attempts", m.getConfirmAttempts());
                slowCtx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
                slowCtx.put("monitor_recovery_checks", m.getRecoveryChecks());
                slowCtx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
                if (m.getTeamId() != null) slowCtx.put("team_id", m.getTeamId());
                if (m.getNotificationGroupId() != null) slowCtx.put("notification_group_id", m.getNotificationGroupId());
                int slowTh = m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : 3000;
                slowCtx.put("threshold_ms", slowTh);
                Long respMs = r.get("response_ms") instanceof Number rn ? rn.longValue() : null;
                if (respMs != null) slowCtx.put("response_ms", respMs);
                boolean slowDown = Boolean.TRUE.equals(m.getSlowResponseEnabled())
                        && r.get("error") == null && respMs != null && respMs > slowTh;
                slowSweep.add(new MonitoringOutageService.SweepItem(
                        EscalationService.TYPE_PORT_SLOW, m.getHost(),
                        respMs != null ? respMs + " ms" : "slow",
                        !slowDown, null,
                        chanCtx(slowCtx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalPortSlow(m)));
                checked++;
            } catch (Exception e) {
                log.warn("Port check failed for {}:{}: {}", m.getHost(), m.getPort(), e.getMessage());
            }
        }
        // Port kesinti alarm pipeline'ı — hatası sweep'i asla kırmasın
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PORT_DOWN, sweep);
        } catch (Exception e) {
            log.warn("Port outage processing failed: {}", e.getMessage(), e);
        }
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PORT_SLOW, slowSweep);
        } catch (Exception e) {
            log.warn("Port slow outage processing failed: {}", e.getMessage());
        }
        log.debug("Port checks complete: {} monitors ({} skipped — not in active inventory)", checked, skipped);
    }

    /** Port check + port_checks persist'i — sweep ve teyit re-check'leri bu
     *  yoldan geçer (teyit izi port geçmişinde görünür). {"status","error"} döner. */
    private Map<String, Object> recheckPort(PortMonitor m) {
        Map<String, Object> r = portCheckerService.check(m);
        boolean open = Boolean.TRUE.equals(r.getOrDefault("open", false));
        try {
            PortCheck check = new PortCheck();
            check.setMonitorId(m.getId());
            check.setOpen(open);
            check.setResponseMs(r.get("response_ms") != null ? ((Number) r.get("response_ms")).longValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(ISO.format(Instant.now()));
            portCheckRepo.save(check);
        } catch (Exception e) {
            log.warn("Port kaydı yazılamadı: {}:{} — {}", m.getHost(), m.getPort(), e.getMessage());
        }
        activityLog.recordCheck(ActivityLogService.PORT, m.getId(), m.getName(),
                m.getHost() + ":" + m.getPort(), m.getTeamId(), false, "scheduler", r);
        return portOutcome(r);
    }

    /** Yavaş yanıt yeniden-ölçümü (PORT_SLOW confirm/recovery re-check'i) — taze check, PortCheck PERSIST ETMEZ.
     *  {"status":"up"|"down","response_ms"?,"threshold_ms"} döner. slowResponseEnabled kapalı/erişim-hatası/kapalı port → up. */
    private Map<String, Object> evalPortSlow(PortMonitor m) {
        Map<String, Object> out = new LinkedHashMap<>();
        int th = m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : 3000;
        out.put("threshold_ms", th);
        if (!Boolean.TRUE.equals(m.getSlowResponseEnabled())) { out.put("status", "up"); return out; }
        Map<String, Object> r = portCheckerService.check(m);
        Long ms = r.get("response_ms") instanceof Number n ? n.longValue() : null;
        if (ms != null) out.put("response_ms", ms);
        boolean slow = r.get("error") == null && Boolean.TRUE.equals(r.get("open")) && ms != null && ms > th;
        out.put("status", slow ? "down" : "up");
        return out;
    }

    // ── Keyword monitor sweep (serbest-form; envanter filtresi YOK) ──────────────
    @Scheduled(fixedDelayString = "${site.monitor.keyword.interval-ms:30000}", initialDelayString = "55000")
    public void runKeywordChecks() {
        if (!appSettings.getBoolean("site.monitor.keyword.alert-enabled", true)) return;   // izleme duraklatıldı → kontrol+alarm yok
        // HA: tüm-tur dağıtık kilit — 2+ pod'da bir turu yalnız bir pod çalıştırır.
        if (!tryAcquireSchedulerLock("keyword-sweep", sweepLockTtlMinutes)) {
            log.debug("Keyword sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runKeywordChecksLocked();
        } finally {
            releaseSchedulerLock("keyword-sweep");
        }
    }

    /** Tüm-tur kilit içinde çalışan gerçek keyword sweep gövdesi (bkz. runKeywordChecks). */
    private void runKeywordChecksLocked() {
        List<KeywordMonitor> monitors = keywordMonitorRepo.findByActiveTrue();
        // Öksüz keyword alarmı temizliği: url rename/silme sonrası recovery'nin kapatamadığı açık KEYWORD alarmı (ping ile paritede).
        if (orphanCleanupDue("keyword")) try {
            java.util.Set<String> existingUrls = keywordMonitorRepo.findAll().stream()
                    .map(KeywordMonitor::getUrl).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedKeywordAlerts(existingUrls);
        } catch (Exception e) {
            log.warn("Öksüz keyword alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        int checked = 0;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slowSweep = new ArrayList<>();
        // Faz 1: gating sweep thread'inde; ağ kontrolü certCheckExecutor'da paralel başlar (F1).
        List<Map.Entry<KeywordMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (KeywordMonitor m : monitors) {
            if (!checkDue("keyword", m.getId(), m.getIntervalSeconds())) continue;   // aralığı dolmadı → bu sweep'te atla
            started.add(Map.entry(m, startNetworkCheck(() -> recheckKeyword(m))));
        }
        // Faz 2: sonuçlar sweep thread'inde SIRALI işlenir.
        for (var entry : started) {
            KeywordMonitor m = entry.getKey();
            try {
                Map<String, Object> r = entry.getValue().get();
                sweep.add(keywordSweepItem(m, r));
                // KEYWORD_SLOW: yanıt süresi eşiği (opsiyonel; kapalı/ölçülemedi/HTTP-hatası → sentetik up = lingering kurtar)
                Map<String, Object> slowCtx = new LinkedHashMap<>();
                slowCtx.put("url", m.getUrl());
                slowCtx.put("monitor_id", m.getId());
                slowCtx.put("monitor_confirm_attempts", m.getConfirmAttempts());
                slowCtx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
                slowCtx.put("monitor_recovery_checks", m.getRecoveryChecks());
                slowCtx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
                if (m.getTeamId() != null) slowCtx.put("team_id", m.getTeamId());
                if (m.getNotificationGroupId() != null) slowCtx.put("notification_group_id", m.getNotificationGroupId());
                int slowTh = m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : 3000;
                slowCtx.put("threshold_ms", slowTh);
                Long respMs = r.get("response_ms") instanceof Number rn ? rn.longValue() : null;
                if (respMs != null) slowCtx.put("response_ms", respMs);
                boolean slowDown = Boolean.TRUE.equals(m.getSlowResponseEnabled())
                        && r.get("error") == null && respMs != null && respMs > slowTh;
                slowSweep.add(new MonitoringOutageService.SweepItem(
                        EscalationService.TYPE_KEYWORD_SLOW, m.getUrl(),
                        respMs != null ? respMs + " ms" : "slow",
                        !slowDown, null,
                        chanCtx(slowCtx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalKeywordSlow(m)));
                checked++;
            } catch (Exception e) {
                log.warn("Keyword check failed for {}: {}", m.getUrl(), e.getMessage());
            }
        }
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_KEYWORD, sweep);
        } catch (Exception e) {
            log.warn("Keyword outage processing failed: {}", e.getMessage(), e);
        }
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_KEYWORD_SLOW, slowSweep);
        } catch (Exception e) {
            log.warn("Keyword slow outage processing failed: {}", e.getMessage());
        }
        log.debug("Keyword checks complete: {} monitors", checked);
    }

    /** Keyword check + keyword_results persist'i. Koşul (içerir/içermez) burada uygulanır;
     *  HTTP hatası → ok=false (down). {"status","error"} döner. */
    private Map<String, Object> recheckKeyword(KeywordMonitor m) {
        int timeout = m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000;
        Map<String, Object> r = keywordCheckerService.check(m.getUrl(), m.getKeyword(), timeout, m.getCustomHeaders(),
                Boolean.TRUE.equals(m.getCaseSensitive()));
        boolean found = Boolean.TRUE.equals(r.getOrDefault("found", false));
        int count = r.get("count") instanceof Number cn ? cn.intValue() : (found ? 1 : 0);
        int threshold = m.getMatchCount() != null ? m.getMatchCount() : 1;
        boolean hadError = r.get("error") != null;
        // Adet koşulu: SAĞLIKLI = count [operatör] threshold (HTTP hatası → down).
        boolean ok = !hadError && KeywordCheckerService.evaluate(count, m.getMatchOperator(), threshold);
        try {
            KeywordResult res = new KeywordResult();
            res.setMonitorId(m.getId());
            res.setFound(found);
            res.setOccurrences(count);
            res.setOk(ok);
            res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
            res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
            res.setSnippet((String) r.get("snippet"));
            res.setError((String) r.get("error"));
            res.setCheckedAt(ISO.format(Instant.now()));
            keywordResultRepo.save(res);
        } catch (Exception e) {
            log.warn("Keyword kaydı yazılamadı: {} — {}", m.getUrl(), e.getMessage());
        }
        r.put("ok", ok);   // aktivite özeti için sağlıklı-mı bayrağı (found + adet koşulu)
        activityLog.recordCheck(ActivityLogService.KEYWORD, m.getId(), m.getName(),
                m.getUrl(), m.getTeamId(), false, "scheduler", r);
        return keywordOutcome(m, r);
    }

    // ── HTTP / Website monitor sweep (serbest-form; envanter filtresi YOK) ────────
    @Scheduled(fixedDelayString = "${site.monitor.http.interval-ms:30000}", initialDelayString = "75000")
    public void runHttpChecks() {
        if (!appSettings.getBoolean("site.monitor.http.alert-enabled", true)) return;   // izleme duraklatıldı → kontrol+alarm yok
        if (!tryAcquireSchedulerLock("http-sweep", sweepLockTtlMinutes)) {
            log.debug("HTTP sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runHttpChecksLocked();
        } finally {
            releaseSchedulerLock("http-sweep");
        }
    }

    /** Tüm-tur kilit içinde çalışan gerçek HTTP uptime sweep gövdesi (bkz. runHttpChecks). */
    private void runHttpChecksLocked() {
        List<HttpMonitor> monitors = httpMonitorRepo.findByActiveTrue();
        // Öksüz HTTP alarmı temizliği: url rename/silme sonrası recovery'nin kapatamadığı açık alarm (keyword/ping ile paritede).
        if (orphanCleanupDue("http")) try {
            java.util.Set<String> existingUrls = httpMonitorRepo.findAll().stream()
                    .map(HttpMonitor::getUrl).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedHttpAlerts(existingUrls);
        } catch (Exception e) {
            log.warn("Öksüz HTTP alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        int checked = 0;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        // Faz 1: gating sweep thread'inde; ağ kontrolü certCheckExecutor'da paralel başlar (F1).
        List<Map.Entry<HttpMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (HttpMonitor m : monitors) {
            if (!checkDue("http", m.getId(), m.getIntervalSeconds())) continue;   // aralığı dolmadı → bu sweep'te atla
            started.add(Map.entry(m, startNetworkCheck(() -> recheckHttp(m))));
        }
        // Faz 2: sonuçlar sweep thread'inde SIRALI işlenir.
        for (var entry : started) {
            HttpMonitor m = entry.getKey();
            try {
                Map<String, Object> r = entry.getValue().get();
                sweep.add(httpSweepItem(m, r));
                checked++;
            } catch (Exception e) {
                log.warn("HTTP check failed for {}: {}", m.getUrl(), e.getMessage());
            }
        }
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_HTTP_DOWN, sweep);
        } catch (Exception e) {
            log.warn("HTTP outage processing failed: {}", e.getMessage(), e);
        }
        log.debug("HTTP checks complete: {} monitors", checked);
    }

    /** Tek DNS monitörü için BİRİNCİL (çözümleme hatası) değerlendirme öğesi — sweep VE manuel
     *  çalıştırma bunu PAYLAŞIR. */
    private MonitoringOutageService.SweepItem dnsFailureSweepItem(DnsMonitor m, Map<String, Object> r,
                                                                  boolean success) {
        Map<String, Object> failCtx = new LinkedHashMap<>();
        failCtx.put("record_type", m.getRecordType());
        failCtx.put("monitor_id", m.getId());   // e-posta CTA deep-link (?tab=dns&monitor=<id>)
        if (m.getTeamId() != null) failCtx.put("team_id", m.getTeamId());   // standalone → alarm takıma
        if (m.getNotificationGroupId() != null) failCtx.put("notification_group_id", m.getNotificationGroupId());
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_DNS_FAILURE, m.getDomain(), m.getRecordType(),
                success, (String) r.get("error"),
                chanCtx(confirmCtx(failCtx, m.getConfirmAttempts(), m.getConfirmIntervalSeconds(),
                        m.getRecoveryChecks(), m.getRecoveryIntervalSeconds()),
                        m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> recheckDns(m));
    }

    // ── Ham kontrol sonucu → sweep şekli ───────────────────────────────────────────────────
    //
    // Manuel "Çalıştır" ucu kontrolü ZATEN koşup satırı kaydediyor. evaluate*Now eskiden
    // recheck*'i çağırıyordu; o da kendi kontrolünü koşup KENDİ satırını yazıyordu → tek tıkla
    // hedefe iki istek, geçmişe iki satır ve uptime yüzdesinin manuel kontrolü ÇİFT sayması.
    // Şekillendirme buraya alındı: recheck* de, manuel yol da aynı saf fonksiyonu kullanır.

    /** HTTP ham sonucu → {"status","error","http_status","response_ms"}. */
    static Map<String, Object> httpOutcome(Map<String, Object> r) {
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        // Yapılandırma hatası (URL'de host yok) kesinti DEĞİL → "up": alarm/teyit zinciri başlamaz,
        // askıda alarm varsa sessizce kapanır. Kontrol kaydı + hata mesajı yine yazıldı.
        boolean cfgError = Boolean.TRUE.equals(r.get("config_error"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", (ok || cfgError) ? "up" : "down");
        out.put("error", cfgError ? null : r.get("error"));
        out.put("http_status", r.get("http_status"));
        out.put("response_ms", r.get("response_ms"));
        return out;
    }

    /** Port ham sonucu → {"status","error","response_ms"}. */
    static Map<String, Object> portOutcome(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", Boolean.TRUE.equals(r.getOrDefault("open", false)) ? "up" : "down");
        out.put("error", r.get("error"));
        out.put("response_ms", r.get("response_ms"));
        return out;
    }

    /** Ping ham sonucu → {"status","error","rtt_ms","packet_loss","na"}. */
    static Map<String, Object> pingOutcome(Map<String, Object> r) {
        boolean up = Boolean.TRUE.equals(r.getOrDefault("up", false));
        boolean na = Boolean.TRUE.equals(r.get("na"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", (up || na) ? "up" : "down");
        out.put("error", r.get("error"));
        out.put("rtt_ms", r.get("rtt_ms"));
        out.put("packet_loss", r.get("packet_loss"));
        out.put("na", na);
        return out;
    }

    /** Keyword ham sonucu → sweep şekli; adet koşulu izlemenin operatör/eşiğinden gelir. */
    static Map<String, Object> keywordOutcome(KeywordMonitor m, Map<String, Object> r) {
        boolean found = Boolean.TRUE.equals(r.getOrDefault("found", false));
        int count = r.get("count") instanceof Number cn ? cn.intValue() : (found ? 1 : 0);
        int threshold = m.getMatchCount() != null ? m.getMatchCount() : 1;
        boolean hadError = r.get("error") != null;
        boolean ok = !hadError && KeywordCheckerService.evaluate(count, m.getMatchOperator(), threshold);
        boolean cfgError = Boolean.TRUE.equals(r.get("config_error"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", (ok || cfgError) ? "up" : "down");
        out.put("error", cfgError ? null : r.get("error"));
        out.put("http_status", r.get("http_status"));
        out.put("response_ms", r.get("response_ms"));
        out.put("snippet", r.get("snippet"));
        out.put("occurrences", count);
        return out;
    }

    /**
     * Manuel "Çalıştır" — DNS'in BİRİNCİL alarmını (çözümleme hatası) zamanlayıcıyla AYNI hatta sokar.
     *
     * <p>Yavaş/beklenmeyen-değer/tutarsızlık alarmları KAPSAM DIŞI: her birinin kendi özel teyit
     * ayarı var (ör. yavaş için 3×60 sn) ve manuel yola taşınmaları ayrı bir ürün kararıdır;
     * onlar zamanlanmış turlarında değerlendirilmeye devam eder.
     */
    @Async("certCheckExecutor")
    public void evaluateDnsNow(DnsMonitor m, Map<String, Object> rawCheckResult) {
        try {
            // HAM sonuc kullanilir: recheckDns {"status","error"} donduruyor ve "success" anahtari
            // YOK — bu yuzden success DAIMA false oluyordu, yani manuel DNS calistirmasi cozumleme
            // basarili olsa bile hata teyit zinciri baslatiyordu. Zamanlayici da (runDnsChecksLocked)
            // ham sonucun "success" anahtarina bakiyor; iki yol artik AYNI ifadeyi kullaniyor.
            Map<String, Object> r = rawCheckResult;
            boolean success = Boolean.TRUE.equals(r.get("success"));
            monitoringOutageService.handleSweepResults(
                    EscalationService.TYPE_DNS_FAILURE, List.of(dnsFailureSweepItem(m, r, success)), true);
        } catch (Exception e) {
            log.warn("Manuel DNS değerlendirmesi başarısız {}: {}", m.getDomain(), e.getMessage());
        }
    }

    /** Tek keyword monitörü için değerlendirme öğesi — sweep VE manuel çalıştırma bunu PAYLAŞIR. */
    private MonitoringOutageService.SweepItem keywordSweepItem(KeywordMonitor m, Map<String, Object> r) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("url", m.getUrl());
        ctx.put("keyword", m.getKeyword());
        ctx.put("condition", m.getAlertCondition());
        ctx.put("operator", m.getMatchOperator());
        ctx.put("match_count", m.getMatchCount());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        if (r.get("http_status") != null) ctx.put("http_status", r.get("http_status"));
        if (r.get("response_ms") != null) ctx.put("response_ms", r.get("response_ms"));
        if (r.get("snippet") != null)     ctx.put("snippet", r.get("snippet"));
        if (r.get("occurrences") != null) ctx.put("occurrences", r.get("occurrences"));
        String kw = m.getKeyword() != null ? m.getKeyword() : "";
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_KEYWORD, m.getUrl(),
                kw.length() > 40 ? kw.substring(0, 40) : kw,
                "up".equals(r.get("status")), (String) r.get("error"),
                chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckKeyword(m));
    }

    /** Manuel "Çalıştır" — bkz. evaluatePageNow. */
    @Async("certCheckExecutor")
    public void evaluateKeywordNow(KeywordMonitor m, Map<String, Object> rawCheckResult) {
        try {
            Map<String, Object> r = keywordOutcome(m, rawCheckResult);
            monitoringOutageService.handleSweepResults(
                    EscalationService.TYPE_KEYWORD, List.of(keywordSweepItem(m, r)), true);
        } catch (Exception e) {
            log.warn("Manuel keyword değerlendirmesi başarısız {}: {}", m.getUrl(), e.getMessage());
        }
    }

    /**
     * Manuel "Çalıştır" — zamanlayıcıyla AYNI değerlendirme hattı. Bu dört türde tek-monitör
     * sweep kurucusu ZATEN vardı; ctx kopyalanmaz, olduğu gibi yeniden kullanılır — aksi halde
     * bir anahtar eksik kalıp alarm sessizce yanlış takıma giderdi.
     *
     * <p>ASENKRON: doğrulama varsayılan 3 × 30 sn sürer. manual=true → toplu-kesinti bastırması atlanır.
     */
    @Async("certCheckExecutor")
    public void evaluatePageNow(com.sitemonitor.model.PageMonitor m, Map<String, Object> checkResult) {
        try {
            List<MonitoringOutageService.SweepItem> down = new ArrayList<>(), integrity = new ArrayList<>();
            addPageSweepItems(m, checkResult, down, integrity);
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGE_DOWN, down, true);
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGE_INTEGRITY, integrity, true);
        } catch (Exception e) {
            log.warn("Manuel sayfa değerlendirmesi başarısız {}: {}", m.getUrl(), e.getMessage());
        }
    }

    /** Manuel "Çalıştır" — bkz. evaluatePageNow. */
    @Async("certCheckExecutor")
    public void evaluatePageSpeedNow(com.sitemonitor.model.PageSpeedMonitor m, Map<String, Object> checkResult) {
        try {
            List<MonitoringOutageService.SweepItem> down = new ArrayList<>(), slow = new ArrayList<>();
            addPageSpeedSweepItems(m, checkResult, down, slow);
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGESPEED_DOWN, down, true);
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGESPEED_SLOW, slow, true);
        } catch (Exception e) {
            log.warn("Manuel sayfa-hızı değerlendirmesi başarısız {}: {}", m.getUrl(), e.getMessage());
        }
    }

    /** Manuel "Çalıştır" — bkz. evaluatePageNow. */
    @Async("certCheckExecutor")
    public void evaluateScriptedNow(com.sitemonitor.model.ScriptedMonitor m, Map<String, Object> checkResult) {
        try {
            List<MonitoringOutageService.SweepItem> fail = new ArrayList<>(), slow = new ArrayList<>();
            addScriptedSweepItems(m, checkResult, fail, slow);
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_SCRIPTED_FAIL, fail, true);
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_SCRIPTED_SLOW, slow, true);
        } catch (Exception e) {
            log.warn("Manuel senaryo değerlendirmesi başarısız {}: {}", m.getName(), e.getMessage());
        }
    }

    /**
     * Tek port monitörü için değerlendirme öğesi — sweep döngüsü VE manuel "Çalıştır" bunu PAYLAŞIR.
     * Manuel yol kendi ctx'ini kursaydı bir anahtar (team_id / notification_group_id) eksik kalıp
     * alarmı SESSİZCE yanlış takıma gönderebilirdi.
     */
    private MonitoringOutageService.SweepItem portSweepItem(PortMonitor m, Map<String, Object> r) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());
        ctx.put("port", m.getPort());
        ctx.put("protocol", m.getProtocol() != null ? m.getProtocol() : "TCP");
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PORT_DOWN, m.getHost(),
                m.getPort() + "/" + m.getProtocol(),
                "up".equals(r.get("status")), (String) r.get("error"),
                chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> recheckPort(m));
    }

    /**
     * Manuel "Çalıştır" — zamanlayıcıyla AYNI değerlendirme hattı (doğrulama + kurtarma).
     * ASENKRON: doğrulama varsayılan 3 × 30 sn sürer. manual=true → toplu-kesinti bastırması atlanır.
     */
    @Async("certCheckExecutor")
    public void evaluatePortNow(PortMonitor m, Map<String, Object> rawCheckResult) {
        try {
            Map<String, Object> r = portOutcome(rawCheckResult);
            monitoringOutageService.handleSweepResults(
                    EscalationService.TYPE_PORT_DOWN, List.of(portSweepItem(m, r)), true);
        } catch (Exception e) {
            log.warn("Manuel port değerlendirmesi başarısız {}: {}", m.getHost(), e.getMessage());
        }
    }

    /** Tek ping monitörü için değerlendirme öğesi — sweep VE manuel çalıştırma bunu PAYLAŞIR. */
    /**
     * Ping yavaşlık hükmü — GÖRECELİ eşik: ölçüm, host'un kendi son N dakikalık ortalamasının
     * %X üstündeyse yavaş.
     *
     * <p><b>Neden sabit ms değil.</b> Port izlemesindeki {@code slowThresholdMs} sabittir; ping'de
     * aynısını yapmak işe yaramaz: aynı eşik yerel bir sunucuda (2 ms) her dalgalanmada öter,
     * denizaşırı bir host'ta (180 ms) hiç ötmez. Taban çizgisi host başına kendiliğinden oluşur.
     *
     * <p><b>Sessiz kalınan haller</b> (hepsi "up" döner, yani alarm YOK):
     * <ul>
     *   <li>özellik kapalı (opt-in),</li>
     *   <li>ölçüm yok — host DOWN ya da ICMP kapalı: erişilemezlik PING_DOWN'ın işi, aynı olayı
     *       iki alarmla anlatmak kullanıcıyı ikinci bildirimle cezalandırır,</li>
     *   <li>taban çizgisi yetersiz ({@code SLOW_BASELINE_MIN_SAMPLES} altında örnek): tek ölçümlük
     *       bir ortalamaya göre yüzde kıyaslaması gürültüyü alarma çevirir. Yeni kurulan izleme
     *       pencere dolana kadar sessiz kalır.</li>
     * </ul>
     *
     * <p>Teyit ve kurtarma sayıları/aralıkları AYRI DEĞİL: izlemenin kendi
     * {@code confirmAttempts}/{@code recoveryChecks} ayarları burada da geçerli (ctx'e aynı
     * anahtarlarla konur), böylece tek bir sıçrama alarm üretmez ve düzelme aynı kurala uyar.
     *
     * @param rttMs bu turun ölçümü ({@code null} → ölçüm yok, sessiz kal)
     * @param until taban çizgisi penceresinin ÜST sınırı (bu ölçümün damgası; {@code null} → şimdi)
     */
    Map<String, Object> pingSlowVerdict(PingMonitor m, Long rttMs, String until) {
        Map<String, Object> out = new LinkedHashMap<>();
        int pct = m.getSlowThresholdPercent() != null ? m.getSlowThresholdPercent() : 20;
        int win = m.getSlowBaselineWindowMinutes() != null ? m.getSlowBaselineWindowMinutes() : 10;
        out.put("threshold_percent", pct);
        out.put("baseline_window_minutes", win);
        if (rttMs != null) out.put("rtt_ms", rttMs);
        if (!Boolean.TRUE.equals(m.getSlowResponseEnabled()) || rttMs == null) {
            out.put("status", "up");
            return out;
        }
        Instant now = Instant.now();
        String since = ISO.format(now.minusSeconds(Math.max(1, win) * 60L));
        String end = (until != null && !until.isBlank()) ? until : ISO.format(now);
        Double avg = null;
        long samples = 0;
        try {
            List<Object[]> rows = pingCheckRepo.slowBaseline(m.getId(), since, end);
            if (!rows.isEmpty() && rows.get(0) != null) {
                Object[] row = rows.get(0);
                if (row.length > 0 && row[0] instanceof Number n) avg = n.doubleValue();
                if (row.length > 1 && row[1] instanceof Number n2) samples = n2.longValue();
            }
        } catch (Exception e) {
            // Taban çizgisi okunamadıysa SESSİZ kal: ölçemediğimiz bir şey için alarm üretmeyiz.
            log.warn("Ping taban çizgisi okunamadı ({}): {}", m.getHost(), e.getMessage());
            out.put("status", "up");
            return out;
        }
        out.put("baseline_samples", samples);
        if (avg == null || avg <= 0 || samples < SLOW_BASELINE_MIN_SAMPLES) {
            out.put("status", "up");
            return out;
        }
        long limit = Math.round(avg * (1 + pct / 100.0));
        out.put("baseline_ms", Math.round(avg));
        out.put("limit_ms", limit);
        out.put("status", rttMs > limit ? "down" : "up");
        return out;
    }

    /** Yavaşlık değerlendirme öğesi — PING_DOWN öğesinin kardeşi. Teyit/kurtarma re-check'i taze
     *  ölçüm alır ve ping_checks'e YAZMAZ (port deseninin aynısı: teyit turu tabanı kaydırmasın). */
    private MonitoringOutageService.SweepItem pingSlowSweepItem(PingMonitor m, Map<String, Object> r) {
        Long rtt = r.get("rtt_ms") instanceof Number n ? n.longValue() : null;
        boolean measured = "up".equals(r.get("status")) && !Boolean.TRUE.equals(r.get("na"));
        Map<String, Object> v = pingSlowVerdict(m, measured ? rtt : null, (String) r.get("checked_at"));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());
        ctx.put("host", m.getHost());
        ctx.put("ip_version", m.getIpVersion());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        v.forEach((k, val) -> { if (!"status".equals(k)) ctx.put(k, val); });
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PING_SLOW, m.getHost(),
                rtt != null ? rtt + " ms" : "slow",
                !"down".equals(v.get("status")), null,
                chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalPingSlow(m));
    }

    /** Yavaşlık yeniden-ölçümü (PING_SLOW confirm/recovery re-check'i) — taze ping, PingCheck PERSIST ETMEZ. */
    private Map<String, Object> evalPingSlow(PingMonitor m) {
        if (!Boolean.TRUE.equals(m.getSlowResponseEnabled())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "up");
            return out;
        }
        Map<String, Object> r = pingCheckerService.check(m.getHost(), m.getIpVersion(),
                m.getPacketCount() != null ? m.getPacketCount() : 4,
                m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000);
        boolean up = Boolean.TRUE.equals(r.getOrDefault("up", false));
        Long rtt = r.get("rtt_ms") instanceof Number n ? n.longValue() : null;
        return pingSlowVerdict(m, up ? rtt : null, null);
    }

    /** Yavaşlık taban çizgisi için gereken en az örnek — altında SESSİZ kalınır (bkz. pingSlowVerdict). */
    static final int SLOW_BASELINE_MIN_SAMPLES = 3;

    private MonitoringOutageService.SweepItem pingSweepItem(PingMonitor m, Map<String, Object> r) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());
        ctx.put("host", m.getHost());
        ctx.put("ip_version", m.getIpVersion());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        if (r.get("rtt_ms") != null)      ctx.put("rtt_ms", r.get("rtt_ms"));
        if (r.get("packet_loss") != null) ctx.put("packet_loss", r.get("packet_loss"));
        if (Boolean.TRUE.equals(r.get("na"))) ctx.put("na", true);
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PING_DOWN, m.getHost(), "ICMP",
                "up".equals(r.get("status")), (String) r.get("error"),
                chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckPing(m));
    }

    /** Manuel "Çalıştır" — bkz. evaluatePortNow. */
    @Async("certCheckExecutor")
    public void evaluatePingNow(PingMonitor m, Map<String, Object> rawCheckResult) {
        try {
            Map<String, Object> r = pingOutcome(rawCheckResult);
            monitoringOutageService.handleSweepResults(
                    EscalationService.TYPE_PING_DOWN, List.of(pingSweepItem(m, r)), true);
            // Manuel "Çalıştır" da yavaşlığı değerlendirir: sweep ile manuel yolun ayrışması,
            // kullanıcının elle koşturduğu kontrolde alarmın açılmaması/kapanmaması demekti.
            monitoringOutageService.handleSweepResults(
                    EscalationService.TYPE_PING_SLOW, List.of(pingSlowSweepItem(m, r)), true);
        } catch (Exception e) {
            log.warn("Manuel ping değerlendirmesi başarısız {}: {}", m.getHost(), e.getMessage());
        }
    }

    /**
     * Tek HTTP monitörü için değerlendirme öğesi — sweep döngüsü VE manuel "Çalıştır" bunu PAYLAŞIR.
     *
     * <p>Ayrı metot olması ZORUNLU: manuel yol kendi ctx'ini kursaydı bir anahtar (ör. team_id ya da
     * notification_group_id) eksik kalabilir ve alarm SESSİZCE yanlış takıma/gruba giderdi. Tek
     * kaynak olduğu sürece iki yol sapamaz.
     */
    private MonitoringOutageService.SweepItem httpSweepItem(HttpMonitor m, Map<String, Object> r) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("url", m.getUrl());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        if (r.get("http_status") != null) ctx.put("http_status", r.get("http_status"));
        if (r.get("response_ms") != null) ctx.put("response_ms", r.get("response_ms"));
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_HTTP_DOWN, m.getUrl(),
                m.getMethod() != null ? m.getMethod() : "GET",
                "up".equals(r.get("status")), (String) r.get("error"),
                chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckHttp(m));
    }

    /**
     * Manuel "Çalıştır" — zamanlayıcıyla AYNI değerlendirme hattı: hata doğrulama denemelerinden
     * geçer, teyit edilirse alarm AÇILIR; düzelme kurtarma sayacından geçer.
     *
     * <p>ASENKRON olmak zorunda: doğrulama varsayılan 3 × 30 sn sürer, HTTP isteği o kadar
     * bekleyemez. Uç hemen döner, kart "çalışıyor" durumunu gösterir.
     *
     * <p>{@code manual=true} → toplu-kesinti bastırması atlanır (bkz. handleSweepResults).
     */
    @Async("certCheckExecutor")
    public void evaluateHttpNow(HttpMonitor m, Map<String, Object> rawCheckResult) {
        try {
            Map<String, Object> r = httpOutcome(rawCheckResult);
            monitoringOutageService.handleSweepResults(
                    EscalationService.TYPE_HTTP_DOWN, List.of(httpSweepItem(m, r)), true);
        } catch (Exception e) {
            log.warn("Manuel HTTP değerlendirmesi başarısız {}: {}", m.getUrl(), e.getMessage());
        }
    }

    /** HTTP uptime check + http_checks persist'i. {"status","error","http_status","response_ms"} döner. */
    private Map<String, Object> recheckHttp(HttpMonitor m) {
        int timeout = m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000;
        Map<String, Object> r = httpCheckerService.check(m.getUrl(), m.getMethod(), m.getExpectedStatus(),
                timeout, Boolean.TRUE.equals(m.getVerifySsl()), !Boolean.FALSE.equals(m.getFollowRedirects()));
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        try {
            HttpCheck res = new HttpCheck();
            res.setMonitorId(m.getId());
            res.setOk(ok);
            res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
            res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
            res.setError((String) r.get("error"));
            res.setCheckedAt(ISO.format(Instant.now()));
            httpCheckRepo.save(res);
        } catch (Exception e) {
            log.warn("HTTP kaydı yazılamadı: {} — {}", m.getUrl(), e.getMessage());
        }
        activityLog.recordCheck(ActivityLogService.HTTP, m.getId(), m.getName(),
                m.getUrl(), m.getTeamId(), false, "scheduler", r);
        return httpOutcome(r);
    }

    // ── Sayfa Bütünlüğü (9. tür) sweep — sık: her monitörün ANA sayfası (SINGLE_PAGE) ────────────────
    @Scheduled(fixedDelayString = "${site.monitor.page.interval-ms:60000}", initialDelayString = "90000")
    public void runPageChecks() {
        if (!appSettings.getBoolean("site.monitor.page.alert-enabled", true)) return;
        if (!tryAcquireSchedulerLock("page-sweep", sweepLockTtlMinutes)) {
            log.debug("Page sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try { runPageChecksLocked(); }
        finally { releaseSchedulerLock("page-sweep"); }
    }

    private void runPageChecksLocked() {
        List<com.sitemonitor.model.PageMonitor> monitors = pageMonitorRepo.findByActiveTrue();
        if (orphanCleanupDue("page")) try {
            java.util.Set<String> existingUrls = pageMonitorRepo.findAll().stream()
                    .map(com.sitemonitor.model.PageMonitor::getUrl).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedPageAlerts(existingUrls);
        } catch (Exception e) {
            log.warn("Öksüz sayfa alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        int checked = 0;
        // İki bağımsız alarm tipi: DOWN (ana sayfa alınamıyor) + INTEGRITY (kaynak/mixed sorunu).
        List<MonitoringOutageService.SweepItem> downSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> integritySweep = new ArrayList<>();
        List<Map.Entry<com.sitemonitor.model.PageMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (com.sitemonitor.model.PageMonitor m : monitors) {
            if (!checkDue("page", m.getId(), m.getIntervalSeconds())) continue;
            // Sık sweep her zaman ANA sayfayı (SINGLE_PAGE) kontrol eder; derin crawl ayrı günlük akışta.
            started.add(Map.entry(m, startNetworkCheck(() -> recheckPage(m, false, "SINGLE_PAGE"))));
        }
        for (var entry : started) {
            com.sitemonitor.model.PageMonitor m = entry.getKey();
            try {
                Map<String, Object> r = entry.getValue().get();
                addPageSweepItems(m, r, downSweep, integritySweep);
                checked++;
            } catch (Exception e) {
                log.warn("Page check failed for {}: {}", m.getUrl(), e.getMessage());
            }
        }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGE_DOWN, downSweep); }
        catch (Exception e) { log.warn("Page DOWN outage processing failed: {}", e.getMessage(), e); }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGE_INTEGRITY, integritySweep); }
        catch (Exception e) { log.warn("Page INTEGRITY outage processing failed: {}", e.getMessage(), e); }
        log.debug("Page checks complete: {} monitors", checked);
    }

    // ── Sayfa Bütünlüğü — DERİN CRAWL: seyrek (günlük), tek site anda, düşük öncelik ──────────────────
    @Scheduled(fixedDelayString = "${site.monitor.page.crawl-interval-ms:86400000}", initialDelayString = "150000")
    public void runPageCrawls() {
        if (!appSettings.getBoolean("site.monitor.page.alert-enabled", true)) return;
        if (!tryAcquireSchedulerLock("page-crawl", sweepLockTtlMinutes)) {
            log.debug("Page crawl — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            List<com.sitemonitor.model.PageMonitor> crawlers = pageMonitorRepo.findByActiveTrue().stream()
                    .filter(m -> "SITE_CRAWL".equalsIgnoreCase(m.getMode())).toList();
            if (crawlers.isEmpty()) return;
            List<MonitoringOutageService.SweepItem> downSweep = new ArrayList<>();
            List<MonitoringOutageService.SweepItem> integritySweep = new ArrayList<>();
            // Tek site anda: SIRALI (fan-out YOK) — tek-pod yük + nezaket.
            for (com.sitemonitor.model.PageMonitor m : crawlers) {
                try {
                    Map<String, Object> r = recheckPage(m, false, "SITE_CRAWL");
                    addPageSweepItems(m, r, downSweep, integritySweep);
                } catch (Exception e) {
                    log.warn("Page crawl failed for {}: {}", m.getUrl(), e.getMessage());
                }
            }
            try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGE_DOWN, downSweep); }
            catch (Exception e) { log.warn("Page crawl DOWN processing failed: {}", e.getMessage(), e); }
            try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGE_INTEGRITY, integritySweep); }
            catch (Exception e) { log.warn("Page crawl INTEGRITY processing failed: {}", e.getMessage(), e); }
            log.debug("Page crawl complete: {} sites", crawlers.size());
        } finally {
            releaseSchedulerLock("page-crawl");
        }
    }

    /** Bir page kontrol sonucundan iki SweepItem (DOWN + INTEGRITY) üretip ilgili listelere ekler. */
    private void addPageSweepItems(com.sitemonitor.model.PageMonitor m, Map<String, Object> r,
                                   List<MonitoringOutageService.SweepItem> downSweep,
                                   List<MonitoringOutageService.SweepItem> integritySweep) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("url", m.getUrl());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        // E-posta detay bölümü için sayfa-özel bağlam (EmailTemplateBuilder isPage dalı okur).
        ctx.put("page_status", r.get("status"));
        ctx.put("page_mode", m.getMode());                                     // SINGLE_PAGE | SITE_CRAWL
        ctx.put("alert_third_party",   Boolean.TRUE.equals(m.getAlertThirdParty()));
        ctx.put("alert_mixed_content", !Boolean.FALSE.equals(m.getAlertMixedContent()));
        ctx.put("alert_timeout",       !Boolean.FALSE.equals(m.getAlertTimeout()));
        if (r.get("broken_resources") != null) ctx.put("broken_resources", r.get("broken_resources"));
        if (r.get("timeout_count") != null) ctx.put("timeout_count", r.get("timeout_count"));
        if (r.get("mixed_content_count") != null) ctx.put("mixed_content_count", r.get("mixed_content_count"));
        if (r.get("problem_resources") != null) ctx.put("problem_resources", r.get("problem_resources"));
        if (r.get("problem_rows") != null)  ctx.put("problem_rows",  r.get("problem_rows"));
        if (r.get("problem_total") != null) ctx.put("problem_total", r.get("problem_total"));
        // Yapılandırma hatası (URL'de host yok) kesinti DEĞİL → sentetik "up" item (upItem/upItemKeyword deseni):
        // alarm açılmaz, teyit zinciri başlamaz, askıda kalmış eski alarm varsa sessizce kapanır.
        boolean cfgError = Boolean.TRUE.equals(r.get("config_error"));
        boolean mainUp = cfgError || Boolean.TRUE.equals(r.get("main_up"));
        boolean integrityUp = cfgError || Boolean.TRUE.equals(r.get("integrity_up"));
        String err = cfgError ? null : (String) r.get("error");
        downSweep.add(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PAGE_DOWN, m.getUrl(), "sayfa",
                mainUp, err, chanCtx(new LinkedHashMap<>(ctx), m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> { Map<String, Object> p = recheckPage(m, false, "SINGLE_PAGE");
                        return Map.of("status", Boolean.TRUE.equals(p.get("config_error"))
                                || Boolean.TRUE.equals(p.get("main_up")) ? "up" : "down"); }));
        Map<String, Object> integ = new LinkedHashMap<>(ctx);
        integ.put("detail", pageIntegrityDetail(r));
        integritySweep.add(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PAGE_INTEGRITY, m.getUrl(), pageIntegrityDetail(r),
                integrityUp, integrityUp ? null : pageIntegrityDetail(r), chanCtx(integ, m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> { Map<String, Object> p = recheckPage(m, false, "SINGLE_PAGE");
                        return Map.of("status", Boolean.TRUE.equals(p.get("config_error"))
                                || Boolean.TRUE.equals(p.get("integrity_up")) ? "up" : "down"); }));
    }

    private static String pageIntegrityDetail(Map<String, Object> r) {
        Object broken = r.getOrDefault("broken_resources", 0);
        Object timeouts = r.getOrDefault("timeout_count", 0);
        Object mixed = r.getOrDefault("mixed_content_count", 0);
        return broken + " kırık, " + timeouts + " zaman aşımı, " + mixed + " mixed content";
    }

    /**
     * Bir sayfa-bütünlüğü kontrolü: motoru çalıştırır, page_checks + page_resource_issues + activity_log yazar.
     * {@code effectiveMode} sık sweep'te "SINGLE_PAGE", günlük crawl'da "SITE_CRAWL" (m.mode'u geçersiz kılar).
     * Döner: {status, main_up, integrity_up, error, http_status, response_ms, broken_resources, mixed_content_count}.
     */
    private Map<String, Object> recheckPage(com.sitemonitor.model.PageMonitor m, boolean manual, String effectiveMode) {
        int timeout = m.getTimeoutMs() != null ? m.getTimeoutMs() : 4000;
        int slow = m.getSlowResourceMs() != null ? m.getSlowResourceMs() : 2000;
        int conc = m.getResourceConcurrency() != null ? m.getResourceConcurrency() : 5;
        int maxCheckSec = appSettings.getInt("site.monitor.page.max-check-seconds", 120);   // wall-clock üst sınır (H1/M1)
        PageCheckerService.PageCheckResult res = pageCheckerService.check(
                m.getUrl(), effectiveMode, timeout, slow, conc,
                m.getExcludePatterns(),
                m.getCrawlDepth() != null ? m.getCrawlDepth() : 2,
                m.getCrawlMaxPages() != null ? m.getCrawlMaxPages() : 50,
                maxCheckSec);

        // Alarm-uygunluk YALNIZ e-posta geçidi (tabloda her sorun görünür). countsForAlarm: BLOCKED/SLOW hiç,
        // LINK yalnız 404/410 (dış link 5xx/timeout alarm üretmez — Q1), yüklenen alt-kaynak broken/timeout.
        // TIMEOUT, MIXED_CONTENT gibi AYRI kovaya alınır → monitör başına alertTimeout toggle'ı ile geçitlenir.
        boolean anyFirstAlarm = false, anyThirdAlarm = false, anyMixedAlarm = false, anyTimeoutAlarm = false;
        for (PageCheckerService.ResourceIssue i : res.issues()) {
            if (!PageCheckerService.countsForAlarm(i.issueType(), i.resourceType(), i.httpStatus())) continue;
            if ("MIXED_CONTENT".equals(i.issueType())) { anyMixedAlarm = true; continue; }
            if ("TIMEOUT".equals(i.issueType()))       { anyTimeoutAlarm = true; continue; }
            if (i.firstParty()) anyFirstAlarm = true; else anyThirdAlarm = true;
        }
        boolean alertThird = Boolean.TRUE.equals(m.getAlertThirdParty());
        boolean alertMixed = !Boolean.FALSE.equals(m.getAlertMixedContent());   // varsayılan true (mevcut davranış)
        boolean alertTimeout = !Boolean.FALSE.equals(m.getAlertTimeout());       // varsayılan true (mevcut davranış)
        boolean alarmWorthy = (alertMixed && anyMixedAlarm) || (alertTimeout && anyTimeoutAlarm)
                            || anyFirstAlarm || (alertThird && anyThirdAlarm);
        boolean mainUp = res.mainReachable();
        boolean integrityUp = !mainUp || !alarmWorthy;   // ana sayfa down iken ayrı bütünlük alarmı üretme

        // KIRIK ve ZAMAN AŞIMI artık AYRI sayaçlar (2026-08-04) — çıkarma matematiği yok.
        // Toggle KAPALI iken timeout'lar "Bozulmuş" durumundan düşer (tabloda/sayaçta yine görünürler).
        int brokenCount  = res.brokenResources();
        int timeoutCount = res.timeoutResources();
        String pageStatus = res.status();
        if (!alertTimeout && timeoutCount > 0
                && mainUp && brokenCount == 0 && res.mixedContentCount() == 0) pageStatus = "OK";

        String ts = ISO.format(Instant.now());
        try {
            PageCheck pc = new PageCheck();
            pc.setMonitorId(m.getId());
            pc.setOk(mainUp && !alarmWorthy);
            pc.setStatus(pageStatus);
            pc.setHttpStatus(res.httpStatus());
            pc.setResponseMs(res.responseMs());
            pc.setTotalResources(res.totalResources());
            pc.setBrokenResources(brokenCount);
            pc.setTimeoutCount(timeoutCount);
            pc.setMixedContentCount(res.mixedContentCount());
            pc.setPagesCrawled(res.pagesCrawled());
            pc.setContentHash(res.contentHash());
            pc.setBodyBytes(res.bodyBytes());
            pc.setError(res.error());
            pc.setCheckedAt(ts);
            pageCheckRepo.save(pc);
            if (!res.issues().isEmpty()) {
                List<PageResourceIssue> rows = new ArrayList<>();
                for (PageCheckerService.ResourceIssue i : res.issues()) {
                    PageResourceIssue row = new PageResourceIssue();
                    row.setCheckId(pc.getId());
                    row.setMonitorId(m.getId());
                    row.setResourceUrl(i.resourceUrl());
                    row.setResourceType(i.resourceType());
                    row.setSourcePage(i.sourcePage());
                    row.setIssueType(i.issueType());
                    row.setFirstParty(i.firstParty());
                    row.setHttpStatus(i.httpStatus());
                    row.setDurationMs(i.durationMs());
                    row.setCheckedAt(ts);
                    rows.add(row);
                }
                pageResourceIssueRepo.saveAll(rows);
            }
        } catch (Exception e) {
            log.warn("Sayfa kaydı yazılamadı: {} — {}", m.getUrl(), e.getMessage());
        }

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("status", pageStatus);
        activity.put("ok", mainUp && !alarmWorthy);
        activity.put("http_status", res.httpStatus());
        activity.put("response_ms", res.responseMs());
        activity.put("total_resources", res.totalResources());
        activity.put("broken_resources", brokenCount);
        activity.put("timeout_count", timeoutCount);
        activity.put("mixed_content_count", res.mixedContentCount());
        activity.put("pages_crawled", res.pagesCrawled());
        if (res.error() != null) activity.put("error", res.error());
        activityLog.recordCheck(ActivityLogService.PAGE, m.getId(), m.getName(),
                m.getUrl(), m.getTeamId(), manual, manual ? "manual" : "scheduler", activity);

        // E-posta "Sorunlu Kaynaklar" bölümü için ilk ≤10 sorun. Toggle KAPALI iken timeout satırları listede öne
        // çıkmasın (shownIssues'ten çıkar). İki biçim: problem_resources (okunaklı, geriye-uyum) + problem_rows
        // (tab-delimited "tür\tURL\tHTTP" — EmailTemplateBuilder hizalı tablo kurar; tab URL'de asla geçmez).
        List<PageCheckerService.ResourceIssue> shownIssues = alertTimeout ? res.issues()
                : res.issues().stream().filter(i -> !"TIMEOUT".equals(i.issueType())).toList();
        final int PROBLEM_LIMIT = 10;
        String problemList = null, problemRows = null;
        int problemTotal = shownIssues.size();
        if (!shownIssues.isEmpty()) {
            StringBuilder probs = new StringBuilder();
            StringBuilder rows = new StringBuilder();
            int shown = 0;
            for (PageCheckerService.ResourceIssue i : shownIssues) {
                if (shown >= PROBLEM_LIMIT) { probs.append("… +").append(problemTotal - shown).append(" daha"); break; }
                String http = i.httpStatus() != null ? String.valueOf(i.httpStatus()) : "";
                probs.append(i.issueType()).append(" · ").append(i.resourceUrl());
                if (!http.isEmpty()) probs.append(" (HTTP ").append(http).append(')');
                probs.append('\n');
                rows.append(i.issueType()).append('\t').append(i.resourceUrl()).append('\t').append(http).append('\n');
                shown++;
            }
            problemList = probs.toString().trim();
            problemRows = rows.toString().trim();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", pageStatus);
        out.put("main_up", mainUp);
        out.put("integrity_up", integrityUp);
        // Yapılandırma hatası (şemasız/host'suz URL): kontrol kaydı + hata mesajı yazılır ama ALARM AÇILMAZ
        // (addPageSweepItems bu bayrakla up=true üretir) — kesinti değil, düzeltmesi kullanıcı elindedir.
        if ("CONFIG_ERROR".equals(res.status())) out.put("config_error", true);
        out.put("error", res.error());
        out.put("http_status", res.httpStatus());
        out.put("response_ms", res.responseMs());
        out.put("broken_resources", brokenCount);
        out.put("timeout_count", timeoutCount);
        out.put("mixed_content_count", res.mixedContentCount());
        if (problemList != null) out.put("problem_resources", problemList);
        if (problemRows != null) { out.put("problem_rows", problemRows); out.put("problem_total", problemTotal); }
        return out;
    }

    /** Manuel tetik (controller) — H1: DAİMA SINGLE_PAGE. Inline SITE_CRAWL request thread'ini dakikalarca
     *  tutup Tomcat worker'larını tüketebilir; derin crawl yalnız günlük runPageCrawls akışında koşar. */
    public Map<String, Object> triggerPageCheck(com.sitemonitor.model.PageMonitor m) {
        return recheckPage(m, true, "SINGLE_PAGE");
    }

    // ── Sayfa Hızı sweep ────────────────────────────────────────────────────────────────────
    // Aralık tabanı 5 dk olduğu için sweep dakikada bir uyanıp yalnız vakti gelenleri ölçer (checkDue).
    @Scheduled(fixedDelayString = "${site.monitor.pagespeed.interval-ms:60000}", initialDelayString = "120000")
    public void runPageSpeedChecks() {
        if (!appSettings.getBoolean("site.monitor.pagespeed.alert-enabled", true)) return;
        if (!tryAcquireSchedulerLock("pagespeed-sweep", sweepLockTtlMinutes)) {
            log.debug("Sayfa hızı sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try { runPageSpeedChecksLocked(); }
        finally { releaseSchedulerLock("pagespeed-sweep"); }
    }

    private void runPageSpeedChecksLocked() {
        List<com.sitemonitor.model.PageSpeedMonitor> monitors = pageSpeedMonitorRepo.findByActiveTrue();
        if (orphanCleanupDue("pagespeed")) try {
            java.util.Set<String> existingUrls = pageSpeedMonitorRepo.findAll().stream()
                    .map(com.sitemonitor.model.PageSpeedMonitor::getUrl).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedPageSpeedAlerts(existingUrls);
        } catch (Exception e) {
            log.warn("Öksüz sayfa hızı alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        int checked = 0;
        // İKİ BAĞIMSIZ alarm tipi: DOWN (sayfa hiç alınamadı — kesinti) + SLOW (eşik aşıldı — kesinti DEĞİL).
        // Ayrı sweep listeleri şart: aynı listeye konsalardı yavaş sayfa uptime'ı düşürürdü.
        List<MonitoringOutageService.SweepItem> downSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slowSweep = new ArrayList<>();
        List<Map.Entry<com.sitemonitor.model.PageSpeedMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (com.sitemonitor.model.PageSpeedMonitor m : monitors) {
            if (!checkDue("pagespeed", m.getId(),
                    com.sitemonitor.service.page.PageSpeedRules.clampInterval(m.getIntervalSeconds()))) continue;
            started.add(Map.entry(m, startNetworkCheck(() -> recheckPageSpeed(m, false))));
        }
        for (var entry : started) {
            com.sitemonitor.model.PageSpeedMonitor m = entry.getKey();
            try {
                addPageSpeedSweepItems(m, entry.getValue().get(), downSweep, slowSweep);
                checked++;
            } catch (Exception e) {
                log.warn("Sayfa hızı ölçümü başarısız {}: {}", m.getUrl(), e.getMessage());
            }
        }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGESPEED_DOWN, downSweep); }
        catch (Exception e) { log.warn("Sayfa hızı DOWN işlenemedi: {}", e.getMessage(), e); }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_PAGESPEED_SLOW, slowSweep); }
        catch (Exception e) { log.warn("Sayfa hızı SLOW işlenemedi: {}", e.getMessage(), e); }
        log.debug("Sayfa hızı ölçümleri tamam: {} izleme", checked);
    }

    private void addPageSpeedSweepItems(com.sitemonitor.model.PageSpeedMonitor m, Map<String, Object> r,
                                        List<MonitoringOutageService.SweepItem> downSweep,
                                        List<MonitoringOutageService.SweepItem> slowSweep) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("url", m.getUrl());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        ctx.put("pagespeed_status", r.get("status"));
        for (String k : new String[]{"response_ms", "ttfb_ms", "total_bytes", "request_count",
                "http_status", "bytes_truncated"}) {
            if (r.get(k) != null) ctx.put(k, r.get(k));
        }
        if (r.get("breached") != null) ctx.put("breached_metrics", r.get("breached"));
        if (r.get("heavy_rows") != null) { ctx.put("heavy_rows", r.get("heavy_rows")); ctx.put("heavy_total", r.get("heavy_total")); }

        // Yapılandırma hatası (URL'de host yok) kesinti DEĞİL → sentetik "up" item: alarm açılmaz,
        // teyit zinciri başlamaz, askıda kalmış eski alarm varsa sessizce kapanır.
        boolean cfgError = Boolean.TRUE.equals(r.get("config_error"));
        boolean up = cfgError || Boolean.TRUE.equals(r.get("reachable"));
        boolean withinThresholds = cfgError || !up || Boolean.TRUE.equals(r.get("within_thresholds"));
        String err = cfgError ? null : (String) r.get("error");

        downSweep.add(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PAGESPEED_DOWN, m.getUrl(), "sayfa hızı",
                up, err, chanCtx(new LinkedHashMap<>(ctx), m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> { Map<String, Object> p = recheckPageSpeed(m, false);
                        return Map.of("status", Boolean.TRUE.equals(p.get("config_error"))
                                || Boolean.TRUE.equals(p.get("reachable")) ? "up" : "down"); }));

        // Sayfa alınamıyorken AYRI bir yavaşlık alarmı üretme (withinThresholds yukarıda !up ile true'lanır):
        // çökmüş bir sayfayı "yavaş" diye ikinci kez raporlamak gürültüdür.
        String slowDetail = pageSpeedBreachDetail(r);
        Map<String, Object> slowCtx = new LinkedHashMap<>(ctx);
        slowCtx.put("detail", slowDetail);
        slowSweep.add(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PAGESPEED_SLOW, m.getUrl(), slowDetail,
                withinThresholds, withinThresholds ? null : slowDetail, chanCtx(slowCtx, m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> { Map<String, Object> p = recheckPageSpeed(m, false);
                        return Map.of("status", Boolean.TRUE.equals(p.get("config_error"))
                                || !Boolean.TRUE.equals(p.get("reachable"))
                                || Boolean.TRUE.equals(p.get("within_thresholds")) ? "up" : "down"); }));
    }

    /** Aşılan eşikleri okunur tek satıra çevirir (e-posta konusu ve alarm detayı). */
    private static String pageSpeedBreachDetail(Map<String, Object> r) {
        Object breached = r.get("breached");
        if (!(breached instanceof List<?> list) || list.isEmpty()) return "eşikler içinde";
        List<String> parts = new ArrayList<>();
        for (Object b : list) {
            switch (String.valueOf(b)) {
                case "LOAD" -> parts.add("yükleme " + r.getOrDefault("response_ms", "?") + " ms");
                case "TTFB" -> parts.add("TTFB " + r.getOrDefault("ttfb_ms", "?") + " ms");
                // Kırpılmış ölçümde rakam alt sınırdır; e-postada da "≥" ile gösterilir ki
                // alarmı okuyan kişi eksik bir sayıya bakıp yanlış karar vermesin.
                case "SIZE" -> parts.add("boyut "
                        + (Boolean.TRUE.equals(r.get("bytes_truncated")) ? "≥ " : "")
                        + kb(r.get("total_bytes")) + " KB");
                case "REQUESTS" -> parts.add(r.getOrDefault("request_count", "?") + " istek");
                default -> parts.add(String.valueOf(b));
            }
        }
        return String.join(" · ", parts) + " eşiği aştı";
    }

    private static String kb(Object bytes) {
        if (!(bytes instanceof Number n)) return "?";
        return String.valueOf(n.longValue() / 1024);
    }

    /**
     * Bir sayfa hızı ölçümü: motoru çalıştırır, pagespeed_checks + pagespeed_resources + activity_log yazar.
     * Döner: {status, reachable, within_thresholds, breached, error, http_status, response_ms, ttfb_ms,
     * total_bytes, request_count}.
     */
    private Map<String, Object> recheckPageSpeed(com.sitemonitor.model.PageSpeedMonitor m, boolean manual) {
        // Delil dondurma KENAR-tetiklidir: bunun icin ONCEKI olcumun ihlal durumu, yeni kayit
        // yazilmadan ONCE okunur (tek indeksli sorgu).
        boolean wasBreached = pageSpeedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId())
                .map(prev -> prev.getBreachedMetrics() != null && !prev.getBreachedMetrics().isBlank())
                .orElse(false);
        PageSpeedCheckerService.Result res = pageSpeedCheckerService.check(m);
        boolean cfgError = "CONFIG_ERROR".equals(res.status());
        boolean reachable = res.reachable();
        String ts = ISO.format(Instant.now());

        try {
            PageSpeedCheck pc = new PageSpeedCheck();
            pc.setMonitorId(m.getId());
            pc.setCheckedAt(ts);
            // ok = SAYFA ALINABİLDİ Mİ. Eşik aşımı burayı FALSE YAPMAZ — yoksa yavaş sayfa uptime'ı düşürürdü.
            pc.setOk(reachable);
            pc.setStatusCode(res.statusCode());
            pc.setTtfbMs((int) res.ttfbMs());
            pc.setHtmlMs((int) res.htmlMs());
            pc.setResponseMs((int) res.totalMs());
            pc.setTotalBytes(res.totalBytes());
            pc.setRequestCount(res.requestCount());
            pc.setFailedCount(res.failedCount());
            pc.setCapped(res.capped());
            pc.setBytesTruncated(res.bytesTruncated());
            pc.setBreachedMetrics(com.sitemonitor.service.page.PageSpeedRules.joinBreaches(res.breached()));
            // TTFB faz kırılımı: sıçramanın hangi fazdan geldiği sonradan da okunabilsin.
            pc.setDnsMs(res.phases().dnsMs());
            pc.setConnectMs(res.phases().connectMs());
            pc.setTlsMs(res.phases().tlsMs());
            pc.setServerMs(res.phases().serverMs());
            pc.setSkippedLazy(res.skippedLazy());
            // İhlal delili ÖLÇÜM ANINDAKİ eşikle donar; izleme sonradan düzenlenirse geçmiş
            // satır yine doğru sayıyı gösterir.
            pc.setBreachDetail(com.sitemonitor.service.page.PageSpeedRules.breachDetail(
                    m, res.breached(), (int) res.totalMs(),
                    res.phases().serverMs() != null ? res.phases().serverMs() : (int) res.ttfbMs(),
                    res.totalBytes(), res.requestCount()));
            pc.setErrorMessage(res.error());
            pageSpeedCheckRepo.save(pc);
            writeResourceBreakdown(m, pc, res, ts, wasBreached);
        } catch (Exception e) {
            log.warn("Sayfa hızı kaydı yazılamadı: {} — {}", m.getUrl(), e.getMessage());
        }

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("status", res.status());
        activity.put("ok", reachable);
        activity.put("http_status", res.statusCode());
        activity.put("response_ms", res.totalMs());
        activity.put("ttfb_ms", res.ttfbMs());
        activity.put("total_bytes", res.totalBytes());
        activity.put("request_count", res.requestCount());
        if (res.error() != null) activity.put("error", res.error());
        activityLog.recordCheck(ActivityLogService.PAGESPEED, m.getId(), m.getName(),
                m.getUrl(), m.getTeamId(), manual, manual ? "manual" : "scheduler", activity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", res.status());
        out.put("reachable", reachable);
        out.put("within_thresholds", res.breached().isEmpty());
        out.put("breached", res.breached());
        if (cfgError) out.put("config_error", true);
        out.put("error", res.error());
        out.put("http_status", res.statusCode());
        out.put("response_ms", res.totalMs());
        out.put("ttfb_ms", res.ttfbMs());
        out.put("dns_ms", res.phases().dnsMs());
        out.put("connect_ms", res.phases().connectMs());
        out.put("tls_ms", res.phases().tlsMs());
        out.put("server_ms", res.phases().serverMs());
        out.put("skipped_lazy", res.skippedLazy());
        out.put("total_bytes", res.totalBytes());
        // Toplam ALT SINIR mı: arayüz "≥" ile gösteriyor, e-posta detayı da bunu okuyor.
        out.put("bytes_truncated", res.bytesTruncated());
        out.put("request_count", res.requestCount());
        // E-posta "En ağır kaynaklar" bölümü: tab-delimited "tür\tURL\tKB" (tab URL'de asla geçmez).
        if (!res.resources().isEmpty()) {
            List<PageSpeedCheckerService.Measured> heavy = new ArrayList<>(res.resources());
            heavy.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
            StringBuilder rows = new StringBuilder();
            int shown = 0;
            for (PageSpeedCheckerService.Measured x : heavy) {
                if (shown >= HEAVY_LIMIT) break;
                rows.append(PageSpeedCheckerService.normalizeType(x.type())).append('\t')
                    .append(x.url()).append('\t').append(x.bytes() / 1024).append('\n');
                shown++;
            }
            out.put("heavy_rows", rows.toString().trim());
            out.put("heavy_total", heavy.size());
        }
        return out;
    }

    private static final int HEAVY_LIMIT = 10;

    /**
     * Kaynak kırılımını yazar. LATEST satırları, YENİSİ VARSA silinip yeniden yazılır (tablo izleme
     * sayısıyla orantılı kalır, kontrol sayısıyla DEĞİL). İhlalin BAŞLADIĞI ölçümde aynı satırlar
     * BREACH işaretiyle ikinci kez KALICI yazılır — "geçen salı neden yavaşladı" sorusu sonradan da
     * cevaplanabilsin diye. İhlal SÜRERKEN tekrar dondurulmaz (bkz. gövdedeki gerekçe).
     */
    private void writeResourceBreakdown(com.sitemonitor.model.PageSpeedMonitor m, PageSpeedCheck pc,
                                        PageSpeedCheckerService.Result res, String ts, boolean wasBreached) {
        // Kırılım YOKSA (sayfa alınamadı / yapılandırma hatası) mevcut LATEST satırlarına DOKUNULMAZ.
        // Önce silip sonra dönmek, tek bir başarısız kontrolde son iyi kırılımı KALICI olarak
        // siliyordu — yani kullanıcı "bozulmadan önce sayfa neye benziyordu" diye baktığı ANDA
        // tablo boşalıyordu. Satırlar kendi `checked_at`'ini taşıyor, arayüz de onu gösteriyor;
        // eski kırılımı tutmak yanıltıcı değil, tek bilgi kaynağı.
        if (res.resources().isEmpty()) return;
        pageSpeedResourceRepo.deleteByMonitorIdAndKeepReason(m.getId(), PageSpeedResource.KEEP_LATEST);
        // KENAR-TETIKLI: delil yalnizca ihlal BASLADIGI anda dondurulur, ihlal SURDUGU her kontrolde
        // degil. Aksi halde kalici yavas bir sayfa 30 dk'da bir 500 kalici satir yazar — gunde
        // ~24.000 satir, 90 gunluk saklamayla tek izleme icin milyonlarca satir. Sorulan soru
        // "ne zaman bozuldu" oldugu icin bozulma ANI zaten yeterli; ihlal surerken kirilim
        // LATEST'te canli duruyor.
        boolean breached = !res.breached().isEmpty() && !wasBreached;
        List<PageSpeedResource> rows = new ArrayList<>(res.resources().size() * (breached ? 2 : 1));
        for (PageSpeedCheckerService.Measured x : res.resources()) {
            rows.add(resourceRow(m, pc, x, ts, PageSpeedResource.KEEP_LATEST));
            if (breached) rows.add(resourceRow(m, pc, x, ts, PageSpeedResource.KEEP_BREACH));
        }
        pageSpeedResourceRepo.saveAll(rows);
    }

    private static PageSpeedResource resourceRow(com.sitemonitor.model.PageSpeedMonitor m, PageSpeedCheck pc,
                                                 PageSpeedCheckerService.Measured x, String ts, String reason) {
        PageSpeedResource row = new PageSpeedResource();
        row.setMonitorId(m.getId());
        row.setCheckId(pc.getId());
        row.setCheckedAt(ts);
        row.setUrl(x.url());
        row.setType(PageSpeedCheckerService.normalizeType(x.type()));
        row.setBytes(x.bytes());
        row.setDurationMs((int) x.durationMs());
        row.setStatusCode(x.statusCode());
        row.setThirdParty(x.thirdParty());
        row.setTruncated(x.truncated());
        row.setKeepReason(reason);
        return row;
    }

    /** Manuel tetik (controller). */
    public Map<String, Object> triggerPageSpeedCheck(com.sitemonitor.model.PageSpeedMonitor m) {
        return recheckPageSpeed(m, true);
    }

    // ── Senaryo İzleme (10. tür) — k6 alt süreç sweep'i (bounded executor, scheduler'ı bloklamaz) ──────
    @Scheduled(fixedDelayString = "${site.monitor.scripted.interval-ms:60000}", initialDelayString = "95000")
    public void runScriptedChecks() {
        if (!appSettings.getBoolean("site.monitor.scripted.enabled", true)) return;
        // k6 yoksa PERİYODİK yeniden dene ve SUS-MA. Eskiden burada koşulsuz `return` vardı ve
        // probeK6 yalnız @PostConstruct'ta çalışıyordu: k6'yı taşıyan volume/sidecar geç hazır olursa
        // sentetik izleme sessizce kalıcı ölüyordu (kontrol yok, alarm yok, kayıt yok, log yok).
        if (!scriptedCheckerService.ensureProbed()) {
            if (scriptedMonitorRepo.countByActiveTrue() > 0)
                log.warn("[K6] Sentetik izleme YÜRÜTÜLMÜYOR — k6 bulunamadı. Aktif sentetik monitörler "
                        + "kontrol edilmiyor; scripted.k6.available metriği 0.");
            return;
        }
        if (!tryAcquireSchedulerLock("scripted-sweep", sweepLockTtlMinutes)) {
            log.debug("Scripted sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try { runScriptedChecksLocked(); }
        finally { releaseSchedulerLock("scripted-sweep"); }
    }

    private void runScriptedChecksLocked() {
        List<com.sitemonitor.model.ScriptedMonitor> monitors = scriptedMonitorRepo.findByActiveTrue();
        if (orphanCleanupDue("scripted")) try {
            java.util.Set<String> names = scriptedMonitorRepo.findAll().stream()
                    .map(com.sitemonitor.model.ScriptedMonitor::getName).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedScriptedAlerts(names);
        } catch (Exception e) {
            log.warn("Öksüz senaryo alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        // k6 süreçleri checker'ın SINIRLI havuzunda koşar; submit() ayrı executor'a atar → scheduler bloklanmaz.
        List<Map.Entry<com.sitemonitor.model.ScriptedMonitor, java.util.concurrent.Future<ScriptedCheckerService.ScriptedResult>>> started = new ArrayList<>();
        for (com.sitemonitor.model.ScriptedMonitor m : monitors) {
            if (!checkDue("scripted", m.getId(), m.getIntervalSeconds())) continue;
            started.add(Map.entry(m, scriptedCheckerService.submit(m)));
        }
        int checked = 0, skipped = 0;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slowSweep = new ArrayList<>();
        for (var entry : started) {
            com.sitemonitor.model.ScriptedMonitor m = entry.getKey();
            try {
                // Bekleme süresi checker'ın permit beklemesinden KISA OLAMAZ: kısa olursa koşum arka
                // planda tamamlanır ama sonucu hiçbir yere yazılmaz (satır yok, checked_at eskir,
                // alarm çıkmaz) ve monitör ekranda sessizce donar. Tek kaynak: maxWaitSeconds().
                ScriptedCheckerService.ScriptedResult res =
                        entry.getValue().get(ScriptedCheckerService.maxWaitSeconds() + 15,
                                java.util.concurrent.TimeUnit.SECONDS);
                // Altyapı atlaması hedefin arızası DEĞİL: ne kayıt yazılır (uptime kirlenmesin)
                // ne alarm zincirine girer. Aksi halde pool-size darlığı 100 monitörlük filoda
                // toplu YANLIŞ alarm üretiyordu.
                if (ScriptedCheckerService.isSkipped(res)) { skipped++; continue; }
                Map<String, Object> r = persistScripted(m, res, false);
                addScriptedSweepItems(m, r, sweep, slowSweep);
                checked++;
            } catch (java.util.concurrent.TimeoutException te) {
                // Future'ı BIRAKMA: iptal edilmezse k6 süreci ve permit'i tutmaya devam eder.
                entry.getValue().cancel(true);
                skipped++;
                log.warn("Senaryo sonucu {} sn içinde gelmedi, koşum iptal edildi: {} — bu tur kontrol yazılmadı",
                        ScriptedCheckerService.maxWaitSeconds() + 15, m.getName());
            } catch (Exception e) {
                log.warn("Scripted check failed for {}: {}", m.getName(), e.getMessage());
            }
        }
        if (skipped > 0)
            log.warn("Senaryo sweep: {} kontrol YÜRÜTÜLEMEDİ (k6 havuzu dolu / k6 yok). Kapasite ≈ "
                    + "pool-size × aralık ÷ koşum süresi; scripted.k6.skipped sayacına bakın.", skipped);
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_SCRIPTED_FAIL, sweep); }
        catch (Exception e) { log.warn("Scripted outage processing failed: {}", e.getMessage(), e); }
        // Yavas kosum alarmi AYRI pipeline: kesinti alarmindan bagimsiz acilir/kapanir (PORT_SLOW deseni).
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_SCRIPTED_SLOW, slowSweep); }
        catch (Exception e) { log.warn("Scripted slow outage processing failed: {}", e.getMessage()); }
        log.debug("Scripted checks complete: {} monitors", checked);
    }

    /** Bir senaryo kontrol sonucundan SweepItem üretir: SCRIPTED_FAIL (up=PASS) ve opsiyonel SCRIPTED_SLOW. */
    private void addScriptedSweepItems(com.sitemonitor.model.ScriptedMonitor m, Map<String, Object> r,
                                       List<MonitoringOutageService.SweepItem> sweep,
                                       List<MonitoringOutageService.SweepItem> slowSweep) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("name", m.getName());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", m.getConfirmAttempts());
        ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() != null ? m.getConfirmIntervalSeconds() * 1000L : null);
        ctx.put("monitor_recovery_checks", m.getRecoveryChecks());
        ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() != null ? m.getRecoveryIntervalSeconds() * 1000L : null);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        ctx.put("scripted_status", r.get("status"));
        if (r.get("checks_failed") != null) ctx.put("checks_failed", r.get("checks_failed"));
        if (r.get("output_tail") != null) ctx.put("output_tail", r.get("output_tail"));
        if (r.get("failed_checks") != null) ctx.put("failed_checks", r.get("failed_checks"));
        // Sebep e-posta şablonuna da geçsin: "Başarısız Check'ler" listesi NEYİN düştüğünü söyler,
        // NEDEN düştüğünü değil (bağlantı reddi / DNS / TLS / istek zaman aşımı).
        if (r.get("error") != null) ctx.put("error", r.get("error"));
        boolean up = Boolean.TRUE.equals(r.get("up"));
        String detail = (String) r.get("detail");
        sweep.add(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_SCRIPTED_FAIL, m.getName(), detail,
                up, up ? null : detail, chanCtx(new LinkedHashMap<>(ctx), m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> { Map<String, Object> p = recheckScripted(m, false);
                        // "skipped" ÜÇÜNCÜ bir cevap: kontrol yürütülemediyse ne up ne down deriz.
                        // "down" desek havuz darlığı sahte kesinti TEYİT ederdi; "up" desek gerçek
                        // bir kesintiyi sessizce kapatırdı. Teyit zinciri bunu kanıt saymaz.
                        if (Boolean.TRUE.equals(p.get("skipped")))
                            return Map.of("status", "skipped", "error", String.valueOf(p.get("error")));
                        return Map.of("status", Boolean.TRUE.equals(p.get("up")) ? "up" : "down"); }));

        // ── SCRIPTED_SLOW (opt-in) ───────────────────────────────────────────────────────────
        // Kapaliysa/olculemediyse/kosum DUSTUYSE sentetik "up" uretilir: boylece daha once acilmis
        // bir SLOW alarmi asili kalmaz. Dusen kosumda sure zaten anlamsizdir (timeout'ta tavan
        // degeri olcerdik) — o durumu SCRIPTED_FAIL anlatir, SLOW ikinci kez bagirmaz.
        Map<String, Object> slowCtx = new LinkedHashMap<>(ctx);
        int slowTh = slowThresholdFor(m);
        slowCtx.put("threshold_ms", slowTh);
        // persistScripted sureyi "response_ms" adiyla dondurur (tum turlerde ortak ad);
        // alarm ctx'inde ise sentetigin dilinde "duration_ms" olarak tasinir.
        Long durMs = r.get("response_ms") instanceof Number dn ? dn.longValue() : null;
        if (durMs != null) slowCtx.put("duration_ms", durMs);
        boolean slowDown = Boolean.TRUE.equals(m.getSlowResponseEnabled()) && up && durMs != null && durMs > slowTh;
        slowSweep.add(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_SCRIPTED_SLOW, m.getName(),
                durMs != null ? durMs + " ms" : "slow",
                !slowDown, null, chanCtx(slowCtx, m.getNotifyEmail(), m.getNotifyWebhook()),
                () -> evalScriptedSlow(m)));
    }

    /** SCRIPTED_SLOW esigi (ms): monitorde bos ise genel ayar, o da yoksa 15000. */
    private int slowThresholdFor(com.sitemonitor.model.ScriptedMonitor m) {
        if (m.getSlowThresholdMs() != null && m.getSlowThresholdMs() > 0) return m.getSlowThresholdMs();
        return appSettings.getInt("site.monitor.scripted.slow-threshold-ms", 15000);
    }

    /**
     * Yavas kosum yeniden-olcumu (SCRIPTED_SLOW teyit/recovery re-check'i) — TAZE kosum.
     *
     * <p>Uc cevap uretir, ikisi degil: kontrol yurutulemediyse (k6 havuzu dolu / k6 yok)
     * {@code "skipped"} doner ve teyit zinciri kanit saymaz. "down" desek havuz darligi sahte bir
     * yavaslik TEYIT ederdi; "up" desek gercek bir yavaslamayi sessizce kapatirdi.
     * Alarmi kapali monitor ve DUSEN kosum → "up" (SLOW, FAIL'in uzerine ikinci alarm acmaz).
     */
    private Map<String, Object> evalScriptedSlow(com.sitemonitor.model.ScriptedMonitor m) {
        Map<String, Object> out = new LinkedHashMap<>();
        int th = slowThresholdFor(m);
        out.put("threshold_ms", th);
        if (!Boolean.TRUE.equals(m.getSlowResponseEnabled())) { out.put("status", "up"); return out; }
        Map<String, Object> p = recheckScripted(m, false);
        if (Boolean.TRUE.equals(p.get("skipped"))) {
            out.put("status", "skipped");
            out.put("error", String.valueOf(p.get("error")));
            return out;
        }
        Long ms = p.get("response_ms") instanceof Number n ? n.longValue() : null;
        if (ms != null) out.put("duration_ms", ms);
        boolean slow = Boolean.TRUE.equals(p.get("up")) && ms != null && ms > th;
        out.put("status", slow ? "down" : "up");
        return out;
    }

    /** k6'yı çalıştır + ScriptedCheck yaz + activity + out map. (Manuel tetik + SweepItem recheck lambda kullanır.) */
    public Map<String, Object> recheckScripted(com.sitemonitor.model.ScriptedMonitor m, boolean manual) {
        return persistScripted(m, scriptedCheckerService.run(m), manual);
    }

    private Map<String, Object> persistScripted(com.sitemonitor.model.ScriptedMonitor m,
                                                ScriptedCheckerService.ScriptedResult res, boolean manual) {
        // Kontrol YÜRÜTÜLEMEDİYSE (k6 yok / havuz dolu) hiçbir şey kaydedilmez: uptime serisine
        // sahte bir başarısızlık, alarm zincirine sahte bir kanıt girmesin. Çağıran "skipped"
        // görür; manuel tetikte kullanıcıya sebep, teyit zincirinde ise zinciri iptal ettirir.
        if (ScriptedCheckerService.isSkipped(res)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", ScriptedCheckerService.STATUS_SKIPPED);
            out.put("skipped", true);
            out.put("up", null);              // BİLİNMİYOR — ne up ne down
            out.put("error", res.error());
            log.warn("Senaryo kontrolü yürütülemedi ({}): {}", m.getName(), res.error());
            return out;
        }
        // ok ile up BİLİNÇLİ olarak ayrışır — CONFIG_ERROR'da (http/page türleri) uygulanan aynı desen.
        //   ok  = "bu koşum doğrulanmış sağlık üretti mi?"  → uptime serisine ve rollup'a gider
        //   up  = "hedef çökük mü, birini çağıralım mı?"    → alarm zincirine gider
        // NO_CHECKS (script koştu ama hiçbir şey doğrulamadı) yapılandırma kusurudur: kayıt hatalı
        // sayılır (uptime düşer, sessiz başarı riski kapanır) ama kimse çağrılmaz — hedef pekâlâ
        // sağlıklı olabilir. no-checks-policy=FAIL ile alarm da açılabilir.
        boolean ok = res.ok();
        boolean noChecks = "NO_CHECKS".equals(res.status());
        boolean up = ok || (noChecks && !scriptedCheckerService.noChecksAlarms());
        String ts = ISO.format(java.time.Instant.now());
        try {
            com.sitemonitor.model.ScriptedCheck c = new com.sitemonitor.model.ScriptedCheck();
            c.setMonitorId(m.getId());
            c.setOk(ok);
            c.setStatus(res.status());
            c.setDurationMs(res.durationMs());
            c.setExitCode(res.exitCode());
            c.setChecksPassed(res.checksPassed());
            c.setChecksFailed(res.checksFailed());
            c.setIterationMs(res.iterationMs());
            c.setHttpReqAvgMs(res.httpReqAvgMs());
            c.setHttpReqP95Ms(res.httpReqP95Ms());
            c.setChecksJson(res.checksJson());
            c.setOutputTail(res.outputTail());
            c.setError(res.error());
            c.setViaProxy(res.viaProxy());
            c.setScriptVersion(m.getScriptVersion());
            var ph = res.phases();
            if (ph != null) {
                c.setReqBlockedMs(ph.blockedMs());
                c.setReqConnectingMs(ph.connectingMs());
                c.setReqTlsMs(ph.tlsMs());
                c.setReqSendingMs(ph.sendingMs());
                c.setReqWaitingMs(ph.waitingMs());
                c.setReqReceivingMs(ph.receivingMs());
                c.setDataSent(ph.dataSent());
                c.setDataReceived(ph.dataReceived());
            }
            c.setCheckedAt(ts);
            scriptedCheckRepo.save(c);
        } catch (Exception e) {
            log.warn("Senaryo kaydı yazılamadı: {} — {}", m.getName(), e.getMessage());
        }

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("status", res.status());
        activity.put("ok", ok);          // aktivite akışı SAĞLIĞI kaydeder, alarm kararını değil
        activity.put("checks_passed", res.checksPassed());
        activity.put("checks_failed", res.checksFailed());
        activity.put("response_ms", res.durationMs());
        if (res.error() != null) activity.put("error", res.error());
        activityLog.recordCheck(ActivityLogService.SCRIPTED, m.getId(), m.getName(), m.getName(),
                m.getTeamId(), manual, manual ? "manual" : "scheduler", activity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", res.status());
        out.put("up", up);
        out.put("error", res.error());
        out.put("detail", scriptedDetail(res));
        out.put("response_ms", res.durationMs());
        if (res.checksFailed() != null) out.put("checks_failed", res.checksFailed());
        if (res.outputTail() != null) out.put("output_tail", res.outputTail());
        out.put("failed_checks", failedCheckNames(res.checksJson()));

        // ── L3: anomali guard'ı — KAYIT YAZILDIKTAN SONRA ────────────────────────────────
        // Sıra önemli: guard ardışık zaman aşımı serisini `scripted_checks` üzerinden okuyor,
        // yani bu koşumun satırı DB'de olmalı. Kapatma gerçekleştiyse manuel tetikleyen kullanıcı
        // sebebi yanıtta görsün — arka planda sessizce kapanmasın.
        // Guard bir GÜVENLİK AĞI'dır, kontrol hattının bir parçası değil: buradaki bir arıza
        // koşumun kaydedilmesini ya da sweep sonucunun üretilmesini ASLA engellememeli.
        // (Guard'ın kendi içi de sessiz; bu ikinci kemer enjeksiyonun hiç yapılmadığı yolları
        // da kapsıyor — aksi halde tek bir null bütün senaryo sweep'ini sessizce düşürürdü.)
        try {
            String disabled = scriptedAnomalyGuard == null ? null : scriptedAnomalyGuard.evaluate(m, res);
            if (disabled != null) {
                out.put("disabled", true);
                out.put("disabled_reason", disabled);
            }
        } catch (Exception e) {
            log.warn("Anomali guard'ı çalıştırılamadı ({}): {}", m.getName(), e.toString());
        }
        return out;
    }

    /**
     * "FAIL — 3✓/1✗ — Request Failed … request timeout" gibi kısa insan-okur özet.
     *
     * <p>SEBEP, check sayacı olsa da eklenir. Eskiden yalnız {@code checksFailed == null} iken
     * ekleniyordu; oysa en sık gerçek arıza biçiminde (istek düştü → check'ler de düştü) sayaç DOLU
     * olur ve nöbetçiye giden alarmda sebep hiç yer almazdı: "sentetik testi başarısız — 1✓/2✗"
     * deyip susuyordu (2026-08: 288 koşum boyunca "request timeout" e-postaya hiç girmedi).
     */
    static String scriptedDetail(ScriptedCheckerService.ScriptedResult res) {
        StringBuilder sb = new StringBuilder(res.status());
        if (res.checksPassed() != null || res.checksFailed() != null)
            sb.append(" — ").append(res.checksPassed() != null ? res.checksPassed() : 0).append("✓/")
              .append(res.checksFailed() != null ? res.checksFailed() : 0).append("✗");
        if (res.error() != null) sb.append(" — ").append(oneLine(res.error()));
        return sb.toString();
    }

    private static final int DETAIL_MAX_CHARS = 300;

    /**
     * Çok satırlı hata metnini alarm başlığına sığacak TEK satıra indirir.
     *
     * <p>Bu değer alarm {@code detail}'ine ve oradan e-postaya gidiyor. {@code error} artık çok
     * satırlı bir Babel kod çerçevesi taşıyabiliyor (sözdizimi hatalarında, 8+ satır); tamamını
     * alarm başlığına koymak hem maili hem bildirim listesini bozar. Tam metin zaten
     * {@code output_tail} ile ayrı alanda taşınıyor.
     *
     * <p>İlk İKİ satır alınır, tek satır değil: {@code summarizeError} birinci satıra yalnız
     * etiketi ({@code "script çalışma-zamanı hatası (çıkış 107):"}) koyuyor, asıl sebep ikinci
     * satırda. Yalnız ilki alınsaydı alarm hatanın ne olduğunu hiç söylemezdi.
     */
    static String oneLine(String error) {
        String[] all = error.split("\\R");
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String raw : all) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            if (used == 2) { sb.append(" …"); break; }
            if (used > 0) sb.append(' ');
            sb.append(s);
            used++;
        }
        String out = sb.toString();
        return out.length() > DETAIL_MAX_CHARS ? out.substring(0, DETAIL_MAX_CHARS) + " …" : out;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SCRIPTED_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** checksJson'dan başarısız check adları (mail "başarısız check listesi" için). */
    private String failedCheckNames(String checksJson) {
        if (checksJson == null || checksJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode arr = SCRIPTED_JSON.readTree(checksJson);
            List<String> failed = new ArrayList<>();
            if (arr.isArray()) for (com.fasterxml.jackson.databind.JsonNode n : arr)
                if (!n.path("passed").asBoolean(true)) failed.add(n.path("name").asText(""));
            return failed.isEmpty() ? null : String.join("\n", failed);
        } catch (Exception e) { return null; }
    }

    /** Manuel tetik (controller). */
    public Map<String, Object> triggerScriptedCheck(com.sitemonitor.model.ScriptedMonitor m) {
        return recheckScripted(m, true);
    }

    /**
     * Manuel "Şimdi Çalıştır" için sanal-thread havuzu.
     *
     * <p>Gerçek eşzamanlılık zaten {@code ScriptedCheckerService}'in semaforuyla (pool-size,
     * varsayılan 2) sınırlı; burada bekleyen thread'ler yalnız park eder. Sanal thread oldukları
     * için maliyetleri ihmal edilebilir, ayrıca monitör başına manuel-tetik cooldown'u (varsayılan
     * 20 sn) kuyruğun şişmesini engelliyor.
     */
    private final java.util.concurrent.ExecutorService manualRunPool =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Kapanışta havuzu kapat (2026-08-20 bellek denetimi). Üretimde etkisi ~sıfırdır (JVM zaten
     * ölüyor, sanal thread'ler daemon); değeri TEST ve context-refresh senaryolarındadır: kapatılmayan
     * her havuz, süit boyunca açılıp kapanan onlarca Spring context'inde arkasında iş parçacığı ve
     * bağlı nesne bırakır. Kod tabanındaki diğer tüm executor'lar (@PreDestroy'lu MonitoringOutage,
     * EmailNotification, PageChecker, ScriptedChecker, HttpChecker) bu deseni izliyor; burası
     * eksik kalmıştı.
     */
    @jakarta.annotation.PreDestroy
    void shutdownManualRunPool() {
        manualRunPool.shutdown();
        try {
            if (!manualRunPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                manualRunPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            manualRunPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Manuel kontrolü İSTEK THREAD'İNİN DIŞINDA başlatır ve {@link Future} döner.
     *
     * <p>Neden gerekli: senaryo kontrolü script'in kendi timeout'u kadar sürebiliyor (tavan 180 sn).
     * Senkron çalıştırıldığında HTTP isteği o kadar bloke kalıyor ve ters-vekil (NetScaler) daha
     * önce kopartıyordu: kullanıcı <b>504 "Gateway timeout"</b> görüyor, oysa kontrol sorunsuz
     * koşup kaydediliyor. Yani buton, işi başarıyla yaptığı hâlde hata veriyordu.
     *
     * <p>{@code recheckScripted} zaten arka plan sweep'inden çağrılıyor; istek bağlamına bağımlı
     * değildir. İstisnalar burada loglanır, yutulmaz.
     */
    public Future<Map<String, Object>> triggerScriptedCheckAsync(com.sitemonitor.model.ScriptedMonitor m) {
        return manualRunPool.submit(() -> {
            try {
                Map<String, Object> r = recheckScripted(m, true);
                // Değerlendirme BURADA yapılır, ucun beklemesinden BAĞIMSIZ olarak. Eskiden uç ayrıca
                // evaluateScriptedNow(m) çağırıyordu; o da kendi k6 koşumunu başlatıyordu → tek tıkla
                // İKİ süreç, iki ScriptedCheck satırı, iki permit. Yarışta recheckScripted "skipped"
                // dönünce teyit zinciri "kanıt yok ⇒ iptal" deyip alarmı sessizce düşürüyordu.
                // Uç kısa bekleme sonunda zaman aşımına düşse bile alarm değerlendirmesi kaybolmaz.
                evaluateScriptedNow(m, r);
                return r;
            } catch (RuntimeException e) {
                log.error("Manuel senaryo kontrolü başarısız (monitor={} id={})", m.getName(), m.getId(), e);
                throw e;
            }
        });
    }

    // ── HTTP SSL + Domain (WHOIS/RDAP) yavaş sweep'i — sıcak uptime döngüsünden AYRI (tek-pod perf) ──
    @Scheduled(fixedDelayString = "${site.monitor.http.ssl-domain-interval-ms:86400000}", initialDelayString = "120000")
    public void runHttpSslDomainChecks() {
        if (!appSettings.getBoolean("site.monitor.http.alert-enabled", true)) return;
        if (!tryAcquireSchedulerLock("http-ssl-domain-sweep", sweepLockTtlMinutes)) {
            log.debug("HTTP SSL/Domain sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runHttpSslDomainChecksLocked();
        } finally {
            releaseSchedulerLock("http-ssl-domain-sweep");
        }
    }

    /** Yavaş döngü: SSL (cert checker) + Domain (RDAP) değerlendirir; her ikisi de teyitsiz eşik durumu.
     *  Toggle kapalıysa lingering alarmı kurtarmak için sentetik "up" gönderilir. */
    private void runHttpSslDomainChecksLocked() {
        List<HttpMonitor> monitors = httpMonitorRepo.findByActiveTrue();
        if (monitors.isEmpty()) return;
        List<MonitoringOutageService.SweepItem> sslSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> domainSweep = new ArrayList<>();
        // Faz 1: SSL değerlendirmesi paralel başlar (F1); Domain (RDAP) SIRALI kalır (registry-dostu —
        // domain sweep'lerindeki bilinçli seri akışla aynı gerekçe). Toggle kapalıysa task başlatılmaz.
        List<java.util.AbstractMap.SimpleEntry<HttpMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (HttpMonitor m : monitors) {
            boolean sslOn = Boolean.TRUE.equals(m.getCheckSslErrors()) || Boolean.TRUE.equals(m.getSslExpiryReminders());
            started.add(new java.util.AbstractMap.SimpleEntry<>(m, sslOn ? startNetworkCheck(() -> evalHttpSsl(m)) : null));
        }
        // Faz 2: sıralı işleme — SSL exception'ı o monitörün domain eval'ini bugünkü gibi atlatır (tek try).
        for (var entry : started) {
            HttpMonitor m = entry.getKey();
            try {
                if (entry.getValue() != null) {
                    Map<String, Object> ev = entry.getValue().get();
                    Map<String, Object> ctx = sslDomainCtx(m);
                    if (ev.get("ssl_days_remaining") != null) ctx.put("ssl_days_remaining", ev.get("ssl_days_remaining"));
                    if (ev.get("detail") != null) ctx.put("detail", ev.get("detail"));
                    sslSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_HTTP_SSL, m.getUrl(),
                            String.valueOf(ev.getOrDefault("detail", "SSL")),
                            "up".equals(ev.get("status")), (String) ev.get("error"),
                            chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalHttpSsl(m)));
                } else {
                    sslSweep.add(upItem(EscalationService.TYPE_HTTP_SSL, m, "SSL"));   // toggle kapalı → lingering alarmı kurtar
                }
                if (Boolean.TRUE.equals(m.getDomainExpiryReminders())) {
                    Map<String, Object> ev = evalHttpDomain(m);
                    Map<String, Object> ctx = sslDomainCtx(m);
                    if (ev.get("domain") != null) ctx.put("domain", ev.get("domain"));
                    if (ev.get("domain_days_remaining") != null) ctx.put("domain_days_remaining", ev.get("domain_days_remaining"));
                    domainSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_DOMAIN_EXPIRY, m.getUrl(),
                            String.valueOf(ev.getOrDefault("domain", "domain")),
                            "up".equals(ev.get("status")), (String) ev.get("error"),
                            chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalHttpDomain(m)));
                } else {
                    domainSweep.add(upItem(EscalationService.TYPE_DOMAIN_EXPIRY, m, "domain"));
                }
            } catch (Exception e) {
                log.warn("HTTP SSL/Domain check failed for {}: {}", m.getUrl(), e.getMessage());
            }
        }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_HTTP_SSL, sslSweep); }
        catch (Exception e) { log.warn("HTTP SSL outage processing failed: {}", e.getMessage()); }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_DOMAIN_EXPIRY, domainSweep); }
        catch (Exception e) { log.warn("Domain expiry outage processing failed: {}", e.getMessage()); }
        log.debug("HTTP SSL/Domain checks complete: {} monitors", monitors.size());
    }

    /** SSL/Domain sweep'i için ortak ctx — eşik durumu (teyitsiz/anında) + takım yönlendirme. */
    private Map<String, Object> sslDomainCtx(HttpMonitor m) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("url", m.getUrl());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", 0);   // eşik durumu — re-check churn'ü yok, anında
        ctx.put("monitor_recovery_checks", 1);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        return ctx;
    }

    /** Toggle kapalı monitör için sentetik "up" SweepItem (lingering alarm kurtarma). */
    private MonitoringOutageService.SweepItem upItem(String type, HttpMonitor m, String detail) {
        return new MonitoringOutageService.SweepItem(type, m.getUrl(), detail, true, null,
                sslDomainCtx(m), SchedulerService::upStatus);
    }

    private static Map<String, Object> upStatus() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("status", "up");
        return u;
    }

    /**
     * Sunulan sertifikanın GÜVENLİK kusuru — HTTP/Keyword SSL dalı için.
     *
     * <p>Sertifika izlemesindeki kararla aynı çekirdeğe ({@code CertificateHealthRules.securityFlags})
     * ve AYNI ayarlara bağlıdır: ayarlar kapalıyken davranış bugünküyle birebir aynı kalır.
     *
     * @return alarm detayı ya da null (kusur yok / ayar kapalı)
     */
    private String sslSecurityDetail(Map<String, Object> cr) {
        Object sanRaw = cr.get("san");
        java.util.List<String> san = sanRaw instanceof java.util.List<?> l
                ? l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : java.util.List.of();
        var flags = CertificateHealthRules.securityFlags(
                (String) cr.get("domain"), san, (String) cr.get("trust_status"));
        if (flags.contains(CertificateHealthRules.FLAG_HOSTNAME_MISMATCH)
                && appSettings.getBoolean(EscalationService.SETTING_ALERT_HOSTNAME_MISMATCH, true)) {
            return "Sertifika bu alan adını kapsamıyor";
        }
        if (flags.contains(CertificateHealthRules.FLAG_UNTRUSTED_CA)
                && appSettings.getBoolean(EscalationService.SETTING_ALERT_UNTRUSTED, false)) {
            return "Sertifika güvenilir bir CA'ya bağlanmıyor";
        }
        return null;
    }

    /** HTTP monitörünün URL host'u için TLS sertifika değerlendirmesi (checkSslErrors + sslExpiryReminders).
     *  {"status":"up"|"down","error"?,"ssl_days_remaining"?,"detail"?} döner. */
    private Map<String, Object> evalHttpSsl(HttpMonitor m) {
        String host = RdapDomainExpiryService.extractHost(m.getUrl());
        Map<String, Object> out = new LinkedHashMap<>();
        if (host == null || host.isBlank()) { out.put("status", "up"); return out; }   // host yok → alarm yok
        Map<String, Object> cr = checkerService.check(host, 443, false, null);
        String status = String.valueOf(cr.get("status"));                     // valid | warning | error
        String chain = String.valueOf(cr.getOrDefault("chain_status", ""));
        Integer days = cr.get("days_remaining") instanceof Number n ? n.intValue() : null;
        boolean problem = false;
        String detail = null;
        if (Boolean.TRUE.equals(m.getCheckSslErrors())) {
            if ("error".equals(status))       { problem = true; detail = "TLS erişim/doğrulama hatası"; }
            else if ("BROKEN".equals(chain))  { problem = true; detail = "Sertifika zinciri bozuk"; }
            else if ("REVOKED".equals(chain)) { problem = true; detail = "Sertifika iptal edilmiş"; }
            else {
                // Sertifika izlemesiyle AYNI hüküm: süresi uzak ama bu host için kabul edilemez bir
                // sertifika (tarayıcının reddettiği şekil) buradan da kaçıyordu.
                String sec = sslSecurityDetail(cr);
                if (sec != null) { problem = true; detail = sec; }
            }
        }
        if (!problem && Boolean.TRUE.equals(m.getSslExpiryReminders()) && days != null) {
            int threshold = maxDays(m.getSslReminderDays(), 30);
            if (days <= threshold) { problem = true; detail = "Sertifika bitişine " + days + " gün"; }
        }
        out.put("status", problem ? "down" : "up");
        if (days != null) out.put("ssl_days_remaining", days);
        if (detail != null) out.put("detail", detail);
        if ("error".equals(status)) out.put("error", cr.get("error"));
        return out;
    }

    /** HTTP monitörünün domain'i için registrar (WHOIS/RDAP) bitiş değerlendirmesi (domainExpiryReminders).
     *  {"status":"up"|"down","error"?,"domain"?,"domain_days_remaining"?} döner. days null (unknown) → alarm yok. */
    private Map<String, Object> evalHttpDomain(HttpMonitor m) {
        Map<String, Object> rd = rdapDomainExpiryService.check(m.getUrl());
        Integer days = rd.get("days_remaining") instanceof Number n ? n.intValue() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", rd.get("domain"));
        boolean problem = false;
        if (days != null) {
            int threshold = maxDays(m.getDomainReminderDays(), 30);
            problem = days <= threshold;
            out.put("domain_days_remaining", days);
        }
        out.put("status", problem ? "down" : "up");   // days null (unknown) → up (alarm yok)
        return out;
    }

    /** CSV gün eşiklerinden en büyüğü ("30,14,7" → 30); parse edilemezse fallback. */
    private static int maxDays(String csv, int fallback) {
        if (csv == null || csv.isBlank()) return fallback;
        int max = -1;
        for (String p : csv.split(",")) {
            try { max = Math.max(max, Integer.parseInt(p.trim())); } catch (NumberFormatException ignore) {}
        }
        return max > 0 ? max : fallback;
    }

    /** Yavaş yanıt yeniden-ölçümü (KEYWORD_SLOW confirm/recovery re-check'i) — taze fetch, KeywordResult PERSIST ETMEZ.
     *  {"status":"up"|"down","response_ms"?,"threshold_ms"} döner. slowResponseEnabled kapalı/HTTP-hatası → up. */
    private Map<String, Object> evalKeywordSlow(KeywordMonitor m) {
        Map<String, Object> out = new LinkedHashMap<>();
        int th = m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : 3000;
        out.put("threshold_ms", th);
        if (!Boolean.TRUE.equals(m.getSlowResponseEnabled())) { out.put("status", "up"); return out; }
        int timeout = m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000;
        Map<String, Object> r = keywordCheckerService.check(m.getUrl(), m.getKeyword(), timeout, m.getCustomHeaders(),
                Boolean.TRUE.equals(m.getCaseSensitive()));
        Long ms = r.get("response_ms") instanceof Number n ? n.longValue() : null;
        if (ms != null) out.put("response_ms", ms);
        boolean slow = r.get("error") == null && ms != null && ms > th;   // HTTP hatası → yavaşlık değerlendirilemez → up
        out.put("status", slow ? "down" : "up");
        return out;
    }

    // ── Keyword SSL + Domain yavaş sweep'i — HTTP'den AYRI tiplerle (KEYWORD_SSL / KEYWORD_DOMAIN_EXPIRY) ──
    @Scheduled(fixedDelayString = "${site.monitor.keyword.ssl-domain-interval-ms:86400000}", initialDelayString = "150000")
    public void runKeywordSslDomainChecks() {
        if (!appSettings.getBoolean("site.monitor.keyword.alert-enabled", true)) return;
        if (!tryAcquireSchedulerLock("keyword-ssl-domain-sweep", sweepLockTtlMinutes)) {
            log.debug("Keyword SSL/Domain sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runKeywordSslDomainChecksLocked();
        } finally {
            releaseSchedulerLock("keyword-ssl-domain-sweep");
        }
    }

    /** Keyword monitörleri için yavaş SSL+Domain döngüsü — runHttpSslDomainChecksLocked aynası, AYRI alarm tipleriyle. */
    private void runKeywordSslDomainChecksLocked() {
        List<KeywordMonitor> monitors = keywordMonitorRepo.findByActiveTrue();
        if (monitors.isEmpty()) return;
        List<MonitoringOutageService.SweepItem> sslSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> domainSweep = new ArrayList<>();
        // Faz 1: SSL değerlendirmesi paralel başlar (F1); Domain (RDAP) SIRALI kalır (registry-dostu).
        List<java.util.AbstractMap.SimpleEntry<KeywordMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (KeywordMonitor m : monitors) {
            boolean sslOn = Boolean.TRUE.equals(m.getCheckSslErrors()) || Boolean.TRUE.equals(m.getSslExpiryReminders());
            started.add(new java.util.AbstractMap.SimpleEntry<>(m, sslOn ? startNetworkCheck(() -> evalKeywordSsl(m)) : null));
        }
        // Faz 2: sıralı işleme — SSL exception'ı o monitörün domain eval'ini bugünkü gibi atlatır (tek try).
        for (var entry : started) {
            KeywordMonitor m = entry.getKey();
            try {
                if (entry.getValue() != null) {
                    Map<String, Object> ev = entry.getValue().get();
                    Map<String, Object> ctx = keywordSslDomainCtx(m);
                    if (ev.get("ssl_days_remaining") != null) ctx.put("ssl_days_remaining", ev.get("ssl_days_remaining"));
                    if (ev.get("detail") != null) ctx.put("detail", ev.get("detail"));
                    sslSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_KEYWORD_SSL, m.getUrl(),
                            String.valueOf(ev.getOrDefault("detail", "SSL")),
                            "up".equals(ev.get("status")), (String) ev.get("error"),
                            chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalKeywordSsl(m)));
                } else {
                    sslSweep.add(upItemKeyword(EscalationService.TYPE_KEYWORD_SSL, m, "SSL"));   // toggle kapalı → lingering kurtar
                }
                if (Boolean.TRUE.equals(m.getDomainExpiryReminders())) {
                    Map<String, Object> ev = evalKeywordDomain(m);
                    Map<String, Object> ctx = keywordSslDomainCtx(m);
                    if (ev.get("domain") != null) ctx.put("domain", ev.get("domain"));
                    if (ev.get("domain_days_remaining") != null) ctx.put("domain_days_remaining", ev.get("domain_days_remaining"));
                    domainSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY, m.getUrl(),
                            String.valueOf(ev.getOrDefault("domain", "domain")),
                            "up".equals(ev.get("status")), (String) ev.get("error"),
                            chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> evalKeywordDomain(m)));
                } else {
                    domainSweep.add(upItemKeyword(EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY, m, "domain"));
                }
            } catch (Exception e) {
                log.warn("Keyword SSL/Domain check failed for {}: {}", m.getUrl(), e.getMessage());
            }
        }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_KEYWORD_SSL, sslSweep); }
        catch (Exception e) { log.warn("Keyword SSL outage processing failed: {}", e.getMessage()); }
        try { monitoringOutageService.handleSweepResults(EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY, domainSweep); }
        catch (Exception e) { log.warn("Keyword domain expiry outage processing failed: {}", e.getMessage()); }
        log.debug("Keyword SSL/Domain checks complete: {} monitors", monitors.size());
    }

    /** Keyword SSL/Domain sweep ortak ctx — eşik durumu (teyitsiz/anında) + takım yönlendirme. */
    private Map<String, Object> keywordSslDomainCtx(KeywordMonitor m) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("url", m.getUrl());
        ctx.put("monitor_id", m.getId());
        ctx.put("monitor_confirm_attempts", 0);   // eşik durumu — re-check churn'ü yok, anında
        ctx.put("monitor_recovery_checks", 1);
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        return ctx;
    }

    /** Toggle kapalı keyword monitörü için sentetik "up" SweepItem (lingering alarm kurtarma). */
    private MonitoringOutageService.SweepItem upItemKeyword(String type, KeywordMonitor m, String detail) {
        return new MonitoringOutageService.SweepItem(type, m.getUrl(), detail, true, null,
                keywordSslDomainCtx(m), SchedulerService::upStatus);
    }

    /** Keyword monitörünün URL host'u için TLS sertifika değerlendirmesi — evalHttpSsl aynası. */
    private Map<String, Object> evalKeywordSsl(KeywordMonitor m) {
        String host = RdapDomainExpiryService.extractHost(m.getUrl());
        Map<String, Object> out = new LinkedHashMap<>();
        if (host == null || host.isBlank()) { out.put("status", "up"); return out; }   // host yok → alarm yok
        Map<String, Object> cr = checkerService.check(host, 443, false, null);
        String status = String.valueOf(cr.get("status"));                     // valid | warning | error
        String chain = String.valueOf(cr.getOrDefault("chain_status", ""));
        Integer days = cr.get("days_remaining") instanceof Number n ? n.intValue() : null;
        boolean problem = false;
        String detail = null;
        if (Boolean.TRUE.equals(m.getCheckSslErrors())) {
            if ("error".equals(status))       { problem = true; detail = "TLS erişim/doğrulama hatası"; }
            else if ("BROKEN".equals(chain))  { problem = true; detail = "Sertifika zinciri bozuk"; }
            else if ("REVOKED".equals(chain)) { problem = true; detail = "Sertifika iptal edilmiş"; }
            else {
                // Sertifika izlemesiyle AYNI hüküm: süresi uzak ama bu host için kabul edilemez bir
                // sertifika (tarayıcının reddettiği şekil) buradan da kaçıyordu.
                String sec = sslSecurityDetail(cr);
                if (sec != null) { problem = true; detail = sec; }
            }
        }
        if (!problem && Boolean.TRUE.equals(m.getSslExpiryReminders()) && days != null) {
            int threshold = maxDays(m.getSslReminderDays(), 30);
            if (days <= threshold) { problem = true; detail = "Sertifika bitişine " + days + " gün"; }
        }
        out.put("status", problem ? "down" : "up");
        if (days != null) out.put("ssl_days_remaining", days);
        if (detail != null) out.put("detail", detail);
        if ("error".equals(status)) out.put("error", cr.get("error"));
        return out;
    }

    /** Keyword monitörünün domain'i için registrar (WHOIS/RDAP) bitiş değerlendirmesi — evalHttpDomain aynası. */
    private Map<String, Object> evalKeywordDomain(KeywordMonitor m) {
        Map<String, Object> rd = rdapDomainExpiryService.check(m.getUrl());
        Integer days = rd.get("days_remaining") instanceof Number n ? n.intValue() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", rd.get("domain"));
        boolean problem = false;
        if (days != null) {
            int threshold = maxDays(m.getDomainReminderDays(), 30);
            problem = days <= threshold;
            out.put("domain_days_remaining", days);
        }
        out.put("status", problem ? "down" : "up");   // days null (unknown) → up (alarm yok)
        return out;
    }

    // ── Domain (alan adı) süre-bitişi sweep'i — günlük + jitter; RDAP/WHOIS (DomainCheckerService) ──
    @Scheduled(fixedDelayString = "${site.monitor.domain.interval-ms:3600000}", initialDelayString = "90000")
    public void runDomainChecks() {
        if (!appSettings.getBoolean("site.monitor.domain.alert-enabled", true)) return;
        if (!tryAcquireSchedulerLock("domain-sweep", sweepLockTtlMinutes)) {
            log.debug("Domain sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runDomainChecksLocked();
        } finally {
            releaseSchedulerLock("domain-sweep");
        }
    }

    // ── Kritik domain ikinci günlük kontrolü — YALNIZ son kontrolünde eşik-altı (days_remaining ≤ threshold) aktif domainler ──
    //    Amaç: kritik bir domain gün içinde YENİLENİRSE (bitiş uzarsa) açık DOMAINMON_EXPIRY alarmı ertesi sabahki
    //    sweep'i beklemeden aynı gün (16:00) otomatik kapansın; durum taze kalsın.
    //    Ayrı scheduler_lock anahtarı ("domain-critical-sweep") ile HA'da tek pod çalışır — günlük "domain-sweep" ile çakışmaz.
    //    evaluateDomainAlarmsNow reAlert dedupe'unu koruduğundan HÂLÂ kritik domain için İKİNCİ bir bildirim ÜRETMEZ:
    //    yalnız durumu günceller / yenilenmişse alarmı kapatır.
    @Scheduled(cron = "${site.monitor.domain.critical-check-cron:0 0 16 * * *}", zone = "Europe/Istanbul")
    public void runCriticalDomainChecks() {
        if (!appSettings.getBoolean("site.monitor.domain.alert-enabled", true)) return;            // domain izleme duraklatıldı → hiç kontrol yok
        if (!appSettings.getBoolean("site.monitor.domain.critical-check-enabled", true)) return;   // ikinci kontrol ops kill-switch
        if (!tryAcquireSchedulerLock("domain-critical-sweep", sweepLockTtlMinutes)) {
            log.debug("Kritik domain sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runCriticalDomainChecksLocked();
        } finally {
            releaseSchedulerLock("domain-critical-sweep");
        }
    }

    /** Son kontrolünde days_remaining ≤ eşik olan aktif domainleri ikinci kez kontrol eder.
     *  RDAP dostu: günlük sweep ile AYNI sıralı akış (paralellik yok; RdapDomainClient 429-retry içeride).
     *  Her domain için yeni bir domain_checks satırı yazılır (aynı gün 2 satır — beklenen, dedupe yok). */
    private void runCriticalDomainChecksLocked() {
        int threshold = appSettings.getInt("site.monitor.domain.critical-check-threshold-days", 7);
        // Son kontrol days_remaining ≤ eşik olan monitör id'leri (tek toplu sorgu — N+1 yok)
        java.util.Set<Long> criticalIds = domainCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null && c.getDaysRemaining() != null && c.getDaysRemaining() <= threshold)
                .map(DomainCheck::getMonitorId)
                .collect(java.util.stream.Collectors.toSet());
        if (criticalIds.isEmpty()) {
            log.debug("Kritik domain ikinci kontrolü: eşik (≤{}g) altında domain yok", threshold);
            return;
        }
        List<DomainMonitor> monitors = domainMonitorRepo.findByActiveTrue().stream()
                .filter(m -> criticalIds.contains(m.getId()))
                .toList();
        int checked = 0, renewed = 0;
        for (DomainMonitor m : monitors) {
            try {
                Map<String, Object> r = domainCheckerService.check(m);   // yeni domain_checks satırı persist eder
                evaluateDomainAlarmsNow(m, r);                           // yenilendiyse EXPIRY alarmı kapanır; hâlâ kritikse reAlert dedupe → yeni bildirim YOK
                Integer days = r.get("days_remaining") instanceof Number n ? n.intValue() : null;
                int warn = m.getWarningDays() != null ? m.getWarningDays() : 30;
                if (days != null && days > warn) renewed++;
                checked++;
            } catch (Exception e) {
                log.warn("Kritik domain kontrolü başarısız: {} — {}", m.getDomain(), e.getMessage());
            }
        }
        log.info("Kritik domain ikinci kontrolü tamam: {} domain (eşik ≤{}g), {} yenilenmiş", checked, threshold, renewed);
    }

    /** Domain izleme her tip için AYRI sweep üretir: UNKNOWN / EXPIRY / STATUS(EPP) / CHANGED. */
    private void runDomainChecksLocked() {
        List<DomainMonitor> monitors = domainMonitorRepo.findByActiveTrue();
        try {
            java.util.Set<String> existing = domainMonitorRepo.findAll().stream()
                    .map(DomainMonitor::getDomain).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedDomainMonAlerts(existing);
        } catch (Exception e) {
            log.warn("Öksüz domain alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        List<MonitoringOutageService.SweepItem> expirySweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> unknownSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> statusSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> changedSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> lockSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> blacklistSweep = new ArrayList<>();
        int checked = 0;
        for (DomainMonitor m : monitors) {
            if (!checkDue("domain", m.getId(), jitteredInterval(m.getIntervalSeconds()))) continue;
            try {
                Map<String, Object> r = domainCheckerService.check(m);   // DomainCheck persist eder
                checked++;
                addDomainSweepItems(m, r, unknownSweep, expirySweep, statusSweep, changedSweep,
                        lockSweep, blacklistSweep);
            } catch (Exception e) {
                log.warn("Domain check failed for {}: {}", m.getDomain(), e.getMessage());
            }
        }
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_UNKNOWN, unknownSweep);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_EXPIRY, expirySweep);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_STATUS, statusSweep);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_CHANGED, changedSweep);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, lockSweep);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_BLACKLIST, blacklistSweep);
        log.debug("Domain checks complete: {} monitors", checked);
    }

    /** Tek domain kontrol sonucundan 4 tipin (UNKNOWN/EXPIRY/STATUS/CHANGED) sweep item'larını üretir.
     *  Sweep döngüsü ve manuel "Şimdi Kontrol Et" (evaluateDomainAlarmsNow) AYNI mantığı paylaşsın diye ayrıldı. */
    private void addDomainSweepItems(DomainMonitor m, Map<String, Object> r,
            List<MonitoringOutageService.SweepItem> unknownSweep,
            List<MonitoringOutageService.SweepItem> expirySweep,
            List<MonitoringOutageService.SweepItem> statusSweep,
            List<MonitoringOutageService.SweepItem> changedSweep,
            List<MonitoringOutageService.SweepItem> lockSweep,
            List<MonitoringOutageService.SweepItem> blacklistSweep) {
        String status = String.valueOf(r.get("status"));
        Integer days = r.get("days_remaining") instanceof Number n ? n.intValue() : null;
        boolean eppCritical = Boolean.TRUE.equals(r.get("epp_critical"));
        boolean eppWarn = Boolean.TRUE.equals(r.get("epp_warn"));
        boolean changed = Boolean.TRUE.equals(r.get("changed"));
        int warn = m.getWarningDays() != null ? m.getWarningDays() : 30;
        int crit = m.getCriticalDays() != null ? m.getCriticalDays() : 7;

        // UNKNOWN — "veri yok" kendi başına alarm (körlük)
        unknownSweep.add(domainItem(EscalationService.TYPE_DOMAINMON_UNKNOWN, m, r, !"UNKNOWN".equals(status), "WARNING"));
        // EXPIRY — gün eşiği. Seviye kartın DURUMU ile HİZALI olmalı (DomainCheckerService: days≤crit→CRITICAL,
        // crit<days≤warn→WARNING); aksi halde 25 günlük (WARNING durumundaki) bir monitör mailde "YÜKSEK" görünür.
        boolean expiryDown = days != null && days <= warn;
        String expiryLevel = (days != null && (days < 0 || days <= crit)) ? "CRITICAL" : "WARNING";
        expirySweep.add(domainItem(EscalationService.TYPE_DOMAINMON_EXPIRY, m, r, !expiryDown, expiryLevel));
        // STATUS — EPP kodları
        statusSweep.add(domainItem(EscalationService.TYPE_DOMAINMON_STATUS, m, r,
                !(eppCritical || eppWarn), eppCritical ? "CRITICAL" : "HIGH"));
        // CHANGED — yalnız değişimde (up gönderilmez → manuel ack'e kadar açık; hijack sinyali)
        if (changed && !Boolean.FALSE.equals(m.getChangeAlert()))
            changedSweep.add(domainItem(EscalationService.TYPE_DOMAINMON_CHANGED, m, r, false, "HIGH"));
        // TRANSFER_LOCK — YALNIZ kesin "NONE" alarm üretir. UNKNOWN (WHOIS/.tr, veri yok) up
        // sayılır: kilidi doğrulayamamak, kilit olmadığı anlamına GELMEZ.
        lockSweep.add(domainItem(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, m, r,
                !(isLockMissing(r) && !Boolean.FALSE.equals(m.getTransferLockAlert())), "HIGH"));
        // BLACKLIST — yalnız LISTED alarm üretir; UNKNOWN/SKIPPED up. Doğrulanamayan bir
        // sorgudan alarm üretmek, kurumsal resolver kısıtında tüm envanteri sahte alarma boğardı.
        blacklistSweep.add(domainItem(EscalationService.TYPE_DOMAINMON_BLACKLIST, m, r,
                !DnsblCheckerService.LISTED.equals(r.get("blacklist_status")), "HIGH"));
    }

    /**
     * Manuel "Şimdi Kontrol Et" sonrası TEK domain için alarm değerlendirmesi — günlük sweep ile AYNI mantık.
     * Neden gerekli: sweep her domaini {@code checkDue} ile ~günde 1 kez işler; kullanıcı bir domaini elle kontrol
     * edip WARNING/CRITICAL/UNKNOWN görürse alarm/e-posta günlük slota kadar gecikmesin, ANINDA üretilsin.
     * {@code r} = domainCheckerService.check(m) sonucu (aynı sonuç tekrar kontrol edilmez).
     */
    public void evaluateDomainAlarmsNow(DomainMonitor m, Map<String, Object> r) {
        if (m == null || r == null) return;
        if (!appSettings.getBoolean("site.monitor.domain.alert-enabled", true)) return;
        List<MonitoringOutageService.SweepItem> unknown = new ArrayList<>(), expiry = new ArrayList<>(),
                status = new ArrayList<>(), changed = new ArrayList<>(),
                lock = new ArrayList<>(), blacklist = new ArrayList<>();
        addDomainSweepItems(m, r, unknown, expiry, status, changed, lock, blacklist);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_UNKNOWN, unknown);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_EXPIRY, expiry);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_STATUS, status);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_CHANGED, changed);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, lock);
        handleDomainSweep(EscalationService.TYPE_DOMAINMON_BLACKLIST, blacklist);
    }

    /** Kilit KESİN yok mu? UNKNOWN (WHOIS/.tr, veri yok) alarm üretmez — kural: doğrulanamadı ≠ yok. */
    private static boolean isLockMissing(Map<String, Object> r) {
        return "NONE".equals(String.valueOf(r.get("transfer_lock")));
    }

    private void handleDomainSweep(String type, List<MonitoringOutageService.SweepItem> sweep) {
        try { monitoringOutageService.handleSweepResults(type, sweep); }
        catch (Exception e) { log.warn("Domain outage processing failed [{}]: {}", type, e.getMessage()); }
    }

    /** Günlük kontrolleri güne yayan jitter — istenen aralıktan rastgele (≤6 saat veya ¼) daha erken due yapar. */
    private static int jitteredInterval(Integer intervalSeconds) {
        int base = intervalSeconds != null ? intervalSeconds : 86400;
        int maxJitter = Math.min(base / 4, 6 * 3600);
        if (maxJitter <= 0) return base;
        return base - java.util.concurrent.ThreadLocalRandom.current().nextInt(maxJitter + 1);
    }

    private MonitoringOutageService.SweepItem domainItem(String type, DomainMonitor m, Map<String, Object> r, boolean up, String level) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_name", m.getName());   // subject standardı: ad > çıplak URL
        ctx.put("domain", m.getDomain());
        ctx.put("monitor_id", m.getId());
        ctx.put("alert_level", level);
        // EşİK durumları (EXPIRY/STATUS/CHANGED) anında alarm açar: "30 gün kaldı" sorusunu
        // 30 sn sonra tekrar sormak cevabı değiştirmez, yalnızca alarmı geciktirir.
        // UNKNOWN farklıdır — sorgu BAŞARISIZ demektir ve bu gerçekten geçici olabilir;
        // yalnız orada izlemenin kendi doğrulama ayarı uygulanır (aksi halde formdaki alan
        // hiçbir şey yapmayan bir süs olurdu — notify_email hatasının aynısı).
        boolean transientType = EscalationService.TYPE_DOMAINMON_UNKNOWN.equals(type);
        ctx.put("monitor_confirm_attempts", transientType ? m.getConfirmAttempts() : Integer.valueOf(0));
        if (transientType && m.getConfirmIntervalSeconds() != null)
            ctx.put("monitor_confirm_interval_ms", m.getConfirmIntervalSeconds() * 1000L);
        ctx.put("monitor_recovery_checks", transientType ? m.getRecoveryChecks() : Integer.valueOf(1));
        if (m.getTeamId() != null) ctx.put("team_id", m.getTeamId());
        if (m.getNotificationGroupId() != null) ctx.put("notification_group_id", m.getNotificationGroupId());
        if (r.get("days_remaining") != null) ctx.put("days", r.get("days_remaining"));
        if (r.get("expiry_date") != null) ctx.put("expiry_date", r.get("expiry_date"));
        if (r.get("registrar") != null) ctx.put("registrar", r.get("registrar"));
        if (r.get("source") != null) ctx.put("source", r.get("source"));
        if (r.get("status_codes") instanceof List<?> l && !l.isEmpty())
            ctx.put("status_codes", l.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
        if (r.get("nameservers") instanceof List<?> ns && !ns.isEmpty())
            ctx.put("nameservers", ns.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
        ctx.put("checked_at", r.get("checked_at") != null ? r.get("checked_at") : ISO.format(Instant.now()));
        if (r.get("change_detail") != null) ctx.put("change_detail", r.get("change_detail"));
        if (r.get("transfer_lock") != null) ctx.put("transfer_lock", r.get("transfer_lock"));
        if (r.get("blacklist_status") != null) ctx.put("blacklist_status", r.get("blacklist_status"));
        if (r.get("blacklist_detail") != null) ctx.put("blacklist_detail", r.get("blacklist_detail"));
        if (r.get("blacklist_hits") != null) ctx.put("blacklist_hits", r.get("blacklist_hits"));
        if (r.get("blacklist_delta") != null) ctx.put("blacklist_delta", r.get("blacklist_delta"));
        if (r.get("error") != null) ctx.put("last_error", r.get("error"));
        return new MonitoringOutageService.SweepItem(type, m.getDomain(), m.getDomain(),
                up, (String) r.get("error"), chanCtx(ctx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckDomainFor(m, type));
    }

    private Map<String, Object> recheckDomainFor(DomainMonitor m, String type) {
        Map<String, Object> r = domainCheckerService.check(m);
        String status = String.valueOf(r.get("status"));
        Integer days = r.get("days_remaining") instanceof Number n ? n.intValue() : null;
        int warn = m.getWarningDays() != null ? m.getWarningDays() : 30;
        boolean up = switch (type) {
            case EscalationService.TYPE_DOMAINMON_UNKNOWN -> !"UNKNOWN".equals(status);
            case EscalationService.TYPE_DOMAINMON_EXPIRY  -> !(days != null && days <= warn);
            case EscalationService.TYPE_DOMAINMON_STATUS  -> !(Boolean.TRUE.equals(r.get("epp_critical")) || Boolean.TRUE.equals(r.get("epp_warn")));
            // Yeni tipler BURAYA da yazılmalı: default -> true onları sessizce "düzeldi" sayar
            // ve alarm hiç açılmazdı.
            case EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK ->
                    !(isLockMissing(r) && !Boolean.FALSE.equals(m.getTransferLockAlert()));
            case EscalationService.TYPE_DOMAINMON_BLACKLIST ->
                    !DnsblCheckerService.LISTED.equals(r.get("blacklist_status"));
            default -> true;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", up ? "up" : "down");
        out.put("error", r.get("error"));
        return out;
    }

    // ── Ping monitor sweep (serbest-form; envanter filtresi YOK) ─────────────────
    @Scheduled(fixedDelayString = "${site.monitor.ping.interval-ms:30000}", initialDelayString = "65000")
    public void runPingChecks() {
        if (!appSettings.getBoolean("site.monitor.ping.alert-enabled", true)) return;   // izleme duraklatıldı → kontrol+alarm yok
        // HA: tüm-tur dağıtık kilit — 2+ pod'da bir turu (öksüz-alarm temizliği dahil) yalnız bir pod çalıştırır.
        if (!tryAcquireSchedulerLock("ping-sweep", sweepLockTtlMinutes)) {
            log.debug("Ping sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runPingChecksLocked();
        } finally {
            releaseSchedulerLock("ping-sweep");
        }
    }

    /** Tüm-tur kilit içinde çalışan gerçek ping sweep gövdesi (bkz. runPingChecks). */
    private void runPingChecksLocked() {
        List<PingMonitor> monitors = pingMonitorRepo.findByActiveTrue();
        // Öksüz ping alarmı temizliği: host rename/silme sonrası recovery'nin asla kapatamadığı açık
        // PING_DOWN alarmlarını kapat (aktif+pasif TÜM mevcut host'lara göre). Aktif izleme yoksa da çalışır.
        if (orphanCleanupDue("ping")) try {
            java.util.Set<String> existingHosts = pingMonitorRepo.findAll().stream()
                    .map(PingMonitor::getHost).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            escalationService.resolveOrphanedPingAlerts(existingHosts);
        } catch (Exception e) {
            log.warn("Öksüz ping alarmı temizliği başarısız: {}", e.getMessage());
        }
        if (monitors.isEmpty()) return;
        int checked = 0;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        // Yavaşlık AYRI pipeline: PING_DOWN ile aynı listede taşınsaydı tek bir alarm türü iki
        // farklı olayı (erişilemiyor / yavaşladı) anlatır, biri diğerini kapatırdı (port deseni).
        List<MonitoringOutageService.SweepItem> slowSweep = new ArrayList<>();
        // Faz 1: gating sweep thread'inde; ağ kontrolü certCheckExecutor'da paralel başlar (F1).
        List<Map.Entry<PingMonitor, java.util.function.Supplier<Map<String, Object>>>> started = new ArrayList<>();
        for (PingMonitor m : monitors) {
            if (!checkDue("ping", m.getId(), m.getIntervalSeconds())) continue;   // aralığı dolmadı → bu sweep'te atla
            started.add(Map.entry(m, startNetworkCheck(() -> recheckPing(m))));
        }
        // Faz 2: sonuçlar sweep thread'inde SIRALI işlenir.
        for (var entry : started) {
            PingMonitor m = entry.getKey();
            try {
                Map<String, Object> r = entry.getValue().get();
                sweep.add(pingSweepItem(m, r));
                slowSweep.add(pingSlowSweepItem(m, r));
                checked++;
            } catch (Exception e) {
                log.warn("Ping check failed for {}: {}", m.getHost(), e.getMessage());
            }
        }
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PING_DOWN, sweep);
        } catch (Exception e) {
            log.warn("Ping outage processing failed: {}", e.getMessage(), e);
        }
        try {
            monitoringOutageService.handleSweepResults(EscalationService.TYPE_PING_SLOW, slowSweep);
        } catch (Exception e) {
            log.warn("Ping slow outage processing failed: {}", e.getMessage());
        }
        log.debug("Ping checks complete: {} monitors", checked);
    }

    /** Ping check + ping_checks persist'i. N/A (ortam ICMP'ye izin vermiyor) → DOWN sayılmaz
     *  (yanlış alarm önlemek için status=up); kayıt up=false + error ile tutulur. */
    private Map<String, Object> recheckPing(PingMonitor m) {
        String persistedAt = null;
        Map<String, Object> r = pingCheckerService.check(m.getHost(), m.getIpVersion(),
                m.getPacketCount() != null ? m.getPacketCount() : 4,
                m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000);
        boolean up = Boolean.TRUE.equals(r.getOrDefault("up", false));
        boolean na = Boolean.TRUE.equals(r.get("na"));
        try {
            PingCheck check = new PingCheck();
            check.setMonitorId(m.getId());
            check.setUp(up);
            check.setRttMs(r.get("rtt_ms") instanceof Number n ? n.longValue() : null);
            check.setPacketLoss(r.get("packet_loss") instanceof Number n ? n.intValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(ISO.format(Instant.now()));
            pingCheckRepo.save(check);
            persistedAt = check.getCheckedAt();
        } catch (Exception e) {
            log.warn("Ping kaydı yazılamadı: {} — {}", m.getHost(), e.getMessage());
        }
        activityLog.recordCheck(ActivityLogService.PING, m.getId(), m.getName(),
                m.getHost(), m.getTeamId(), false, "scheduler", r);
        Map<String, Object> out = pingOutcome(r);
        // Taban çizgisi bu ölçümün KENDİSİNİ içermemeli: kayıt zaten yazıldı, pencerenin üst
        // sınırı olarak damgası taşınır (yoksa ölçüm kendi ortalamasını yukarı çekip sapmayı gizler).
        if (persistedAt != null) out.put("checked_at", persistedAt);
        return out;
    }

    @Scheduled(fixedDelayString = "${site.monitor.dns.interval-ms:300000}", initialDelayString = "60000")
    public void runDnsChecks() {
        if (!appSettings.getBoolean("site.monitor.dns.alert-enabled", true)) return;   // izleme duraklatıldı → kontrol+alarm yok
        // HA: tüm-tur dağıtık kilit — 2+ pod'da bir turu yalnız bir pod çalıştırır.
        if (!tryAcquireSchedulerLock("dns-sweep", sweepLockTtlMinutes)) {
            log.debug("DNS sweep — lock başka instance'da, atlanıyor");
            return;
        }
        try {
            runDnsChecksLocked();
        } finally {
            releaseSchedulerLock("dns-sweep");
        }
    }

    /** Tüm-tur kilit içinde çalışan gerçek DNS sweep gövdesi (bkz. runDnsChecks). */
    private void runDnsChecksLocked() {
        List<DnsMonitor> monitors = dnsMonitorRepo.findByActiveTrue();
        if (monitors.isEmpty()) return;
        // Skip monitors whose domain is no longer in active inventory (soft-deleted / inactive)
        Set<String> activeDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .map(CertificateInventory::getDomain).collect(Collectors.toSet());
        String now = ISO.format(Instant.now());
        int checked = 0, skipped = 0;
        boolean cleanupDue = orphanCleanupDue("dns");
        // Envanteri pasifleşen monitör hiç kontrol edilmiyor → recovery de gelmiyor; açık alarm sessizce
        // kapatılmazsa sonsuza kadar açık kalır (port sweep'iyle aynı desen).
        Set<String> skippedDomains = new LinkedHashSet<>();
        Set<String> checkableDomains = new HashSet<>();
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slowSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> unexpectedSweep = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> inconsistentSweep = new ArrayList<>();
        List<MonitoringOutageService.DnsChange> changes = new ArrayList<>();
        int slowThresholdMs       = appSettings.getInt("site.monitor.dns.slow-threshold-ms", 1500);
        int slowConfirmAttempts   = appSettings.getInt("site.monitor.dns.slow-confirm-attempts", 3);
        int slowConfirmIntervalMs = appSettings.getInt("site.monitor.dns.slow-confirm-interval-ms", 60000);
        // Çoklu-resolver tutarlılık (propagation) için public resolver listesi (opt-in monitörlerde kullanılır).
        List<String> dnsResolvers = java.util.Arrays.stream(
                        appSettings.getString("site.monitor.dns.resolvers", "8.8.8.8,1.1.1.1,9.9.9.9").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        // Faz 1: gating sweep thread'inde; ana DNS sorgusu + (opt-in) propagation sorgusu monitör başına
        // iki bağımsız task olarak certCheckExecutor'da paralel başlar (F1).
        record DnsStarted(DnsMonitor m,
                          java.util.function.Supplier<Map<String, Object>> main,
                          java.util.function.Supplier<Map<String, Object>> prop) {}
        List<DnsStarted> started = new ArrayList<>();
        for (DnsMonitor m : monitors) {
            // Standalone monitör (DNS sayfasından eklenen, sertifikadan bağımsız) envanter-skip'i baypas eder.
            if (!Boolean.TRUE.equals(m.getStandalone()) && !activeDomains.contains(m.getDomain())) {
                skipped++;
                if (m.getDomain() != null) skippedDomains.add(m.getDomain());
                continue;
            }
            if (m.getDomain() != null) checkableDomains.add(m.getDomain());   // checkDue ile atlanan da KONTROL EDİLEBİLİR
            if (!checkDue("dns", m.getId(), m.getIntervalSeconds())) continue;   // aralığı dolmadı → bu sweep'te atla (port/ping/keyword ile paritede)
            var main = startNetworkCheck(() -> dnsCheckerService.check(m.getDomain(), m.getRecordType()));
            var prop = (Boolean.TRUE.equals(m.getPropagationCheck()) && dnsResolvers.size() >= 2)
                    ? startNetworkCheck(() -> dnsCheckerService.checkPropagation(m.getDomain(), m.getRecordType(), dnsResolvers))
                    : null;
            started.add(new DnsStarted(m, main, prop));
        }
        skippedDomains.removeAll(checkableDomains);   // aynı domain'i izleyen aktif monitör varsa alarmı bırak
        if (cleanupDue && !skippedDomains.isEmpty()) {
            log.info("DNS izlemesi envanterde aktif olmadığı için atlandı ({}): {}", skippedDomains.size(), skippedDomains);
            for (String domain : skippedDomains) {
                escalationService.resolveOpenAlertsSilently(domain, DNS_SKIP_ALERT_TYPES,
                        "Sistem (envanterde aktif değil — izleme durdu)");
            }
        }
        // Faz 2: sonuçlar sweep thread'inde SIRALI işlenir — prevOk okuma/DnsRecord save/değişiklik
        // tespiti/item kurulumu birebir korunur; iki ayrı try/catch aynen (ana warn, propagation debug).
        for (DnsStarted st : started) {
            DnsMonitor m = st.m();
            try {
                Map<String, Object> r = st.main().get();
                boolean success = Boolean.TRUE.equals(r.get("success"));
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) r.getOrDefault("values", List.of());
                String valueStr = String.join("\n", values);

                // Değişiklik tespiti SADECE başarılı sorguda ve son BAŞARILI
                // kayda karşı yapılır — aksi halde dolu→boş(hata) geçişi sahte
                // CHANGED üretir (DNS_FAILURE her seferinde DNS_CHANGED'i tetiklerdi).
                DnsRecord prevOk = success
                        ? dnsRecordRepo.findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(m.getId(), "").orElse(null)
                        : null;
                String prevValue = prevOk != null ? prevOk.getValue() : null;
                DnsCheckerService.ChangeKind kind = success
                        ? DnsCheckerService.detectChange(prevValue, valueStr)
                        : DnsCheckerService.ChangeKind.NONE;
                boolean changed = kind == DnsCheckerService.ChangeKind.CHANGED;
                boolean rotated = kind == DnsCheckerService.ChangeKind.ROTATED;

                DnsRecord record = new DnsRecord();
                record.setMonitorId(m.getId());
                record.setRecordType(m.getRecordType());
                record.setValue(valueStr);
                record.setChanged(changed);
                record.setRotated(rotated);
                record.setPreviousValue(prevValue);
                record.setCheckedAt(now);
                record.setTtl(r.get("ttl") instanceof Number tn ? tn.longValue() : null);
                record.setResponseMs(r.get("response_ms") instanceof Number rn ? rn.longValue() : null);
                dnsRecordRepo.save(record);
                activityLog.recordCheck(ActivityLogService.DNS, m.getId(), m.getName(),
                        m.getDomain() + " " + m.getRecordType(), m.getTeamId(), false, "scheduler", r);

                sweep.add(dnsFailureSweepItem(m, r, success));

                // YAVAŞ/TIMEOUT'lu çözümleme: çözüm BAŞARILI ama response_ms eşiği aşıyor (primary DNS timeout
                // → fallback). Ayrı DNS_SLOW alarmı; 1 dk arayla 3 yeniden ölçümde de yavaşsa doğrulanır (ctxExtra).
                if (success) {
                    long responseMs = r.get("response_ms") instanceof Number rn2 ? rn2.longValue() : 0L;
                    int effSlow = m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : slowThresholdMs;  // per-monitor eşik ?? global
                    boolean slow = responseMs > effSlow;
                    Map<String, Object> slowCtx = new LinkedHashMap<>();
                    slowCtx.put("record_type", m.getRecordType());
                    slowCtx.put("response_ms", responseMs);
                    slowCtx.put("slow_threshold_ms", effSlow);
                    slowCtx.put("monitor_confirm_attempts", slowConfirmAttempts);
                    slowCtx.put("monitor_confirm_interval_ms", (long) slowConfirmIntervalMs);
                    if (m.getTeamId() != null) slowCtx.put("team_id", m.getTeamId());   // standalone → alarm takıma
                    if (m.getNotificationGroupId() != null) slowCtx.put("notification_group_id", m.getNotificationGroupId());
                    slowSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_DNS_SLOW, m.getDomain(), m.getRecordType(),
                            !slow, slow ? responseMs + " ms" : null,
                            chanCtx(slowCtx, m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckDnsSlow(m, effSlow)));
                }

                // BEKLENEN-DEĞER KİLİDİ: sabitlenen "beklenen değer"de OLMAYAN bir değer çözümlenirse
                // DNS_UNEXPECTED (esnek/hijack-odaklı). State alarmı: değer beklenene dönünce oto-kapanır.
                if (success && m.getExpectedValue() != null && !m.getExpectedValue().isBlank()) {
                    List<String> unexpected = DnsCheckerService.unexpectedValues(m.getExpectedValue(), values);
                    Map<String, Object> unexpCtx = new LinkedHashMap<>();
                    unexpCtx.put("record_type", m.getRecordType());
                    unexpCtx.put("unexpected_values", unexpected);
                    unexpCtx.put("expected_values", DnsCheckerService.splitLines(m.getExpectedValue()));
                    if (m.getTeamId() != null) unexpCtx.put("team_id", m.getTeamId());   // standalone → alarm takıma
                    if (m.getNotificationGroupId() != null) unexpCtx.put("notification_group_id", m.getNotificationGroupId());
                    unexpectedSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_DNS_UNEXPECTED, m.getDomain(), m.getRecordType(),
                            unexpected.isEmpty(), unexpected.isEmpty() ? null : String.join(", ", unexpected),
                            chanCtx(confirmCtx(unexpCtx, m.getConfirmAttempts(), m.getConfirmIntervalSeconds(), m.getRecoveryChecks(), m.getRecoveryIntervalSeconds()), m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckDnsUnexpected(m)));
                }

                if (changed) {
                    // Bastırma (kayıt yine changed=true saklanır; yalnız alarm hattı atlanır):
                    //  1) monitör bazlı aç/kapa (null=açık), 2) beklenen-set flip'i — canlı değerlerin TAMAMI
                    //  kullanıcının sabitlediği beklenen listedeyse (iç/dış IP split-horizon gidip-gelmesi).
                    boolean changeAlertOn = !Boolean.FALSE.equals(m.getDnsChangeAlertEnabled());
                    boolean expectedFlip  = DnsCheckerService.withinExpected(m.getExpectedValue(), values);
                    if (changeAlertOn && !expectedFlip) {
                        log.warn("DNS change detected for {} {}: was='{}' now='{}'",
                                m.getRecordType(), m.getDomain(), prevValue, valueStr);
                        // DNS_CHANGED artık 3× teyitli: değişiklik ardışık kontrollerde kalıcıysa alarmlanır
                        // (geçici/rotasyon baseline'a dönerse iptal). Baseline = değişiklik öncesi bilinen-iyi değer.
                        changes.add(new MonitoringOutageService.DnsChange(
                                m.getDomain(), m.getRecordType(), prevValue, valueStr, now, m.getTeamId(),
                                m.getNotificationGroupId(),
                                () -> recheckDnsChanged(m, prevValue)));
                    } else {
                        log.info("DNS change suppressed for {} {} ({}): was='{}' now='{}'",
                                m.getRecordType(), m.getDomain(),
                                changeAlertOn ? "expected-flip" : "alert-disabled", prevValue, valueStr);
                    }
                }
                checked++;
            } catch (Exception e) {
                log.warn("DNS check failed for {} {}: {}", m.getRecordType(), m.getDomain(), e.getMessage());
            }
            // Çoklu-resolver tutarlılık (propagation) — OPT-IN: yalnız propagationCheck açık monitörlerde.
            // Domain'i her public resolver'a AYRI sorar; cevaplar farklıysa DNS_INCONSISTENT (teyitli; oto-kapanır).
            if (st.prop() != null) {
                try {
                    Map<String, Object> prop = st.prop().get();
                    boolean inconsistent = Boolean.TRUE.equals(prop.get("inconsistent"));
                    @SuppressWarnings("unchecked")
                    Map<String, String> perResolver = (Map<String, String>) prop.getOrDefault("per_resolver", Map.of());
                    String detail = perResolver.entrySet().stream()
                            .map(en -> en.getKey() + "→" + en.getValue()).collect(Collectors.joining(" | "));
                    Map<String, Object> incCtx = new LinkedHashMap<>();
                    incCtx.put("record_type", m.getRecordType());
                    incCtx.put("resolver_detail", detail);
                    if (m.getTeamId() != null) incCtx.put("team_id", m.getTeamId());   // standalone → alarm takıma
                    if (m.getNotificationGroupId() != null) incCtx.put("notification_group_id", m.getNotificationGroupId());
                    inconsistentSweep.add(new MonitoringOutageService.SweepItem(
                            EscalationService.TYPE_DNS_INCONSISTENT, m.getDomain(), m.getRecordType(),
                            !inconsistent, inconsistent ? detail : null,
                            chanCtx(confirmCtx(incCtx, m.getConfirmAttempts(), m.getConfirmIntervalSeconds(), m.getRecoveryChecks(), m.getRecoveryIntervalSeconds()), m.getNotifyEmail(), m.getNotifyWebhook()), () -> recheckDnsPropagation(m, dnsResolvers)));
                } catch (Exception ex) {
                    log.debug("DNS propagation check failed for {}: {}", m.getDomain(), ex.getMessage());
                }
            }
        }
        // DNS alarm pipeline'ı (hata + değişiklik + beklenmeyen + tutarsızlık) — sweep'i kırmasın
        try {
            monitoringOutageService.handleDnsSweep(sweep, slowSweep, changes, unexpectedSweep, inconsistentSweep);
        } catch (Exception e) {
            log.warn("DNS outage processing failed: {}", e.getMessage(), e);
        }
        log.debug("DNS checks complete: {} monitors ({} skipped — not in active inventory)", checked, skipped);
    }

    /** DNS teyit re-check'i — BİLEREK DnsRecord persist ETMEZ: dns_records
     *  değişiklik-tespiti state'idir, 30 sn'lik teyit satırları kirletir. */
    private Map<String, Object> recheckDns(DnsMonitor m) {
        Map<String, Object> r = dnsCheckerService.check(m.getDomain(), m.getRecordType());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", Boolean.TRUE.equals(r.get("success")) ? "up" : "down");
        out.put("error", r.get("error"));
        return out;
    }

    /** DNS_SLOW teyit re-check'i: çözüm başarılı ama hâlâ yavaş (response_ms > eşik) ise "down" (teyit sürer);
     *  hızlandıysa ya da çözülemiyorsa "up" (slow alarmı üretilmez/kapanır — başarısızlık DNS_FAILURE'ın işi). */
    private Map<String, Object> recheckDnsSlow(DnsMonitor m, int thresholdMs) {
        Map<String, Object> r = dnsCheckerService.check(m.getDomain(), m.getRecordType());
        boolean success = Boolean.TRUE.equals(r.get("success"));
        long responseMs = r.get("response_ms") instanceof Number rn ? rn.longValue() : 0L;
        boolean slow = success && responseMs > thresholdMs;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", slow ? "down" : "up");
        out.put("error", slow ? responseMs + " ms" : (String) r.get("error"));
        return out;
    }

    /** DNS_CHANGED teyit re-check'i: canlı değer HÂLÂ pre-change baseline'dan AYRIK (CHANGED) ise "down"
     *  (değişiklik kalıcı → teyit sürer → alarm); baseline'a dönmüş / rotasyon / çözülemiyorsa "up"
     *  (geçici → alarm üretilmez). Baseline = değişikliğin önceki (bilinen-iyi) değeri. */
    private Map<String, Object> recheckDnsChanged(DnsMonitor m, String baseline) {
        Map<String, Object> r = dnsCheckerService.check(m.getDomain(), m.getRecordType());
        boolean success = Boolean.TRUE.equals(r.get("success"));
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) r.getOrDefault("values", List.of());
        boolean stillChanged = success
                && DnsCheckerService.detectChange(baseline, String.join("\n", values))
                   == DnsCheckerService.ChangeKind.CHANGED;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", stillChanged ? "down" : "up");
        out.put("error", stillChanged ? "changed" : null);
        return out;
    }

    /** DNS_UNEXPECTED teyit re-check'i: canlı sonuçta hâlâ BEKLENMEYEN değer varsa "down" (alarm sürer);
     *  değer beklenene dönmüşse ya da çözülemiyorsa "up" (alarm kapanır — başarısızlık DNS_FAILURE'ın işi). */
    private Map<String, Object> recheckDnsUnexpected(DnsMonitor m) {
        Map<String, Object> r = dnsCheckerService.check(m.getDomain(), m.getRecordType());
        boolean success = Boolean.TRUE.equals(r.get("success"));
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) r.getOrDefault("values", List.of());
        List<String> unexpected = success ? DnsCheckerService.unexpectedValues(m.getExpectedValue(), values) : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", !unexpected.isEmpty() ? "down" : "up");
        out.put("error", unexpected.isEmpty() ? null : String.join(", ", unexpected));
        return out;
    }

    /** DNS_INCONSISTENT teyit re-check'i: resolver'lar arası hâlâ tutarsızsa "down" (alarm sürer);
     *  aynılaştıysa "up" (alarm kapanır — propagation gecikmesi geçti). */
    private Map<String, Object> recheckDnsPropagation(DnsMonitor m, List<String> resolvers) {
        Map<String, Object> prop = dnsCheckerService.checkPropagation(m.getDomain(), m.getRecordType(), resolvers);
        boolean inconsistent = Boolean.TRUE.equals(prop.get("inconsistent"));
        @SuppressWarnings("unchecked")
        Map<String, String> perResolver = (Map<String, String>) prop.getOrDefault("per_resolver", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", inconsistent ? "down" : "up");
        out.put("error", inconsistent ? perResolver.toString() : null);
        return out;
    }

    private static String resolveHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "node"; }
    }

    // ── Network outage state — getters used by ExtendedHealthService ──────────

    public boolean isNetworkOutageActive() { return networkOutageActive.get(); }
    public String  getNetworkOutageDetectedAt() { return networkOutageDetectedAt.get(); }
    public String  getNetworkOutageResolvedAt() { return networkOutageResolvedAt.get(); }
    public double  getNetworkLastErrorRate() { return networkLastErrorRate.get(); }
    public int     getNetworkLastNetworkErrors() { return networkLastNetworkErrors.get(); }
    public int     getNetworkLastTotal() { return networkLastTotal.get(); }
    public double  getErrorRateThreshold() { return appSettings.getDouble("site.monitor.network.error-rate-threshold", networkErrorRateThreshold); }
    public int     getMinNetworkErrors() { return appSettings.getInt("site.monitor.network.min-errors", networkMinErrors); }
    public boolean isPendingAdminAlertEmail() { return pendingAdminAlertEmail.get(); }
    public boolean isPendingAdminResolvedEmail() { return pendingAdminResolvedEmail.get(); }

    // ── Admin email — outage notification + resilient retry ───────────────────

    private void trySendAdminAlert() {
        String adminEmail = appSettings.getString("site.monitor.system-admin.email", systemAdminEmail);
        double rateThreshold = appSettings.getDouble("site.monitor.network.error-rate-threshold", networkErrorRateThreshold);
        String status;
        try {
            status = emailService.sendSystemAdminNetworkAlert(
                    adminEmail,
                    networkOutageDetectedAt.get(),
                    networkLastNetworkErrors.get(),
                    networkLastTotal.get(),
                    networkLastErrorRate.get(),
                    rateThreshold);
        } catch (Exception e) {
            status = "FAILED: " + e.getMessage();
        }
        if ("SENT".equals(status) || "SKIPPED_DISABLED".equals(status)) {
            pendingAdminAlertEmail.set(false);
            log.info("System admin notified about network outage ({}) status={}", adminEmail, status);
        } else {
            pendingAdminAlertEmail.set(true);
            log.warn("Failed to send admin network alert (likely same outage blocking SMTP): {}", status);
        }
    }

    private void trySendAdminResolved() {
        String adminEmail = appSettings.getString("site.monitor.system-admin.email", systemAdminEmail);
        long durationMs = computeOutageDurationMs();
        String status;
        try {
            status = emailService.sendSystemAdminNetworkResolved(
                    adminEmail,
                    networkOutageDetectedAt.get(),
                    networkOutageResolvedAt.get(),
                    durationMs,
                    networkLastNetworkErrors.get(),
                    networkLastTotal.get(),
                    networkLastErrorRate.get());
        } catch (Exception e) {
            status = "FAILED: " + e.getMessage();
        }
        if ("SENT".equals(status) || "SKIPPED_DISABLED".equals(status)) {
            pendingAdminResolvedEmail.set(false);
            // Resolved email contains full timeline → no need to send the detection-only one separately
            pendingAdminAlertEmail.set(false);
            log.info("System admin notified about network outage resolution ({}) status={}",
                    adminEmail, status);
        } else {
            pendingAdminResolvedEmail.set(true);
            log.warn("Failed to send admin network resolved notification: {}", status);
        }
    }

    // ── Outage event persistence ──────────────────────────────────────────────

    private void persistOutageDetected(String detectedAt, int networkErrors, int totalChecks, double rate) {
        try {
            NetworkOutageEvent ev = new NetworkOutageEvent();
            ev.setDetectedAt(detectedAt);
            ev.setNetworkErrors(networkErrors);
            ev.setTotalChecks(totalChecks);
            ev.setErrorRate(rate);
            ev.setThreshold(appSettings.getDouble("site.monitor.network.error-rate-threshold", networkErrorRateThreshold));
            ev.setStatus("ONGOING");
            // Kaynak imzasi: ayni tabloya artik izleme sweep'lerinin bastirma olaylari da yaziliyor
            // (MonitoringOutageService). Isaretlenmezse iki olgu "ag kesintisi gecmisi"nde karisir.
            ev.setSource("CERT");
            networkOutageRepo.save(ev);
        } catch (Exception e) {
            log.warn("Failed to persist outage detection event: {}", e.getMessage());
        }
    }

    /** Sertifika sweep'ine ait acik kesinti olayi (source NULL = 2026-08 oncesi kayitlar). */
    private java.util.Optional<NetworkOutageEvent> certOngoingOutage() {
        return networkOutageRepo.findCertByStatus("ONGOING", org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
    }

    private void persistOutageResolved(String resolvedAt) {
        try {
            // Kapsamli sorgu SART: kapsamsiz hali, izleme sweep'inin daha yeni acik olayini
            // sertifika kesintisi sanip yanlis kaydi RESOLVED'a ceker / panoya tasirdi.
            certOngoingOutage().ifPresent(ev -> {
                ev.setResolvedAt(resolvedAt);
                ev.setStatus("RESOLVED");
                try {
                    Instant det = Instant.from(ISO.parse(ev.getDetectedAt()));
                    Instant res = Instant.from(ISO.parse(resolvedAt));
                    ev.setDurationMs(res.toEpochMilli() - det.toEpochMilli());
                } catch (Exception ignored) { /* duration stays null */ }
                networkOutageRepo.save(ev);
            });
        } catch (Exception e) {
            log.warn("Failed to persist outage resolution event: {}", e.getMessage());
        }
    }

    /** Called from startup — if an ONGOING outage row exists in DB (previous run
     *  crashed/exited mid-outage), restore the in-memory active state so the dashboard
     *  banner persists across restarts. The next healthy scan run will resolve it. */
    private void restoreOutageStateFromDb() {
        try {
            // Kapsamli sorgu SART: kapsamsiz hali, izleme sweep'inin daha yeni acik olayini
            // sertifika kesintisi sanip yanlis kaydi RESOLVED'a ceker / panoya tasirdi.
            certOngoingOutage().ifPresent(ev -> {
                networkOutageActive.set(true);
                networkOutageDetectedAt.set(ev.getDetectedAt());
                networkOutageResolvedAt.set(null);
                if (ev.getNetworkErrors() != null)  networkLastNetworkErrors.set(ev.getNetworkErrors());
                if (ev.getTotalChecks()  != null)  networkLastTotal.set(ev.getTotalChecks());
                if (ev.getErrorRate()    != null)  networkLastErrorRate.set(ev.getErrorRate());
                log.info("Restored ONGOING network outage state from DB (detected_at={})", ev.getDetectedAt());
            });
        } catch (Exception e) {
            log.warn("Failed to restore outage state from DB: {}", e.getMessage());
        }
    }

    private long computeOutageDurationMs() {
        String d = networkOutageDetectedAt.get();
        String r = networkOutageResolvedAt.get();
        if (d == null || r == null) return 0L;
        try {
            Instant det = Instant.from(ISO.parse(d));
            Instant res = Instant.from(ISO.parse(r));
            return res.toEpochMilli() - det.toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }
}
