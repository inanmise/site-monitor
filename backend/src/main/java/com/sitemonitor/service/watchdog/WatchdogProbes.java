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
            + "s.db_active, s.db_max, s.clock_skew_ms FROM system_metric_samples s JOIN (SELECT instance_id, MAX(sampled_at) AS m "
            + "FROM system_metric_samples WHERE sampled_at >= ? GROUP BY instance_id) x "
            + "ON x.instance_id = s.instance_id AND x.m = s.sampled_at";
    /** Saat farkı kolonu henüz yokken (açılışın ilk saniyeleri, yama düşmüş) — fark "ölçülmedi". */
    static final String SQL_POD_SAMPLES_NO_SKEW = "SELECT s.instance_id, s.pod_name, s.sampled_at, s.shutdown_marker, s.db_pending, "
            + "s.db_active, s.db_max FROM system_metric_samples s JOIN (SELECT instance_id, MAX(sampled_at) AS m "
            + "FROM system_metric_samples WHERE sampled_at >= ? GROUP BY instance_id) x "
            + "ON x.instance_id = s.instance_id AND x.m = s.sampled_at";

    /** Tek yoklama sonucu. */
    public record Probe(WatchdogCheck check, Result result, String severity, Map<String, Object> facts) {
        static Probe na(WatchdogCheck c, String reason) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", reason);
            return new Probe(c, Result.NA, null, f);
        }
    }

    /** Canlı bir pod'un son ölçüm örneği (kapanış işareti olmayan). {@code skewMs} null = ölçülmedi (eski pod / kolon yok). */
    public record PodSample(String instanceId, String pod, String sampledAt, Integer dbPending, Integer dbActive, Integer dbMax,
                            Long skewMs) {
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

    /**
     * Push kuyruğu: karar YALNIZ takılı PENDING satır sayısıyla (veritabanı geneli). Devre kesici pod'a özgü bellek durumudur
     * (her pod kendi ağ geçidi hatalarını sayar) — olgu olarak değerlendiren pod'un adıyla saklanır, durumu değiştirmez
     * (MP-36: A'da açık / B'de kapalı devre turdan tura onay sayacını döndürüyordu). Açık devre gönderimi durdurur → satırlar
     * takılır → sayı eşiği aşınca yine sorun açılır.
     */
    Probe pushOutbox(Instant now) {
        if (push == null || !push.enabled()) return Probe.na(WatchdogCheck.PUSH_OUTBOX, "push_disabled");
        String created = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES + 5)));
        String due = WatchdogTransition.ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES)));
        Long stuck = jdbc.queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'PENDING' "
                + "AND created_at < ? AND (next_attempt_at IS NULL OR next_attempt_at < ?)", Long.class, created, due);
        Boolean circuitOpen = null;
        try {
            circuitOpen = Boolean.TRUE.equals(push.healthSnapshot().get("circuit_open"));
        } catch (Exception e) {
            log.debug("watchdog: push devre kesici okunamadı: {}", e.toString());
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stuck", stuck == null ? 0 : stuck);
        f.put("stuck_minutes", PUSH_STUCK_MINUTES);
        f.put("circuit_open", circuitOpen);
        f.put("evaluated_by", SystemMetricSampler.currentPodName());
        boolean problem = stuck != null && stuck >= PUSH_STUCK_THRESHOLD;
        return new Probe(WatchdogCheck.PUSH_OUTBOX, problem ? Result.PROBLEM : Result.OK, WatchdogCheck.PUSH_OUTBOX.severity().name(), f);
    }

    /**
     * Veritabanı: karar veritabanı GENELİNDEKİ kontrollerle (bağlantı, sorgu süresi, yazılabilirlik — DOWN → kritik,
     * DEGRADED → orta) ve her CANLI pod'un son örneğindeki havuz doluluğuyla (bekleyen bağlantı ya da tam dolu havuz → orta).
     * Turu kazanan pod'un kendi havuzu ve açılış şema yaması sonucu yalnız olgu ({@code evaluated_by} ile). Veritabanına
     * erişilemiyorsa (DOWN) örnekler okunmaz (yerel kip — {@link WatchdogService#evaluateLocalDatabase}).
     */
    Probe database(Instant now) {
        if (database == null) return Probe.na(WatchdogCheck.DATABASE, "no_health_service");
        DatabaseHealthService.Snapshot s = database.current();
        Map<?, ?> checks = s.body() != null && s.body().get("checks") instanceof Map<?, ?> m ? m : null;
        // Gövde kontrol ayrıntısı taşımıyorsa (eski / sade gövde) genel durum aynen kullanılır
        String dbWide = checks == null ? s.status() : worst(checks, List.of("connection", "query", "writable"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("database_status", dbWide);
        f.put("evaluated_by", SystemMetricSampler.currentPodName());
        if (checks != null) {
            if (checks.get("pool") instanceof Map<?, ?> pool) f.put("evaluating_pod_pool", String.valueOf(pool.get("status")));
            if (checks.get("schema") instanceof Map<?, ?> schema && schema.get("failed_patches") instanceof Number n && n.intValue() > 0) {
                f.put("evaluating_pod_schema_failed", n.intValue());
            }
        }
        if (DatabaseHealthService.DOWN.equals(dbWide)) {
            f.put("status", DatabaseHealthService.DOWN);
            return new Probe(WatchdogCheck.DATABASE, Result.PROBLEM, WatchdogCheck.Severity.CRITICAL.name(), f);
        }
        // Küme havuzu: her canlı pod'un son örneği (okunamazsa yalnız "bilinmiyor" olgusu — karara girmez)
        List<String> saturated = new ArrayList<>();
        if (samplesEnabled) {
            try {
                List<PodSample> pods = livePods(now);
                for (PodSample p : pods) if (p.poolSaturated() && saturated.size() < MAX_FACT_PODS) saturated.add(p.pod());
                f.put("pool_pods_read", pods.size());
            } catch (Exception e) {
                f.put("pool_unknown", true);
                log.debug("watchdog: pod havuz örnekleri okunamadı: {}", e.toString());
            }
        }
        if (!saturated.isEmpty()) f.put("pool_saturated_pods", saturated);
        boolean degraded = DatabaseHealthService.DEGRADED.equals(dbWide) || !saturated.isEmpty();
        f.put("status", degraded ? DatabaseHealthService.DEGRADED : DatabaseHealthService.UP);
        if (!degraded) return new Probe(WatchdogCheck.DATABASE, Result.OK, null, f);
        return new Probe(WatchdogCheck.DATABASE, Result.PROBLEM, WatchdogCheck.Severity.MEDIUM.name(), f);
    }

    /**
     * Saat uyumu (MP-37c): her canlı pod'un son örneğindeki {@code clock_skew_ms} (pod − veritabanı). En büyük mutlak fark
     * {@link #SKEW_PROBLEM_MS} üstü → yüksek, {@link #SKEW_WARN_MS} üstü → orta önemli sorun. Hiçbir canlı pod'un farkı
     * bilinmiyorsa (eski sürüm pod'lar, açılışın ilk dakikası) UNKNOWN — sayaçlar değişmez.
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
        known.sort(Comparator.comparingLong((PodSample p) -> -Math.abs(p.skewMs())).thenComparing(PodSample::pod));
        List<Map<String, Object>> list = new ArrayList<>();
        for (PodSample p : known) {
            if (list.size() >= MAX_FACT_PODS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pod", p.pod());
            m.put("skew_ms", p.skewMs());
            list.add(m);
        }
        PodSample worst = known.get(0);
        long maxAbs = Math.abs(worst.skewMs());
        f.put("pods", list);
        f.put("max_abs_ms", maxAbs);
        f.put("worst_pod", worst.pod());
        f.put("worst_skew_ms", worst.skewMs());
        if (maxAbs > SKEW_PROBLEM_MS) return new Probe(WatchdogCheck.CLOCK_SKEW, Result.PROBLEM, WatchdogCheck.Severity.HIGH.name(), f);
        if (maxAbs > SKEW_WARN_MS) return new Probe(WatchdogCheck.CLOCK_SKEW, Result.PROBLEM, WatchdogCheck.Severity.MEDIUM.name(), f);
        return new Probe(WatchdogCheck.CLOCK_SKEW, Result.OK, null, f);
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
                    longOrNull(rs.getObject(8))}, since);
        } catch (Exception e) {
            // Saat farkı kolonu yok (eski şema / yama henüz koşmadı) — kolonsuz okuma, fark "ölçülmedi"
            rows = jdbc.query(SQL_POD_SAMPLES_NO_SKEW, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getObject(4), intOrNull(rs.getObject(5)), intOrNull(rs.getObject(6)), intOrNull(rs.getObject(7)), null}, since);
        }
        Map<String, Object[]> newest = new LinkedHashMap<>();
        Map<String, Boolean> stopped = new HashMap<>();
        for (Object[] r : rows) {
            String inst = (String) r[0];
            if (inst == null) continue;
            if (truthy(r[3])) { stopped.put(inst, true); continue; }   // en yeni dakikada kapanış işareti → durdu
            newest.putIfAbsent(inst, r);
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
            out.add(new PodSample(inst, name, (String) r[2], (Integer) r[4], (Integer) r[5], (Integer) r[6], (Long) r[7]));
        }
        out.sort(Comparator.comparing(PodSample::pod));
        return out;
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
