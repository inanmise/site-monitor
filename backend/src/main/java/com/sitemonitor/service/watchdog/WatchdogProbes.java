package com.sitemonitor.service.watchdog;

import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.UserPushService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kendi kendini izleme yoklamaları (2026-10-11). Her kontrol SABİT sayıda ucuz sorgu (toplam ≤ 8) — dakikalık turda
 * çalışır; ağır hesap yok (izleme durumu Genel Bakış'ın 30 sn'lik belleğinden okunur). Bir yoklamanın hatası diğerlerini
 * durdurmaz: o kontrol UNKNOWN olur (alarm da iyileşme de üretmez). Olgular yalnız sayı / kısa kod — ad, adres, hata
 * metni taşımaz (dış uç da aynı durumu okur).
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

    /** Tek yoklama sonucu. */
    public record Probe(WatchdogCheck check, Result result, String severity, Map<String, Object> facts) {
        static Probe na(WatchdogCheck c, String reason) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", reason);
            return new Probe(c, Result.NA, null, f);
        }
    }

    private final JdbcTemplate jdbc;
    private final AppSettingsService settings;
    private ClusterScheduleService cluster;
    private MonitoringOverviewService overview;
    private NotificationLogRepository notificationLogs;
    private UserPushService push;
    private DatabaseHealthService database;

    @Value("${site.monitor.scheduler.sweep-leader-ttl-ms:180000}")
    private long leaseTtlMs = 180_000;

    public WatchdogProbes(JdbcTemplate jdbc, AppSettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Autowired(required = false) @Lazy public void setCluster(ClusterScheduleService v) { this.cluster = v; }
    @Autowired(required = false) @Lazy public void setOverview(MonitoringOverviewService v) { this.overview = v; }
    @Autowired(required = false) public void setNotificationLogs(NotificationLogRepository v) { this.notificationLogs = v; }
    @Autowired(required = false) @Lazy public void setPush(UserPushService v) { this.push = v; }
    @Autowired(required = false) @Lazy public void setDatabase(DatabaseHealthService v) { this.database = v; }

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
                case DATABASE -> database();
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
        return new Probe(WatchdogCheck.CERT_SWEEP, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.CERT_SWEEP.severity().name(), f);
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

    Probe pushOutbox(Instant now) {
        if (push == null || !push.enabled()) return Probe.na(WatchdogCheck.PUSH_OUTBOX, "push_disabled");
        String created = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES + 5)));
        String due = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES)));
        Long stuck = jdbc.queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'PENDING' "
                + "AND created_at < ? AND (next_attempt_at IS NULL OR next_attempt_at < ?)", Long.class, created, due);
        boolean circuitOpen = Boolean.TRUE.equals(push.healthSnapshot().get("circuit_open"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stuck", stuck == null ? 0 : stuck);
        f.put("stuck_minutes", PUSH_STUCK_MINUTES);
        f.put("circuit_open", circuitOpen);
        boolean problem = circuitOpen || (stuck != null && stuck >= PUSH_STUCK_THRESHOLD);
        return new Probe(WatchdogCheck.PUSH_OUTBOX, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.PUSH_OUTBOX.severity().name(), f);
    }

    Probe database() {
        if (database == null) return Probe.na(WatchdogCheck.DATABASE, "no_health_service");
        DatabaseHealthService.Snapshot s = database.current();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", s.status());
        if (DatabaseHealthService.UP.equals(s.status())) return new Probe(WatchdogCheck.DATABASE, Result.OK, null, f);
        String sev = DatabaseHealthService.DOWN.equals(s.status()) ? WatchdogCheck.Severity.CRITICAL.name() : WatchdogCheck.Severity.MEDIUM.name();
        return new Probe(WatchdogCheck.DATABASE, Result.PROBLEM, sev, f);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
