package com.sitemonitor.service.metrics;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.ActiveStorm;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.DomainDays;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Families;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Owners;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Row;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.TeamDirectory;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.TeamRef;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.watchdog.WatchdogCheck;
import com.sitemonitor.service.watchdog.WatchdogService;
import com.sitemonitor.util.TtlMemo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * İzleme ölçümleri aktarıcısı (2026-10-18, kullanıcı isteği: "SiteMonitor'ü Grafana'da uçtan uca izleyeyim; izlemelerin
 * durumu, alarmlar, sistemin kendi sağlığı"). {@code sitemonitor_*} iş gauge'larını AYRI kayıt defterine
 * ({@link BusinessMetricsRegistry}) yazar; yalnız taşıyıcı belirteçli {@code /metrics/sitemonitor} ucu kazır
 * ({@code MetricsExportController}) — kimliksiz {@code /metrics}'te iş verisi YOK. Belirteç ({@link MetricsExportToken})
 * tanımlı değilse aktarıcı hiç koşmaz. Ad ve etiket kataloğu {@link MonitoringMetricNames}, hesap
 * {@link MonitoringMetricsModel} (saf).
 *
 * <ul>
 *   <li><b>İstek yolunun dışında:</b> kendi tek daemon iş parçacığında, {@code refresh-seconds} (60 sn) aralıkla;
 *       paylaşılan zamanlayıcı havuzunu (8 iş parçacığı, 54 iş) kullanmaz. Kazıma (scrape) yalnız bellekteki değeri okur.</li>
 *   <li><b>Sınırlı iş:</b> tur başına sabit sayıda okuma — Genel Bakış'ın 30 sn'lik belleği (kuruluş görünümü), sertifika
 *       listesinin önbelleği ({@code cert-latest}), açık alarmların dar izdüşümü, envanter sahipleri, gruplu bildirim / push
 *       sayımları, kilit ve kira satırları. Kayıt başına sorgu YOK.</li>
 *   <li><b>Çok pod:</b> her pod aynı kuruluş değerlerini yayar; pano {@code max by (etiketler)} ile birleştirir. Pod'a
 *       özgü olanlar ({@code sitemonitor_sweep_leader}, aktarıcının kendi ölçümleri) pod başına okunur.</li>
 *   <li><b>Dürüstlük:</b> okunamayan kaynak o ailenin serilerini DÜŞÜRÜR ({@code sitemonitor_exporter_source_up=0}); sahte 0
 *       ya da "up" yazılmaz. Silinmiş / envanterden çıkmış izleme "down" yayılmaz.</li>
 *   <li><b>Kardinalite:</b> izleme / sertifika / alan adı / alarm başına seriler {@code per-monitor} anahtarıyla kapanır;
 *       aile başına {@code max-entities} (5000) tavanı — aşılınca sorunlular korunur, kalanı düşer, saatte bir WARN ve
 *       {@code sitemonitor_exporter_capped{family}=1}.</li>
 *   <li><b>Gizlilik:</b> etiketlerde kişi verisi YOK (takım ve izleme adları, hedef: şema + host + port + yol — sorgu,
 *       parça ve kullanıcı bilgisi HİÇ yazılmaz). Belirteci bilen herkes bütün envanteri görür; Grafana takım kapsamı uygulamaz
 *       (docs/RUNBOOK.md "Grafana panosu ve Prometheus").</li>
 *   <li><b>Tazelik:</b> son tamamlanan tur {@code refresh-seconds}'ın 3 katından (en az 3 dk) eskiyse uç iş değerlerini
 *       yayınlamaz ({@link #visibleNames()}) — takılmış iş parçacığının eski değerleri "güncel" görünmez.</li>
 * </ul>
 */
@Slf4j
@Component
public class MonitoringMetricsExporter {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Açık alarm izdüşümü üst sınırı — aşılırsa alarm aileleri dürüstçe düşer (gerçekte görülmez). */
    static final int OPEN_ALERT_READ_CAP = 50_000;
    /** Kilit adı tavanı (dinamik anahtarlar zaten {@code ad:*}'a katlanır). */
    static final int MAX_LOCK_NAMES = 200;
    /** Push "takılı" tanımı — kendi kendini izleme yoklamasıyla AYNI (10 dk). */
    static final int PUSH_STUCK_MINUTES = 10;

    static final String SQL_NOTIFY_EMAIL = "SELECT 'email', trg, st, COUNT(*) FROM (SELECT trigger AS trg, "
            + "CASE WHEN email_status IS NULL OR email_status = '' THEN 'NONE' WHEN email_status LIKE 'SENT%' THEN 'SENT' "
            + "WHEN email_status LIKE 'FAILED%' THEN 'FAILED' WHEN email_status LIKE 'SKIPPED%' THEN 'SKIPPED' "
            + "WHEN email_status LIKE 'QUEUED%' THEN 'QUEUED' ELSE 'OTHER' END AS st "
            + "FROM notification_logs WHERE sent_at >= ?) x GROUP BY trg, st";
    static final String SQL_NOTIFY_WEBHOOK = "SELECT 'webhook', trg, st, COUNT(*) FROM (SELECT trigger AS trg, "
            + "CASE WHEN webhook_status IS NULL OR webhook_status = '' THEN 'NONE' WHEN webhook_status LIKE 'SENT%' THEN 'SENT' "
            + "WHEN webhook_status LIKE 'FAILED%' THEN 'FAILED' WHEN webhook_status LIKE 'SKIPPED%' THEN 'SKIPPED' "
            + "WHEN webhook_status LIKE 'QUEUED%' THEN 'QUEUED' ELSE 'OTHER' END AS st "
            + "FROM notification_logs WHERE sent_at >= ?) x GROUP BY trg, st";
    static final String SQL_PUSH_24H = "SELECT status, COUNT(*) FROM user_push_deliveries WHERE created_at >= ? GROUP BY status";
    static final String SQL_PUSH_PENDING = "SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'PENDING'";
    static final String SQL_PUSH_STUCK = "SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'PENDING' "
            + "AND created_at < ? AND (next_attempt_at IS NULL OR next_attempt_at < ?)";
    static final String SQL_LOCKS = "SELECT name, locked_until FROM scheduler_lock";

    private final MeterRegistry registry;
    private final AppSettingsService settings;
    /** Belirteç tanımlı mı (açılışta sabit — ortam değişkeni çalışırken değişmez). */
    private final boolean tokenConfigured;
    /** Son TAMAMLANAN turun saati (epoch ms; 0 = hiç). */
    private volatile long lastCompletedMs;
    private final Map<String, MultiGauge> gauges = new LinkedHashMap<>();

    private MonitoringOverviewService overview;
    private AlertEventRepository alertRepo;
    private CertificateInventoryRepository inventoryRepo;
    private CertificateService certificateService;
    private DomainCheckRepository domainCheckRepo;
    private AlertStormRepository stormRepo;
    private TeamRepository teamRepo;
    private JdbcTemplate jdbc;
    private WatchdogService watchdog;
    private ClusterScheduleService cluster;
    private DatabaseHealthService database;
    private SystemMaintenanceService maintenance;
    private UserPushService push;

    private Supplier<Instant> clock = Instant::now;
    private volatile TeamDirectory lastTeams;
    private volatile Status lastStatus;
    private final AtomicLong capWarnAt = new AtomicLong();
    private volatile boolean stopping;
    private ScheduledExecutorService executor;

    /** Son turun özeti — Ayarlar → Grafana panosu "canlı kontrol" kartı (bu pod). */
    public record Status(boolean enabled, boolean perMonitor, String refreshedAt, long durationMs, int seriesTotal,
                         Map<String, Integer> entities, Map<String, Boolean> sources, List<String> capped,
                         int refreshSeconds, int maxEntities) {}

    /** Spring: iş kayıt defteri + belirteç. Küresel (kimliksiz {@code /metrics}) kayıt defterine HİÇBİR şey yazılmaz. */
    @Autowired
    public MonitoringMetricsExporter(BusinessMetricsRegistry business, AppSettingsService settings, MetricsExportToken token) {
        this(business.meters(), settings, token != null && token.configured());
    }

    /** Testler: belirteç tanımlıymış gibi. */
    MonitoringMetricsExporter(MeterRegistry registry, AppSettingsService settings) {
        this(registry, settings, true);
    }

    MonitoringMetricsExporter(MeterRegistry registry, AppSettingsService settings, boolean tokenConfigured) {
        this.registry = registry;
        this.settings = settings;
        this.tokenConfigured = tokenConfigured;
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            gauges.put(d.name(), MultiGauge.builder(d.name()).description(d.help()).register(registry));
        }
    }

    /** Belirteç tanımlı mı (yoksa aktarıcı koşmaz, uç 404). */
    public boolean tokenConfigured() {
        return tokenConfigured;
    }

    @Autowired(required = false) @Lazy public void setOverview(MonitoringOverviewService v) { this.overview = v; }
    @Autowired(required = false) public void setAlertRepo(AlertEventRepository v) { this.alertRepo = v; }
    @Autowired(required = false) public void setInventoryRepo(CertificateInventoryRepository v) { this.inventoryRepo = v; }
    @Autowired(required = false) @Lazy public void setCertificateService(CertificateService v) { this.certificateService = v; }
    @Autowired(required = false) public void setDomainCheckRepo(DomainCheckRepository v) { this.domainCheckRepo = v; }
    @Autowired(required = false) public void setStormRepo(AlertStormRepository v) { this.stormRepo = v; }
    @Autowired(required = false) public void setTeamRepo(TeamRepository v) { this.teamRepo = v; }
    @Autowired(required = false) public void setJdbc(JdbcTemplate v) { this.jdbc = v; }
    @Autowired(required = false) @Lazy public void setWatchdog(WatchdogService v) { this.watchdog = v; }
    @Autowired(required = false) @Lazy public void setCluster(ClusterScheduleService v) { this.cluster = v; }
    @Autowired(required = false) @Lazy public void setDatabase(DatabaseHealthService v) { this.database = v; }
    @Autowired(required = false) @Lazy public void setMaintenance(SystemMaintenanceService v) { this.maintenance = v; }
    @Autowired(required = false) @Lazy public void setPush(UserPushService v) { this.push = v; }

    /** Testler: saat enjeksiyonu. */
    void setClock(Supplier<Instant> clock) { this.clock = clock; }

    // ── Zamanlama ─────────────────────────────────────────────────────────────────────────────────────────

    /** Açılıştan 45 sn sonra ilk tur (Genel Bakış ısınsın), sonra her turun ardından {@code refresh-seconds} bekler. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (executor != null) return;
        if (!tokenConfigured) {
            // Belirteç yok → iş ölçümleri yayınlanamaz; okuma yükü de olmasın (iş parçacığı hiç başlamaz)
            log.info("İş ölçümleri aktarıcısı kapalı: {} tanımlı değil (ya da kısa) — /metrics/sitemonitor 404 döner.",
                    MetricsExportToken.ENV);
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-exporter");
            t.setDaemon(true);
            return t;
        });
        executor.schedule(this::loop, 45, TimeUnit.SECONDS);
    }

    private void loop() {
        if (stopping) return;
        try {
            refresh();
        } catch (Throwable t) {
            log.warn("İzleme ölçümleri tazelenemedi: {}", t.toString());
        } finally {
            if (!stopping && executor != null) {
                try {
                    executor.schedule(this::loop, MetricsExportSettings.refreshSeconds(settings), TimeUnit.SECONDS);
                } catch (Exception ignored) { /* kapanıyor */ }
            }
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        if (executor != null) executor.shutdownNow();
    }

    // ── Tur ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Bir tur: kaynakları oku, satırları üret, kayıt defterine yaz. Eşzamanlı çağrı (elle + zamanlı) sıralanır. */
    public synchronized Status refresh() {
        long t0 = System.nanoTime();
        Instant now = clock.get();
        boolean enabled = tokenConfigured && MetricsExportSettings.enabled(settings);
        boolean perEntity = MetricsExportSettings.perMonitor(settings);
        int maxEntities = MetricsExportSettings.maxEntities(settings);
        Families f = new Families();
        Map<String, Boolean> sources = new LinkedHashMap<>();

        if (enabled) collect(f, sources, now, perEntity, maxEntities);

        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        // Aktarıcının kendi ölçümleri (her zaman)
        f.add(MonitoringMetricNames.EXPORTER_ENABLED, enabled ? 1 : 0);
        f.add(MonitoringMetricNames.EXPORTER_PER_MONITOR, enabled && perEntity ? 1 : 0);
        if (enabled) {
            f.add(MonitoringMetricNames.EXPORTER_LAST_REFRESH, now.getEpochSecond());
            f.add(MonitoringMetricNames.EXPORTER_DURATION, durationMs / 1000.0);
            for (Map.Entry<String, Boolean> e : sources.entrySet()) {
                f.add(MonitoringMetricNames.EXPORTER_SOURCE, e.getValue() ? 1 : 0, "source", e.getKey());
            }
            for (String fam : List.of("monitors", "alerts", "certificates", "domains", "scheduler_locks")) {
                f.add(MonitoringMetricNames.EXPORTER_CAPPED, Boolean.TRUE.equals(f.capped().get(fam)) ? 1 : 0, "family", fam);
            }
        }
        int total = 0;
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            if (d.name().startsWith("sitemonitor.exporter.")) continue;
            int n = f.size(d.name());
            total += n;
            if (enabled && n > 0) f.add(MonitoringMetricNames.EXPORTER_SERIES, n, "family", d.prom());
        }
        apply(f);
        warnCapped(f);

        Map<String, Integer> entities = new LinkedHashMap<>();
        entities.put("monitors", f.size(MonitoringMetricNames.MONITOR_STATE));
        entities.put("certificates", f.size(MonitoringMetricNames.CERT_STATUS));
        entities.put("domains", f.size(MonitoringMetricNames.DOMAIN_DAYS));
        entities.put("alerts", f.size(MonitoringMetricNames.ALERT_OPEN));
        Status s = new Status(enabled, perEntity, ISO.format(now), durationMs, total, entities, sources,
                List.copyOf(f.capped().keySet()), MetricsExportSettings.refreshSeconds(settings), maxEntities);
        lastStatus = s;
        lastCompletedMs = clock.get().toEpochMilli();
        return s;
    }

    /** Bayatlık eşiği (ms): {@code refresh-seconds}'ın 3 katı, en az 3 dk. */
    long staleAfterMs() {
        return Math.max(180L, 3L * MetricsExportSettings.refreshSeconds(settings)) * 1000L;
    }

    /** Son tamamlanan tur eşikten yeni mi (hiç tur yoksa false). */
    public boolean fresh() {
        long last = lastCompletedMs;
        return last > 0 && clock.get().toEpochMilli() - last <= staleAfterMs();
    }

    /**
     * Kazımada yayınlanacak aileler: tazeyse {@code null} (hepsi); bayatsa yalnız {@link MonitoringMetricNames#STALE_VISIBLE}
     * (aktarıcının açık / son tur / süre ölçümleri). Böylece takılmış bir iş parçacığının eski iş değerleri ve
     * {@code source_up=1} kazınmaz; panoda iş panelleri "veri yok", tazelik şeridi "bayat" gösterir.
     */
    public java.util.Set<String> visibleNames() {
        return fresh() ? null : MonitoringMetricNames.STALE_VISIBLE;
    }

    /** Son turun özeti; hiç tur koşmadıysa null. */
    public Status lastStatus() {
        return lastStatus;
    }

    private void collect(Families f, Map<String, Boolean> sources, Instant now, boolean perEntity, int maxEntities) {
        TeamDirectory teams = readTeams(sources);
        if (teams != null) MonitoringMetricsModel.teams(f, teams);

        // Envanter sahipleri (alarm sahipliği + sertifika UG takımı) — bir kez okunur
        Map<String, Owners> owners = read(sources, "inventory", () -> {
            if (inventoryRepo == null) return null;
            Map<String, Owners> m = new HashMap<>();
            for (Object[] r : inventoryRepo.findDomainOwners()) {
                if (r == null || r.length < 3 || r[0] == null) continue;
                m.put(String.valueOf(r[0]), new Owners(asLong(r[1]), asLong(r[2])));
            }
            return m;
        });

        // İzlemeler + alan adları (Genel Bakış kuruluş görünümü — paylaşılan 30 sn'lik bellek)
        Map<String, Object> ov = read(sources, "overview", () -> {
            if (overview == null) return null;
            Map<String, Object> m = overview.build(TtlMemo.scopeKey(true, null), team -> true, true,
                    MonitoringOverviewService.ORG_SUMMARY_WINDOW_HOURS);
            if (m == null) throw new IllegalStateException("overview unavailable");
            // Açık alarmlar okunamadıysa durumlar alarmsız hesaplandı — "down" izleme "up" görünebilir: yayılmaz
            if (Boolean.TRUE.equals(m.get("alerts_unavailable"))) throw new IllegalStateException("overview alerts unavailable");
            return m;
        });
        if (ov != null && teams != null) {
            java.util.Set<String> failedTypes = MonitoringMetricsModel.monitors(f, ov, teams, perEntity, maxEntities);
            // Her tür kendi kaynak satırını yazar (1 okundu / 0 okunamadı): pano "süzgece uyan tür okunamadı" kararını
            // pod'lar arası max ile verir (bir pod okuduysa veri tamdır).
            for (String type : MonitoringMetricsModel.overviewTypes(ov)) sources.put("monitors:" + type, !failedTypes.contains(type));
            for (String type : failedTypes) sources.put("monitors:" + type, false);
            // Alan adı izlemeleri okunamadıysa kovalar "0 alan adı" diye yazılmasın (sahte 0 yok) — kaynak düşer
            List<DomainDays> days = failedTypes.contains("domain") ? null : read(sources, "domains", () -> {
                if (domainCheckRepo == null) return null;
                List<DomainDays> l = new ArrayList<>();
                for (DomainCheck c : domainCheckRepo.findLatestPerMonitor()) {
                    if (c != null && c.getMonitorId() != null) l.add(new DomainDays(c.getMonitorId(), c.getDaysRemaining()));
                }
                return l;
            });
            if (failedTypes.contains("domain")) sources.put("domains", false);
            if (days != null) MonitoringMetricsModel.domains(f, ov, days, teams, perEntity, maxEntities);
        }

        // Açık alarmlar
        if (teams != null && owners != null) {
            List<Object[]> open = read(sources, "alerts", () -> {
                if (alertRepo == null) return null;
                List<Object[]> l = alertRepo.findOpenMetricRows(PageRequest.of(0, OPEN_ALERT_READ_CAP + 1));
                if (l.size() > OPEN_ALERT_READ_CAP) throw new IllegalStateException("too many open alerts");
                return l;
            });
            if (open != null) MonitoringMetricsModel.alerts(f, open, owners, teams, perEntity, maxEntities, now);
        }

        // Sertifikalar (aktif envanter, cert-latest önbelleği)
        if (teams != null && owners != null) {
            List<CertificateDto> certs = read(sources, "certificates", () -> certificateService == null ? null : certificateService.getAllLatest());
            if (certs != null) MonitoringMetricsModel.certificates(f, certs, owners, teams, perEntity, maxEntities);
        }

        // Fırtınalar
        if (teams != null) {
            List<ActiveStorm> storms = read(sources, "storms", () -> {
                if (stormRepo == null) return null;
                List<ActiveStorm> l = new ArrayList<>();
                for (AlertStorm s : stormRepo.findByResolvedFalse()) l.add(new ActiveStorm(s.getTeamId(), s.getMemberCount()));
                return l;
            });
            if (storms != null) MonitoringMetricsModel.storms(f, storms, teams);
        }

        String since24h = ISO.format(now.minus(Duration.ofHours(24)));
        // Bildirimler (son 24 sa, kanal × tetik × durum sınıfı)
        List<Object[]> notif = read(sources, "notifications", () -> {
            if (jdbc == null) return null;
            List<Object[]> l = new ArrayList<>(jdbc.query(SQL_NOTIFY_EMAIL, MonitoringMetricsExporter::row4, since24h));
            l.addAll(jdbc.query(SQL_NOTIFY_WEBHOOK, MonitoringMetricsExporter::row4, since24h));
            return l;
        });
        if (notif != null) MonitoringMetricsModel.notifications(f, notif);

        // Kişisel push
        mergeInto(f, read(sources, "push", () -> {
            Families t = new Families();
            if (jdbc == null) return null;
            List<Object[]> rows = jdbc.query(SQL_PUSH_24H, (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, since24h);
            Long pending = jdbc.queryForObject(SQL_PUSH_PENDING, Long.class);
            String created = ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES + 5)));
            String due = ISO.format(now.minus(Duration.ofMinutes(PUSH_STUCK_MINUTES)));
            Long stuck = jdbc.queryForObject(SQL_PUSH_STUCK, Long.class, created, due);
            MonitoringMetricsModel.pushDeliveries(t, rows);
            t.add(MonitoringMetricNames.PUSH_PENDING, pending == null ? 0 : pending);
            t.add(MonitoringMetricNames.PUSH_STUCK, stuck == null ? 0 : stuck);
            if (push != null) {
                t.add(MonitoringMetricNames.PUSH_ENABLED, push.enabled() ? 1 : 0);
                t.add(MonitoringMetricNames.PUSH_CIRCUIT, Boolean.TRUE.equals(push.healthSnapshot().get("circuit_open")) ? 1 : 0);
            }
            return t;
        }));

        // Kendi kendini izleme
        mergeInto(f, read(sources, "watchdog", () -> {
            Families t = new Families();
            if (watchdog == null) return null;
            Map<String, Object> ps = watchdog.publicStatus(now);
            Integer code = MonitoringMetricsModel.watchdogCode(ps == null ? null : String.valueOf(ps.get("status")));
            if (code != null) t.add(MonitoringMetricNames.WATCHDOG_STATUS, code);
            Long evaluated = MonitoringMetricsModel.epochSeconds(ps == null || ps.get("evaluated_at") == null ? null
                    : String.valueOf(ps.get("evaluated_at")));
            if (evaluated != null) t.add(MonitoringMetricNames.WATCHDOG_EVALUATED, evaluated);
            Map<String, Map<?, ?>> byKey = new HashMap<>();
            if (ps != null && ps.get("checks") instanceof List<?> checks) {
                for (Object o : checks) if (o instanceof Map<?, ?> m && m.get("key") != null) byKey.put(String.valueOf(m.get("key")), m);
            }
            for (WatchdogCheck c : WatchdogCheck.values()) {
                Map<?, ?> m = byKey.get(c.key());
                t.add(MonitoringMetricNames.WATCHDOG_CHECK, MonitoringMetricsModel.watchdogCheckCode(
                        m == null || m.get("status") == null ? null : String.valueOf(m.get("status")),
                        m == null || m.get("severity") == null ? null : String.valueOf(m.get("severity"))), "check", c.key());
            }
            return t;
        }));

        // Tarama liderliği (salt okuma — kira ALINMAZ)
        mergeInto(f, read(sources, "cluster", () -> {
            Families t = new Families();
            if (cluster == null) return null;
            ClusterScheduleService.Lease lease = cluster.lease("sweep-leader");
            if (lease == null) return t;   // kira satırı yok (tek pod / henüz alınmadı) → bilinmiyor, yazılmaz
            boolean mine = cluster.owner() != null && cluster.owner().equals(lease.owner()) && lease.untilMs() > now.toEpochMilli();
            t.add(MonitoringMetricNames.SWEEP_LEADER, mine ? 1 : 0);
            t.add(MonitoringMetricNames.SWEEP_LEASE, (lease.untilMs() - now.toEpochMilli()) / 1000.0);
            return t;
        }));

        // Zamanlayıcı kilitleri
        List<Object[]> locks = read(sources, "scheduler_locks", () -> jdbc == null ? null
                : jdbc.query(SQL_LOCKS, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)}));
        if (locks != null) MonitoringMetricsModel.schedulerLocks(f, locks, now, MAX_LOCK_NAMES);

        // Veritabanı sağlığı (bu pod, 5 sn önbellek) ve sistem bakımı
        mergeInto(f, read(sources, "database", () -> {
            Families t = new Families();
            if (database == null) return null;
            Integer code = MonitoringMetricsModel.databaseCode(database.current().status());
            if (code != null) t.add(MonitoringMetricNames.DATABASE_STATUS, code);
            return t;
        }));
        mergeInto(f, read(sources, "maintenance", () -> {
            Families t = new Families();
            if (maintenance == null) return null;
            Map<String, Object> b = maintenance.clientBlock();
            Integer code = MonitoringMetricsModel.maintenanceCode(b == null || b.get("state") == null ? null : String.valueOf(b.get("state")));
            if (code != null) t.add(MonitoringMetricNames.SYSTEM_MAINTENANCE, code);
            return t;
        }));
    }

    /** Geçici aile kümesini (kaynak tamamen okunduysa) asıl kümeye ekler — yarım okunan kaynak satır bırakmaz. */
    private static void mergeInto(Families f, Families part) {
        if (part != null) f.mergeFrom(part);
    }

    /** Takım dizini: okunamazsa son başarılı okuma (adlar sabit kalsın, seriler el değiştirmesin); hiç yoksa null. */
    private TeamDirectory readTeams(Map<String, Boolean> sources) {
        TeamDirectory dir = read(sources, "teams", () -> {
            if (teamRepo == null) return null;
            Map<Long, TeamRef> m = new LinkedHashMap<>();
            for (Team t : teamRepo.findAll()) {
                if (t.getId() != null) m.put(t.getId(), new TeamRef(t.getId(), t.getName(), !Boolean.FALSE.equals(t.getActive())));
            }
            return new TeamDirectory(m);
        });
        if (dir != null) lastTeams = dir;
        return dir != null ? dir : lastTeams;
    }

    /** Kaynak okuma: başarı → değer + {@code source=1}; istisna → null + {@code source=0}; bağımlılık yok (null) → yazılmaz. */
    private <T> T read(Map<String, Boolean> sources, String name, Supplier<T> supplier) {
        try {
            T v = supplier.get();
            if (v != null) sources.put(name, true);
            return v;
        } catch (Exception e) {
            sources.put(name, false);
            log.debug("Ölçüm kaynağı '{}' okunamadı: {}", name, e.toString());
            return null;
        }
    }

    /**
     * Satırları kayıt defterine yazar. Her seri için KALICI bir değer tutucusu vardır: var olan seri yeniden kaydedilmez,
     * yalnız tutucunun değeri güncellenir ({@code overwrite=false}) — her turda kaldır / yeniden kaydet, eşzamanlı bir kazımanın
     * seriyi bir an göremeyip "bayat" işaretlemesine (grafikte boşluk, alarm anotasyonunda sahte "yeni alarm") yol açardı.
     * Kaybolan satırları MultiGauge siler; tutucusu da bırakılır. Gauge değer nesnesini ZAYIF tutar (DefaultGauge →
     * WeakReference) — tutucular {@link #holders} haritasında güçlü tutulur, GC'den sonra NaN olmaz.
     */
    void apply(Families f) {
        Map<String, Holder> next = new HashMap<>();
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            MultiGauge g = gauges.get(d.name());
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            for (Row r : f.get(d.name())) {
                List<Tag> tags = new ArrayList<>(r.labels().size());
                StringBuilder key = new StringBuilder(d.name());
                for (Map.Entry<String, String> e : r.labels().entrySet()) {
                    tags.add(Tag.of(e.getKey(), e.getValue()));
                    key.append('\u0000').append(e.getKey()).append('=').append(e.getValue());
                }
                String k = key.toString();
                Holder h = holders.get(k);
                if (h == null) h = new Holder();
                h.value = r.value();
                next.put(k, h);
                rows.add(MultiGauge.Row.of(Tags.of(tags), h, Holder::get));
            }
            g.register(rows, false);
        }
        holders = next;
    }

    /** Bir serinin değişken değer tutucusu. */
    static final class Holder {
        volatile double value;
        double get() { return value; }
    }

    /** Kayıtlı serilerin değer tutucuları (ölçüm adı + etiketler → tutucu). */
    private volatile Map<String, Holder> holders = new HashMap<>();

    private void warnCapped(Families f) {
        if (f.capped().isEmpty()) return;
        long last = capWarnAt.get();
        long nowMs = System.currentTimeMillis();
        if (nowMs - last < 3_600_000L || !capWarnAt.compareAndSet(last, nowMs)) return;
        log.warn("İzleme ölçümleri seri tavanına takıldı ({}): aile başına en çok {} kayıt yayılıyor — sorunlu kayıtlar korunur. "
                + "Ayarlar → Platform → Grafana panosu → kayıt tavanını artırın ya da izleme başına serileri kapatın.",
                f.capped().keySet(), MetricsExportSettings.maxEntities(settings));
    }

    private static Object[] row4(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)};
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    /** Pod kimliği (canlı kontrol kartı). */
    public static String instanceId() {
        try { return SchedulerService.instanceId(); } catch (Throwable t) { return "unknown"; }
    }
}
