package com.sitemonitor.service.schema;

import com.sitemonitor.service.DeploymentHistoryService;
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
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * GEREKSİZ indekslerin TEK SEFERLİK, güvenli düşürülmesi (2026-11-06, kullanıcı isteği: "uçtan uca performans — gereksiz
 * indeksleri güvenle kaldır"). Ölçüm (yerel 2,7 GB PostgreSQL, {@code pg_index}): {@link #TARGETS}'taki her indeks ya
 * birebir kopyadır ({@code idx_pri_check} = {@code idx_pri_check_id}, {@code idx_mch_type_key_hour} = birincil anahtar) ya da
 * aynı tabloda aynı öncü sütunları taşıyan daha geniş, kısmi olmayan bir btree'nin sol önekidir (ör. {@code (monitor_id)} ←
 * {@code (monitor_id, checked_at)}). Her yazımda boşuna güncellenir ve disk tutar; planlayıcı geniş dizini aynı sorgular için
 * aynı biçimde kullanır (EXPLAIN — rapor). Bildirimleri (entity {@code @Index}, {@code applySchemaPatches} satırları) aynı
 * sürümde kaldırıldı: yeni kurulum onları hiç yaratmaz; bu iş ESKİ kurulumdaki kopyaları düşürür.
 *
 * <p><b>Ne zaman:</b> açılış yolunda değil — açılıştan {@code site.monitor.index-maintenance.redundant-drop.initial-delay-ms}
 * (3 dk, en az 0) sonra, sonra {@code …interval-ms}'de (10 dk, en az {@value #MIN_INTERVAL_MS} ms) bir, iş bitene dek; pod'un
 * yan iş koşucusunda (yerel kadans "bitiş + aralık"). Zamanlama programla kaydedilir ({@link #configureTasks}): 0 / negatif
 * ayar sıkıştırılır, açılışı düşürmez (inceleme DÜŞÜK-6). Yalnız PostgreSQL. Türü okunamayan veritabanı = "sonra dene" (asla
 * "PostgreSQL değil → bitti").
 *
 * <p><b>Kapı — başka sürüm koşan canlı pod yok:</b> {@link DeploymentHistoryService#liveOtherVersions()} boş VE okunabilir
 * olmalı. Eski sürüm bir pod ayaktayken (yayılım sürüyor) düşürülen indeks, o pod yeniden başlarsa ddl-auto / eski yama ile
 * KİLİTLEYEREK (düz {@code CREATE INDEX}) geri yaratılır — bu yüzden beklenir; okunamazsa da beklenir. Dağıtım geçmişi
 * kapalıysa ({@code site.monitor.deploy.history.enabled=false}) sürüm sinyali yoktur ve iş hemen koşar
 * ({@code K6SettingsNormalizer} ile aynı kural; geri dönüşte indeks yine geri yaratılır — zararsız, yalnız fazla dizin).
 *
 * <p><b>Tek koşucu:</b> havuz dışı oturumda ertelenmiş indeks kurucusuyla AYNI advisory lock
 * ({@link DeferredIndexBuilder#LOCK_KEY}, {@code SMIDXBLD}) — doluysa bu tur atlanır (beklenmez, deneme sayılmaz); başka bir
 * pod açılış yamalarını koşuyorsa ({@link SchemaPatchRunner#LOCK_KEY}) da atlanır. Nişan ({@value #MARKER}) kilit İÇİNDE
 * yeniden okunur.
 *
 * <p><b>Çalışma anı doğrulaması (her düşürmeden önce, katalogdan):</b> aday benzersiz değil, birincil / dışlama anahtarı değil,
 * hiçbir kısıtı desteklemiyor, btree, kısmi değil, ifade içermiyor, INCLUDE yok ve sütunları beklenen tanım; kapsayan = aynı
 * tabloda, {@link #TARGETS} DIŞINDA bir btree — geçerli ({@code indisvalid}), hazır ({@code indisready}), canlı, kısmi değil,
 * ifade içermiyor ve ilk k sütunu adayın sütunlarıyla, aynı opclass / collation / sıralama seçenekleriyle birebir aynı.
 * Kapsayan benzersiz olabilir (birincil anahtar). Düşürme {@code DROP INDEX CONCURRENTLY IF EXISTS <şema>.<ad>} —
 * {@code lock_timeout} ile (kilit beklemesi aşılırsa 55P03 → WARN, sonraki tur; yarıda kalan düşürme adayı GEÇERSİZ bırakabilir,
 * sonraki tur onu da düşürür — geçici kümede doğrulandı). Düşürme sürerken bekçi ({@link DdlBlockWatch}) bizi ≈ 6 sn bekleyen
 * başka bir oturum görürse deyimi iptal eder: WARN "başka bir oturumu bekletiyordu, bırakıldı", tur burada biter (bekleme
 * sayılır, deneme sayılmaz), sonraki turda yeniden denenir.
 *
 * <p><b>Doğrulanamayan aday iki türlüdür</b> (gerekçe, 2026-11-06): (1) ADAYIN KENDİSİ bizim tanımımız değil (benzersiz, kısıt,
 * kısmi, ifade, farklı sütun — bir DBA'nın aynı adla kurduğu dizin): hiçbir koşulda düşürülmez, yeniden denemek anlamsızdır →
 * INFO, "tamam" sayılmaz ama nişanı ENGELLEMEZ. (2) KAPSAYAN şu an yok / geçersiz / hazır değil ya da tabloda bir kurulum
 * sürüyor: bu geçicidir (ör. {@code idx_act_type_mon_time} ertelenmiş kurucuda yeniden kurulacak) → aday düşürülmez, nişan
 * YAZILMAZ, tur "tamamlanamadı" sayılır. Pod başına en çok {@value #MAX_ATTEMPTS} tamamlanamayan / hatalı tur (bekleme turları
 * — kapı, kilit, tür — sayılmaz), sonra bu pod bırakır (WARN); sonraki açılış yeniden dener. Bir indeks kapsayanı olmadan
 * ASLA düşürülmez. Nişan yalnız her hedef YOKKEN ya da (1) türündeyken yazılır.
 *
 * <p><b>Geri alma:</b> eski sürüme dönülürse ddl-auto ve eski yamalar bu indeksleri açılışta KİLİTLEYEREK yeniden yaratır
 * (düz {@code CREATE INDEX}: yapım süresince tabloya yazımlar bekler — {@code activity_log}'da saniyeler-dakikalar); geri
 * almadan ÖNCE dizinleri {@code CREATE INDEX CONCURRENTLY IF NOT EXISTS} ile kurun — RUNBOOK "İndeks bakımı".
 */
@Slf4j
@Service
public class RedundantIndexCleanup implements SchedulingConfigurer {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    public static final String MARKER = "2026-11-06-redundant-index-cleanup";
    /** Yan iş adı (Küme kartı "Yan işler") — kalıcı. */
    static final String JOB = "redundant-index-cleanup";
    /** Pod başına tamamlanamayan / hatalı tur sınırı (10 dk × 24 = 4 saat); bekleme turları sayılmaz. */
    static final int MAX_ATTEMPTS = 24;
    /** Bir düşürmenin toplam süre tavanı (her bekleme ayrıca {@code lock_timeout} ile sınırlı). */
    static final long DROP_STATEMENT_TIMEOUT_MS = 5 * 60_000L;
    /** Tur aralığının alt sınırı (ayar 0 / negatif / küçük → bu). */
    static final long MIN_INTERVAL_MS = 60_000L;
    static final long DEFAULT_INITIAL_DELAY_MS = 180_000L;

    /** Düşürülecek indeks: ad, tablo, beklenen anahtar sütunları (sırayla). */
    public record Target(String index, String table, List<String> columns) {
        public Target {
            columns = List.copyOf(columns);
        }
    }

    /**
     * Ölçümle bulunan gereksiz indeksler (2026-11-06). Sıra = düşürme sırası. Adlar KALICI — yeni bir hedef eklenirse nişan
     * anahtarı da değişmeli (eski nişan yeni hedefi kapsamaz).
     */
    public static final List<Target> TARGETS = List.of(
            // Birebir kopyalar
            new Target("idx_pri_check", "page_resource_issues", List.of("check_id")),                 // = idx_pri_check_id
            new Target("idx_mch_type_key_hour", "monitor_check_hourly",
                    List.of("monitor_type", "monitor_key", "hour_bucket")),                          // = birincil anahtar
            // Sol önekler — kapsayan: idx_act_type_mon_time (monitor_type, monitor_id, activity_time)
            new Target("idx_act_type", "activity_log", List.of("monitor_type")),
            new Target("idx_act_type_mon", "activity_log", List.of("monitor_type", "monitor_id")),
            // (monitor_id) ← (monitor_id, checked_at)
            new Target("idx_portc_monitor_id", "port_checks", List.of("monitor_id")),
            new Target("idx_pc_monitor_id", "page_checks", List.of("monitor_id")),
            new Target("idx_dnsr_monitor_id", "dns_records", List.of("monitor_id")),
            new Target("idx_kwr_monitor_id", "keyword_results", List.of("monitor_id")),
            new Target("idx_pingc_monitor_id", "ping_checks", List.of("monitor_id")),
            new Target("idx_hc_monitor_id", "http_checks", List.of("monitor_id")),
            new Target("idx_dc_monitor_id", "domain_checks", List.of("monitor_id")),
            new Target("idx_sc_monitor_id", "scripted_checks", List.of("monitor_id")),
            new Target("idx_pri_monitor_id", "page_resource_issues", List.of("monitor_id")),
            // (domain) ← idx_cc_domain_ts (domain, checked_at); (domain, port) ← idx_uc_domain_port_checked
            new Target("idx_cc_domain", "certificate_checks", List.of("domain")),
            new Target("idx_uc_domain_port", "uptime_checks", List.of("domain", "port")));

    /** Bir adayın kararı. */
    enum Verdict { ABSENT, DROP, BUSY, NO_COVER, NOT_OURS }

    record Decision(Verdict verdict, String reason, IndexCatalog.IndexFacts candidate, IndexCatalog.IndexFacts covering) { }

    /** Bir turun sonucu. WAITING sayılmaz; INCOMPLETE / FAILED deneme sayılır; DONE / NOT_POSTGRES bu pod'da biter. */
    enum Outcome { DONE, NOT_POSTGRES, WAITING, INCOMPLETE, FAILED }

    private final List<Target> targets;
    private final Set<String> targetNames;
    private final String marker;
    private final Supplier<Boolean> postgres;
    private final IndexCatalog.Factory catalogs;
    /** Başka sürüm koşan canlı pod'lar; null = okunamadı. */
    private final Supplier<List<Map<String, String>>> otherVersions;
    private final BooleanSupplier schedulerStopping;
    /** Yan iş koşucusuna verir; false = üstlenilmedi (gövde çağıranın iş parçacığında koşar). */
    private final Predicate<SideJob> offload;
    private final boolean enabled;
    private final long lockTimeoutMs;
    private final long intervalMs;
    private final long initialDelayMs;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean finished;
    private volatile boolean closing;
    private volatile IndexCatalog current;
    /** Aynı bekleme gerekçesi her turda INFO yazmasın: hedef → son gerekçe. */
    private final Map<String, String> lastPending = new ConcurrentHashMap<>();
    private final Set<String> reportedNotOurs = ConcurrentHashMap.newKeySet();

    @Autowired
    public RedundantIndexCleanup(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                 ObjectProvider<DeploymentHistoryService> history,
                                 @Value("${site.monitor.index-maintenance.enabled:true}") boolean enabled,
                                 @Value("${site.monitor.index-maintenance.lock-timeout-ms:30000}") long lockTimeoutMs,
                                 @Value("${site.monitor.index-maintenance.redundant-drop.interval-ms:600000}") long intervalMs,
                                 @Value("${site.monitor.index-maintenance.redundant-drop.initial-delay-ms:180000}") long initialDelayMs) {
        this(TARGETS, MARKER, DeferredIndexBuilder.memoKnown(() -> DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource())),
                () -> JdbcIndexCatalog.open(jdbc.getDataSource()),
                () -> {
                    DeploymentHistoryService h = history.getIfAvailable();
                    return h == null ? List.of() : h.liveOtherVersions();
                },
                () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sideJobsStopping();
                },
                job -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.offloadSideJob(job);
                },
                enabled, lockTimeoutMs, intervalMs, initialDelayMs);
    }

    /** Test: varsayılan ilk gecikme. */
    RedundantIndexCleanup(List<Target> targets, String marker, Supplier<Boolean> postgres, IndexCatalog.Factory catalogs,
                          Supplier<List<Map<String, String>>> otherVersions, BooleanSupplier schedulerStopping,
                          Predicate<SideJob> offload, boolean enabled, long lockTimeoutMs, long intervalMs) {
        this(targets, marker, postgres, catalogs, otherVersions, schedulerStopping, offload, enabled, lockTimeoutMs, intervalMs,
                DEFAULT_INITIAL_DELAY_MS);
    }

    RedundantIndexCleanup(List<Target> targets, String marker, Supplier<Boolean> postgres, IndexCatalog.Factory catalogs,
                          Supplier<List<Map<String, String>>> otherVersions, BooleanSupplier schedulerStopping,
                          Predicate<SideJob> offload, boolean enabled, long lockTimeoutMs, long intervalMs,
                          long initialDelayMs) {
        this.targets = List.copyOf(targets);
        this.targetNames = this.targets.stream().map(Target::index).collect(Collectors.toUnmodifiableSet());
        this.marker = marker;
        this.postgres = postgres;
        this.catalogs = catalogs;
        this.otherVersions = otherVersions;
        this.schedulerStopping = schedulerStopping;
        this.offload = offload;
        this.enabled = enabled;
        this.lockTimeoutMs = Math.max(1_000L, lockTimeoutMs);
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
    }

    /**
     * Tur zamanlaması programla (inceleme DÜŞÜK-6): {@code @Scheduled(fixedDelayString)} 0 / negatif aralıkta
     * {@code scheduleWithFixedDelay} hatasıyla açılışı düşürürdü; burada aralık en az {@value #MIN_INTERVAL_MS} ms, ilk
     * gecikme en az 0 olarak sıkıştırılmış değerler kaydedilir. Kapalıysa hiç kaydedilmez.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        registrar.addFixedDelayTask(new FixedDelayTask(this::scheduled, Duration.ofMillis(intervalMs),
                Duration.ofMillis(initialDelayMs)));
    }

    public void scheduled() {
        if (!enabled || finished || closing || failures.get() >= MAX_ATTEMPTS) return;
        Runnable body = this::runAndCount;
        if (!offload.test(SideJob.fixedDelay(JOB, intervalMs, body))) body.run();
    }

    /** Kapanış: yeni tur başlamaz; süren düşürme cin yardımcıda iptal edilir (kapanış iş parçacığında veritabanı yok). */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 4)
    public void onContextClosed(ContextClosedEvent event) {
        closing = true;
        IndexCatalog c = current;
        if (c == null) return;
        Thread t = new Thread(c::cancel, "redundant-index-cancel");
        t.setDaemon(true);
        t.start();
    }

    /** Bir tur + sayaç (yan iş gövdesi). */
    void runAndCount() {
        Outcome o;
        try {
            o = runOnce();
        } catch (RuntimeException e) {
            log.warn("Gereksiz indeks temizliği beklenmedik hatayla düştü (sonraki turda yeniden denenecek): {}",
                    DeferredIndexBuilder.firstLine(e));
            o = Outcome.FAILED;
        }
        switch (o) {
            case DONE, NOT_POSTGRES -> finished = true;
            case INCOMPLETE, FAILED -> {
                int n = failures.incrementAndGet();
                if (n >= MAX_ATTEMPTS) {
                    log.warn("Gereksiz indeks temizliği {} turda tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı,"
                            + " sonraki açılış yeniden dener)", n);
                }
            }
            case WAITING -> { /* sayılmaz */ }
        }
    }

    boolean finished() { return finished; }

    int failures() { return failures.get(); }

    /** Bir tur. Hata yukarı çıkmaz (FAILED). */
    Outcome runOnce() {
        if (stopping()) return Outcome.WAITING;
        Boolean pg = postgres.get();
        if (pg == null) {
            log.debug("Gereksiz indeks temizliği bekliyor: veritabanı türü okunamadı");
            return Outcome.WAITING;
        }
        if (!pg) return Outcome.NOT_POSTGRES;
        if (!singleVersion()) return Outcome.WAITING;
        IndexCatalog db;
        try {
            db = catalogs.open();
        } catch (SQLException | RuntimeException e) {
            log.warn("Gereksiz indeks temizliği: oturum açılamadı (sonraki turda yeniden denenecek): {}",
                    DeferredIndexBuilder.firstLine(e));
            return Outcome.FAILED;
        }
        current = db;
        try {
            if (!db.tryLock(DeferredIndexBuilder.LOCK_KEY)) {
                log.debug("Gereksiz indeks temizliği bekliyor: indeks kilidi (SMIDXBLD) başka bir oturumda");
                return Outcome.WAITING;
            }
            try {
                if (!patchLockFree(db)) {
                    log.debug("Gereksiz indeks temizliği bekliyor: başka bir pod şema yamalarını koşuyor");
                    return Outcome.WAITING;
                }
                if (db.markerExists(marker)) return Outcome.DONE;
                return cleanLocked(db);
            } finally {
                db.unlock(DeferredIndexBuilder.LOCK_KEY);
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Gereksiz indeks temizliği başarısız (sonraki turda yeniden denenecek): {}", DeferredIndexBuilder.firstLine(e));
            return Outcome.FAILED;
        } finally {
            current = null;
            db.close();
        }
    }

    /** Kilit altında: her hedefi doğrular, doğrulananı düşürür; iş bittiyse nişanı yazar. */
    private Outcome cleanLocked(IndexCatalog db) throws SQLException {
        int dropped = 0;
        long freed = 0;
        boolean pending = false;
        Set<String> notOurs = new LinkedHashSet<>();
        for (Target t : targets) {
            if (stopping()) return Outcome.WAITING;
            List<IndexCatalog.IndexFacts> indexes = db.indexes(t.table());
            boolean present = indexes.stream().anyMatch(i -> i.name().equals(t.index()));
            boolean busy = present && db.building(t.table());
            Decision d = decide(t, indexes, busy, targetNames);
            switch (d.verdict()) {
                case ABSENT -> lastPending.remove(t.index());
                case NOT_OURS -> {
                    notOurs.add(t.index());
                    if (reportedNotOurs.add(t.index())) {
                        log.info("Gereksiz indeks adayı düşürülmedi — bizim tanımımız değil ({}): {} ON {}", d.reason(),
                                t.index(), t.table());
                    }
                }
                case BUSY, NO_COVER -> {
                    pending = true;
                    notePending(t, d.reason());
                }
                case DROP -> {
                    try {
                        db.executeBounded("DROP INDEX CONCURRENTLY IF EXISTS " + d.candidate().qualifiedName(),
                                lockTimeoutMs, DROP_STATEMENT_TIMEOUT_MS);
                        dropped++;
                        freed += Math.max(0, d.candidate().bytes());
                        lastPending.remove(t.index());
                        log.info("Gereksiz indeks düşürüldü: {} ON {} ({}; kapsayan: {})", t.index(), t.table(),
                                IndexBloatEstimator.human(d.candidate().bytes()), d.covering().name());
                    } catch (BlockingOthersException e) {
                        // Bekçi: bir oturum (ör. yeni pod'un ddl-auto ALTER'ı) bu düşürmeyi bekliyordu → bu tur burada biter
                        log.warn("Gereksiz indeks düşürme başka bir oturumu bekletiyordu, bırakıldı: {} ON {} ({}) — sonraki turda"
                                + " yeniden denenecek", t.index(), t.table(), e.reason());
                        return Outcome.WAITING;
                    } catch (SQLException e) {
                        pending = true;
                        log.warn("Gereksiz indeks düşürülemedi (sonraki turda yeniden denenecek): {} ON {} — {}{}", t.index(),
                                t.table(), lockTimeout(e) ? "kilit beklemesi aşıldı: " : "", DeferredIndexBuilder.firstLine(e));
                    }
                }
            }
        }
        if (pending) return Outcome.INCOMPLETE;
        db.writeMarker(marker, dropped);
        if (dropped > 0 || !notOurs.isEmpty()) {
            log.info("Gereksiz indeks temizliği tamamlandı: {} indeks düşürüldü ({} boşaldı){} — nişan yazıldı", dropped,
                    IndexBloatEstimator.human(freed), notOurs.isEmpty() ? "" : "; bizim tanımımız olmadığı için dokunulmayan: " + notOurs);
        } else {
            log.info("Gereksiz indeks temizliği: düşürülecek indeks yok (yeni kurulum ya da daha önce temizlenmiş) — nişan yazıldı");
        }
        return Outcome.DONE;
    }

    private void notePending(Target t, String reason) {
        String prev = lastPending.put(t.index(), reason);
        if (!reason.equals(prev)) {
            log.info("Gereksiz indeks şimdilik düşürülmedi ({}): {} ON {} — sonraki turda yeniden bakılacak", reason, t.index(), t.table());
        } else {
            log.debug("Gereksiz indeks hâlâ bekliyor ({}): {}", reason, t.index());
        }
    }

    /**
     * Saf karar (birim testi): aday yok → ABSENT; tabloda kurulum sürüyor → BUSY; aday bizim tanımımız değil → NOT_OURS;
     * geçerli + hazır kapsayan var → DROP; yoksa NO_COVER. Kapsayan {@code targetNames} DIŞINDAN seçilir (düşürülecek bir
     * dizin başka bir adayı kapsamaz).
     */
    static Decision decide(Target t, List<IndexCatalog.IndexFacts> indexes, boolean tableBuilding, Set<String> targetNames) {
        IndexCatalog.IndexFacts cand = null;
        for (IndexCatalog.IndexFacts i : indexes) {
            if (i.name().equals(t.index())) { cand = i; break; }
        }
        if (cand == null) return new Decision(Verdict.ABSENT, "yok", null, null);
        String notOurs = notOursReason(t, cand);
        if (notOurs != null) return new Decision(Verdict.NOT_OURS, notOurs, cand, null);
        if (tableBuilding) return new Decision(Verdict.BUSY, "tabloda şu an bir indeks kuruluyor", cand, null);
        IndexCatalog.IndexFacts notReady = null;
        for (IndexCatalog.IndexFacts o : indexes) {
            if (o.name().equals(cand.name()) || targetNames.contains(o.name())) continue;
            if (!coversShape(o, cand)) continue;
            if (o.valid() && o.ready() && o.live()) return new Decision(Verdict.DROP, "kapsayan " + o.name(), cand, o);
            notReady = o;
        }
        if (notReady != null) {
            return new Decision(Verdict.NO_COVER, "kapsayan " + notReady.name() + " henüz geçerli / hazır değil", cand, notReady);
        }
        return new Decision(Verdict.NO_COVER, "geçerli bir kapsayan indeks yok", cand, null);
    }

    /** Aday bizim tanımımız mı; değilse gerekçe. */
    static String notOursReason(Target t, IndexCatalog.IndexFacts c) {
        if (!"btree".equals(c.accessMethod())) return "btree değil (" + c.accessMethod() + ")";
        if (c.primary()) return "birincil anahtar";
        if (c.unique()) return "benzersiz";
        if (c.exclusion()) return "dışlama kısıtı";
        if (c.constraintBacked()) return "bir kısıtı destekliyor";
        if (c.partial()) return "kısmi";
        if (c.expressions()) return "ifade içeriyor";
        if (c.columns().size() != c.keyCount()) return "INCLUDE sütunu var";
        if (!c.keyColumns().equals(t.columns())) return "sütunları beklenen değil: " + c.keyColumns();
        return null;
    }

    /** {@code o} şekil olarak {@code c}'yi kapsıyor mu (geçerlilik ayrı): btree, kısmi / ifade değil, öncü sütunlar birebir. */
    static boolean coversShape(IndexCatalog.IndexFacts o, IndexCatalog.IndexFacts c) {
        if (!"btree".equals(o.accessMethod()) || o.partial() || o.expressions()) return false;
        int k = c.keyCount();
        if (k <= 0 || o.keyCount() < k) return false;
        if (o.columns().size() < k || o.opclasses().size() < k || o.collations().size() < k || o.options().size() < k) return false;
        if (c.opclasses().size() < k || c.collations().size() < k || c.options().size() < k) return false;
        for (int i = 0; i < k; i++) {
            String col = o.columns().get(i);
            if (col.isEmpty() || !col.equals(c.columns().get(i))) return false;
            if (!o.opclasses().get(i).equals(c.opclasses().get(i))) return false;
            if (!o.collations().get(i).equals(c.collations().get(i))) return false;
            if (!o.options().get(i).equals(c.options().get(i))) return false;
        }
        return true;
    }

    /** Başka sürüm koşan canlı pod yok mu; okunamazsa "hayır" (bekle). */
    boolean singleVersion() {
        List<Map<String, String>> others;
        try {
            others = otherVersions.get();
        } catch (RuntimeException e) {
            others = null;
        }
        if (others == null) {
            log.debug("Gereksiz indeks temizliği bekliyor: pod sürümleri okunamadı");
            return false;
        }
        if (!others.isEmpty()) {
            log.debug("Gereksiz indeks temizliği bekliyor: başka sürüm koşan pod var {}", others);
            return false;
        }
        return true;
    }

    private boolean stopping() {
        if (closing) return true;
        try {
            return schedulerStopping.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Başka bir pod açılış yamalarını koşuyor mu — yama kilidi yalnız yoklanır, hemen bırakılır. */
    static boolean patchLockFree(IndexCatalog db) {
        try {
            if (db.tryLock(SchemaPatchRunner.LOCK_KEY)) {
                db.unlock(SchemaPatchRunner.LOCK_KEY);
                return true;
            }
            return false;
        } catch (SQLException e) {
            return true;   // yoklanamadı → beklemeden devam (DeferredIndexBuilder ile aynı)
        }
    }

    /** PostgreSQL kilit zaman aşımı (55P03 lock_not_available). */
    static boolean lockTimeout(SQLException e) {
        return "55P03".equals(e.getSQLState());
    }

    /** Test / tanılama: hedef adı → son bekleme gerekçesi (kopya). */
    Map<String, String> pendingReasons() {
        return new HashMap<>(lastPending);
    }
}
