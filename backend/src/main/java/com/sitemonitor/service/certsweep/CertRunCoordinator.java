package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.PodLiveness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Parçalı sertifika taramasının KOORDİNATÖRÜ (2026-10-29, çok pod Sürüm 3-4 paket B; tasarım R3-4 §2, §4.5; kullanıcı kararları
 * V-1…V-3). {@code SchedulerService.runCheckForDomains} — bugünkü tetik kodu, {@code cert-check} kilidi ve tur satırları
 * DEĞİŞMEDEN — toplama adımında {@link #gather} çağırır:
 * <ul>
 *   <li>{@code null} → klasik toplama ({@code gatherCertResults}, 20.170.0'ın satırları birebir);</li>
 *   <li>aksi: koşu + parça satırları yazılır, her 3-4 pod'un işçisi (bu pod dahil) parçaları sahiplenip YALNIZ ÖLÇER, bu sınıf
 *       sonuçları KLASİK SIRADA yeniden kurar ve listeyi döner — kayıt, önbellek boşaltma, toplu kesinti kuralı
 *       ({@code onRun}), alarm işleme ve {@code scan_runs} çağıranın DEĞİŞMEMİŞ koduyla, TEK pod'da (L-2), tüm tura
 *       bakarak (V-3) çalışır.</li>
 * </ul>
 *
 * <p><b>Kapalı ≡ v20.170.0 (L-1).</b> {@link #gather}'in İLK deyimi {@link CertChunking#enabled()} (bellek) okumasıdır; kapalıysa
 * {@code null} döner — depo, işçi, {@code PodLiveness}, veritabanı, günlük, iş parçacığı yok. {@link #finish} yalnız parçalı
 * koşulan turda çağrılır.
 *
 * <p><b>Kip (§4.5 adım 1).</b> Anahtar açık ∧ depo bean'i var ve tabloları hazır ∧ ölçücü ({@link CertChunkRunner}) var ∧ alan
 * adı sayısı ≥ {@link CertChunking.Tuning#minDomains()} ∧ kapanmıyor ∧ BAŞKA canlı pod var ({@code PodLiveness.anotherLivePod()};
 * bilinmiyor = yok). Biri tutmazsa klasik (sayaç {@code classic_fallback}). Tek pod'da anahtar açık olsa da tur klasiktir.
 *
 * <p><b>Bekleme döngüsü (§2.4 / §2.5).</b> {@value CertChunking#COORDINATOR_WAIT_POLL_MS} ms'de bir: kapanış → SHUTDOWN, kilit
 * kaybı → LOCK_LOST (sonuç işlenmez — klasik SS:3340 / SS:3348 ile aynı), {@value CertChunking#COORDINATOR_HEARTBEAT_MS} ms'de
 * bir nabız (0 satır → LOCK_LOST), parça durumları, yeni DONE parçaların sonuçları. Kullanılamayan sonuç ({@code failed} /
 * kırpılmış) ve KAYIP parça ({@link ChunkState#lost}) koordinatörün KENDİ klasik toplamasıyla ölçülür (L-4). İlerleme
 * ({@code sweep-max-wait-seconds}, 900 sn) yoksa, {@value #NO_WORKER_MS} ms içinde hiçbir parça sahiplenilmediyse (hiçbir pod'da
 * işçi yok) ya da depo {@value CertChunking#STORE_FAILURE_LOCAL_MS} ms boyunca okunamadıysa kalan parçalar yerelde ölçülür — tur
 * koordinatör yaşadıkça ve kilidi tuttukça HER alan adı için tek sonuçla biter.
 *
 * <p><b>Aykırı pod — ikinci görüş (§2.6).</b> Bir pod'un ağ hatası oranı eşiğin üstünde, diğerleri temizken o pod'un ağ sınıfı
 * hataları BAŞKA bir pod'dan (kaçınılan işçi = o pod) bir kez yeniden ölçülür; yeni ölçüm eskisinin yerine geçer (L-3'ün tek
 * bilinçli istisnası).
 *
 * <p><b>Ölçüm koordinatörün iş parçacığında</b> (tasarım §4.5 adım 3): {@link CertChunkRunner#checkDomains} = klasik toplama, aynı
 * istisna anlamıyla — bir kontrolün istisnası turu klasikteki gibi düşürür (koşu FAILED, istisna AYNEN fırlar). Koordinatörün
 * kendi beklenmedik hatası ise turu düşürmez: koşu kapatılır ve {@code null} döner → klasik toplama.
 */
@Slf4j
@Component
public class CertRunCoordinator {

    /** Bu kadar (ms) içinde hiçbir parça sahiplenilmediyse (kümede hizmet veren işçi yok) koordinatör hepsini kendisi ölçer. */
    public static final long NO_WORKER_MS = CertChunking.COORDINATOR_ALIVE_MS;
    /** Yayın kuruluşu okumasında {@code IN (…)} başına alan adı. */
    static final int ISSUER_BATCH = 500;
    /** Koşu başlatılamadı uyarısının aralığı (ms). */
    static final long CREATE_WARN_EVERY_MS = 60_000L;
    /** Aykırı pod uyarısının aralığı (ms) — pod başına saatte bir. */
    static final long OUTLIER_WARN_EVERY_MS = 3_600_000L;

    /**
     * Turun bağlamı (çağıran {@code SchedulerService} kurar — değerler TEMBEL okunur, anahtar kapalıyken hiçbiri çağrılmaz).
     *
     * @param runId         klasik tur kimliği (= koşu kimliği, {@code certificate_checks.run_id})
     * @param kind          {@code hourly} / {@code stale} / {@code full}
     * @param evictCaches   tur sonunda önbellek boşaltılacak mı (yalnız bilgi — boşaltmayı çağıran yapar)
     * @param lockHeld      {@code cert-check} tutuşu hâlâ bu pod'da mı (bellek, {@code lockStillHeld})
     * @param stopping      kapanış başladı mı (bellek, {@code startupCheckStopped || stopping()})
     * @param maxWaitMs     ilerlemesiz bekleme tavanı (ms, {@code sweep-max-wait-seconds})
     * @param minErrors     toplu kesinti kuralının {@code min-errors} değeri (aykırı pod kuralı için)
     * @param rateThreshold toplu kesinti kuralının oran eşiği (aykırı pod kuralı için)
     */
    public record Request(String runId, String kind, boolean evictCaches, BooleanSupplier lockHeld, BooleanSupplier stopping,
                          long maxWaitMs, IntSupplier minErrors, DoubleSupplier rateThreshold) {}

    /**
     * Parçalı toplamanın sonucu.
     *
     * @param runId        koşu kimliği
     * @param results      KLASİK SIRADA sonuç haritaları (değiştirilebilir; {@code aborted} iken boş)
     * @param abortOutcome null = tamam; {@link CertSweepStore#OUTCOME_SHUTDOWN} / {@link CertSweepStore#OUTCOME_LOCK_LOST} =
     *                     sonuçlar işlenmez, çağıran erken döner
     * @param measured     bırakıldığı anda toplanmış sonuç sayısı (günlük için)
     */
    public record Gathered(String runId, List<Map<String, Object>> results, String abortOutcome, int measured) {
        public boolean aborted() {
            return abortOutcome != null;
        }
    }

    /** Uyku (testler sahte saatle ilerletir). */
    interface Pause {
        void sleep(long ms) throws InterruptedException;
    }

    private final CertChunking chunking;
    private final Supplier<CertSweepStore> storeSource;
    private final Supplier<CertChunkRunner> runnerSource;
    private final BooleanSupplier anotherLivePod;
    private final Function<List<String>, Map<String, String>> issuerLookup;
    private final Supplier<String> instanceId;
    private final LongSupplier monoMs;
    private final Pause pause;

    private volatile Runnable localWorkerPoke;
    private final Map<String, Session> open = new ConcurrentHashMap<>();

    private final AtomicLong chunkedRuns = new AtomicLong();
    private final AtomicLong classicFallback = new AtomicLong();
    private final AtomicLong takeovers = new AtomicLong();
    private final AtomicLong remeasured = new AtomicLong();
    private final AtomicLong secondOpinions = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();
    private final AtomicLong createWarnAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong outlierWarnAt = new AtomicLong(Long.MIN_VALUE);

    private volatile JdbcTemplate issuerJdbc;

    /** Spring kurucusu — depo, ölçücü, canlılık ve veri kaynağı TEMBEL çözülür (bağımlılık döngüsü yok, açılışta iş yok). */
    @Autowired
    public CertRunCoordinator(CertChunking chunking, ObjectProvider<CertSweepStore> store,
                              ObjectProvider<CertChunkRunner> runner, ObjectProvider<PodLiveness> podLiveness,
                              ObjectProvider<DataSource> dataSource) {
        this.chunking = chunking;
        this.storeSource = () -> available(store);
        this.runnerSource = () -> available(runner);
        this.anotherLivePod = () -> {
            PodLiveness p = available(podLiveness);
            return p != null && p.anotherLivePod();
        };
        this.issuerLookup = domains -> springIssuers(dataSource, domains);
        this.instanceId = com.sitemonitor.service.SchedulerService::instanceId;
        this.monoMs = () -> System.nanoTime() / 1_000_000L;
        this.pause = Thread::sleep;
        if (chunking != null) chunking.registerCoordinatorStatus(this::status);
    }

    /** Testler / elle kurulum: her bağımlılık doğrudan (null depo / ölçücü → hep klasik). */
    CertRunCoordinator(CertChunking chunking, CertSweepStore store, CertChunkRunner runner, BooleanSupplier anotherLivePod,
                       Function<List<String>, Map<String, String>> issuerLookup, Supplier<String> instanceId,
                       LongSupplier monoMs, Pause pause) {
        this.chunking = chunking;
        this.storeSource = () -> store;
        this.runnerSource = () -> runner;
        this.anotherLivePod = anotherLivePod == null ? () -> false : anotherLivePod;
        this.issuerLookup = issuerLookup == null ? d -> Map.of() : issuerLookup;
        this.instanceId = instanceId;
        this.monoMs = monoMs;
        this.pause = pause;
        if (chunking != null) chunking.registerCoordinatorStatus(this::status);
    }

    /**
     * Bu pod'un işçisini yeni koşudan haberdar eden kanca (işçi boşta yoklamasını beklemeden sahiplensin — tasarım §4.4).
     * Yoksa işçi bir sonraki yoklamasında ({@code idle-poll-ms}) görür. Hızlı olmalı; istisnası yutulur.
     */
    public void setLocalWorkerPoke(Runnable poke) {
        this.localWorkerPoke = poke;
    }

    /** Bu pod'un koordinatör sayaçları (bellek; Sistem Sağlığı / ölçümler). */
    public CertSweepStatus.Coordinator status() {
        return new CertSweepStatus.Coordinator(chunkedRuns.get(), classicFallback.get(), takeovers.get(), remeasured.get(),
                secondOpinions.get(), abandoned.get());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Toplama
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Turun toplama adımı — {@code null} = klasik toplamayı koş.
     *
     * @param domains klasik vade listesi (sıra = karar sırası)
     * @param req     turun bağlamı
     * @return null (klasik) ya da klasik sırada sonuçlar / bırakma sonucu
     * @throws RuntimeException bir kontrolün istisnası (klasik toplamanın {@code join()} istisnası, AYNI nesne)
     */
    public Gathered gather(List<Map<String, Object>> domains, Request req) {
        CertChunking cc = chunking;
        if (cc == null || !cc.enabled()) return null;                // İLK deyim: bellek (L-1)
        CertSweepStore store = storeSource.get();
        CertChunkRunner runner = runnerSource.get();
        if (!chunkedRunWanted(cc, store, runner, domains, req)) {
            classicFallback.incrementAndGet();
            return null;
        }
        String me = instanceId.get();
        CertRunSpec spec;
        try {
            for (CertSweepStatus.Run orphan : store.settleOrphans()) {
                log.warn("Sahipsiz parçalı tarama kapatıldı (runId={}, koordinatör={}) — sonuçları kaydedilmemişti, "
                        + "bayat tarama telafi eder", orphan.runId(), ClusterHealthStore.podOf(orphan.coordinator()));
            }
            pruneQuietly(store);
            spec = buildSpec(domains, req, me, cc.tuning());
            store.createRun(spec);
        } catch (RuntimeException e) {
            warnCreate(e);
            classicFallback.incrementAndGet();
            return null;
        }
        chunkedRuns.incrementAndGet();
        log.info("Sertifika taraması parçalı — runId={}, {} alan adı, {} parça (boyut {}) [instance={}]",
                spec.runId(), spec.domainsTotal(), spec.chunksTotal(), spec.chunkSize(), me);
        Runnable poke = localWorkerPoke;
        if (poke != null) {
            try {
                poke.run();
            } catch (RuntimeException e) {
                log.debug("Yerel parça işçisi dürtülemedi: {}", e.getClass().getSimpleName());
            }
        }
        Session s = new Session(spec, req, me, store, runner);
        open.put(spec.runId(), s);
        try {
            return s.run();
        } catch (RunnerFailure rf) {
            // Bir kontrolün istisnası — klasik toplamadaki gibi tur düşer; koşu burada kapanır (çağıranın kancası çağrılmaz)
            open.remove(spec.runId());
            finishQuietly(store, spec.runId(), CertSweepStore.RUN_FAILED, CertSweepStore.OUTCOME_FAILED, false);
            throw rf.rethrowable();
        } catch (RuntimeException e) {
            // Koordinatörün kendi hatası turu düşürmez: koşu kapanır, tur klasik toplamayla sürer (yeniden ölçüm yan etkisiz)
            open.remove(spec.runId());
            log.warn("Parçalı tarama iç hatası — tur klasik toplamaya dönüyor (runId={}): {}", spec.runId(),
                    e.getClass().getSimpleName());
            finishQuietly(store, spec.runId(), CertSweepStore.RUN_ABANDONED, CertSweepStore.OUTCOME_FAILED, false);
            abandoned.incrementAndGet();
            classicFallback.incrementAndGet();
            return null;
        } catch (Error e) {
            open.remove(spec.runId());
            finishQuietly(store, spec.runId(), CertSweepStore.RUN_FAILED, CertSweepStore.OUTCOME_FAILED, false);
            throw e;
        } finally {
            if (s.interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Turun son durumunu yazar ({@code runCheckForDomains}'in {@code finally}'si — yalnız parçalı koşulan turda): bırakıldıysa
     * ABANDONED (sonucuyla), istisna yakalandıysa FAILED, bittiyse DONE (DECIDED / STORE_FAILED), sonuçlar işlenmeden erken
     * dönüldüyse ABANDONED (kapanış → SHUTDOWN, aksi → LOCK_LOST). Asla fırlatmaz.
     */
    public void finish(String runId, boolean finished, boolean failed) {
        if (runId == null) return;
        Session s = open.remove(runId);
        CertSweepStore store = s != null ? s.store : storeSource.get();
        if (store == null) return;
        String state;
        String outcome;
        if (s != null && s.abortOutcome != null) {
            state = CertSweepStore.RUN_ABANDONED;
            outcome = s.abortOutcome;
        } else if (failed) {
            state = CertSweepStore.RUN_FAILED;
            outcome = CertSweepStore.OUTCOME_FAILED;
        } else if (finished) {
            state = CertSweepStore.RUN_DONE;
            outcome = s != null && s.storeFailed ? CertSweepStore.OUTCOME_STORE_FAILED : CertSweepStore.OUTCOME_DECIDED;
        } else {
            state = CertSweepStore.RUN_ABANDONED;
            outcome = closingNow(s) ? CertSweepStore.OUTCOME_SHUTDOWN : CertSweepStore.OUTCOME_LOCK_LOST;
        }
        if (CertSweepStore.RUN_ABANDONED.equals(state)) abandoned.incrementAndGet();
        finishQuietly(store, runId, state, outcome, CertSweepStore.OUTCOME_SHUTDOWN.equals(outcome));
    }

    private boolean closingNow(Session s) {
        try {
            if (s != null && s.req.stopping() != null && s.req.stopping().getAsBoolean()) return true;
            CertChunkRunner r = s != null ? s.runner : runnerSource.get();
            return r != null && r.closing();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void finishQuietly(CertSweepStore store, String runId, String state, String outcome, boolean atShutdown) {
        try {
            if (!store.finish(runId, state, outcome, atShutdown)) {
                log.debug("Parçalı koşu kapatılamadı (zaten kapalı ya da yazılamadı): runId={}, {}", runId, state);
            }
        } catch (RuntimeException e) {
            log.warn("Parçalı koşu kapanışı yazılamadı (runId={}): {}", runId, e.getClass().getSimpleName());
        }
    }

    private boolean chunkedRunWanted(CertChunking cc, CertSweepStore store, CertChunkRunner runner,
                                     List<Map<String, Object>> domains, Request req) {
        if (store == null || runner == null || domains == null || req == null) return false;
        try {
            if (!store.tablesReady()) return false;
            if (domains.size() < cc.tuning().minDomains()) return false;
            if (req.stopping() != null && req.stopping().getAsBoolean()) return false;
            return anotherLivePod.getAsBoolean();                    // SQL (30 sn önbellekli) — EN SON
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void pruneQuietly(CertSweepStore store) {
        try {
            store.pruneRuns(store.nowMs() - CertChunking.RUN_RETENTION_MS, CertChunking.RUN_PRUNE_LIMIT);
        } catch (RuntimeException e) {
            log.debug("Eski parçalı koşu satırları budanamadı: {}", e.getClass().getSimpleName());
        }
    }

    private void warnCreate(RuntimeException e) {
        long now = monoMs.getAsLong();
        long last = createWarnAt.get();
        if (last != Long.MIN_VALUE && now - last < CREATE_WARN_EVERY_MS) {
            log.debug("Parçalı tarama başlatılamadı — tur klasik koşuyor: {}", e.getClass().getSimpleName());
            return;
        }
        if (createWarnAt.compareAndSet(last, now)) {
            log.warn("Parçalı tarama başlatılamadı — tur klasik koşuyor: {}", e.getClass().getSimpleName());
        }
    }

    // ── Parça kurulumu (§4.5 adım 2) ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Vade listesini yayın kuruluşu yakınlığıyla sıralar (son {@code latest_checks.issuer_dn}; bilinmiyorsa alan adının
     * kendisi; eşitlikte klasik sıra) ve {@link CertChunking.Tuning#effectiveChunkSize} boyutunda parçalara böler. Her girdi
     * KLASİK sıra numarasını taşır — karar listesi bu numarayla yeniden kurulur. Yanlış türlü satır klasik toplamadaki gibi
     * {@code ClassCastException} fırlatır (çağıran klasiğe döner).
     */
    CertRunSpec buildSpec(List<Map<String, Object>> domains, Request req, String me, CertChunking.Tuning tuning) {
        int n = domains.size();
        List<CertChunkSpec.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) entries.add(CertChunkSpec.Entry.of(i, domains.get(i)));
        Map<String, String> issuers = issuersOf(entries);
        List<CertChunkSpec.Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing((CertChunkSpec.Entry e) -> {
            String issuer = issuers.get(e.domain());
            return issuer == null || issuer.isBlank() ? e.domain() : issuer;
        }).thenComparingInt(CertChunkSpec.Entry::index));
        int size = tuning.effectiveChunkSize(n);
        List<CertChunkSpec> chunks = new ArrayList<>();
        for (int from = 0, no = 0; from < n; from += size, no++) {
            chunks.add(CertChunkSpec.of(no, ordered.subList(from, Math.min(n, from + size))));
        }
        return new CertRunSpec(req.runId(), req.kind(), me, req.evictCaches(), n, size, chunks);
    }

    private Map<String, String> issuersOf(List<CertChunkSpec.Entry> entries) {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(entries.stream().map(CertChunkSpec.Entry::domain).toList()));
        try {
            Map<String, String> m = issuerLookup.apply(names);
            return m == null ? Map.of() : m;
        } catch (RuntimeException e) {
            log.debug("Yayın kuruluşu sırası okunamadı — klasik sırayla bölünüyor: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, String> springIssuers(ObjectProvider<DataSource> dataSource, List<String> domains) {
        JdbcTemplate j = issuerJdbc;
        if (j == null) {
            DataSource ds = available(dataSource);
            if (ds == null) return Map.of();
            j = new JdbcTemplate(ds);
            j.setQueryTimeout(CertSweepStore.QUERY_TIMEOUT_SECONDS);
            issuerJdbc = j;
        }
        return lookupIssuers(j, domains);
    }

    /** {@code SELECT domain, issuer_dn FROM latest_checks WHERE domain IN (…)} — {@value #ISSUER_BATCH}'lük gruplar. */
    static Map<String, String> lookupIssuers(JdbcTemplate jdbc, List<String> domains) {
        Map<String, String> out = new HashMap<>();
        if (jdbc == null || domains == null) return out;
        for (int from = 0; from < domains.size(); from += ISSUER_BATCH) {
            List<String> part = domains.subList(from, Math.min(domains.size(), from + ISSUER_BATCH));
            String in = String.join(",", Collections.nCopies(part.size(), "?"));
            jdbc.query("SELECT domain, issuer_dn FROM latest_checks WHERE domain IN (" + in + ")", rs -> {
                String d = rs.getString(1);
                String i = rs.getString(2);
                if (d != null && i != null && !i.isBlank()) out.put(d, i);
            }, part.toArray());
        }
        return out;
    }

    private static <T> T available(ObjectProvider<T> p) {
        if (p == null) return null;
        try {
            return p.getIfAvailable();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Bir koşunun toplaması
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Ölçücünün (klasik toplama) istisnası — koordinatörün kendi hatasından ayrılsın diye sarılır, çağırana AYNEN iletilir. */
    static final class RunnerFailure extends RuntimeException {
        private final Throwable original;

        RunnerFailure(Throwable original) {
            super(null, null, false, false);
            this.original = original;
        }

        RuntimeException rethrowable() {
            if (original instanceof RuntimeException re) return re;
            if (original instanceof Error err) throw err;
            return new IllegalStateException(original);
        }
    }

    private final class Session {
        final CertRunSpec spec;
        final Request req;
        final String me;
        final CertSweepStore store;
        final CertChunkRunner runner;
        final String runId;
        final int n;
        final List<Map<String, Object>> results;
        final String[] by;
        final CertChunkSpec.Entry[] entries;
        final Map<Integer, CertChunkSpec> specs = new HashMap<>();
        final Map<Integer, Long> idByNo = new HashMap<>();
        final Map<Long, Integer> noById = new HashMap<>();
        final Map<Integer, ChunkState> lastState = new HashMap<>();
        final Map<Integer, String> fingerprint = new HashMap<>();
        final Map<Integer, Integer> maxAttempts = new HashMap<>();
        final Set<Integer> settled = new HashSet<>();
        final Set<Integer> queued = new HashSet<>();
        final List<CertChunkSpec.Entry> localEntries = new ArrayList<>();
        final Set<Integer> localChunks = new HashSet<>();
        final Map<String, Integer> chunkTally = new HashMap<>();
        int takeoverChunks;
        int secondOpinionDomains;
        boolean storeFailed;
        long storeFailingSince = -1L;
        boolean anyClaimSeen;
        boolean noWorkerFired;
        long createdMono;
        long lastProgress;
        long lastHeartbeat;
        int nextChunkNo;
        volatile String abortOutcome;
        boolean interrupted;

        Session(CertRunSpec spec, Request req, String me, CertSweepStore store, CertChunkRunner runner) {
            this.spec = spec;
            this.req = req;
            this.me = me;
            this.store = store;
            this.runner = runner;
            this.runId = spec.runId();
            this.n = spec.domainsTotal();
            this.results = new ArrayList<>(Collections.nCopies(n, null));
            this.by = new String[n];
            this.entries = new CertChunkSpec.Entry[n];
            for (CertChunkSpec c : spec.chunks()) {
                register(c);
                for (CertChunkSpec.Entry e : c.entries()) entries[e.index()] = e;
            }
            this.nextChunkNo = spec.chunksTotal();
        }

        private void register(CertChunkSpec c) {
            specs.put(c.chunkNo(), c);
            fingerprint.put(c.chunkNo(), fp(CertSweepStore.CHUNK_PENDING, 0, null, null));
        }

        Gathered run() {
            createdMono = lastProgress = lastHeartbeat = monoMs.getAsLong();
            Set<Integer> initial = new LinkedHashSet<>(specs.keySet());
            String abort = waitFor(initial, false, createdMono);
            if (abort == null) abort = secondOpinion();
            if (abort != null) return aborted(abort);
            List<Map<String, Object>> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Map<String, Object> m = results.get(i);
                if (m == null) throw new IllegalStateException("eksik sonuç: " + i);   // olmamalı — iç hata → klasik
                list.add(m);
            }
            List<CertSweepStatus.WorkerTally> tallies = tallies();
            int reclaims = 0;
            for (int a : maxAttempts.values()) reclaims += Math.max(0, a - 1);
            if (!storeFailed) {
                try {
                    if (!store.markDeciding(runId, CertSweepStatus.encodeWorkers(tallies), takeoverChunks, reclaims,
                            secondOpinionDomains)) {
                        return aborted(CertSweepStore.OUTCOME_LOCK_LOST);
                    }
                } catch (RuntimeException e) {
                    log.debug("Parçalı koşu DECIDING yazılamadı (runId={}): {}", runId, e.getClass().getSimpleName());
                }
            }
            Map<String, Integer> perPod = new TreeMap<>();
            for (CertSweepStatus.WorkerTally t : tallies) perPod.merge(podOf(t.worker()), t.domains(), Integer::sum);
            log.info("Parçalı tarama toplandı — runId={}, pod başına {}, devralınan {}, ikinci görüş {}, {} sn",
                    runId, perPod, takeoverChunks, secondOpinionDomains,
                    (monoMs.getAsLong() - createdMono) / 1000L);
            return new Gathered(runId, list, null, n);
        }

        private Gathered aborted(String outcome) {
            abortOutcome = outcome;
            return new Gathered(runId, List.of(), outcome, measured());
        }

        int measured() {
            int k = 0;
            for (Map<String, Object> m : results) if (m != null) k++;
            return k;
        }

        // ── Bekleme döngüsü ─────────────────────────────────────────────────────────────────────────────────────────

        /**
         * {@code nos} parçalarının hepsi yerleşene (sonucu toplandı / yerelde ölçüldü / ikinci görüşte eskisi kaldı) kadar bekler.
         *
         * @return null = tamam; aksi bırakma sonucu
         */
        private String waitFor(Set<Integer> nos, boolean secondOpinion, long phaseStart) {
            while (true) {
                if (closing()) return CertSweepStore.OUTCOME_SHUTDOWN;
                if (!req.lockHeld().getAsBoolean()) return CertSweepStore.OUTCOME_LOCK_LOST;
                if (localEntries.isEmpty() && settled.containsAll(nos)) return null;
                long now = monoMs.getAsLong();
                if (!storeFailed && now - lastHeartbeat >= CertChunking.COORDINATOR_HEARTBEAT_MS) {
                    try {
                        boolean alive = store.heartbeat(runId);
                        storeOk();
                        if (!alive) return CertSweepStore.OUTCOME_LOCK_LOST;
                        lastHeartbeat = now;
                    } catch (RuntimeException e) {
                        storeError(e, now);
                    }
                }
                if (!storeFailed) {
                    try {
                        List<ChunkState> states = store.chunkStates(runId);
                        storeOk();
                        observe(states, nos, secondOpinion, phaseStart, now);
                    } catch (RunnerFailure rf) {
                        throw rf;
                    } catch (RuntimeException e) {
                        storeError(e, now);
                    }
                }
                if (storeFailed) {
                    for (int no : nos) takeover(no, null, false, "store");
                } else if (now - lastProgress >= Math.max(1L, req.maxWaitMs())) {
                    log.warn("Parçalı tarama {} sn ilerlemesiz — kalan parçaları koordinatör ölçüyor (runId={})",
                            Math.max(1L, req.maxWaitMs()) / 1000L, runId);
                    for (int no : nos) takeover(no, lastState.get(no), true, "progress");
                    lastProgress = now;
                } else if (!secondOpinion && !anyClaimSeen && !noWorkerFired && now - createdMono >= NO_WORKER_MS) {
                    noWorkerFired = true;
                    log.warn("Parçalı taramada {} sn içinde hiçbir parça sahiplenilmedi (çalışan işçi yok) — koordinatör "
                            + "hepsini ölçüyor (runId={})", NO_WORKER_MS / 1000L, runId);
                    for (int no : nos) takeover(no, lastState.get(no), true, "no_worker");
                } else if (secondOpinion && now - phaseStart >= CertChunking.SECOND_OPINION_WAIT_MS) {
                    for (int no : nos) {
                        ChunkState c = lastState.get(no);
                        if (c == null || (c.pending() && c.attempts() == 0)) takeover(no, c, c != null, "second_opinion");
                    }
                }
                if (!localEntries.isEmpty()) {
                    String abort = measureLocal();
                    if (abort != null) return abort;
                    lastProgress = monoMs.getAsLong();
                    continue;
                }
                if (settled.containsAll(nos)) return null;
                sleep(CertChunking.COORDINATOR_WAIT_POLL_MS);
            }
        }

        private boolean closing() {
            if (req.stopping() != null && req.stopping().getAsBoolean()) return true;
            try {
                return runner.closing();
            } catch (RuntimeException e) {
                return false;
            }
        }

        private void observe(List<ChunkState> states, Set<Integer> nos, boolean secondOpinion, long phaseStart, long now) {
            if (states == null) return;
            long storeNow = store.nowMs();
            List<Long> toRead = new ArrayList<>();
            for (ChunkState c : states) {
                if (c == null || !specs.containsKey(c.chunkNo())) continue;
                int no = c.chunkNo();
                idByNo.put(no, c.id());
                noById.put(c.id(), no);
                lastState.put(no, c);
                String f = fp(c.state(), c.attempts(), c.claimedMs(), c.leaseUntilMs());
                if (!f.equals(fingerprint.put(no, f))) lastProgress = now;
                if (c.attempts() > 0 || !c.pending()) anyClaimSeen = true;
                maxAttempts.merge(no, c.attempts(), Math::max);
                if (!nos.contains(no) || settled.contains(no) || queued.contains(no)) continue;
                if (c.done()) toRead.add(c.id());
                else if (c.cancelled()) takeover(no, c, false, "cancelled");
                else if (c.lost(storeNow)) takeover(no, c, true, "lost");
            }
            if (!toRead.isEmpty()) collect(toRead);
        }

        /**
         * Parçayı koordinatöre alır: gerekirse iptal eder (0 satır = bu arada DONE oldu → sonraki turda toplanır), girdilerini
         * yerel ölçüm kuyruğuna koyar. İkinci görüş parçasında kaçınılan işçi bu pod ise yeni ölçüm yapılmaz — eski sonuç kalır.
         */
        private void takeover(int no, ChunkState c, boolean cancel, String reason) {
            if (settled.contains(no) || queued.contains(no)) return;
            CertChunkSpec spec = specs.get(no);
            if (spec == null) return;
            if (c != null && c.done()) return;                       // sonucu toplanacak
            if (cancel) {
                Long id = c != null ? Long.valueOf(c.id()) : idByNo.get(no);
                if (id != null) {
                    try {
                        boolean ok = store.cancel(runId, id);
                        storeOk();
                        if (!ok) return;                             // DONE oldu (ya da zaten iptal) → toplanır
                    } catch (RuntimeException e) {
                        storeError(e, monoMs.getAsLong());
                        if (!storeFailed) return;                    // sonraki turda yeniden denenir
                    }
                }
            }
            queued.add(no);
            if (spec.avoidWorker() != null && spec.avoidWorker().equals(me)) {
                settled.add(no);                                     // aykırı pod koordinatörün kendisi: eski sonuç kalır
                return;
            }
            if ("lost".equals(reason)) {
                log.info("Parça {} kayboldu ({} deneme) — koordinatör kendisi ölçüyor (runId={})", no,
                        c == null ? 0 : c.attempts(), runId);
            }
            takeoverChunks++;
            takeovers.incrementAndGet();
            localEntries.addAll(spec.entries());
            localChunks.add(no);
            chunkTally.merge(me, 1, Integer::sum);
        }

        private void collect(List<Long> ids) {
            Map<Long, String> json;
            try {
                json = store.readResults(runId, ids);
                storeOk();
            } catch (RuntimeException e) {
                storeError(e, monoMs.getAsLong());
                return;
            }
            if (json == null) return;
            for (Long id : ids) {
                String j = json.get(id);
                Integer no = noById.get(id);
                if (j == null || no == null || settled.contains(no) || queued.contains(no)) continue;
                CertChunkSpec spec = specs.get(no);
                ChunkState state = lastState.get(no);
                String worker = state == null || state.worker() == null || state.worker().isBlank() ? null : state.worker();
                boolean secondOpinion = spec.avoidWorker() != null;
                Map<Integer, ChunkCompletion.Result> byIndex = new HashMap<>();
                for (ChunkCompletion.Result r : ChunkCompletion.decodeResults(j)) byIndex.putIfAbsent(r.index(), r);
                int used = 0;
                for (CertChunkSpec.Entry e : spec.entries()) {
                    ChunkCompletion.Result r = byIndex.get(e.index());
                    if (r != null && r.usable()) {
                        put(e.index(), r.result(), worker == null ? "?" : worker);
                        used++;
                    } else if (!secondOpinion) {
                        localEntries.add(e);                         // kullanılamaz sonuç → koordinatör yeniden ölçer (L-4)
                        remeasured.incrementAndGet();
                    }                                                // ikinci görüşte kullanılamazsa eski sonuç kalır
                }
                if (used > 0) chunkTally.merge(worker == null ? "?" : worker, 1, Integer::sum);
                settled.add(no);
            }
        }

        private void put(int index, Map<String, Object> result, String worker) {
            result.putIfAbsent("run_id", runId);
            results.set(index, result);
            by[index] = worker;
        }

        /** Yerel ölçüm kuyruğunu koordinatörün iş parçacığında, klasik toplamayla ölçer. */
        private String measureLocal() {
            Map<Integer, CertChunkSpec.Entry> unique = new TreeMap<>();
            for (CertChunkSpec.Entry e : localEntries) unique.putIfAbsent(e.index(), e);
            localEntries.clear();
            List<CertChunkSpec.Entry> batch = new ArrayList<>(unique.values());
            List<Map<String, Object>> out;
            try {
                out = runner.checkDomains(CertChunkSpec.rowsOf(batch), runId);
            } catch (RuntimeException | Error e) {
                throw new RunnerFailure(e);
            }
            if (out == null || out.size() < batch.size()) return CertSweepStore.OUTCOME_SHUTDOWN;   // kapanışta erken durdu
            for (int k = 0; k < batch.size(); k++) put(batch.get(k).index(), out.get(k), me);
            settled.addAll(localChunks);
            localChunks.clear();
            return null;
        }

        // ── Aykırı pod (§2.6) ───────────────────────────────────────────────────────────────────────────────────────

        private String secondOpinion() {
            if (storeFailed) return null;
            int minErrors;
            double threshold;
            try {
                minErrors = req.minErrors().getAsInt();
                threshold = req.rateThreshold().getAsDouble();
            } catch (RuntimeException e) {
                return null;
            }
            Map<String, List<Integer>> byWorker = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) byWorker.computeIfAbsent(by[i], k -> new ArrayList<>()).add(i);
            Map<String, Integer> ne = new HashMap<>();
            int totalNe = 0;
            for (Map.Entry<String, List<Integer>> e : byWorker.entrySet()) {
                int c = 0;
                for (int i : e.getValue()) if (networkError(results.get(i))) c++;
                ne.put(e.getKey(), c);
                totalNe += c;
            }
            List<CertChunkSpec> chunks = new ArrayList<>();
            int size = spec.chunkSize();
            for (Map.Entry<String, List<Integer>> e : byWorker.entrySet()) {
                String w = e.getKey();
                if (w == null || "?".equals(w)) continue;
                int nw = e.getValue().size();
                int new_ = ne.get(w);
                int otherN = n - nw;
                int otherNe = totalNe - new_;
                if (!outlier(nw, new_, otherN, otherNe, minErrors, threshold)) continue;
                if (w.isBlank() || w.length() > CertChunkSpec.MAX_WORKER_CHARS) continue;
                List<CertChunkSpec.Entry> again = new ArrayList<>();
                for (int i : e.getValue()) if (networkError(results.get(i))) again.add(entryOf(i));
                for (int from = 0; from < again.size(); from += size) {
                    chunks.add(new CertChunkSpec(nextChunkNo++, again.subList(from, Math.min(again.size(), from + size)), w));
                }
                warnOutlier(w, nw, new_, otherN, otherNe, again.size());
                secondOpinionDomains += again.size();
                secondOpinions.addAndGet(again.size());
            }
            if (chunks.isEmpty()) return null;
            try {
                if (store.addChunks(runId, chunks) <= 0) return null;   // eklenemedi → eski sonuçlar kalır
                storeOk();
            } catch (RuntimeException e) {
                log.warn("İkinci görüş parçaları yazılamadı — mevcut sonuçlar geçerli (runId={}): {}", runId,
                        e.getClass().getSimpleName());
                return null;
            }
            Set<Integer> nos = new LinkedHashSet<>();
            for (CertChunkSpec c : chunks) {
                register(c);
                nos.add(c.chunkNo());
            }
            long start = monoMs.getAsLong();
            lastProgress = start;
            return waitFor(nos, true, start);
        }

        private CertChunkSpec.Entry entryOf(int index) {
            CertChunkSpec.Entry e = entries[index];
            if (e == null) throw new IllegalStateException("girdi yok: " + index);
            return e;
        }

        private boolean networkError(Map<String, Object> r) {
            return r != null && runner.networkErrorCount(List.of(r)) > 0;
        }

        private void warnOutlier(String w, int nw, int nev, int otherN, int otherNe, int count) {
            long now = monoMs.getAsLong();
            long last = outlierWarnAt.get();
            String x = String.format(Locale.ROOT, "%.0f", 100.0 * nev / Math.max(1, nw));
            String y = String.format(Locale.ROOT, "%.0f", 100.0 * otherNe / Math.max(1, otherN));
            if (last == Long.MIN_VALUE || now - last >= OUTLIER_WARN_EVERY_MS) {
                if (outlierWarnAt.compareAndSet(last, now)) {
                    log.warn("Sertifika taraması: {} pod'unun parçalarında ağ hatası oranı %{} (diğerleri %{}) — {} alan adı "
                            + "başka pod'dan yeniden ölçüldü; çıkış eşliğini (P1) doğrulayın", podOf(w), x, y, count);
                    return;
                }
            }
            log.info("İkinci görüş: {} pod'u ağ hatası %{} (diğerleri %{}) — {} alan adı yeniden ölçülüyor (runId={})",
                    podOf(w), x, y, count, runId);
        }

        // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────

        private List<CertSweepStatus.WorkerTally> tallies() {
            Map<String, int[]> t = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int[] a = t.computeIfAbsent(by[i] == null ? "?" : by[i], k -> new int[2]);
                a[0]++;
                if (networkError(results.get(i))) a[1]++;
            }
            List<CertSweepStatus.WorkerTally> out = new ArrayList<>();
            for (Map.Entry<String, int[]> e : t.entrySet()) {
                out.add(new CertSweepStatus.WorkerTally(e.getKey(), chunkTally.getOrDefault(e.getKey(), 0), e.getValue()[0],
                        e.getValue()[1]));
            }
            out.sort(CertSweepStatus.WorkerTally.ORDER);
            return out;
        }

        private void storeOk() {
            storeFailingSince = -1L;
        }

        private void storeError(RuntimeException e, long now) {
            if (e instanceof RunnerFailure rf) throw rf;
            if (storeFailingSince < 0) storeFailingSince = now;
            log.debug("Parçalı tarama deposu okunamadı (runId={}): {}", runId, e.getClass().getSimpleName());
            if (!storeFailed && now - storeFailingSince >= CertChunking.STORE_FAILURE_LOCAL_MS) {
                storeFailed = true;
                log.warn("Parçalı tarama deposu {} sn okunamadı — toplanmamış parçaları koordinatör ölçüyor (runId={}): {}",
                        CertChunking.STORE_FAILURE_LOCAL_MS / 1000L, runId, e.getClass().getSimpleName());
            }
        }

        private void sleep(long ms) {
            try {
                pause.sleep(ms);
            } catch (InterruptedException e) {
                interrupted = true;                                   // klasik bekleme gibi: sürer, bayrak sonunda geri konur
            }
        }
    }

    /**
     * Aykırı pod kuralı (§2.6): pod'un ağ hatası ≥ {@code minErrors} ∧ oranı ≥ eşik ∧ diğerlerinin birleşik oranı ≤ eşik / 2 ∧
     * diğerlerinin ≥ 2 × {@code minErrors} sonucu var.
     */
    static boolean outlier(int nw, int nev, int otherN, int otherNe, int minErrors, double threshold) {
        if (nw <= 0 || nev <= 0 || nev < minErrors) return false;
        if ((double) nev / nw < threshold) return false;
        if (otherN <= 0 || otherN < 2L * Math.max(0, minErrors)) return false;
        return (double) otherNe / otherN <= threshold / 2.0;
    }

    private static String fp(String state, int attempts, Long claimedMs, Long leaseUntilMs) {
        return state + '|' + attempts + '|' + claimedMs + '|' + leaseUntilMs;
    }

    private static String podOf(String worker) {
        String p = ClusterHealthStore.podOf(worker);
        return p == null ? "?" : p;
    }
}
