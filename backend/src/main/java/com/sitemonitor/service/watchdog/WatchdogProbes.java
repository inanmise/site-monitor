package com.sitemonitor.service.watchdog;

import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.metrics.SystemMetricSampler;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kendi kendini izleme yoklamaları (2026-10-11). Her kontrol SABİT sayıda ucuz sorgu (toplam ≤ 10) — dakikalık turda
 * çalışır; ağır hesap yok (izleme durumu Genel Bakış'ın 30 sn'lik belleğinden okunur). Bir yoklamanın hatası diğerlerini
 * durdurmaz: o kontrol UNKNOWN olur (alarm da iyileşme de üretmez). Olgular yalnız sayı / kısa kod / pod adı — kayıt adı,
 * adres, hata metni taşımaz (dış uç yalnız durum ve anahtarı okur).
 *
 * <p><b>Küme geneli karar (2026-10-23, çok pod R2b, MP-36).</b> Değerlendirme turunu ({@code scheduler_lock} "watchdog")
 * HERHANGİ bir pod kazanır; turu kazanan pod'un BELLEĞİNDEKİ olgular (push devre kesicisi, kendi Hikari havuzu, açılıştaki
 * şema yaması sonucu) karara girerse onay sayacı pod'dan pod'a döner (A'da açık devre → sorun, B'de kapalı → sağlıklı:
 * dalgalanma ya da geciken / kaçan bildirim). Kural: durumu YALNIZ veritabanı genelindeki gerçekler değiştirir
 * (takılı push satırı sayısı, bağlantı / sorgu / yazılabilirlik, her CANLI pod'un {@code system_metric_samples} son örneği);
 * pod'a özgü olgular yalnız bilgi olarak, değerlendiren pod'un adıyla ({@code evaluated_by}) saklanır.
 */
@Slf4j
@Component
public class WatchdogProbes {

    /** Bir türün "taraması takıldı" sayılması için en az etkin izleme. */
    static final int MIN_ACTIVE_PER_TYPE = 2;
    /** Bildirim oranı penceresi (dk) ve en az deneme. */
    static final int NOTIFY_WINDOW_MIN = 30;
    static final int NOTIFY_MIN_ATTEMPTS = 3;
    static final int NOTIFY_FAIL_PERCENT = 50;
    /** Push: bu kadar dakikadır zamanı gelmiş ama gönderilmemiş PENDING satır sayısı eşiği. */
    static final int PUSH_STUCK_MINUTES = 10;
    static final int PUSH_STUCK_THRESHOLD = 10;
    /** Saat farkı eşikleri (ms): üstü sorun — orta / yüksek önem (R1 güvenli kira payı 30 sn). */
    static final long SKEW_WARN_MS = 5_000L;
    static final long SKEW_PROBLEM_MS = 30_000L;
    /** Canlı pod: son ölçüm örneği (saat farkıyla düzeltilmiş) bu kadar dakikadan yeni — Metrikler sayfasıyla aynı (3). */
    static final int LIVE_MINUTES = 3;
    /** Örnek penceresi (dk): saati GERİ kalmış pod'un satırları da bulunsun (en çok bu kadar kayma görülür). */
    static final int SAMPLE_WINDOW_MINUTES = 120;
    /** Olgulardaki pod listesi tavanı (TEXT kolonu, yönetici ekranı). */
    static final int MAX_FACT_PODS = 20;

    /**
     * Her pod'un {@code sampled_at} olarak EN YENİ satırı (pencere içinde). Kapanış işareti aynı dakikaya düşebilir — ikisi
     * de döner, ayrım Java'da. Pencere: pod sayısı × pencere dakikası satır üzerinde tek GROUP BY (dizin
     * {@code idx_sms_instance_sampled}).
     */
    static final String SQL_POD_SAMPLES = "SELECT s.instance_id, s.pod_name, s.sampled_at, s.shutdown_marker, s.db_pending, "
            + "s.db_active, s.db_max, s.clock_skew_ms, s.schema_failed_patches, s.id FROM system_metric_samples s "
            + "JOIN (SELECT instance_id, MAX(sampled_at) AS m "
            + "FROM system_metric_samples WHERE sampled_at >= ? GROUP BY instance_id) x "
            + "ON x.instance_id = s.instance_id AND x.m = s.sampled_at";
    /** R2b kolonları ({@code clock_skew_ms}, {@code schema_failed_patches}) henüz yokken — fark ve yama sonucu "bilinmiyor". */
    static final String SQL_POD_SAMPLES_NO_SKEW = "SELECT s.instance_id, s.pod_name, s.sampled_at, s.shutdown_marker, s.db_pending, "
            + "s.db_active, s.db_max, s.id FROM system_metric_samples s JOIN (SELECT instance_id, MAX(sampled_at) AS m "
            + "FROM system_metric_samples WHERE sampled_at >= ? GROUP BY instance_id) x "
            + "ON x.instance_id = s.instance_id AND x.m = s.sampled_at";
    /**
     * Push sonuçları (inceleme W-2, 2026-10-23): son {@value #PUSH_OUTCOME_WINDOW_MIN} dk'da oluşturulan, sonuçlanmış (SENT /
     * FAILED) satırlar — test gönderimi hariç. Ağ geçidi düşünce satırlar devre kesici bekleme süresi + geri çekilme içinde
     * FAILED olur (takılı PENDING sayısı yükselmez); kesinti bu oranla, küme genelinde görünür.
     */
    static final String SQL_PUSH_OUTCOMES = "SELECT status, COUNT(*) FROM user_push_deliveries WHERE created_at >= ? "
            + "AND status IN ('SENT', 'FAILED') AND push_trigger <> 'TEST' GROUP BY status";
    static final int PUSH_OUTCOME_WINDOW_MIN = 30;
    static final int PUSH_OUTCOME_MIN = 3;
    static final int PUSH_FAIL_PERCENT = 50;
    /** Dağıtık kontrol kuyruğu (R3-3): en eski bekleyen / değerlendirilmeyen bu kadar ms'den eskiyse sorun (tasarım §7.5). */
    static final long CHECK_QUEUE_LIMIT_MS = 120_000L;

    /** Tek yoklama sonucu. */
    public record Probe(WatchdogCheck check, Result result, String severity, Map<String, Object> facts) {
        static Probe na(WatchdogCheck c, String reason) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", reason);
            return new Probe(c, Result.NA, null, f);
        }
    }

    /**
     * Canlı bir pod'un son ölçüm örneği (kapanış işareti olmayan). {@code skewMs} / {@code schemaFailed} null = bilinmiyor
     * (eski pod / kolon yok).
     */
    public record PodSample(String instanceId, String pod, String sampledAt, Integer dbPending, Integer dbActive, Integer dbMax,
                            Long skewMs, Integer schemaFailed) {
        /** Havuz doygun mu: bağlantı bekleyen iş parçacığı var ya da havuz tamamen dolu ({@code DatabaseHealthService} kuralı). */
        boolean poolSaturated() {
            return (dbPending != null && dbPending > 0) || (dbMax != null && dbMax > 0 && dbActive != null && dbActive >= dbMax);
        }
    }

    private final JdbcTemplate jdbc;
    private final AppSettingsService settings;
    private ClusterScheduleService cluster;
    private MonitoringOverviewService overview;
    private NotificationLogRepository notificationLogs;
    private UserPushService push;
    private DatabaseHealthService database;
    /** Çok pod R3-3 (paket D1): dağıtık kontroller — anahtar (bellek), kuyruk (yalnız açıkken SQL), işçi kaydı (bellek). */
    private com.sitemonitor.service.checks.DistributedChecks distributedChecks;
    private com.sitemonitor.service.checks.CheckJobStore checkJobStore;
    private com.sitemonitor.service.checks.CheckWorkerRegistry checkWorkerRegistry;
    /** Çok pod R3-4 (paket C1): parçalı sertifika taraması — anahtar (bellek), koşu deposu (yalnız açıkken SQL). */
    private com.sitemonitor.service.certsweep.CertChunking certChunking;
    private com.sitemonitor.service.certsweep.CertSweepStore certSweepStore;

    @Value("${site.monitor.scheduler.sweep-leader-ttl-ms:180000}")
    private long leaseTtlMs = 180_000;

    /** Sistem metrikleri örnekleyicisi açık mı (kapalıysa pod örneği yok → saat farkı uygulanmaz, havuz yalnız bilgi). */
    @Value("${site.monitor.metrics.system.enabled:true}")
    boolean samplesEnabled = true;

    public WatchdogProbes(JdbcTemplate jdbc, AppSettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Autowired(required = false) @Lazy public void setCluster(ClusterScheduleService v) { this.cluster = v; }
    @Autowired(required = false) @Lazy public void setOverview(MonitoringOverviewService v) { this.overview = v; }
    @Autowired(required = false) public void setNotificationLogs(NotificationLogRepository v) { this.notificationLogs = v; }
    @Autowired(required = false) @Lazy public void setPush(UserPushService v) { this.push = v; }
    @Autowired(required = false) @Lazy public void setDatabase(DatabaseHealthService v) { this.database = v; }
    @Autowired(required = false) public void setDistributedChecks(com.sitemonitor.service.checks.DistributedChecks v) { this.distributedChecks = v; }
    @Autowired(required = false) public void setCheckJobStore(com.sitemonitor.service.checks.CheckJobStore v) { this.checkJobStore = v; }
    @Autowired(required = false) public void setCheckWorkerRegistry(com.sitemonitor.service.checks.CheckWorkerRegistry v) { this.checkWorkerRegistry = v; }
    @Autowired(required = false) public void setCertChunking(com.sitemonitor.service.certsweep.CertChunking v) { this.certChunking = v; }
    @Autowired(required = false) public void setCertSweepStore(com.sitemonitor.service.certsweep.CertSweepStore v) { this.certSweepStore = v; }

    /** Tüm kontroller katalog sırasıyla. */
    public List<Probe> runAll(Instant now) {
        List<Probe> out = new ArrayList<>();
        for (WatchdogCheck c : WatchdogCheck.values()) out.add(run(c, now));
        return out;
    }

    public Probe run(WatchdogCheck c, Instant now) {
        try {
            return switch (c) {
                case SWEEP_LEADER -> sweepLeader(now);
                case MONITOR_SWEEP -> monitorSweep();
                case CERT_SWEEP -> certSweep(now);
                case NOTIFICATIONS -> notifications(now);
                case PUSH_OUTBOX -> pushOutbox(now);
                case DATABASE -> database(now);
                case CLOCK_SKEW -> clockSkew(now);
                case CHECK_QUEUE -> checkQueue();
            };
        } catch (Exception e) {
            log.warn("Kendi kendini izleme: {} ölçülemedi: {}", c.key(), e.toString());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", "probe_failed");
            return new Probe(c, Result.UNKNOWN, null, f);
        }
    }

    Probe sweepLeader(Instant now) {
        if (cluster == null) return Probe.na(WatchdogCheck.SWEEP_LEADER, "no_cluster_store");
        ClusterScheduleService.Lease lease = cluster.lease("sweep-leader");
        if (lease == null) return Probe.na(WatchdogCheck.SWEEP_LEADER, "no_lease");   // tek pod / küme deposu kapalı
        long expiredMs = now.toEpochMilli() - lease.untilMs();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("expired_seconds", Math.max(0, expiredMs / 1000));
        f.put("ttl_seconds", leaseTtlMs / 1000);
        // Kira bir TTL'den fazla süredir dolu ve kimse yenilemedi → hiçbir pod lider değil (yenileyiciler ttl/3'te bir koşar)
        boolean problem = expiredMs > Math.max(60_000L, leaseTtlMs);
        return new Probe(WatchdogCheck.SWEEP_LEADER, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.SWEEP_LEADER.severity().name(), f);
    }

    Probe monitorSweep() {
        if (overview == null) return Probe.na(WatchdogCheck.MONITOR_SWEEP, "no_overview");
        List<Map<String, Object>> types = overview.orgTypes();
        if (types == null) return new Probe(WatchdogCheck.MONITOR_SWEEP, Result.UNKNOWN, null, Map.of("reason", "overview_unavailable"));
        int ratio = WatchdogSettings.staleRatio(settings);
        List<Map<String, Object>> late = new ArrayList<>();
        int checkedTypes = 0;
        for (Map<String, Object> t : types) {
            long active = num(t.get("active"));
            long stale = num(t.get("stale"));
            if (active < MIN_ACTIVE_PER_TYPE) continue;
            checkedTypes++;
            if (stale * 100 >= (long) ratio * active) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", String.valueOf(t.get("type")));
                m.put("active", active);
                m.put("stale", stale);
                late.add(m);
            }
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ratio_percent", ratio);
        f.put("types_checked", checkedTypes);
        f.put("late_types", late);
        if (checkedTypes == 0) return new Probe(WatchdogCheck.MONITOR_SWEEP, Result.NA, null, f);
        return new Probe(WatchdogCheck.MONITOR_SWEEP, late.isEmpty() ? Result.OK : Result.PROBLEM,
                WatchdogCheck.MONITOR_SWEEP.severity().name(), f);
    }

    Probe certSweep(Instant now) {
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM certificate_inventory WHERE active = TRUE "
                + "AND (cert_source IS NULL OR cert_source <> 'MANUAL')", Long.class);
        if (active == null || active == 0) return Probe.na(WatchdogCheck.CERT_SWEEP, "no_active_inventory");
        String last = jdbc.queryForObject("SELECT MAX(checked_at) FROM latest_checks", String.class);
        if (last == null || last.isBlank()) return Probe.na(WatchdogCheck.CERT_SWEEP, "never_checked");
        Instant at = WatchdogTransition.parse(last);
        long hours = Duration.between(at, now).toMinutes() / 60;
        int limit = WatchdogSettings.certStaleHours(settings);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("last_result_at", last.length() > 19 ? last.substring(0, 19) : last);
        f.put("hours_since", Math.max(0, hours));
        f.put("limit_hours", limit);
        f.put("active_certificates", active);
        boolean problem = Duration.between(at, now).toMinutes() > limit * 60L;
        certChunkFacts(f);
        return new Probe(WatchdogCheck.CERT_SWEEP, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.CERT_SWEEP.severity().name(), f);
    }

    /** Parçalı tarama olgularının okuduğu en çok koşu (depo sınırı). */
    static final int CERT_CHUNK_STATUS_RUNS = 20;
    /** Terk edilmiş koşu penceresi (ms). */
    static final long CERT_CHUNK_ABANDON_WINDOW_MS = 24L * 3_600_000L;

    /**
     * Parçalı sertifika taraması olguları (çok pod R3-4, tasarım §7.5 — YENİ KONTROL YOK, karar DEĞİŞMEZ): yalnız anahtar AÇIKKEN
     * (kapalı: tek bellek okuması, sorgu ve olgu yok — L-1) tek {@code CertSweepStore.status} okuması → {@code chunked: true},
     * {@code chunked_run_age_s} (açık koşunun yaşı; yoksa null), {@code oldest_pending_chunk_s} (en eski bekleyen parça; yoksa
     * null), {@code abandoned_runs_24h} (+ {@code abandoned_runs_24h_lower_bound} — okunan koşular pencereyi kapsamıyorsa).
     * Okunamadı → {@code chunked_status: unreadable}. Takılan ya da ölen parçalı tur hiçbir şey KAYDETMEZ — {@code latest_checks}
     * tazeliği (bu kontrolün kararı) ve 2 sa tarama alarmı onu klasik tur gibi yakalar.
     */
    void certChunkFacts(Map<String, Object> f) {
        if (certChunking == null || certSweepStore == null) return;
        boolean on;
        try {
            on = certChunking.enabled();
        } catch (RuntimeException e) {
            on = false;
        }
        if (!on) return;
        f.put("chunked", true);
        com.sitemonitor.service.certsweep.CertSweepStatus st;
        try {
            st = certSweepStore.status(CERT_CHUNK_STATUS_RUNS);
        } catch (RuntimeException e) {
            f.put("chunked_status", "unreadable");
            return;
        }
        if (st == null) {
            f.put("chunked_status", "unreadable");
            return;
        }
        com.sitemonitor.service.certsweep.CertSweepStatus.Run open = st.openRun();
        f.put("chunked_run_age_s", open == null ? null : open.ageMs(st.nowMs()) / 1000L);
        Long oldest = st.oldestPendingAgeMs();
        f.put("oldest_pending_chunk_s", oldest == null ? null : oldest / 1000L);
        long[] ab = com.sitemonitor.service.certsweep.CertSweepView.abandonedSince(st, st.nowMs() - CERT_CHUNK_ABANDON_WINDOW_MS,
                CERT_CHUNK_STATUS_RUNS);
        f.put("abandoned_runs_24h", ab[0]);
        if (ab[1] == 0) f.put("abandoned_runs_24h_lower_bound", true);
    }

    Probe notifications(Instant now) {
        if (notificationLogs == null) return Probe.na(WatchdogCheck.NOTIFICATIONS, "no_repository");
        String cutoff = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(NOTIFY_WINDOW_MIN)));
        long attempted = notificationLogs.countAttemptedSince(cutoff);
        long sent = notificationLogs.countSentSince(cutoff);
        long failed = Math.max(0, attempted - sent);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("window_minutes", NOTIFY_WINDOW_MIN);
        f.put("attempted", attempted);
        f.put("failed", failed);
        if (attempted < NOTIFY_MIN_ATTEMPTS) return new Probe(WatchdogCheck.NOTIFICATIONS, Result.OK, null, f);
        boolean problem = failed * 100 >= (long) NOTIFY_FAIL_PERCENT * attempted;
        return new Probe(WatchdogCheck.NOTIFICATIONS, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.NOTIFICATIONS.severity().name(), f);
    }

    /**
     * Push kuyruğu: karar YALNIZ veritabanı genelindeki gerçeklerle — takılı PENDING satır sayısı ve son
     * {@value #PUSH_OUTCOME_WINDOW_MIN} dk'nın sonuç oranı (FAILED / (SENT + FAILED) ≥ %{@value #PUSH_FAIL_PERCENT}, en az
     * {@value #PUSH_OUTCOME_MIN} sonuç). Devre kesici pod'a özgü bellek durumudur (her pod kendi ağ geçidi hatalarını sayar) —
     * olgu olarak değerlendiren pod'un adıyla saklanır, durumu değiştirmez (MP-36: A'da açık / B'de kapalı devre turdan tura
     * onay sayacını döndürüyordu). Ölü ağ geçidi (inceleme W-2): satırlar devre kesici bekleme süresi + geri çekilme içinde
     * FAILED olur — takılı sayı yükselmez, sonuç oranı yükselir.
     */
    Probe pushOutbox(Instant now) {
        if (push == null || !push.enabled()) return Probe.na(WatchdogCheck.PUSH_OUTBOX, "push_disabled");
        String created = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES + 5)));
        String due = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES)));
        Long stuck = jdbc.queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'PENDING' "
                + "AND created_at < ? AND (next_attempt_at IS NULL OR next_attempt_at < ?)", Long.class, created, due);
        long sent = 0, failed = 0;
        String since = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_OUTCOME_WINDOW_MIN)));
        for (Object[] r : jdbc.query(SQL_PUSH_OUTCOMES, (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, since)) {
            long n = r[1] instanceof Number x ? x.longValue() : 0L;
            if ("SENT".equals(r[0])) sent += n;
            else if ("FAILED".equals(r[0])) failed += n;
        }
        Boolean circuitOpen = null;
        try {
            circuitOpen = Boolean.TRUE.equals(push.healthSnapshot().get("circuit_open"));
        } catch (Exception e) {
            log.debug("watchdog: push devre kesici okunamadı: {}", e.toString());
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stuck", stuck == null ? 0 : stuck);
        f.put("stuck_minutes", PUSH_STUCK_MINUTES);
        f.put("recent_sent", sent);
        f.put("recent_failed", failed);
        f.put("outcome_window_minutes", PUSH_OUTCOME_WINDOW_MIN);
        f.put("circuit_open", circuitOpen);
        f.put("evaluated_by", SystemMetricSampler.currentPodName());
        long outcomes = sent + failed;
        boolean failing = outcomes >= PUSH_OUTCOME_MIN && failed * 100 >= (long) PUSH_FAIL_PERCENT * outcomes;
        boolean problem = failing || (stuck != null && stuck >= PUSH_STUCK_THRESHOLD);
        return new Probe(WatchdogCheck.PUSH_OUTBOX, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.PUSH_OUTBOX.severity().name(), f);
    }

    /**
     * Veritabanı: karar veritabanı GENELİNDEKİ kontrollerle (bağlantı, sorgu süresi, yazılabilirlik — DOWN → kritik,
     * DEGRADED → orta) ve HER canlı pod'un son örneğiyle: havuz doluluğu (bekleyen bağlantı ya da tam dolu havuz) ya da
     * açılışta başarısız şema yaması olan pod → orta. Pod örneği yoksa (örnekleyici kapalı, örnekler okunamadı ya da henüz
     * canlı pod örneği yok) karar eskisi gibi DEĞERLENDİREN pod'un kendi havuzu ve şema yamasıyla verilir ve öyle etiketlenir
     * ({@code decision_source = evaluating_pod}; inceleme W-3). Veritabanına erişilemiyorsa (DOWN) örnekler okunmaz (yerel kip —
     * {@link WatchdogService#evaluateLocalDatabase}).
     */
    Probe database(Instant now) {
        if (database == null) return Probe.na(WatchdogCheck.DATABASE, "no_health_service");
        DatabaseHealthService.Snapshot s = database.current();
        Map<?, ?> checks = s.body() != null && s.body().get("checks") instanceof Map<?, ?> m ? m : null;
        // Gövde kontrol ayrıntısı taşımıyorsa (eski / sade gövde) genel durum aynen kullanılır
        String dbWide = checks == null ? s.status() : worst(checks, List.of("connection", "query", "writable"));
        String self = SystemMetricSampler.currentPodName();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("database_status", dbWide);
        f.put("evaluated_by", self);
        boolean ownPoolDegraded = false;
        int ownSchemaFailed = 0;
        if (checks != null) {
            if (checks.get("pool") instanceof Map<?, ?> pool) {
                f.put("evaluating_pod_pool", String.valueOf(pool.get("status")));
                ownPoolDegraded = DatabaseHealthService.DEGRADED.equals(String.valueOf(pool.get("status")));
            }
            if (checks.get("schema") instanceof Map<?, ?> schema && schema.get("failed_patches") instanceof Number n && n.intValue() > 0) {
                ownSchemaFailed = n.intValue();
                f.put("evaluating_pod_schema_failed", ownSchemaFailed);
            }
        }
        if (DatabaseHealthService.DOWN.equals(dbWide)) {
            f.put("status", DatabaseHealthService.DOWN);
            return new Probe(WatchdogCheck.DATABASE, Result.PROBLEM, WatchdogCheck.Severity.CRITICAL.name(), f);
        }
        // Küme: her canlı pod'un son örneği. Örnek yoksa değerlendiren pod'un kendi olguları karar verir (etiketli).
        List<String> saturated = new ArrayList<>();
        List<String> schemaFailed = new ArrayList<>();
        List<PodSample> pods = null;
        if (samplesEnabled) {
            try {
                pods = livePods(now);
                f.put("pool_pods_read", pods.size());
            } catch (Exception e) {
                f.put("pool_unknown", true);
                log.debug("watchdog: pod havuz örnekleri okunamadı: {}", e.toString());
            }
        }
        if (pods != null && !pods.isEmpty()) {
            f.put("decision_source", "cluster_samples");
            for (PodSample p : pods) {
                if (p.poolSaturated() && saturated.size() < MAX_FACT_PODS) saturated.add(p.pod());
                if (p.schemaFailed() != null && p.schemaFailed() > 0 && schemaFailed.size() < MAX_FACT_PODS) schemaFailed.add(p.pod());
            }
        } else {
            f.put("decision_source", "evaluating_pod");
            if (ownPoolDegraded) saturated.add(self);
            if (ownSchemaFailed > 0) schemaFailed.add(self);
        }
        if (!saturated.isEmpty()) f.put("pool_saturated_pods", saturated);
        if (!schemaFailed.isEmpty()) f.put("schema_failed_pods", schemaFailed);
        boolean degraded = DatabaseHealthService.DEGRADED.equals(dbWide) || !saturated.isEmpty() || !schemaFailed.isEmpty();
        f.put("status", degraded ? DatabaseHealthService.DEGRADED : DatabaseHealthService.UP);
        if (!degraded) return new Probe(WatchdogCheck.DATABASE, Result.OK, null, f);
        return new Probe(WatchdogCheck.DATABASE, Result.PROBLEM, WatchdogCheck.Severity.MEDIUM.name(), f);
    }

    /**
     * Saat uyumu (MP-37c; inceleme W-4): kira ve kilitler YALNIZ pod saatleriyle karşılaştırılır — risk pod'lar ARASI farktır.
     * Her canlı pod'un son örneğindeki {@code clock_skew_ms} (pod − veritabanı) okunur; önem pod'lar arası YAYILIMDAN (en büyük −
     * en küçük): {@link #SKEW_PROBLEM_MS} üstü → yüksek, {@link #SKEW_WARN_MS} üstü → orta. Veritabanı saatinin pod'lardan farkı
     * (ortanca) yalnız olgudur ({@code db_offset_ms}) — saatleri uyumlu pod'lar ve 40 sn kaymış bir veritabanı "sorun" değildir.
     * Pod başına {@code deviation_ms} = pod'un ortancadan sapması. Hiçbir canlı pod'un farkı bilinmiyorsa (eski sürüm pod'lar,
     * açılışın ilk dakikası) UNKNOWN — sayaçlar değişmez.
     */
    Probe clockSkew(Instant now) {
        if (!samplesEnabled) return Probe.na(WatchdogCheck.CLOCK_SKEW, "metrics_disabled");
        List<PodSample> pods = livePods(now);
        List<PodSample> known = new ArrayList<>();
        int unknown = 0;
        for (PodSample p : pods) {
            if (p.skewMs() == null) unknown++;
            else known.add(p);
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("warn_seconds", SKEW_WARN_MS / 1000);
        f.put("problem_seconds", SKEW_PROBLEM_MS / 1000);
        f.put("unknown_pods", unknown);
        if (known.isEmpty()) {
            f.put("reason", pods.isEmpty() ? "no_live_samples" : "skew_unknown");
            return new Probe(WatchdogCheck.CLOCK_SKEW, Result.UNKNOWN, null, f);
        }
        List<Long> values = new ArrayList<>();
        for (PodSample p : known) values.add(p.skewMs());
        values.sort(Long::compare);
        int n = values.size();
        long median = n % 2 == 1 ? values.get(n / 2) : Math.round((values.get(n / 2 - 1) + values.get(n / 2)) / 2.0);
        PodSample min = known.get(0), max = known.get(0);
        for (PodSample p : known) {
            if (p.skewMs() < min.skewMs() || (p.skewMs().equals(min.skewMs()) && p.pod().compareTo(min.pod()) < 0)) min = p;
            if (p.skewMs() > max.skewMs() || (p.skewMs().equals(max.skewMs()) && p.pod().compareTo(max.pod()) < 0)) max = p;
        }
        long spread = max.skewMs() - min.skewMs();
        known.sort(Comparator.comparingLong((PodSample p) -> -Math.abs(p.skewMs() - median)).thenComparing(PodSample::pod));
        List<Map<String, Object>> list = new ArrayList<>();
        for (PodSample p : known) {
            if (list.size() >= MAX_FACT_PODS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pod", p.pod());
            m.put("skew_ms", p.skewMs());
            m.put("deviation_ms", p.skewMs() - median);
            list.add(m);
        }
        f.put("pods", list);
        f.put("known_pods", known.size());
        f.put("spread_ms", spread);
        f.put("db_offset_ms", median);
        if (known.size() > 1) {
            f.put("min_pod", min.pod());
            f.put("max_pod", max.pod());
        }
        if (spread > SKEW_PROBLEM_MS) return new Probe(WatchdogCheck.CLOCK_SKEW, Result.PROBLEM, WatchdogCheck.Severity.HIGH.name(), f);
        if (spread > SKEW_WARN_MS) return new Probe(WatchdogCheck.CLOCK_SKEW, Result.PROBLEM, WatchdogCheck.Severity.MEDIUM.name(), f);
        return new Probe(WatchdogCheck.CLOCK_SKEW, Result.OK, null, f);
    }

    /**
     * Dağıtık kontrol kuyruğu (2026-10-29, çok pod Sürüm 3-3, paket D1; tasarım §7.5). Ana anahtar KAPALI ya da hiç tür seçili
     * değil → NA, SORGU YOK (bellek okuması — K-1; {@code CheckJobStore} sözleşmesi: anahtar kapalıyken çağrılmaz). Açık: tek
     * gruplu kuyruk deyimi ({@code CheckJobStore.stats()} — veritabanı geneli, değerlendiren pod'dan bağımsız): en eski bekleyen
     * iş ya da en eski değerlendirilmeyen sonuç {@value #CHECK_QUEUE_LIMIT_MS} ms'den eski, ya da kirası dolmuş sahiplenme varsa
     * PROBLEM (orta önem); onay {@code confirm-checks} turla (geçişin genel kuralı). Olgular: sayılar, en eski yaşlar (sn),
     * canlı işçi sayısı (kayıt önbelleği; bilinmiyorsa yok), değerlendiren pod.
     */
    Probe checkQueue() {
        com.sitemonitor.service.checks.DistributedChecks dc = distributedChecks;
        if (dc == null || !dc.anyDistributed()) return Probe.na(WatchdogCheck.CHECK_QUEUE, "distributed_off");
        if (checkJobStore == null) return Probe.na(WatchdogCheck.CHECK_QUEUE, "no_check_store");
        com.sitemonitor.service.checks.CheckJobStore.QueueStats st = checkJobStore.stats();
        if (st == null) return new Probe(WatchdogCheck.CHECK_QUEUE, Result.UNKNOWN, null, Map.of("reason", "probe_failed"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pending", st.count(com.sitemonitor.service.checks.CheckJobStore.STATE_PENDING));
        f.put("claimed", st.count(com.sitemonitor.service.checks.CheckJobStore.STATE_CLAIMED));
        f.put("done", st.count(com.sitemonitor.service.checks.CheckJobStore.STATE_DONE));
        Long pendingMs = st.oldestPendingAgeMs();
        Long doneMs = st.oldestDoneAgeMs();
        if (pendingMs != null) f.put("oldest_pending_seconds", Math.max(0L, pendingMs) / 1000);
        if (doneMs != null) f.put("oldest_done_seconds", Math.max(0L, doneMs) / 1000);
        f.put("expired_claims", st.expiredClaims());
        f.put("limit_seconds", CHECK_QUEUE_LIMIT_MS / 1000);
        Integer live = liveWorkers();
        if (live != null) f.put("live_workers", live);
        f.put("evaluated_by", SystemMetricSampler.currentPodName());
        boolean problem = (pendingMs != null && pendingMs > CHECK_QUEUE_LIMIT_MS) || (doneMs != null && doneMs > CHECK_QUEUE_LIMIT_MS)
                || st.expiredClaims() > 0;
        return new Probe(WatchdogCheck.CHECK_QUEUE, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.CHECK_QUEUE.severity().name(), f);
    }

    /** Canlı (durmayan) işçi sayısı — kayıt önbelleğinden (bellek); önbellek bilinmiyorsa null. */
    private Integer liveWorkers() {
        com.sitemonitor.service.checks.CheckWorkerRegistry r = checkWorkerRegistry;
        if (r == null) return null;
        try {
            if (r.liveOtherWorkers(com.sitemonitor.service.checks.CheckJobTypes.PORT)
                    == com.sitemonitor.service.checks.CheckWorkerRegistry.UNKNOWN) return null;
            int n = 0;
            for (var w : r.workers()) if (w != null && w.live() && !w.stopping()) n++;
            return n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Canlı pod'ların son ölçüm örnekleri: pencere içindeki en yeni satırı kapanış işareti OLMAYAN ve o satırın zamanı
     * (pod saatiyle yazılır — saat farkıyla veritabanı zamanına düzeltilir) son {@value #LIVE_MINUTES} dakika içinde olan
     * her pod. Referans "şimdi" de bu pod'un kendi farkıyla düzeltilir (değerlendiren pod'un saati kaymışsa diğerlerini
     * yanlışlıkla "sessiz" saymasın). Aynı ada sahip iki süreç (konteyner yeniden başlaması) kimlik sonekiyle ayrılır.
     */
    List<PodSample> livePods(Instant now) {
        String since = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(SAMPLE_WINDOW_MINUTES)));
        List<Object[]> rows;
        try {
            rows = jdbc.query(SQL_POD_SAMPLES, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getObject(4), intOrNull(rs.getObject(5)), intOrNull(rs.getObject(6)), intOrNull(rs.getObject(7)),
                    longOrNull(rs.getObject(8)), intOrNull(rs.getObject(9)), longOrNull(rs.getObject(10))}, since);
        } catch (Exception e) {
            // R2b kolonları yok (eski şema / yama henüz koşmadı) — kolonsuz okuma, fark ve yama sonucu "bilinmiyor"
            rows = jdbc.query(SQL_POD_SAMPLES_NO_SKEW, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getObject(4), intOrNull(rs.getObject(5)), intOrNull(rs.getObject(6)), intOrNull(rs.getObject(7)), null, null,
                    longOrNull(rs.getObject(8))}, since);
        }
        // En yeni dakikada SON YAZILAN satır karar verir (satır kimliği yazım sırasıdır): kapanış işareti → durdu, ölçüm → canlı.
        // Aynı dakikada önce işaret sonra ölçüm = aynı JVM'de bağlam yeniden açıldı (örnek kimliği JVM başınadır — DevTools,
        // postgres-it'nin iki açılışı): pod canlıdır. Eskiden dakikadaki HERHANGİ bir işaret pod'u durmuş sayıyordu.
        Map<String, Object[]> lastWritten = new LinkedHashMap<>();
        Map<String, Object[]> newest = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String inst = (String) r[0];
            if (inst == null) continue;
            Object[] prevLast = lastWritten.get(inst);
            if (prevLast == null || idOf(r) > idOf(prevLast)) lastWritten.put(inst, r);
            if (!truthy(r[3])) {
                Object[] prevSample = newest.get(inst);
                if (prevSample == null || idOf(r) > idOf(prevSample)) newest.put(inst, r);
            }
        }
        Map<String, Boolean> stopped = new HashMap<>();
        for (Map.Entry<String, Object[]> e : lastWritten.entrySet()) {
            if (truthy(e.getValue()[3])) stopped.put(e.getKey(), true);
        }
        String self = SchedulerService.instanceId();
        Object[] own = newest.get(self);
        long ownSkew = own != null && own[7] instanceof Long l ? l : 0L;
        long cutoff = now.toEpochMilli() - ownSkew - LIVE_MINUTES * 60_000L;
        Map<String, Integer> nameCount = new HashMap<>();
        List<Object[]> live = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : newest.entrySet()) {
            if (Boolean.TRUE.equals(stopped.get(e.getKey()))) continue;
            Object[] r = e.getValue();
            Long at = epochMs((String) r[2]);
            if (at == null) continue;
            long skew = r[7] instanceof Long l ? l : 0L;
            if (at - skew < cutoff) continue;   // sessiz pod (çöküş / takılma) — son örneği eski
            live.add(r);
            nameCount.merge(displayName(r), 1, Integer::sum);
        }
        List<PodSample> out = new ArrayList<>();
        for (Object[] r : live) {
            String name = displayName(r);
            String inst = (String) r[0];
            if (nameCount.getOrDefault(name, 0) > 1) name = name + " (" + suffix(inst) + ")";
            out.add(new PodSample(inst, name, (String) r[2], (Integer) r[4], (Integer) r[5], (Integer) r[6], (Long) r[7],
                    (Integer) r[8]));
        }
        out.sort(Comparator.comparing(PodSample::pod));
        return out;
    }

    /** Satır kimliği (yazım sırası); bilinmiyorsa en küçük. */
    private static long idOf(Object[] r) {
        return r.length > 9 && r[9] instanceof Long l ? l : Long.MIN_VALUE;
    }

    private static String displayName(Object[] r) {
        String pod = (String) r[1];
        return pod == null || pod.isBlank() ? String.valueOf(r[0]) : pod;
    }

    private static String suffix(String instanceId) {
        int dash = instanceId == null ? -1 : instanceId.lastIndexOf('-');
        return dash >= 0 && dash < instanceId.length() - 1 ? instanceId.substring(dash + 1) : String.valueOf(instanceId);
    }

    private static Long epochMs(String iso) {
        if (iso == null) return null;
        try {
            return LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    private static Long longOrNull(Object v) {
        return v instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    /** Kontrol listesinin en kötüsü: DOWN > DEGRADED > UP; hiçbiri yoksa UP. */
    private static String worst(Map<?, ?> checks, List<String> keys) {
        String out = DatabaseHealthService.UP;
        for (String k : keys) {
            if (!(checks.get(k) instanceof Map<?, ?> m)) continue;
            String st = String.valueOf(m.get("status"));
            if (DatabaseHealthService.DOWN.equals(st)) return DatabaseHealthService.DOWN;
            if (DatabaseHealthService.DEGRADED.equals(st)) out = DatabaseHealthService.DEGRADED;
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
