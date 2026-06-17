package com.certmonitor.service;

import com.certmonitor.model.AlertThreshold;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.DnsMonitor;
import com.certmonitor.model.DnsRecord;
import com.certmonitor.model.PortCheck;
import com.certmonitor.model.PortMonitor;
import com.certmonitor.model.UptimeCheck;
import com.certmonitor.model.NetworkOutageEvent;
import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DnsRecordRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.NetworkOutageEventRepository;
import com.certmonitor.repository.PortCheckRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.UptimeCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;
    private final PermissionService permissionService;
    private final DataSource dataSource;

    private final PortCheckerService portCheckerService;
    private final PortMonitorRepository portMonitorRepo;
    private final PortCheckRepository portCheckRepo;

    private final DnsCheckerService dnsCheckerService;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final DnsRecordRepository dnsRecordRepo;

    private final UptimeHttpCheckerService uptimeHttpCheckerService;
    private final UptimeCheckRepository uptimeCheckRepo;
    private final MonitoringOutageService monitoringOutageService;

    private final NetworkOutageEventRepository networkOutageRepo;

    private final WeeklyReportReminderService weeklyReportReminderService;

    private final IncidentService incidentService;

    private final AppSettingsService appSettings;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("certCheckExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor certCheckExecutor;

    @Value("${cert.monitor.username:user}")
    private String adminUsername;

    @Value("${cert.monitor.password:password}")
    private String adminPassword;

    /** Stale threshold: domain not checked within this many minutes is considered stale. */
    @Value("${cert.monitor.scheduler.stale-minutes:65}")
    private int staleMinutes;

    /** Lock TTL: must be > the longest possible check run but short enough that a crashed
     *  instance doesn't block the cluster for too long. */
    @Value("${cert.monitor.scheduler.lock-ttl-minutes:10}")
    private int lockTtlMinutes;

    /** Açılışta ağır iş (catch-up + tam tarama) bu kadar ms geciktirilir — login/BCrypt
     *  ilk dakikada CPU'yu kapışmasın. 0 = anında (eski davranış). */
    @Value("${cert.monitor.scheduler.startup-check-delay-ms:60000}")
    private long startupCheckDelayMs;

    @Value("${cert.monitor.alert.default-warning-days:30}")
    private int defaultWarningDays;

    @Value("${cert.monitor.alert.default-high-days:15}")
    private int defaultHighDays;

    @Value("${cert.monitor.alert.default-critical-days:7}")
    private int defaultCriticalDays;

    @Value("${cert.monitor.alert.default-realert-hours:24}")
    private int defaultReAlertHours;

    /** Bulk network failure threshold — when the network-class error rate in a single
     *  run reaches or exceeds this value (AND minNetworkErrors), the run is treated as
     *  a suspected outage and downstream alarm processing is suppressed. */
    @Value("${cert.monitor.network.error-rate-threshold:0.50}")
    private double networkErrorRateThreshold;

    @Value("${cert.monitor.network.min-errors:3}")
    private int networkMinErrors;

    @Value("${cert.monitor.system-admin.email:erdi.inanmis@gmail.com}")
    private String systemAdminEmail;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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
        applySchemaPatches();
        userService.ensureBootstrapped(adminUsername, adminPassword);
        permissionService.seedDefaultsIfEmpty();
        permissionService.seedMissingDefaults(); // katalogda yeni eklenen modüllerin grant'lerini backfill et
        try { incidentService.seedOptions(); } // olay modülü varsayılan kanalları (idempotent)
        catch (Exception e) { log.warn("Incident option seed failed: {}", e.getMessage()); }
        ensureDefaultThreshold();
        assignOrphanedCertsToDefaultTeam();
        clearStaleLocksForThisHost();
        restoreOutageStateFromDb();
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
            if (deleted > 0) {
                log.info("Cleared {} stale scheduler lock(s) from previous instance(s) on this host", deleted);
            }
        } catch (Exception e) {
            log.warn("Could not clear stale scheduler lock: {}", e.getMessage());
        }
    }

    /** Idempotent DDL patches for columns that ddl-auto=update may miss on existing tables. */
    private void applySchemaPatches() {
        patch("ALTER TABLE certificate_checks ADD COLUMN run_id TEXT");
        patch("ALTER TABLE alert_events ADD COLUMN resolved_by TEXT");
        patch("ALTER TABLE notification_logs ADD COLUMN message TEXT");
        patch("ALTER TABLE certificate_inventory ADD COLUMN team_id INTEGER");
        patch("ALTER TABLE certificate_inventory ADD COLUMN use_proxy BOOLEAN DEFAULT false");
        patch("ALTER TABLE certificate_inventory ADD COLUMN tls_mode TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN via TEXT");
        patch("ALTER TABLE latest_checks ADD COLUMN tls_mode_used TEXT");
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
        // DNS monitoring tables
        patch("CREATE TABLE IF NOT EXISTS dns_monitors (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, domain TEXT NOT NULL, record_type TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, interval_seconds INTEGER NOT NULL DEFAULT 300, created_at TEXT, updated_at TEXT)");
        patch("CREATE TABLE IF NOT EXISTS dns_records (id INTEGER PRIMARY KEY AUTOINCREMENT, monitor_id INTEGER NOT NULL, record_type TEXT, value TEXT, changed INTEGER NOT NULL DEFAULT 0, previous_value TEXT, checked_at TEXT)");

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
        // Yönetilen seçenekler (kanal/domain) — tablo ddl-auto ile oluşur; (type,value) unique güvenlik ağı.
        patch("CREATE UNIQUE INDEX IF NOT EXISTS uk_inc_opt_type_value ON incident_options(type, opt_value)");
        patch("CREATE INDEX IF NOT EXISTS idx_inc_opt_type ON incident_options(type)");
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
    }

    /** Assigns any certs/contacts without a team to the first (default) team. */
    private void assignOrphanedCertsToDefaultTeam() {
        try {
            userService.listTeams().stream().findFirst().ifPresent(defaultTeam -> {
                Long tid = defaultTeam.getId();
                try {
                    jdbcTemplate.update(
                        "UPDATE certificate_inventory SET team_id = ? WHERE team_id IS NULL", tid);
                    jdbcTemplate.update(
                        "UPDATE escalation_contacts SET team_id = ? WHERE team_id IS NULL", tid);
                    log.info("Assigned orphaned certs/contacts to default team '{}' (id={})",
                            defaultTeam.getName(), tid);
                } catch (Exception e) {
                    log.warn("Could not assign orphaned records to default team: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("assignOrphanedCertsToDefaultTeam failed: {}", e.getMessage());
        }
    }

    private void patch(String ddl) {
        try {
            jdbcTemplate.execute(ddl);
            log.info("Schema patch applied: {}", ddl.length() > 60 ? ddl.substring(0, 60) + "…" : ddl);
        } catch (Exception e) {
            log.debug("Schema patch skipped: {}", e.getMessage());
        }
    }

    /** Full sweep: runs at the top of every hour (configurable via cert.monitor.scheduler.cron). */
    @Scheduled(cron = "${cert.monitor.scheduler.cron:0 0 * * * *}")
    public void scheduledHourlyCheck() {
        log.info("Hourly scheduled check triggered [instance={}]", INSTANCE_ID);
        runCheck();
    }

    /** Stale sweep: checks domains not checked within stale-minutes (configurable). */
    @Scheduled(fixedDelayString = "${cert.monitor.scheduler.stale-check-interval-ms:300000}",
               initialDelayString = "${cert.monitor.scheduler.stale-check-interval-ms:300000}")
    public void checkStaleInventory() {
        int staleMin = appSettings.getInt("cert.monitor.scheduler.stale-minutes", staleMinutes);
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
        runCheckForDomains(staleDomains);
    }

    /**
     * Gece 03:30 (Europe/Istanbul implicit — backend ISO timestamp'i UTC tutuyor
     * ama cron Spring TaskScheduler'a göre çalışır) eski log/geçmiş kayıtlarını siler.
     * Bellek/disk şişmesini önlemek için:
     *   - audit_log       → 180 gün üstü
     *   - notification_logs → 90 gün üstü
     *   - sql_query_history → 30 gün üstü
     * Bulk DELETE → tek transaction, kısa süreli.
     */
    @Scheduled(cron = "${cert.monitor.scheduler.cleanup-cron:0 30 3 * * *}")
    public void cleanupOldLogs() {
        try {
            String auditCutoff = ISO.format(Instant.now().minus(180, ChronoUnit.DAYS));
            String notifCutoff = ISO.format(Instant.now().minus(90,  ChronoUnit.DAYS));
            String sqlCutoff   = ISO.format(Instant.now().minus(30,  ChronoUnit.DAYS));
            int a = safeDelete("DELETE FROM audit_log         WHERE event_time   < ?", auditCutoff);
            int n = safeDelete("DELETE FROM notification_logs WHERE sent_at      < ?", notifCutoff);
            int s = safeDelete("DELETE FROM sql_query_history WHERE executed_at < ?", sqlCutoff);
            log.info("Nightly cleanup done: audit={}, notif={}, sql={} (cutoffs: {} / {} / {})",
                    a, n, s, auditCutoff, notifCutoff, sqlCutoff);
        } catch (Exception e) {
            log.warn("Nightly cleanup failed: {}", e.getMessage());
        }
    }

    private int safeDelete(String sql, String cutoff) {
        try {
            return jdbcTemplate.update(sql, cutoff);
        } catch (Exception e) {
            log.warn("Cleanup '{}' failed: {}", sql, e.getMessage());
            return -1;
        }
    }

    /**
     * Her Cuma 09:00 Europe/Istanbul — o anki ISO haftası raporunu henüz onaya
     * göndermemiş aktif SY takımlarına hatırlatma maili. HA: scheduler_lock ile
     * yalnız bir pod gönderir (TTL = cert-check ile aynı lock-ttl).
     */
    @Scheduled(cron = "${cert.monitor.weekly-report.reminder-cron:0 0 9 ? * FRI}", zone = "Europe/Istanbul")
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
        runCheckForDomains(domains);
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

    private void runCheckForDomains(List<Map<String, Object>> domains) {
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
                            blankToNull((String) d.get("tls_mode"))))
                    .toList();

            List<Map<String, Object>> results = futures.stream()
                    .map(CompletableFuture::join)
                    .map(r -> { Map<String, Object> m = new LinkedHashMap<>(r); m.put("run_id", runId); return m; })
                    .toList();

            results.forEach(certService::saveResult);
            // Tek seferlik batch evict — saveResult'tan @CacheEvict çıkarıldı,
            // dashboard sweep sonunda atomik olarak güncel veri görür.
            certService.evictAllCaches();

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
            lastRunErrors.set((int) errors);
            lastRunWarnings.set((int) warnings);

            int    minErrors = appSettings.getInt("cert.monitor.network.min-errors", networkMinErrors);
            double rateThreshold = appSettings.getDouble("cert.monitor.network.error-rate-threshold", networkErrorRateThreshold);
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
                escalationService.processResults(results);
            }

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
        } catch (Exception e) {
            // Unique constraint violation → another instance holds the lock
            if (e.getMessage() != null && (e.getMessage().contains("UNIQUE") || e.getMessage().contains("unique"))) {
                return false;
            }
            // Lock table unavailable — allow single-instance fallback
            log.warn("Distributed lock table unavailable (HA degraded): {}", e.getMessage());
            return true;
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

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_run",       lastRun.get() != null ? lastRun.get().toString() : "Not yet run");
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
        schedulerMap.put("next_run",       LocalDateTime.now(ZoneOffset.UTC)
                                               .truncatedTo(ChronoUnit.HOURS)
                                               .plusHours(1)
                                               .toString());
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
        h.put("scan",       scanMap);
        h.put("scan_alarm", scanAlarm);

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
            result.add(Map.of(
                "domain",    item.getDomain(),
                "port",      item.getPort(),
                "use_proxy", Boolean.TRUE.equals(item.getUseProxy()),
                "tls_mode",  item.getTlsMode() == null ? "" : item.getTlsMode()
            ));
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

    // ── Port / DNS / Uptime periodic checks ──────────────────────────────────

    @Scheduled(fixedDelayString = "${cert.monitor.uptime.interval-ms:300000}", initialDelayString = "60000")
    public void runUptimeChecks() {
        List<CertificateInventory> active = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        if (active.isEmpty()) return;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        for (CertificateInventory inv : active) {
            int port = inv.getPort() != null ? inv.getPort() : 443;
            try {
                Map<String, Object> r = recheckUptime(inv.getDomain(), port);
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
            uptimeCheckRepo.save(check);
        } catch (Exception e) {
            log.warn("Uptime kaydı yazılamadı: {}:{} — {}", domain, port, e.getMessage());
        }
        return r;
    }

    @Scheduled(fixedDelayString = "${cert.monitor.port.interval-ms:60000}", initialDelayString = "45000")
    public void runPortChecks() {
        List<PortMonitor> monitors = portMonitorRepo.findByActiveTrue();
        if (monitors.isEmpty()) return;
        // Skip monitors whose host is no longer in active inventory (soft-deleted / inactive)
        Set<String> activeDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .map(CertificateInventory::getDomain).collect(Collectors.toSet());
        int checked = 0, skipped = 0;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        for (PortMonitor m : monitors) {
            if (!activeDomains.contains(m.getHost())) { skipped++; continue; }
            try {
                Map<String, Object> r = recheckPort(m);
                // Hata fırlatan monitör item üretmez — yanlış all-up resolve olmaz
                sweep.add(new MonitoringOutageService.SweepItem(
                        EscalationService.TYPE_PORT_DOWN, m.getHost(),
                        m.getPort() + "/" + m.getProtocol(),
                        "up".equals(r.get("status")), (String) r.get("error"),
                        Map.of("port", m.getPort(), "protocol", m.getProtocol() != null ? m.getProtocol() : "TCP"),
                        () -> recheckPort(m)));
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
        log.debug("Port checks complete: {} monitors ({} skipped — not in active inventory)", checked, skipped);
    }

    /** Port check + port_checks persist'i — sweep ve teyit re-check'leri bu
     *  yoldan geçer (teyit izi port geçmişinde görünür). {"status","error"} döner. */
    private Map<String, Object> recheckPort(PortMonitor m) {
        Map<String, Object> r = portCheckerService.check(m.getHost(), m.getPort(), m.getTimeoutMs());
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", open ? "up" : "down");
        out.put("error", r.get("error"));
        return out;
    }

    @Scheduled(fixedDelayString = "${cert.monitor.dns.interval-ms:300000}", initialDelayString = "60000")
    public void runDnsChecks() {
        List<DnsMonitor> monitors = dnsMonitorRepo.findByActiveTrue();
        if (monitors.isEmpty()) return;
        // Skip monitors whose domain is no longer in active inventory (soft-deleted / inactive)
        Set<String> activeDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .map(CertificateInventory::getDomain).collect(Collectors.toSet());
        String now = ISO.format(Instant.now());
        int checked = 0, skipped = 0;
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        List<MonitoringOutageService.DnsChange> changes = new ArrayList<>();
        for (DnsMonitor m : monitors) {
            if (!activeDomains.contains(m.getDomain())) { skipped++; continue; }
            try {
                Map<String, Object> r = dnsCheckerService.check(m.getDomain(), m.getRecordType());
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

                sweep.add(new MonitoringOutageService.SweepItem(
                        EscalationService.TYPE_DNS_FAILURE, m.getDomain(), m.getRecordType(),
                        success, (String) r.get("error"),
                        Map.of("record_type", m.getRecordType()),
                        () -> recheckDns(m)));

                if (changed) {
                    log.warn("DNS change detected for {} {}: was='{}' now='{}'",
                            m.getRecordType(), m.getDomain(), prevValue, valueStr);
                    changes.add(new MonitoringOutageService.DnsChange(
                            m.getDomain(), m.getRecordType(), prevValue, valueStr, now));
                }
                checked++;
            } catch (Exception e) {
                log.warn("DNS check failed for {} {}: {}", m.getRecordType(), m.getDomain(), e.getMessage());
            }
        }
        // DNS alarm pipeline'ı (çözümleme hatası + kayıt değişikliği) — sweep'i kırmasın
        try {
            monitoringOutageService.handleDnsSweep(sweep, changes);
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
    public double  getErrorRateThreshold() { return appSettings.getDouble("cert.monitor.network.error-rate-threshold", networkErrorRateThreshold); }
    public int     getMinNetworkErrors() { return appSettings.getInt("cert.monitor.network.min-errors", networkMinErrors); }
    public boolean isPendingAdminAlertEmail() { return pendingAdminAlertEmail.get(); }
    public boolean isPendingAdminResolvedEmail() { return pendingAdminResolvedEmail.get(); }

    // ── Admin email — outage notification + resilient retry ───────────────────

    private void trySendAdminAlert() {
        String adminEmail = appSettings.getString("cert.monitor.system-admin.email", systemAdminEmail);
        double rateThreshold = appSettings.getDouble("cert.monitor.network.error-rate-threshold", networkErrorRateThreshold);
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
        String adminEmail = appSettings.getString("cert.monitor.system-admin.email", systemAdminEmail);
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
            ev.setThreshold(appSettings.getDouble("cert.monitor.network.error-rate-threshold", networkErrorRateThreshold));
            ev.setStatus("ONGOING");
            networkOutageRepo.save(ev);
        } catch (Exception e) {
            log.warn("Failed to persist outage detection event: {}", e.getMessage());
        }
    }

    private void persistOutageResolved(String resolvedAt) {
        try {
            networkOutageRepo.findFirstByStatusOrderByIdDesc("ONGOING").ifPresent(ev -> {
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
            networkOutageRepo.findFirstByStatusOrderByIdDesc("ONGOING").ifPresent(ev -> {
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
