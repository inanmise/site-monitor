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
import com.sitemonitor.service.evalstate.EvalStateMode;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalVerifyReport;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import java.util.concurrent.RejectedExecutionException;
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
 *   <li><b>Çok pod:</b> kuruluş değerlerini aynı anda yayan her pod aynı değeri yayar; pano {@code max by (etiketler)} ile
 *       birleştirir. Pod'a özgü olanlar ({@code sitemonitor_sweep_leader}, aktarıcının kendi ölçümleri) pod başına okunur.</li>
 *   <li><b>Kurum geneli ailelerin yerleşimi (2026-10-23, çok pod R3-1, plan 3e §3.6):</b> tarama lideri, BAŞKA bir pod'un taze
 *       {@code metrics-org} gözlemini ({@code cluster_observations}, son {@code 2,5 × refresh-seconds}) görürse yalnız POD
 *       kapsamlı aileleri ve kendi ölçümlerini yayar — Genel Bakış kurulumu, alarm / sertifika / bildirim okumaları lideri
 *       yormaz. Gözlemi kurum ailelerini EKSİKSİZ (hiç kaynak düşmeden) aktaran LİDER OLMAYAN pod yazar (bkz.
 *       {@link #exportOrgFamilies}). Tek pod, okunamayan gözlem, 20.167.0 komşu (gözlem yazmaz) → lider de yayar
 *       (bugünkü davranış). Lider değişince yeni lider, eski lider takipçi olarak yayana dek yaymayı sürdürür (örtüşme, boşluk
 *       yok). <b>Kapanış ve takip (2026-10-23, R3-1 inceleme M1):</b> {@code ContextClosedEvent}'ten sonra pod gözlem YAZMAZ
 *       (liderliği bırakan eski lider "takipçi" olarak yazıp yeni lideri susturmasın); satır bu pod'unsa yerine bir KAPANIŞ
 *       işareti ({@code {"stopping":true}}) yazılır — lider onu "başka yayan yok" sayar. Yalnız POD aileleri yayan lider satırı
 *       {@value #ORG_RECHECK_MS} ms'de bir (tek PK okuması) yeniden denetler; satır yok / eski / kapanış işareti / yayımlayanı
 *       artık canlı değil (heartbeat + kapanış işareti, {@code PodLiveness}) ise hemen kurum ailelerini yayan bir tur koşar.
 *       Gözlem turun BİTİŞ anını ve süresini taşır; tazelik penceresi {@code 2,5 × aralık + son tur süresi}.</li>
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
    /** Kurum geneli aileleri başka pod yayıyor sayılması için gözlemin en çok yaşı: {@code refresh-seconds} × bu çarpan. */
    static final double ORG_OBSERVATION_FRESH_FACTOR = 2.5;
    /**
     * Yalnız POD aileleri yayan liderin {@code metrics-org} satırını yeniden denetleme aralığı (ms) — kurum ailelerini yayan
     * pod giderse (kapanış işareti) lider en geç bu süre + bir tur içinde yaymaya döner (tam {@code refresh-seconds} beklemez).
     */
    static final long ORG_RECHECK_MS = 10_000L;
    /** Gözlem yükündeki kapanış işareti alanı: yazan pod kapanıyor, kurum ailelerini artık yaymayacak. */
    static final String ORG_PAYLOAD_STOPPING = "stopping";
    private static final tools.jackson.databind.ObjectMapper PAYLOAD_JSON = tools.jackson.databind.json.JsonMapper.builder().build();

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
    private com.sitemonitor.service.schedule.ClusterLockService lockService;
    private SweepLeadershipCounter leadership;
    private com.sitemonitor.service.monitoring.ManualEvalInbox inbox;
    private SystemMetricSampler sampler;
    /** Çok pod R3-1: liderlik ipucu (veritabanısız), küme gözlemi ve bu pod'un değerlendirme durumu kaydedicisi. */
    private SchedulerService scheduler;
    private com.sitemonitor.service.health.ClusterHealthStore clusterHealth;
    private ObjectProvider<EvalStateSink> evalState;
    /** Çok pod R3-3: ana anahtar + tür ayarı (bellek), iş kuyruğu (yalnız açıkken SQL), bu pod'un işçisi ve sonuç boşaltıcısı. */
    private com.sitemonitor.service.checks.DistributedChecks distributedChecks;
    private com.sitemonitor.service.checks.CheckJobStore checkJobStore;
    private com.sitemonitor.service.checks.CheckWorker checkWorker;
    private com.sitemonitor.service.checks.CheckResultDrainer checkResultDrainer;
    /** Çok pod R3-4 (paket C1): parçalı sertifika taraması anahtarı + pod sayaçları (bellek), koşu deposu (yalnız açıkken SQL). */
    private com.sitemonitor.service.certsweep.CertChunking certChunking;
    private com.sitemonitor.service.certsweep.CertSweepStore certSweepStore;

    private Supplier<Instant> clock = Instant::now;
    private volatile TeamDirectory lastTeams;
    private volatile Status lastStatus;
    private final AtomicLong capWarnAt = new AtomicLong();
    private volatile boolean stopping;
    /** {@code ContextClosedEvent} geldi (R3-1 inceleme M1): gözlem yazılmaz; tur yine koşar (pod ölene dek yayar). */
    private volatile boolean closing;
    private volatile ScheduledExecutorService executor;
    /** {@code ContextClosedEvent}'in BU bağlamdan geldiğini ayırt etmek için (alt bağlamın kapanışı kapanış saymasın). */
    private ApplicationContext context;
    /** Gözlemde kullanılan örnek kimliği; null = {@link #instanceId()} (testler iki aktarıcıya ayrı kimlik verir). */
    private volatile String selfId;
    /**
     * Gözlemi yazan pod canlı mı (heartbeat + kapanış işareti) — aktarıcının KENDİ örneği (yan iş yerleşiminin önbelleğini
     * ısıtmasın / değiştirmesin); yalnız aktarıcı başladığında kurulur. Null = denetim yok (satıra güvenilir).
     */
    private volatile com.sitemonitor.service.schedule.PodLiveness liveness;
    /** İlk tur gecikmesi ve POD-only yeniden denetim aralığı (ms) — testler kısaltır. */
    long initialDelayMs = 45_000L;
    long recheckMs = ORG_RECHECK_MS;

    /**
     * Son turun özeti — Ayarlar → Grafana panosu "canlı kontrol" kartı (bu pod). {@code orgFamilies} false = bu pod yalnız
     * kendi (POD) ailelerini yaydı (başka pod kurum ailelerini yayıyor — çok pod R3-1); o turda {@code entities} boştur
     * (ekran "—" der, sahte 0 değil).
     */
    public record Status(boolean enabled, boolean perMonitor, String refreshedAt, long durationMs, int seriesTotal,
                         Map<String, Integer> entities, Map<String, Boolean> sources, List<String> capped,
                         int refreshSeconds, int maxEntities, boolean orgFamilies) {

        /** R3-1 öncesi imza (kurum aileleri yayıldı) — mevcut çağıranlar / testler. */
        public Status(boolean enabled, boolean perMonitor, String refreshedAt, long durationMs, int seriesTotal,
                      Map<String, Integer> entities, Map<String, Boolean> sources, List<String> capped,
                      int refreshSeconds, int maxEntities) {
            this(enabled, perMonitor, refreshedAt, durationMs, seriesTotal, entities, sources, capped, refreshSeconds, maxEntities, true);
        }
    }

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
    @Autowired(required = false) @Lazy public void setLockService(com.sitemonitor.service.schedule.ClusterLockService v) { this.lockService = v; }
    @Autowired(required = false) public void setLeadership(SweepLeadershipCounter v) { this.leadership = v; }
    @Autowired(required = false) @Lazy public void setInbox(com.sitemonitor.service.monitoring.ManualEvalInbox v) { this.inbox = v; }
    @Autowired(required = false) @Lazy public void setSampler(SystemMetricSampler v) { this.sampler = v; }
    @Autowired(required = false) @Lazy public void setScheduler(SchedulerService v) { this.scheduler = v; }
    @Autowired(required = false) public void setClusterHealth(com.sitemonitor.service.health.ClusterHealthStore v) { this.clusterHealth = v; }
    /** Kaydedici (paket A1) isteğe bağlı ve döngüsüz: tur anında çözülür; yoksa değerlendirme durumu ölçümleri yazılmaz. */
    @Autowired(required = false) public void setEvalState(ObjectProvider<EvalStateSink> v) { this.evalState = v; }
    /** Çok pod R3-3 (paket D1): dağıtık kontroller — hepsi isteğe bağlı; anahtar kapalıyken hiçbiri okunmaz. */
    @Autowired(required = false) public void setDistributedChecks(com.sitemonitor.service.checks.DistributedChecks v) { this.distributedChecks = v; }
    @Autowired(required = false) public void setCheckJobStore(com.sitemonitor.service.checks.CheckJobStore v) { this.checkJobStore = v; }
    @Autowired(required = false) public void setCheckWorker(com.sitemonitor.service.checks.CheckWorker v) { this.checkWorker = v; }
    @Autowired(required = false) public void setCheckResultDrainer(com.sitemonitor.service.checks.CheckResultDrainer v) { this.checkResultDrainer = v; }
    /** Çok pod R3-4 (paket C1): parçalı sertifika taraması — isteğe bağlı; anahtar kapalıyken hiçbiri okunmaz. */
    @Autowired(required = false) public void setCertChunking(com.sitemonitor.service.certsweep.CertChunking v) { this.certChunking = v; }
    @Autowired(required = false) public void setCertSweepStore(com.sitemonitor.service.certsweep.CertSweepStore v) { this.certSweepStore = v; }

    @Autowired(required = false) public void setContext(ApplicationContext v) { this.context = v; }

    /** Testler: saat enjeksiyonu. */
    void setClock(Supplier<Instant> clock) { this.clock = clock; }

    /** Testler: aynı JVM'deki iki aktarıcı ayrı pod gibi gözlem yazsın (üretimde {@link #instanceId()}). */
    void setInstanceIdForTest(String id) { this.selfId = id; }

    /** Testler: gözlemi yazanın canlılık denetimi (üretimde aktarıcı başlarken kendi örneğini kurar). */
    void setLivenessForTest(com.sitemonitor.service.schedule.PodLiveness v) { this.liveness = v; }

    /** Bu aktarıcının örnek kimliği. */
    String self() {
        String id = selfId;
        return id != null ? id : instanceId();
    }

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
        // Gözlemi yazanın canlılığı: aktarıcının KENDİ örneği (yan iş yerleşiminin paylaşılan önbelleğine dokunmaz)
        if (liveness == null && jdbc != null) {
            liveness = new com.sitemonitor.service.schedule.PodLiveness(jdbc, self(), () -> clock.get().toEpochMilli());
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-exporter");
            t.setDaemon(true);
            return t;
        });
        executor.schedule(this::loop, initialDelayMs, TimeUnit.MILLISECONDS);
    }

    private void loop() {
        if (stopping) return;
        try {
            refresh();
        } catch (Throwable t) {
            log.warn("İzleme ölçümleri tazelenemedi: {}", t.toString());
        } finally {
            scheduleNext();
        }
    }

    /**
     * Sonraki turu planlar. Lider yalnız POD aileleri yaydıysa (kurum ailelerini başka pod yayıyor) tur aralığı boyunca satırı
     * {@link #recheckMs} ms'de bir denetler ({@link #watch}); aksi hâlde bugünkü gibi {@code refresh-seconds} sonra.
     */
    private void scheduleNext() {
        ScheduledExecutorService ex = executor;
        if (stopping || ex == null) return;
        try {
            long refreshMs = MetricsExportSettings.refreshSeconds(settings) * 1000L;
            Status s = lastStatus;
            if (s != null && s.enabled() && !s.orgFamilies() && recheckMs > 0 && recheckMs < refreshMs) {
                long dueNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(refreshMs);
                ex.schedule(() -> watch(dueNanos), recheckMs, TimeUnit.MILLISECONDS);
            } else {
                ex.schedule(this::loop, refreshMs, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException ignored) { /* kapanıyor */ }
    }

    /**
     * POD-only lider beklemesi (R3-1 inceleme M1): tur zamanı geldiyse ya da kurum ailelerini artık bu pod'un yayması
     * gerekiyorsa ({@link #orgPlacementChanged}) HEMEN tur koşar; aksi hâlde {@link #recheckMs} sonra yeniden bakar.
     */
    private void watch(long dueNanos) {
        if (stopping) return;
        boolean runNow;
        try {
            runNow = System.nanoTime() - dueNanos >= 0 || orgPlacementChanged();
        } catch (Throwable t) {
            runNow = true;
        }
        if (runNow) {
            loop();
            return;
        }
        ScheduledExecutorService ex = executor;
        if (ex == null) return;
        try {
            long leftMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(dueNanos - System.nanoTime()));
            ex.schedule(() -> watch(dueNanos), Math.min(recheckMs, leftMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) { /* kapanıyor */ }
    }

    /**
     * Yalnız POD aileleri yayan pod'un kararı değişti mi: artık lider değil (takipçi kurum ailelerini yayar) ya da kurum
     * ailelerini yayan başka pod kalmadı ({@link #exportOrgFamilies} — satır yok / eski / kapanış işareti / yayımlayan canlı
     * değil). Tek PK okuması; canlılık kararı 30 sn önbellekli.
     */
    boolean orgPlacementChanged() {
        if (!(tokenConfigured && MetricsExportSettings.enabled(settings))) return false;
        if (!leaderHint()) return true;
        return exportOrgFamilies(clock.get());
    }

    /**
     * Kapanış başladı (R3-1 inceleme M1): bu andan sonra {@code metrics-org} gözlemi YAZILMAZ — liderliği bırakan pod'un
     * ipucu false olur ve "takipçi" olarak yazdığı gözlem kalan pod'u yalnız POD ailelerine düşürürdü. Satır bu pod'unsa
     * aktarıcının kendi iş parçacığında (süren turdan SONRA) kapanış işareti yazılır; dinleyici beklemez, veritabanına gitmez
     * (ortak kapanış bütçesi değişmez). Tur yine koşar: pod ölene dek kurum ailelerini de yayar (örtüşme, boşluk değil).
     * Tarama liderliği kirası bırakıldıktan hemen SONRA, bildirim havuzu boşaltmasından (≤ 20 sn) ÖNCE.
     */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public void onContextClosed(ContextClosedEvent event) {
        if (context != null && event != null && event.getApplicationContext() != context) return;
        closing = true;
        ScheduledExecutorService ex = executor;
        if (ex == null) return;   // aktarıcı hiç başlamadı (belirteç yok) → gözlem de yazılmadı
        try {
            ex.execute(this::writeTombstone);
        } catch (RejectedExecutionException ignored) { /* zaten duruyor */ }
    }

    /**
     * Kapanış işareti: satır BU pod'un gözlemiyse (başka takipçinin taze gözlemi ezilmesin) aynı satıra "yayan kapanıyor"
     * yazılır (en yeni kazanır — satırdakinden 1 ms yeni). Hiç fırlatmaz.
     */
    void writeTombstone() {
        com.sitemonitor.service.health.ClusterHealthStore store = clusterHealth;
        if (store == null) return;
        try {
            java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation> o =
                    store.read(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG);
            if (o == null || o.isEmpty() || !selfObserver().equals(o.get().instanceId())) return;
            if (OrgPayload.parse(o.get().payload()).stopping()) return;
            long at = Math.max(clock.get().toEpochMilli(), o.get().observedMs() + 1);
            String iso = ISO.format(Instant.ofEpochMilli(at));
            store.publish(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG, self(), at,
                    "{\"at\":\"" + iso + "\",\"" + ORG_PAYLOAD_STOPPING + "\":true}", iso);
        } catch (RuntimeException e) {
            log.debug("Kurum ölçüleri kapanış işareti yazılamadı: {}", e.toString());
        }
    }

    /** Kapanış başladı mı (testler). */
    boolean closing() {
        return closing;
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        closing = true;
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
        // Kurum geneli aileler bu turda bu pod'dan mı (R3-1 §3.6) — kapalıyken karar okunmaz (veritabanı yükü yok)
        boolean leader = enabled && leaderHint();
        boolean org = !enabled || !leader || exportOrgFamilies(now);

        if (enabled) collect(f, sources, now, perEntity, maxEntities, org);

        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        // Aktarıcının kendi ölçümleri (her zaman)
        f.add(MonitoringMetricNames.EXPORTER_ENABLED, enabled ? 1 : 0);
        f.add(MonitoringMetricNames.EXPORTER_PER_MONITOR, enabled && perEntity ? 1 : 0);
        if (enabled) {
            f.add(MonitoringMetricNames.EXPORTER_LAST_REFRESH, now.getEpochSecond());
            f.add(MonitoringMetricNames.EXPORTER_DURATION, durationMs / 1000.0);
            f.add(MonitoringMetricNames.EXPORTER_ORG_FAMILIES, org ? 1 : 0);
            for (Map.Entry<String, Boolean> e : sources.entrySet()) {
                f.add(MonitoringMetricNames.EXPORTER_SOURCE, e.getValue() ? 1 : 0, "source", e.getKey());
            }
            // Tavan yalnız kurum ailelerinin özelliği — yalnız POD ailesi yayan tur "0" yazıp başka pod'un "1"ini gölgelemez
            if (org) {
                for (String fam : List.of("monitors", "alerts", "certificates", "domains", "scheduler_locks")) {
                    f.add(MonitoringMetricNames.EXPORTER_CAPPED, Boolean.TRUE.equals(f.capped().get(fam)) ? 1 : 0, "family", fam);
                }
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
        if (org) {
            entities.put("monitors", f.size(MonitoringMetricNames.MONITOR_STATE));
            entities.put("certificates", f.size(MonitoringMetricNames.CERT_STATUS));
            entities.put("domains", f.size(MonitoringMetricNames.DOMAIN_DAYS));
            entities.put("alerts", f.size(MonitoringMetricNames.ALERT_OPEN));
        }
        Status s = new Status(enabled, perEntity, ISO.format(now), durationMs, total, entities, sources,
                List.copyOf(f.capped().keySet()), MetricsExportSettings.refreshSeconds(settings), maxEntities, org);
        lastStatus = s;
        long doneMs = clock.get().toEpochMilli();
        lastCompletedMs = doneMs;
        // Kurum ailelerini EKSİKSİZ yayan lider OLMAYAN pod gözlemi yazar; lider yazmaz — yazsaydı takipçinin gözlemini
        // ezip (en yeni kazanır) kendini bir sonraki turda yine "tek yayan" görebilirdi. Kapanan pod yazmaz (M1: liderliği
        // bırakan pod'un ipucu false olur); gözlem turun BİTİŞ anını taşır (L3: döngü bitişten sonra bekler).
        if (enabled && org && !leader && !closing && !sources.containsValue(Boolean.FALSE)) {
            publishOrgObservation(doneMs, total, durationMs);
        }
        return s;
    }

    // ── Kurum geneli ailelerin yerleşimi (2026-10-23, çok pod R3-1, plan 3e §3.6) ────────────────────────────────

    /** Bu pod tarama lideri mi — VERİTABANISIZ ipucu ({@code SchedulerService.sweepLeaderHint()}); bilinmiyorsa false. */
    private boolean leaderHint() {
        if (scheduler == null) return false;
        try {
            return scheduler.sweepLeaderHint();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Lider bu turda kurum geneli aileleri de yayacak mı: yalnız BAŞKA bir pod'un {@code metrics-org} gözlemi son
     * {@code 2,5 × refresh-seconds + yayımlayanın son tur süresi} içindeyse, kapanış işareti DEĞİLSE ve yayımlayan canlıysa
     * (heartbeat; bilinmiyorsa satıra güvenilir) HAYIR. Gözlem yok / kendi gözlemi / eski / kapanış işareti / yayımlayan canlı
     * değil / okunamadı / küme deposu yok → EVET (bugünkü davranış: veri boşluğu yerine çift yayın — pano {@code max by} ile
     * birleştirir).
     */
    boolean exportOrgFamilies(Instant now) {
        if (clusterHealth == null) return true;
        try {
            java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation> o =
                    clusterHealth.read(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG);
            if (o == null || o.isEmpty()) return true;
            String by = o.get().instanceId();
            if (by == null || by.isBlank() || by.equals(selfObserver())) return true;
            OrgPayload p = OrgPayload.parse(o.get().payload());
            if (p.stopping()) return true;                              // yayan pod kapanıyor (M1)
            long windowMs = (long) (ORG_OBSERVATION_FRESH_FACTOR * MetricsExportSettings.refreshSeconds(settings) * 1000L)
                    + Math.min(Math.max(0L, p.durationMs()), staleAfterMs());  // yavaş takipçinin tur süresi (L3)
            if (o.get().observedMs() < now.toEpochMilli() - windowMs) return true;
            return publisherKnownGone(by);
        } catch (RuntimeException e) {
            log.debug("Kurum ölçüleri gözlemi okunamadı — bu pod da yayıyor: {}", e.toString());
            return true;
        }
    }

    /**
     * Gözlemi yazan pod KESİNLİKLE canlı değil mi (heartbeat penceresinde yok ya da kapanış işareti yazmış). Canlılık
     * okunamadı / denetim yok → false (satıra güvenilir). Hiç fırlatmaz.
     */
    private boolean publisherKnownGone(String by) {
        com.sitemonitor.service.schedule.PodLiveness l = liveness;
        if (l == null) return false;
        try {
            if (l.otherLivePods().isEmpty()) return false;
            return !l.cachedOtherPods().contains(by);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Gözlem satırına yazılan örnek kimliği ({@code ClusterHealthStore} 120 karakterde kırpar — karşılaştırma da öyle). */
    private String selfObserver() {
        String id = self();
        return id.length() <= 120 ? id : id.substring(0, 119) + "…";
    }

    private void publishOrgObservation(long atMs, int seriesTotal, long durationMs) {
        if (clusterHealth == null) return;
        try {
            String at = ISO.format(Instant.ofEpochMilli(atMs));
            clusterHealth.publish(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG, self(), atMs,
                    "{\"at\":\"" + at + "\",\"series\":" + seriesTotal + ",\"duration_ms\":" + Math.max(0L, durationMs) + "}", at);
        } catch (RuntimeException e) {
            log.debug("Kurum ölçüleri gözlemi yazılamadı: {}", e.toString());
        }
    }

    /** {@code metrics-org} gözlem yükü: kapanış işareti mi, yayımlayanın son tur süresi (ms; yoksa 0). Bozuk yük = sıradan gözlem. */
    record OrgPayload(boolean stopping, long durationMs) {
        static OrgPayload parse(String payload) {
            if (payload == null || payload.isBlank()) return new OrgPayload(false, 0L);
            try {
                Object o = PAYLOAD_JSON.readValue(payload, Map.class);
                if (!(o instanceof Map<?, ?> m)) return new OrgPayload(false, 0L);
                boolean stop = Boolean.TRUE.equals(m.get(ORG_PAYLOAD_STOPPING));
                long d = m.get("duration_ms") instanceof Number n ? n.longValue() : 0L;
                return new OrgPayload(stop, d);
            } catch (RuntimeException e) {
                return new OrgPayload(false, 0L);
            }
        }
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

    /**
     * Kaynakları okur. {@code org} false → yalnız POD kapsamlı aileler (tarama lideri bayrağı, veritabanı sağlığı, push
     * devre kesicisi, çok pod sayaçları, değerlendirme durumu): Genel Bakış, alarm, sertifika, bildirim, kilit, gelen kutusu,
     * kendi kendini izleme ve bakım OKUNMAZ, KÜME aileleri ve onların {@code source_up} satırları yazılmaz (yok ≠ 0; başka
     * pod yayıyor). Push devre kesicisi bu pod'un belleğidir: kaynak satırı yazılmaz (pano {@code guard()} "push" kaynağını
     * kurum aileleri için okur — yalnız POD yayan pod'un "1"i başka pod'un okuyamadığı push sayılarını sahte 0 yapmasın).
     */
    private void collect(Families f, Map<String, Boolean> sources, Instant now, boolean perEntity, int maxEntities, boolean org) {
        if (!org) {
            collectPodOnly(f, sources, now);
            return;
        }
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

        // Çok pod çalışma ölçümleri (2026-10-23, R2b): pod'a özgü birikimli sayaçlar bu pod'un belleğinden (okuma hatası
        // olamaz), elle kontrol gelen kutusu derinliği küme genelinden (tek COUNT; okunamazsa seri düşer, sahte 0 yok)
        podRuntime(f);
        evalStateMeters(f);
        Long depth = read(sources, "eval_inbox", () -> inbox == null ? null : Long.valueOf(inbox.depth()));
        if (depth != null) f.add(MonitoringMetricNames.EVAL_INBOX_DEPTH, depth);
        // Dağıtık kontroller (R3-3): kuyruk KÜME ailesi (tek gruplu deyim, yalnız anahtar + tür açıkken), işçi POD ailesi (bellek)
        checkJobQueueMeters(f, sources);
        checkJobPodMeters(f);
        // Parçalı sertifika taraması (R3-4): açık koşunun parçaları + tür başına son tur (KÜME, yalnız anahtar açıkken), pod sayaçları
        certSweepClusterMeters(f, sources);
        certSweepPodMeters(f);

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

    /** Yalnız POD aileleri (kurum aileleri başka pod'da) — {@link #collect} açıklaması. */
    private void collectPodOnly(Families f, Map<String, Boolean> sources, Instant now) {
        if (push != null) {
            try {
                f.add(MonitoringMetricNames.PUSH_CIRCUIT, Boolean.TRUE.equals(push.healthSnapshot().get("circuit_open")) ? 1 : 0);
            } catch (RuntimeException e) {
                log.debug("Push devre kesici durumu okunamadı: {}", e.toString());
            }
        }
        // Tarama liderliği (salt okuma): lider bayrağı POD ailesi — her pod yayar (toplam 1 olmalı); kira süresi KÜME ailesi
        mergeInto(f, read(sources, "cluster", () -> {
            Families t = new Families();
            if (cluster == null) return null;
            ClusterScheduleService.Lease lease = cluster.lease("sweep-leader");
            if (lease == null) return t;
            boolean mine = cluster.owner() != null && cluster.owner().equals(lease.owner()) && lease.untilMs() > now.toEpochMilli();
            t.add(MonitoringMetricNames.SWEEP_LEADER, mine ? 1 : 0);
            return t;
        }));
        podRuntime(f);
        evalStateMeters(f);
        checkJobPodMeters(f);
        certSweepPodMeters(f);
        mergeInto(f, read(sources, "database", () -> {
            Families t = new Families();
            if (database == null) return null;
            Integer code = MonitoringMetricsModel.databaseCode(database.current().status());
            if (code != null) t.add(MonitoringMetricNames.DATABASE_STATUS, code);
            return t;
        }));
    }

    // ── R3-3 D1: dağıtık kontrol ölçümleri (tasarım §7.4) ─────────────────────────────────────────────────────────

    /** Kuyruk ölçümlerinin durumları (sunucu durum adı; etiket küçük harf). */
    static final List<String> CHECK_JOB_STATES = List.of(com.sitemonitor.service.checks.CheckJobStore.STATE_PENDING,
            com.sitemonitor.service.checks.CheckJobStore.STATE_CLAIMED, com.sitemonitor.service.checks.CheckJobStore.STATE_DONE);

    /**
     * Kontrol işi kuyruğu (KÜME): yalnız ana anahtar AÇIK ve en az bir tür seçiliyken ({@code anyDistributed()}, bellek) — tek
     * gruplu deyim ({@code CheckJobStore.stats()}); her durum × tür satırı sıfır dahil (okunmuş kuyrukta 0 gerçektir), en eski
     * bekleyen / değerlendirilmeyen yaşı (yoksa 0 = bekleyen yok). Okunamadı → aile düşer, {@code source_up{check_jobs}=0}.
     * Anahtar kapalıyken hiçbir seri ve kaynak satırı yazılmaz (yok ≠ 0; K-1: sorgu da yok). Yalnız kurum ailelerini yayan
     * turda çağrılır (POD-only lider okumaz).
     */
    void checkJobQueueMeters(Families f, Map<String, Boolean> sources) {
        if (distributedChecks == null || checkJobStore == null) return;
        boolean on;
        try {
            on = distributedChecks.anyDistributed();
        } catch (RuntimeException e) {
            on = false;
        }
        if (!on) return;
        com.sitemonitor.service.checks.CheckJobStore.QueueStats st = read(sources, "check_jobs", () -> checkJobStore.stats());
        if (st == null) return;
        List<String> types = new ArrayList<>(com.sitemonitor.service.checks.CheckJobTypes.ALL);
        for (String t : st.types()) if (!types.contains(t)) types.add(t);
        for (String state : CHECK_JOB_STATES) {
            String label = state.toLowerCase(java.util.Locale.ROOT);
            for (String type : types) f.add(MonitoringMetricNames.CHECK_JOBS, st.count(state, type), "state", label, "type", type);
        }
        Long pending = st.oldestPendingAgeMs();
        Long done = st.oldestDoneAgeMs();
        f.add(MonitoringMetricNames.CHECK_JOBS_OLDEST, pending == null ? 0 : Math.max(0L, pending) / 1000.0, "state", "pending");
        f.add(MonitoringMetricNames.CHECK_JOBS_OLDEST, done == null ? 0 : Math.max(0L, done) / 1000.0, "state", "done");
    }

    /**
     * Bu pod'un dağıtık kontrol işçisi (POD, bellek): yuvalar (meşgul / toplam), birikimli iş sonuçları (tamamlanan, geç; liderken
     * biçilen = kayıp, değerlendirilmeden düşen = düşen), sahiplenme gecikmesi p95. Yalnız ana anahtar AÇIKKEN; okunamayan parça
     * yazılmaz (sahte 0 yok). POD-only turda da yayılır.
     */
    void checkJobPodMeters(Families f) {
        if (distributedChecks == null) return;
        boolean on;
        try {
            on = distributedChecks.enabled();
        } catch (RuntimeException e) {
            on = false;
        }
        if (!on) return;
        if (checkWorker != null) {
            try {
                com.sitemonitor.service.checks.CheckWorker.Status ws = checkWorker.status();
                if (ws != null) {
                    f.add(MonitoringMetricNames.CHECK_WORKER_SLOTS, Math.max(0, ws.busy()), "kind", "busy");
                    f.add(MonitoringMetricNames.CHECK_WORKER_SLOTS, ws.workerEnabled() ? ws.slots() : 0, "kind", "total");
                    f.add(MonitoringMetricNames.CHECK_WORKER_JOBS, ws.completed(), "outcome", "completed");
                    f.add(MonitoringMetricNames.CHECK_WORKER_JOBS, ws.late(), "outcome", "late");
                    if (ws.claimLagP95Ms() != null) {
                        f.add(MonitoringMetricNames.CHECK_CLAIM_LAG, Math.max(0L, ws.claimLagP95Ms()) / 1000.0);
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Dağıtık kontrol işçisi durumu okunamadı: {}", e.toString());
            }
        }
        // Liderin sayaçları: biçilen (kayıp) ve değerlendirilmeden düşen sonuçlar — bir parçası okunamazsa o sonuç yazılmaz
        Map<String, Object> d = null;
        if (scheduler != null) {
            try {
                d = scheduler.distributedCheckStatus();
            } catch (RuntimeException e) {
                d = null;
            }
        }
        if (d == null) return;
        if (d.get("lost") instanceof Number lost) f.add(MonitoringMetricNames.CHECK_WORKER_JOBS, lost.longValue(), "outcome", "lost");
        long dropped = 0;
        for (String k : List.of("dropped_inactive", "dropped_target", "dropped_toggle", "dropped_inventory")) {
            if (!(d.get(k) instanceof Number n)) return;
            dropped += n.longValue();
        }
        if (checkResultDrainer != null) {
            try {
                com.sitemonitor.service.checks.CheckResultDrainer.Status ds = checkResultDrainer.status();
                if (ds == null) return;
                dropped += ds.droppedStale() + ds.outOfOrder() + ds.droppedObsolete() + ds.droppedSuperseded()
                        + ds.droppedHandover();
            } catch (RuntimeException e) {
                return;
            }
        }
        f.add(MonitoringMetricNames.CHECK_WORKER_JOBS, dropped, "outcome", "dropped");
    }
    // ── /R3-3 D1 ──────────────────────────────────────────────────────────────────────────────────────────────────

    // ── R3-4 C1: parçalı sertifika taraması ölçümleri (tasarım §7.4) ──────────────────────────────────────────────

    /** Durum okumasının istediği en çok koşu (tür başına son turun kipine karar vermek için; depo sınırı 20). */
    static final int CERT_SWEEP_STATUS_RUNS = 20;
    /** Parça durumları (sunucu adı; etiket küçük harf). */
    static final List<String> CERT_CHUNK_STATES = List.of(com.sitemonitor.service.certsweep.CertSweepStore.CHUNK_PENDING,
            com.sitemonitor.service.certsweep.CertSweepStore.CHUNK_CLAIMED, com.sitemonitor.service.certsweep.CertSweepStore.CHUNK_DONE,
            com.sitemonitor.service.certsweep.CertSweepStore.CHUNK_CANCELLED);

    /** Parçalı tarama anahtarı açık mı — bellek; bean yok / hata → kapalı. */
    private boolean certChunksOn() {
        if (certChunking == null) return false;
        try {
            return certChunking.enabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Parçalı tarama KÜME aileleri — yalnız anahtar AÇIKKEN (kapalı: hiçbir seri, kaynak satırı ve SQL yok — L-1). Tek
     * {@code CertSweepStore.status} okuması ({@code cert_sweep} kaynağı): açık koşu varsa durum başına parça sayısı (sıfırlar
     * dahil; açık koşu yoksa seri YOK — yok ≠ 0); {@code scan_runs} okuması ({@code scan_runs} kaynağı) ile tür başına son turun
     * süresi, kipiyle ({@link com.sitemonitor.service.certsweep.CertSweepView#modeOf}). Kaynaklardan biri okunamadıysa ilgili
     * aile düşer. Yalnız kurum ailelerini yayan turda çağrılır.
     */
    void certSweepClusterMeters(Families f, Map<String, Boolean> sources) {
        if (certSweepStore == null || !certChunksOn()) return;
        com.sitemonitor.service.certsweep.CertSweepStatus st = read(sources, "cert_sweep", () -> certSweepStore.status(CERT_SWEEP_STATUS_RUNS));
        if (st != null && st.openRun() != null) {
            for (String state : CERT_CHUNK_STATES) {
                f.add(MonitoringMetricNames.CERT_SWEEP_CHUNKS, st.chunks(state), "state", state.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (st == null || clusterHealth == null) return;
        Map<String, com.sitemonitor.service.health.ClusterHealthStore.ScanRun> runs = read(sources, "scan_runs", () -> clusterHealth.readScanRuns());
        if (runs == null) return;
        for (String kind : List.of(com.sitemonitor.service.health.ClusterHealthStore.KIND_HOURLY,
                com.sitemonitor.service.health.ClusterHealthStore.KIND_STALE, com.sitemonitor.service.health.ClusterHealthStore.KIND_FULL)) {
            com.sitemonitor.service.health.ClusterHealthStore.ScanRun r = runs.get(kind);
            if (r == null || r.durationMs() == null || r.lastRunAt() == null) continue;
            String mode = com.sitemonitor.service.certsweep.CertSweepView.modeOf(r.runId(), r.lastRunAt(), r.durationMs(), st, CERT_SWEEP_STATUS_RUNS);
            f.add(MonitoringMetricNames.CERT_SWEEP_LAST_RUN, Math.max(0L, r.durationMs()) / 1000.0, "kind", kind, "mode", mode);
        }
    }

    /**
     * Bu pod'un parçalı tarama sayaçları (POD, bellek, birikimli): işçi tamamlanan / geç / bırakılan parça, koordinatör devraldığı
     * parça / ikinci görüş alan adı. Yalnız anahtar AÇIKKEN; kayıt yok (işçi / koordinatör henüz kurulmadı) → o çıkışlar yazılmaz
     * (sahte 0 yok). POD-only turda da yayılır.
     */
    void certSweepPodMeters(Families f) {
        if (!certChunksOn()) return;
        com.sitemonitor.service.certsweep.CertSweepStatus.Worker w = certChunking.workerStatus();
        if (w != null) {
            f.add(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, w.completed(), "outcome", "completed");
            f.add(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, w.late(), "outcome", "late");
            f.add(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, w.released(), "outcome", "released");
        }
        com.sitemonitor.service.certsweep.CertSweepStatus.Coordinator c = certChunking.coordinatorStatus();
        if (c != null) {
            f.add(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, c.takeovers(), "outcome", "takeover");
            f.add(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, c.secondOpinions(), "outcome", "second_opinion");
        }
    }
    // ── /R3-4 C1 ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Bu pod'un kalıcı değerlendirme durumu kaydedicisi (çok pod R3-1, plan 3a): kip, bekleyen, yazma hatası (her pod);
     * son doğrulama turu yalnız bu pod YAZAN liderken ({@code active}) VE kip doğrulamayken ({@code verify}) — eski liderin ya da
     * doğrulaması kapatılmış ({@code write}) kaydedicinin bayat raporu yayılmaz. Bilinmeyen tur
     * ({@code unknown}) uyuşmazlık serisi yazmaz (sahte 0 yok); kaydedici yok / durum yok → hiçbir seri.
     */
    void evalStateMeters(Families f) {
        EvalStateSink sink;
        EvalStateSink.Status s;
        try {
            sink = evalState == null ? null : evalState.getIfAvailable();
            s = sink == null ? null : sink.status();
        } catch (RuntimeException e) {
            log.debug("Değerlendirme durumu okunamadı: {}", e.toString());
            return;
        }
        if (s == null) return;
        Integer mode = evalModeCode(s.mode());
        if (mode != null) f.add(MonitoringMetricNames.EVAL_STATE_MODE, mode);
        f.add(MonitoringMetricNames.EVAL_STATE_PENDING, s.pending());
        f.add(MonitoringMetricNames.EVAL_STATE_WRITE_FAILURES, s.writeFailures());
        EvalVerifyReport v = s.lastVerify();
        // Doğrulama ölçümleri yalnız doğrulama KİPİNDE (R3-1 inceleme L1): verify → write geçişinde kaydedici son raporu
        // bellekte tutar; onu yaymak eski turu "güncel" gösterirdi (pano metni: doğrulama kapalıyken veri yok)
        if (!s.active() || v == null || !Integer.valueOf(2).equals(mode)) return;
        f.add(MonitoringMetricNames.EVAL_STATE_VERIFY_AT, v.atMs() / 1000.0);
        if (v.unknown()) return;
        for (String kind : EvalVerifyReport.KINDS) {
            EvalVerifyReport.KindResult r = v.kinds().get(kind);
            if (r != null) f.add(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES, Math.max(0, r.mismatches()), "kind", kind);
        }
    }

    /** Kip kodu: off 0, write 1, verify 2; tanınmayan → null (seri yok — {@code EvalStateMode.parse} varsayılana düşerdi). */
    static Integer evalModeCode(String mode) {
        if (EvalStateMode.OFF.key().equals(mode)) return 0;
        if (EvalStateMode.WRITE.key().equals(mode)) return 1;
        if (EvalStateMode.VERIFY.key().equals(mode)) return 2;
        return null;
    }

    /**
     * Pod'a özgü çalışma sayaçları: kaybedilen kilit, liderlik geçişleri (neden), push kira tazeleme hataları (neden) — hepsi
     * bu pod açıldığından beri birikimli; pano {@code increase()} ile pod başına okur. Saat farkı yalnız TAZE ölçüm varsa
     * (örnekleyici kapalı / ölçülemedi → seri yok, "0 sn" uydurulmaz).
     */
    private void podRuntime(Families f) {
        if (lockService != null) f.add(MonitoringMetricNames.CLUSTER_LOCK_LOST, lockService.lostCount());
        if (leadership != null) {
            for (Map.Entry<String, Long> e : leadership.snapshot().entrySet()) {
                f.add(MonitoringMetricNames.SWEEP_LEADER_TRANSITIONS, e.getValue(), "reason", e.getKey());
            }
        }
        if (push != null) {
            for (Map.Entry<String, Long> e : push.leaseRenewFailures().entrySet()) {
                f.add(MonitoringMetricNames.PUSH_LEASE_RENEW_FAILED, e.getValue(), "reason", e.getKey());
            }
        }
        if (sampler != null) {
            Long skew = sampler.lastClockSkewMs();
            if (skew != null) f.add(MonitoringMetricNames.CLOCK_SKEW, skew / 1000.0);
        }
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
