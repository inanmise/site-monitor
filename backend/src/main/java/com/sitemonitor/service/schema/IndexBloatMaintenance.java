package com.sitemonitor.service.schema;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.SideJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * ŞİŞMİŞ indekslerin gece yeniden kurulması (2026-11-06, indeks bakımı). Ölçüm (yerel 2,7 GB PostgreSQL): {@code page_checks}
 * yığını 10 MB / 46 bin satırken indeksleri 183 MB, {@code page_resource_issues} 15 MB / 61 bin satırken 338 MB — saklama
 * silmelerinden sonra btree'de kalan yarı boş sayfalar ({@code VACUUM} sayfayı yeniden kullanıma açar ama indeksi küçültmez;
 * zaman sıralı anahtarlarda eski aralığın sayfaları bir daha dolmaz). Tekrar edebilir, bu yüzden periyodik.
 *
 * <p><b>Ne zaman / kim:</b> günde bir, gece temizliğinden (03:00 İstanbul) sonra — {@value #CRON_KEY} (varsayılan
 * {@value #DEFAULT_CRON} İstanbul; {@code -} ya da boş = kapalı; GEÇERSİZ ifade açılışı düşürmez — bir WARN, iş kapalı);
 * pod'un yan iş koşucusunda (uzun iş), {@code scheduler_lock} {@value #JOB} + tetik başına küme turu
 * ({@code round:}{@value #JOB}) ile küme genelinde BİR KEZ; içeride ertelenmiş indeks kurucusu ve gereksiz indeks temizliğiyle
 * AYNI advisory lock ({@link DeferredIndexBuilder#LOCK_KEY}) — üçü asla aynı anda DDL yapmaz; iki yeniden kurma arasında kilit
 * {@link #LOCK_YIELD_MS} bırakılır (gece açılan bir pod'un ertelenmiş kurucusu sırasını alabilsin). Yalnız PostgreSQL. Açılış anahtarı
 * {@code site.monitor.index-maintenance.enabled} (true).
 *
 * <p><b>Ne:</b> yalnız {@link #ALLOWED_TABLES} — saklama motorunun yaşa göre sildiği büyük, zaman sıralı kontrol / geçmiş
 * tabloları (RetentionCatalog'da yaş kuralı olan; gerekçe listede). Bu tablolarda yalnız sade btree indeksler: geçerli,
 * benzersiz değil, kısıt desteklemiyor, kısmi / ifade değil (benzersiz / birincil anahtar dizinleri bilerek dışarıda —
 * {@code ON CONFLICT} hedefleri; eşzamanlı yeniden kurma yarışlarından uzak durulur). Beklenen boyut {@link IndexBloatEstimator};
 * şişmiş = gerçek ≥ max(2 × beklenen, beklenen + {@code min-excess-mb}) (varsayılan 64 MB — küçük dizinlere dokunulmaz). En
 * büyük fazladan başlayarak koşu başına en çok {@code max-indexes-per-run} indeks, toplam {@code max-mb-per-run} ve
 * {@code time-budget-minutes} süre.
 *
 * <p><b>Nasıl:</b> {@code REINDEX INDEX CONCURRENTLY <şema>.<ad>} havuz dışı oturumda, {@code lock_timeout}
 * ({@code site.monitor.index-maintenance.lock-timeout-ms}, 30 sn) ve kalan süre bütçesi kadar {@code statement_timeout} ile.
 * {@code CONCURRENTLY} eski işlemleri / anlık görüntüleri {@code VirtualXactLock} ile bekler; bu bekleme {@code lock_timeout}'a
 * tabidir (geçici kümede doğrulandı) — sınırsız bekleme yok. Deyim sürerken bir BEKÇİ ({@link DdlBlockWatch}, ayrı bağlantı,
 * 2 sn'de bir) bizim oturumumuzu bekleyen başka oturum arar: ≈ 6 sn bekleyen varsa (ör. yeni pod'un ddl-auto
 * {@code ALTER TABLE}'ı — o beklerken tablonun bütün sorguları onun arkasında birikir) deyim iptal edilir, WARN
 * "başka bir oturumu bekletiyordu, bırakıldı", kalıntı temizlenir ve bu gecenin koşusu biter. Kesilen / başarısız bir yeniden
 * kurma GEÇERSİZ bir {@code <ad>_ccnew} (yeni dizin bitmedi) ya da {@code <ad>_ccold} (eski dizin düşürülemedi; yeni dizin
 * zaten yerinde) bırakır: hemen, yoksa her koşunun başında ve pod açılışında ({@link #runLeftoverSweep}: trafiğe hazır
 * olduktan {@code leftover-sweep.delay-ms} (3 dk) sonra, açılış başına bir kez) — yalnız izinli tablolarda, kurulum
 * sürmüyorsa — {@code DROP INDEX CONCURRENTLY IF EXISTS} ile temizlenir. Bütçemiz içinde bitemeyen ({@code statement_timeout},
 * bütçenin en az dörtte üçüyle başlamış) bir indeks 7 gün küme genelinde atlanır ({@code schema_patch_markers}
 * {@value #SKIP_PREFIX}{@code <ad>}; tek WARN), diğerleri sırasını alır. Önce / sonra boyut INFO. Pod kapanırken yeni indekse
 * geçilmez, süren deyim iptal edilir; kalıntı sonraki koşuda temizlenir. Başka bir pod açılış yamalarını koşuyorsa indekse
 * başlanmaz.
 */
@Slf4j
@Service
public class IndexBloatMaintenance implements SchedulingConfigurer {

    /** Yan iş, kilit ve tur adı — kalıcı. */
    static final String JOB = "index-bloat-maintenance";
    static final String CRON_KEY = "site.monitor.index-maintenance.bloat-cron";
    static final String DEFAULT_CRON = "0 40 4 * * *";
    static final String ZONE = "Europe/Istanbul";
    /** Açılış kalıntı taramasının yan iş adı — kalıcı. */
    static final String SWEEP_JOB = "index-leftover-sweep";
    /** Açılış kalıntı taramasının yoklama aralığı ve deneme tavanı (SMIDXBLD dolu / yama sürüyor → sonraki yoklama). */
    static final long SWEEP_TICK_MS = 60_000L;
    static final int SWEEP_MAX_ATTEMPTS = 30;
    /** Bütçe aşımıyla atlanan indeksin nişan öneki ({@code schema_patch_markers}) — kalıcı. */
    static final String SKIP_PREFIX = "index-bloat-skip:";
    static final long SKIP_MS = 7L * 24 * 60 * 60_000L;
    /** Şişme oranı (gerçek / beklenen). */
    static final double FACTOR = 2.0;
    /** Yeni bir yeniden kurmaya en az bu kadar süre kalmadıysa başlanmaz. */
    static final long MIN_INDEX_BUDGET_MS = 60_000L;
    /** {@code SMIDXBLD} doluysa en çok bu kadar beklenir (ertelenmiş kurucu ilk dağıtımda uzun sürebilir), sonra bugün atlanır. */
    static final long LOCK_WAIT_MS = 5 * 60_000L;
    static final long LOCK_POLL_MS = 15_000L;
    /**
     * İki yeniden kurma arasında {@code SMIDXBLD} bu kadar BIRAKILIR, sonra yeniden denenir: açılan bir pod'un ertelenmiş
     * indeks kurucusu kilidi 5 sn'de bir yoklar ({@link DeferredIndexBuilder#POLL_MS}) ve en çok 30 dk bekler — gece bakımı
     * bütün bütçesi boyunca tutsaydı o açılışın yeni indeksleri bir sonraki açılışa kalırdı. Kilit başkasına geçtiyse bu
     * gece biter, kalanlar sonraki gece.
     */
    static final long LOCK_YIELD_MS = DeferredIndexBuilder.POLL_MS + 1_000L;
    /** Bir kalıntı düşürmesinin toplam süre tavanı. */
    static final long DROP_STATEMENT_TIMEOUT_MS = 5 * 60_000L;

    /**
     * İzinli tablolar — saklama motorunun YAŞA göre sildiği (RetentionCatalog yaş kuralı), sürekli eklenen ve zaman sıralı
     * anahtar taşıyan tablolar; şişme burada birikir. Kontrol serileri (11 izleme türü + sertifika / erişilebilirlik),
     * {@code activity_log} (365 gün, en büyük tablo), saatlik özet, HTTP / sistem metrik örnekleri ve kalp atışı. Dışarıda:
     * denetim günlüğü (hash zinciri, güvenlik kaydı), bildirim / push günlükleri (küçük, benzersiz dizinli), varlık tabloları.
     * Kapı: {@code IndexBloatMaintenanceTest.allowedTablesHaveAgeRetention}.
     */
    public static final List<String> ALLOWED_TABLES = List.of(
            "page_checks", "page_resource_issues", "port_checks", "keyword_results", "ping_checks", "http_checks",
            "dns_records", "uptime_checks", "certificate_checks", "scripted_checks", "domain_checks", "pagespeed_checks",
            "pagespeed_resources", "dbquery_checks", "activity_log", "monitor_check_hourly", "http_metric_minute",
            "system_metric_samples", "system_heartbeat");

    /**
     * Bir koşunun özeti (test + günlük). {@code skippedForTime}: şişmiş olduğu hâlde 7 günlük bütçe atlamasındaki indeksler.
     * {@code stoppedEarly}: kapanış / kilit başkasına geçti / yama sürüyor / bir oturumu beklettiği için bırakıldı.
     */
    record Report(boolean locked, int leftoversDropped, int leftoversFailed, List<String> rebuilt, List<String> failed,
                  int deferred, int unknownEstimates, long bytesBefore, long bytesAfter, boolean stoppedEarly, long millis,
                  int skippedForTime) {

        static Report notLocked(long millis) {
            return new Report(false, 0, 0, List.of(), List.of(), 0, 0, 0, 0, false, millis, 0);
        }
    }

    @FunctionalInterface
    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final List<String> tables;
    private final Supplier<Boolean> postgres;
    private final IndexCatalog.Factory catalogs;
    private final ObjectProvider<SchedulerService> scheduler;
    private final boolean enabled;
    private final long lockTimeoutMs;
    private final int maxIndexes;
    private final long maxBytes;
    private final long budgetMs;
    private final long minExcessBytes;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final BooleanSupplier schedulerStopping;
    /** Geçerli cron ifadesi; null = zamanlanmış koşu kapalı ({@code -}, boş ya da geçersiz). */
    private final String cron;
    private final long sweepDelayMs;

    private volatile boolean closing;
    private volatile IndexCatalog current;
    private volatile IndexCatalog currentSweep;
    /** Trafiğe hazır anı (pod saati); -1 = henüz değil. */
    private volatile long readyAtMs = -1;
    private volatile boolean sweepDone;
    private final AtomicInteger sweepAttempts = new AtomicInteger();

    @Autowired
    public IndexBloatMaintenance(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                 @Value("${site.monitor.index-maintenance.enabled:true}") boolean enabled,
                                 @Value("${site.monitor.index-maintenance.lock-timeout-ms:30000}") long lockTimeoutMs,
                                 @Value("${site.monitor.index-maintenance.max-indexes-per-run:6}") int maxIndexes,
                                 @Value("${site.monitor.index-maintenance.max-mb-per-run:4096}") long maxMb,
                                 @Value("${site.monitor.index-maintenance.time-budget-minutes:45}") long budgetMinutes,
                                 @Value("${site.monitor.index-maintenance.min-excess-mb:64}") long minExcessMb,
                                 @Value("${" + CRON_KEY + ":" + DEFAULT_CRON + "}") String cron,
                                 @Value("${site.monitor.index-maintenance.leftover-sweep.delay-ms:180000}") long sweepDelayMs) {
        this(ALLOWED_TABLES, DeferredIndexBuilder.memoKnown(() -> DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource())),
                () -> JdbcIndexCatalog.open(jdbc.getDataSource()), scheduler, enabled, lockTimeoutMs, maxIndexes,
                maxMb * 1024 * 1024, budgetMinutes * 60_000L, minExcessMb * 1024 * 1024, System::currentTimeMillis,
                Thread::sleep, () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sideJobsStopping();
                }, cron, sweepDelayMs);
    }

    /** Test: varsayılan cron ve açılış taraması gecikmesi. */
    IndexBloatMaintenance(List<String> tables, Supplier<Boolean> postgres, IndexCatalog.Factory catalogs,
                          ObjectProvider<SchedulerService> scheduler, boolean enabled, long lockTimeoutMs, int maxIndexes,
                          long maxBytes, long budgetMs, long minExcessBytes, LongSupplier clock, Sleeper sleeper,
                          BooleanSupplier schedulerStopping) {
        this(tables, postgres, catalogs, scheduler, enabled, lockTimeoutMs, maxIndexes, maxBytes, budgetMs, minExcessBytes, clock,
                sleeper, schedulerStopping, DEFAULT_CRON, 180_000L);
    }

    IndexBloatMaintenance(List<String> tables, Supplier<Boolean> postgres, IndexCatalog.Factory catalogs,
                          ObjectProvider<SchedulerService> scheduler, boolean enabled, long lockTimeoutMs, int maxIndexes,
                          long maxBytes, long budgetMs, long minExcessBytes, LongSupplier clock, Sleeper sleeper,
                          BooleanSupplier schedulerStopping, String cron, long sweepDelayMs) {
        this.tables = List.copyOf(tables);
        this.postgres = postgres;
        this.catalogs = catalogs;
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.lockTimeoutMs = Math.max(1_000L, lockTimeoutMs);
        this.maxIndexes = Math.max(0, Math.min(maxIndexes, 50));
        this.maxBytes = Math.max(0L, maxBytes);
        this.budgetMs = Math.max(MIN_INDEX_BUDGET_MS, Math.min(budgetMs, 4 * 60 * 60_000L));
        this.minExcessBytes = Math.max(0L, minExcessBytes);
        this.clock = clock;
        this.sleeper = sleeper;
        this.schedulerStopping = schedulerStopping;
        this.cron = enabled ? validCron(cron) : null;
        this.sweepDelayMs = Math.max(0L, sweepDelayMs);
    }

    /**
     * Cron ayarını doğrular (inceleme DÜŞÜK-6). {@code @Scheduled(cron)} geçersiz ifadede açılışı DÜŞÜRÜRDÜ — bir bakım işi
     * yüzünden pod açılmamamalı. Karar: geçersiz → WARN + iş KAPALI (varsayılana sessizce dönülmez: yanlış yazılmış bir
     * saat ayarı yoğun saatte DDL koşturabilirdi; WARN her açılışta görünür). {@code -} / boş → bilinçli kapalı (INFO).
     */
    static String validCron(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || "-".equals(v)) {
            log.info("Şişmiş indeks bakımı kapalı ({} = '{}')", CRON_KEY, v);
            return null;
        }
        try {
            CronExpression.parse(v);
            return v;
        } catch (IllegalArgumentException e) {
            log.warn("Şişmiş indeks bakımı KAPALI: {} geçersiz ('{}') — {}. Altı alanlı Spring cron yazın (ör. '{}') ya da"
                    + " kapatmak için '-'", CRON_KEY, v, DeferredIndexBuilder.firstLine(e), DEFAULT_CRON);
            return null;
        }
    }

    /** Test: zamanlanmış koşunun cron ifadesi (null = kapalı). */
    String cron() {
        return cron;
    }

    /** Gece tetiği yalnız geçerli bir cron ifadesiyle kaydedilir (geçersiz ifade açılışı düşürmez). */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled || cron == null) return;
        registrar.addCronTask(new CronTask(this::scheduled, new CronTrigger(cron, ZoneId.of(ZONE))));
    }

    /**
     * Gece tetiği. Gövde yan iş koşucusunda; tarama lideri başka canlı pod varken tetiği erteler, ertelenen denemede bu tetiğin
     * turu başka pod'da alındıysa gövde koşmaz.
     */
    public void scheduled() {
        if (!enabled || closing || cron == null) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;   // dar bağlam (test): küme kilidi / turu olmadan koşmaz
        Runnable body = () -> runScheduled(s);
        if (!s.offloadSideJob(SideJob.cron(JOB, true, JOB, cron, ZONE, null, body))) body.run();
    }

    /** Kapanış: yeni indekse geçilmez; süren deyimler cin yardımcıda iptal edilir (kapanış iş parçacığında veritabanı yok). */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 4)
    public void onContextClosed(ContextClosedEvent event) {
        closing = true;
        IndexCatalog c = current;
        IndexCatalog w = currentSweep;
        if (c == null && w == null) return;
        Thread t = new Thread(() -> {
            if (c != null) c.cancel();
            if (w != null) w.cancel();
        }, "index-bloat-cancel");
        t.setDaemon(true);
        t.start();
    }

    /** Trafiğe hazır anı (açılış kalıntı taraması bundan {@code leftover-sweep.delay-ms} sonra). */
    @EventListener
    public void onReadiness(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC && readyAtMs < 0) readyAtMs = clock.getAsLong();
    }

    /** Test: hazır anını elle verir. */
    void markReady(long atMs) {
        readyAtMs = atMs;
    }

    boolean sweepDone() {
        return sweepDone;
    }

    int sweepAttempts() {
        return sweepAttempts.get();
    }

    /**
     * Açılış kalıntı taraması yoklaması (inceleme DÜŞÜK-4): öldürülen bir pod'un yarıda kalan yeniden kurmasının GEÇERSİZ
     * {@code _ccnew} dizini yazmalarda bakım maliyeti taşır — gece koşusunu beklemeden, trafiğe hazır olduktan
     * {@code leftover-sweep.delay-ms} sonra bir kez temizlenir. Yan iş koşucusunda (uzun iş olabilir).
     */
    @Scheduled(initialDelay = SWEEP_TICK_MS, fixedDelay = SWEEP_TICK_MS)
    public void sweepTick() {
        if (!enabled || closing || sweepDone) return;
        long ready = readyAtMs;
        if (ready < 0 || clock.getAsLong() < ready + sweepDelayMs) return;
        Runnable body = this::runLeftoverSweep;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null || !s.offloadSideJob(SideJob.heavy(SWEEP_JOB, body))) body.run();
    }

    /**
     * Açılış kalıntı taraması: SMIDXBLD altında (beklemeden — doluysa sonraki yoklama), başka pod yama koşmuyorsa, yalnız
     * izinli tablolarda ve kurulum sürmeyen tablolarda {@code _ccnew} / {@code _ccold} kalıntılarını düşürür. Açılış başına
     * bir kez; en çok {@link #SWEEP_MAX_ATTEMPTS} deneme, sonra bırakılır (gece koşusu da temizler).
     */
    void runLeftoverSweep() {
        if (sweepDone || stopping()) return;
        Boolean pg = postgres.get();
        if (pg == null) {
            sweepAttemptFailed("veritabanı türü okunamadı");
            return;
        }
        if (!pg) {
            sweepDone = true;
            return;
        }
        IndexCatalog db;
        try {
            db = catalogs.open();
        } catch (SQLException | RuntimeException e) {
            sweepAttemptFailed("oturum açılamadı: " + DeferredIndexBuilder.firstLine(e));
            return;
        }
        currentSweep = db;
        try {
            if (!db.tryLock(DeferredIndexBuilder.LOCK_KEY)) {
                sweepAttemptFailed("indeks kilidi (SMIDXBLD) başka bir oturumda");
                return;
            }
            try {
                if (!RedundantIndexCleanup.patchLockFree(db)) {
                    sweepAttemptFailed("başka bir pod şema yamalarını koşuyor");
                    return;
                }
                int[] r = dropLeftovers(db, tables);
                sweepDone = true;
                if (r[0] + r[1] > 0) {
                    log.info("Açılış kalıntı taraması: {} yarıda kalmış yeniden kurma kalıntısı düşürüldü{}", r[0],
                            r[1] > 0 ? " (" + r[1] + " düşürülemedi — gece bakımı yeniden dener)" : "");
                } else {
                    log.debug("Açılış kalıntı taraması: kalıntı yok");
                }
            } finally {
                db.unlock(DeferredIndexBuilder.LOCK_KEY);
            }
        } catch (SQLException | RuntimeException e) {
            sweepAttemptFailed("hata: " + DeferredIndexBuilder.firstLine(e));
        } finally {
            currentSweep = null;
            db.close();
        }
    }

    private void sweepAttemptFailed(String why) {
        int n = sweepAttempts.incrementAndGet();
        if (n >= SWEEP_MAX_ATTEMPTS) {
            sweepDone = true;
            log.info("Açılış kalıntı taraması {} denemede yapılamadı ({}) — bu açılışta bırakıldı; gece bakımı kalıntıları temizler",
                    n, why);
        } else {
            log.debug("Açılış kalıntı taraması ertelendi ({}) — deneme {}/{}", why, n, SWEEP_MAX_ATTEMPTS);
        }
    }

    /** Kilit + tur altında bir koşu. İş kendi turunu (öğe korumalı: yinelenen koşu yalnız yeniden ölçer) tetik başına bir kez alır. */
    void runScheduled(SchedulerService s) {
        if (stopping()) return;
        Boolean pg = postgres.get();
        if (!Boolean.TRUE.equals(pg)) {
            if (pg == null) log.debug("İndeks bakımı atlandı: veritabanı türü okunamadı");
            return;
        }
        try {
            s.callWithSchedulerLock(JOB, () -> {
                if (!s.claimItemProtectedCronRound(JOB, cron, ZONE)) {
                    log.debug("İndeks bakımı: bu tetiğin turu başka bir pod'da koştu");
                    return Boolean.FALSE;
                }
                maintain();
                return Boolean.TRUE;
            });
        } catch (RuntimeException e) {
            log.warn("İndeks bakımı başarısız (sonraki gece yeniden denenecek): {}", DeferredIndexBuilder.firstLine(e));
        }
    }

    /** Bir bakım koşusu (havuz dışı oturum, SMIDXBLD altında). Hata yukarı çıkmaz; özet döner. */
    Report maintain() {
        long t0 = clock.getAsLong();
        long deadline = t0 + budgetMs;
        IndexCatalog db;
        try {
            db = catalogs.open();
        } catch (SQLException | RuntimeException e) {
            log.warn("İndeks bakımı: oturum açılamadı (sonraki gece yeniden denenecek): {}", DeferredIndexBuilder.firstLine(e));
            return Report.notLocked(clock.getAsLong() - t0);
        }
        current = db;
        try {
            if (!acquire(db)) {
                log.info("İndeks bakımı bu gece atlandı: indeks kilidi (SMIDXBLD) {} dk içinde alınamadı — başka bir pod indeks"
                        + " kuruyor ya da temizliyor", LOCK_WAIT_MS / 60_000);
                return Report.notLocked(clock.getAsLong() - t0);
            }
            boolean[] held = { true };
            try {
                return maintainLocked(db, t0, deadline, held);
            } finally {
                if (held[0]) db.unlock(DeferredIndexBuilder.LOCK_KEY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Report.notLocked(clock.getAsLong() - t0);
        } catch (SQLException | RuntimeException e) {
            log.warn("İndeks bakımı yarıda kaldı (sonraki gece yeniden denenecek): {}", DeferredIndexBuilder.firstLine(e));
            return new Report(true, 0, 0, List.of(), List.of(), 0, 0, 0, 0, true, clock.getAsLong() - t0, 0);
        } finally {
            current = null;
            db.close();
        }
    }

    private Report maintainLocked(IndexCatalog db, long t0, long deadline, boolean[] held) throws SQLException {
        int[] left = dropLeftovers(db, tables);
        boolean stopped = left[2] > 0;   // bir kalıntı düşürmesi başka oturumu beklettiği için bırakıldı → bu gece bu kadar
        Set<String> skip = activeSkips(db);
        List<IndexCatalog.IndexStats> inputs = new ArrayList<>();
        int skippedForTime = 0;
        for (IndexCatalog.IndexStats s : db.bloatInputs(tables)) {
            if (!skip.contains(s.name())) {
                inputs.add(s);
                continue;
            }
            long exp = IndexBloatEstimator.expectedBytes(s);
            if (exp >= 0 && IndexBloatEstimator.bloated(s.bytes(), exp, FACTOR, minExcessBytes)) skippedForTime++;
        }
        IndexBloatEstimator.Selection sel = IndexBloatEstimator.select(inputs, FACTOR, minExcessBytes, maxIndexes, maxBytes);
        List<String> rebuilt = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long before = 0;
        long after = 0;
        // kalıntı aşamasında bırakıldıysa hiçbir yeniden kurmaya girişilmez; seçilenler sonraki geceye kalır
        int skippedForBudget = stopped ? sel.picked().size() : 0;
        boolean first = true;
        for (IndexBloatEstimator.Candidate c : stopped ? List.<IndexBloatEstimator.Candidate>of() : sel.picked()) {
            IndexCatalog.IndexStats s = c.stats();
            if (stopping()) { stopped = true; break; }
            if (!first && !yieldLock(db, held)) {
                log.info("İndeks bakımı durdu: indeks kilidi (SMIDXBLD) başka bir oturuma geçti (ör. açılan pod'un ertelenmiş"
                        + " indeks kurulumu) — kalanlar sonraki gece");
                stopped = true;
                break;
            }
            first = false;
            long remaining = deadline - clock.getAsLong();
            if (stopping()) { stopped = true; break; }
            if (remaining < MIN_INDEX_BUDGET_MS) { skippedForBudget = sel.picked().size() - rebuilt.size() - failed.size(); break; }
            if (!RedundantIndexCleanup.patchLockFree(db)) {
                log.info("İndeks bakımı durdu: başka bir pod şema yamalarını koşuyor — kalanlar sonraki gece");
                stopped = true;
                break;
            }
            long start = clock.getAsLong();
            try {
                db.executeBounded("REINDEX INDEX CONCURRENTLY " + s.qualifiedName(), lockTimeoutMs, remaining);
                long now = db.indexBytes(s.qualifiedName());
                rebuilt.add(s.name());
                before += s.bytes();
                after += Math.max(0, now);
                log.info("Şişmiş indeks yeniden kuruldu: {} ON {} — {} → {} (beklenen ≈ {}), {} ms", s.name(), s.table(),
                        IndexBloatEstimator.human(s.bytes()), IndexBloatEstimator.human(now),
                        IndexBloatEstimator.human(c.expectedBytes()), clock.getAsLong() - start);
            } catch (SQLException e) {
                failed.add(s.name());
                long elapsed = clock.getAsLong() - start;
                if (e instanceof BlockingOthersException) {
                    log.warn("Şişmiş indeks yeniden kurma başka bir oturumu bekletiyordu, bırakıldı: {} ON {} ({}) — bu gecenin"
                            + " koşusu burada biter, kalanlar sonraki gece", s.name(), s.table(), ((BlockingOthersException) e).reason());
                    stopped = true;
                } else if (!stopping() && budgetTimeout(e, elapsed, remaining)) {
                    markBudgetSkip(db, s, elapsed);
                } else {
                    log.warn("Şişmiş indeks yeniden kurulamadı (sonraki gece yeniden denenecek): {} ON {} — {}{}", s.name(),
                            s.table(), timeoutKind(e), DeferredIndexBuilder.firstLine(e));
                }
                if (stopping()) { stopped = true; break; }
                int[] l = dropLeftovers(db, List.of(s.table()));   // yarıda kalan _ccnew / _ccold hemen
                left[0] += l[0];
                left[1] += l[1];
                if (stopped || l[2] > 0) { stopped = true; break; }
            }
        }
        int deferred = sel.deferred().size() + skippedForBudget;
        Report r = new Report(true, left[0], left[1], List.copyOf(rebuilt), List.copyOf(failed), deferred, sel.unknown(),
                before, after, stopped, clock.getAsLong() - t0, skippedForTime);
        if (r.leftoversDropped() + r.leftoversFailed() + rebuilt.size() + failed.size() + deferred + skippedForTime > 0) {
            log.info("İndeks bakımı: {} kalıntı temizlendi{}, {} indeks yeniden kuruldu ({} → {}), {} başarısız, {} sonraki geceye"
                            + " kaldı{}{} — {} ms", r.leftoversDropped(),
                    r.leftoversFailed() > 0 ? " (" + r.leftoversFailed() + " temizlenemedi)" : "", rebuilt.size(),
                    IndexBloatEstimator.human(before), IndexBloatEstimator.human(after), failed.size(), deferred,
                    skippedForTime > 0 ? ", " + skippedForTime + " indeks 7 günlük bütçe atlamasında" : "",
                    stopped ? ", durduruldu" : "", r.millis());
        } else {
            log.debug("İndeks bakımı: şişmiş indeks yok ({} tahmin edilemedi) — {} ms", sel.unknown(), r.millis());
        }
        return r;
    }

    /**
     * BİZİM süre bütçemiz mi doldu (inceleme DÜŞÜK-3): {@code statement_timeout} (57014, bekçi iptali değil), deyim verilen
     * süreyi doldurdu ve bu süre bütçenin en az dörtte üçüydü (gecenin sonuna kalmış kısa süreli bir deneme indeksi
     * cezalandırmasın — ertesi gece o indeks zaten başa geçer). Kapanış iptali çağıranda ayrıca elenir.
     */
    boolean budgetTimeout(SQLException e, long elapsedMs, long statementMs) {
        if (e instanceof BlockingOthersException || !"57014".equals(e.getSQLState())) return false;
        return elapsedMs >= statementMs - 1_000L && statementMs >= budgetMs * 3 / 4;
    }

    /** Bütçe içinde bitemeyen indeksi 7 gün küme genelinde atlatır (tek WARN; nişan her pod'dan okunur). */
    private void markBudgetSkip(IndexCatalog db, IndexCatalog.IndexStats s, long elapsedMs) {
        String key = SKIP_PREFIX + s.name();
        try {
            db.upsertMarker(key, (int) Math.min(Integer.MAX_VALUE, Math.max(0, s.bytes()) / (1024 * 1024)));
            log.warn("Şişmiş indeks süre bütçesi içinde yeniden kurulamadı: {} ON {} ({}, {} dk sonra kesildi) — 7 gün atlanacak"
                            + " (küme genelinde), diğer indeksler sırasını alır; sonra yeniden denenir. Bütçeyi büyütün"
                            + " (site.monitor.index-maintenance.time-budget-minutes) ya da bakım penceresinde elle REINDEX",
                    s.name(), s.table(), IndexBloatEstimator.human(s.bytes()), elapsedMs / 60_000);
        } catch (SQLException e) {
            log.warn("Şişmiş indeks süre bütçesi içinde yeniden kurulamadı: {} ON {} — atlama kaydı yazılamadı (sonraki gece"
                    + " yeniden denenecek): {}", s.name(), s.table(), DeferredIndexBuilder.firstLine(e));
        }
    }

    /** Süresi dolmamış bütçe atlamaları (indeks adları); süresi dolanların nişanı silinir. Okunamazsa boş (atlama yok). */
    private Set<String> activeSkips(IndexCatalog db) {
        Map<String, String> markers;
        try {
            markers = db.markersWithPrefix(SKIP_PREFIX);
        } catch (SQLException e) {
            log.debug("İndeks bakımı: bütçe atlama kayıtları okunamadı (atlama uygulanmıyor): {}", DeferredIndexBuilder.firstLine(e));
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        long now = clock.getAsLong();
        for (Map.Entry<String, String> m : markers.entrySet()) {
            String name = m.getKey().substring(SKIP_PREFIX.length());
            long at = parseUtc(m.getValue());
            if (at > 0 && now - at < SKIP_MS) {
                out.add(name);
                continue;
            }
            try {
                db.deleteMarker(m.getKey());
                log.info("Şişmiş indeksin 7 günlük bütçe atlaması doldu, yeniden denenecek: {}", name);
            } catch (SQLException e) {
                log.debug("Bütçe atlama kaydı silinemedi ({}): {}", m.getKey(), DeferredIndexBuilder.firstLine(e));
            }
        }
        return out;
    }

    /** {@code schema_patch_markers.applied_at} (UTC, {@code yyyy-MM-ddTHH:mm:ss}) → epoch ms; okunamazsa -1. */
    static long parseUtc(String v) {
        if (v == null || v.isBlank()) return -1;
        try {
            return LocalDateTime.parse(v.trim()).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Yarıda kalmış yeniden kurma kalıntılarını düşürür (yalnız verilen tablolarda, GEÇERSİZ {@code _ccnew} / {@code _ccold},
     * tabloda kurulum sürmüyorsa). Dönüş: {düşürülen, düşürülemeyen, bırakıldı (1 = bir düşürme başka bir oturumu
     * beklettiği için bekçi tarafından iptal edildi — kalanlara geçilmez)}. Kapanışta yeni düşürmeye başlanmaz.
     */
    private int[] dropLeftovers(IndexCatalog db, List<String> onTables) throws SQLException {
        int ok = 0;
        int bad = 0;
        int yielded = 0;
        for (IndexCatalog.Leftover l : db.leftovers(onTables)) {
            if (stopping()) break;
            if (l.tableBuilding()) {
                log.info("Yeniden kurma kalıntısına dokunulmadı (tabloda şu an bir kurulum sürüyor): {} ON {}", l.name(), l.table());
                continue;
            }
            try {
                db.executeBounded("DROP INDEX CONCURRENTLY IF EXISTS " + l.qualifiedName(), lockTimeoutMs, DROP_STATEMENT_TIMEOUT_MS);
                ok++;
                log.info("Yarıda kalmış yeniden kurma kalıntısı düşürüldü: {} ON {} ({})", l.name(), l.table(),
                        IndexBloatEstimator.human(l.bytes()));
            } catch (BlockingOthersException e) {
                bad++;
                yielded = 1;
                log.warn("Yeniden kurma kalıntısı düşürme başka bir oturumu bekletiyordu, bırakıldı: {} ON {} ({}) — sonraki koşuda"
                        + " yeniden denenecek", l.name(), l.table(), e.reason());
                break;
            } catch (SQLException e) {
                bad++;
                log.warn("Yeniden kurma kalıntısı düşürülemedi (sonraki koşuda yeniden denenecek): {} ON {} — {}{}", l.name(), l.table(),
                        timeoutKind(e), DeferredIndexBuilder.firstLine(e));
            }
        }
        return new int[] { ok, bad, yielded };
    }

    /** Kilidi kısa süre bırakıp yeniden alır (bekleyen başka bir kurucuya sıra verir). Dönüş: yeniden alındı mı. */
    private boolean yieldLock(IndexCatalog db, boolean[] held) throws SQLException {
        db.unlock(DeferredIndexBuilder.LOCK_KEY);
        held[0] = false;
        try {
            sleeper.sleep(LOCK_YIELD_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (stopping()) return false;
        held[0] = db.tryLock(DeferredIndexBuilder.LOCK_KEY);
        return held[0];
    }

    /** Kilidi sınırlı süre bekleyerek alır; kapanışta bırakır. */
    private boolean acquire(IndexCatalog db) throws SQLException, InterruptedException {
        long until = clock.getAsLong() + LOCK_WAIT_MS;
        long maxTries = LOCK_WAIT_MS / LOCK_POLL_MS + 1;   // saat ilerlemese de (test saati) döngü sınırlı
        for (long i = 0; ; i++) {
            if (db.tryLock(DeferredIndexBuilder.LOCK_KEY)) return true;
            if (stopping() || clock.getAsLong() >= until || i >= maxTries) return false;
            sleeper.sleep(LOCK_POLL_MS);
        }
    }

    private boolean stopping() {
        if (closing) return true;
        try {
            return schedulerStopping.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Günlük öneki: kilit zaman aşımı (55P03) / deyim zaman aşımı ya da iptal (57014). */
    static String timeoutKind(SQLException e) {
        String st = e.getSQLState();
        if ("55P03".equals(st)) return "kilit / eski işlem beklemesi lock_timeout'u aştı: ";
        if ("57014".equals(st)) return "süre bütçesi doldu ya da iptal edildi: ";
        return "";
    }
}
