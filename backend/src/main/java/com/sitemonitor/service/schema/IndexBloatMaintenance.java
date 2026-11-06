package com.sitemonitor.service.schema;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.SideJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * ŞİŞMİŞ indekslerin gece yeniden kurulması (2026-11-06, indeks bakımı). Ölçüm (yerel 2,7 GB PostgreSQL): {@code page_checks}
 * yığını 10 MB / 46 bin satırken indeksleri 183 MB, {@code page_resource_issues} 15 MB / 61 bin satırken 338 MB — saklama
 * silmelerinden sonra btree'de kalan yarı boş sayfalar ({@code VACUUM} sayfayı yeniden kullanıma açar ama indeksi küçültmez;
 * zaman sıralı anahtarlarda eski aralığın sayfaları bir daha dolmaz). Tekrar edebilir, bu yüzden periyodik.
 *
 * <p><b>Ne zaman / kim:</b> günde bir, gece temizliğinden (03:00 İstanbul) sonra — {@value #CRON_KEY} (varsayılan 04:40
 * İstanbul); pod'un yan iş koşucusunda (uzun iş), {@code scheduler_lock} {@value #JOB} + tetik başına küme turu
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
 * tabidir (geçici kümede doğrulandı: boşta bekleyen bir okuma işlemi yer değiştirme sonrası aşamayı, açık bir REPEATABLE READ
 * anlık görüntüsü doğrulama aşamasını ~{@code lock_timeout}'ta kesti) — sınırsız bekleme yok. Kesilen / başarısız bir yeniden
 * kurma GEÇERSİZ bir {@code <ad>_ccnew} (yeni dizin bitmedi) ya da {@code <ad>_ccold} (eski dizin düşürülemedi; yeni dizin
 * zaten yerinde) bırakır: hemen, yoksa her koşunun başında (yalnız izinli tablolarda, kurulum sürmüyorsa)
 * {@code DROP INDEX CONCURRENTLY IF EXISTS} ile temizlenir. Önce / sonra boyut INFO. Pod kapanırken yeni indekse geçilmez,
 * süren deyim iptal edilir; kalıntı sonraki koşuda temizlenir. Başka bir pod açılış yamalarını koşuyorsa indekse başlanmaz.
 */
@Slf4j
@Service
public class IndexBloatMaintenance {

    /** Yan iş, kilit ve tur adı — kalıcı. */
    static final String JOB = "index-bloat-maintenance";
    static final String CRON_KEY = "site.monitor.index-maintenance.bloat-cron";
    /** Zamanlanmış tetiğin cron ifadesi (annotation, tur kaydı ve yan iş kaydı aynı sabiti okur). */
    static final String CRON = "${" + CRON_KEY + ":0 40 4 * * *}";
    static final String ZONE = "Europe/Istanbul";
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

    /** Bir koşunun özeti (test + günlük). */
    record Report(boolean locked, int leftoversDropped, int leftoversFailed, List<String> rebuilt, List<String> failed,
                  int deferred, int unknownEstimates, long bytesBefore, long bytesAfter, boolean stoppedEarly, long millis) {

        static Report notLocked(long millis) {
            return new Report(false, 0, 0, List.of(), List.of(), 0, 0, 0, 0, false, millis);
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

    private volatile boolean closing;
    private volatile IndexCatalog current;

    @Autowired
    public IndexBloatMaintenance(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                 @Value("${site.monitor.index-maintenance.enabled:true}") boolean enabled,
                                 @Value("${site.monitor.index-maintenance.lock-timeout-ms:30000}") long lockTimeoutMs,
                                 @Value("${site.monitor.index-maintenance.max-indexes-per-run:6}") int maxIndexes,
                                 @Value("${site.monitor.index-maintenance.max-mb-per-run:4096}") long maxMb,
                                 @Value("${site.monitor.index-maintenance.time-budget-minutes:45}") long budgetMinutes,
                                 @Value("${site.monitor.index-maintenance.min-excess-mb:64}") long minExcessMb) {
        this(ALLOWED_TABLES, DeferredIndexBuilder.memoKnown(() -> DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource())),
                () -> JdbcIndexCatalog.open(jdbc.getDataSource()), scheduler, enabled, lockTimeoutMs, maxIndexes,
                maxMb * 1024 * 1024, budgetMinutes * 60_000L, minExcessMb * 1024 * 1024, System::currentTimeMillis,
                Thread::sleep, () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sideJobsStopping();
                });
    }

    IndexBloatMaintenance(List<String> tables, Supplier<Boolean> postgres, IndexCatalog.Factory catalogs,
                          ObjectProvider<SchedulerService> scheduler, boolean enabled, long lockTimeoutMs, int maxIndexes,
                          long maxBytes, long budgetMs, long minExcessBytes, LongSupplier clock, Sleeper sleeper,
                          BooleanSupplier schedulerStopping) {
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
    }

    /**
     * Gece tetiği. Gövde yan iş koşucusunda; tarama lideri başka canlı pod varken tetiği erteler, ertelenen denemede bu tetiğin
     * turu başka pod'da alındıysa gövde koşmaz.
     */
    @Scheduled(cron = CRON, zone = ZONE)
    public void scheduled() {
        if (!enabled || closing) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;   // dar bağlam (test): küme kilidi / turu olmadan koşmaz
        Runnable body = () -> runScheduled(s);
        if (!s.offloadSideJob(SideJob.cron(JOB, true, JOB, CRON, ZONE, null, body))) body.run();
    }

    /** Kapanış: yeni indekse geçilmez; süren deyim cin yardımcıda iptal edilir (kapanış iş parçacığında veritabanı yok). */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 4)
    public void onContextClosed(ContextClosedEvent event) {
        closing = true;
        IndexCatalog c = current;
        if (c == null) return;
        Thread t = new Thread(c::cancel, "index-bloat-cancel");
        t.setDaemon(true);
        t.start();
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
                if (!s.claimItemProtectedCronRound(JOB, CRON, ZONE)) {
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
            return new Report(true, 0, 0, List.of(), List.of(), 0, 0, 0, 0, true, clock.getAsLong() - t0);
        } finally {
            current = null;
            db.close();
        }
    }

    private Report maintainLocked(IndexCatalog db, long t0, long deadline, boolean[] held) throws SQLException {
        int[] left = dropLeftovers(db, tables);
        IndexBloatEstimator.Selection sel = IndexBloatEstimator.select(db.bloatInputs(tables), FACTOR, minExcessBytes,
                maxIndexes, maxBytes);
        List<String> rebuilt = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long before = 0;
        long after = 0;
        boolean stopped = false;
        int skippedForBudget = 0;
        boolean first = true;
        for (IndexBloatEstimator.Candidate c : sel.picked()) {
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
                log.warn("Şişmiş indeks yeniden kurulamadı (sonraki gece yeniden denenecek): {} ON {} — {}{}", s.name(), s.table(),
                        timeoutKind(e), DeferredIndexBuilder.firstLine(e));
                if (stopping()) { stopped = true; break; }
                int[] l = dropLeftovers(db, List.of(s.table()));   // yarıda kalan _ccnew / _ccold hemen
                left[0] += l[0];
                left[1] += l[1];
            }
        }
        int deferred = sel.deferred().size() + skippedForBudget;
        Report r = new Report(true, left[0], left[1], List.copyOf(rebuilt), List.copyOf(failed), deferred, sel.unknown(),
                before, after, stopped, clock.getAsLong() - t0);
        if (r.leftoversDropped() + r.leftoversFailed() + rebuilt.size() + failed.size() + deferred > 0) {
            log.info("İndeks bakımı: {} kalıntı temizlendi{}, {} indeks yeniden kuruldu ({} → {}), {} başarısız, {} sonraki geceye"
                            + " kaldı{} — {} ms", r.leftoversDropped(),
                    r.leftoversFailed() > 0 ? " (" + r.leftoversFailed() + " temizlenemedi)" : "", rebuilt.size(),
                    IndexBloatEstimator.human(before), IndexBloatEstimator.human(after), failed.size(), deferred,
                    stopped ? ", durduruldu" : "", r.millis());
        } else {
            log.debug("İndeks bakımı: şişmiş indeks yok ({} tahmin edilemedi) — {} ms", sel.unknown(), r.millis());
        }
        return r;
    }

    /**
     * Yarıda kalmış yeniden kurma kalıntılarını düşürür (yalnız verilen tablolarda, GEÇERSİZ {@code _ccnew} / {@code _ccold},
     * tabloda kurulum sürmüyorsa). Dönüş: {düşürülen, düşürülemeyen}. Kapanışta yeni düşürmeye başlanmaz.
     */
    private int[] dropLeftovers(IndexCatalog db, List<String> onTables) throws SQLException {
        int ok = 0;
        int bad = 0;
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
            } catch (SQLException e) {
                bad++;
                log.warn("Yeniden kurma kalıntısı düşürülemedi (sonraki koşuda yeniden denenecek): {} ON {} — {}{}", l.name(), l.table(),
                        timeoutKind(e), DeferredIndexBuilder.firstLine(e));
            }
        }
        return new int[] { ok, bad };
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
