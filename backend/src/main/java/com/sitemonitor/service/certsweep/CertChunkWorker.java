package com.sitemonitor.service.certsweep;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.schedule.PodLiveness;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Parçalı sertifika taramasının İŞÇİSİ (2026-10-29, çok pod Sürüm 3-4 paket A; tasarım R3-4 §3.4 / §4.4 / §6.6) — her pod'da
 * (koordinatörün kendi pod'u ve tarama lideri dahil) koşunun parçalarını sahiplenir ({@link CertSweepStore#claim}), parçanın
 * alan adlarını KLASİK toplamayla bu pod'un {@code certCheckExecutor}'ında ölçer ({@link CertChunkRunner#checkDomains}, paket B)
 * ve sonuç haritalarını {@link ChunkCompletion} ile parça satırına yazar ({@link CertSweepStore#complete}). YALNIZ ÖLÇER (L-2):
 * kayıt, önbellek boşaltma, toplu kesinti kuralı, alarm — hiçbiri burada değil; denetleyici satır yazmaz, yeniden ölçülen parçanın
 * kalıcı etkisi yoktur.
 *
 * <p><b>Tembel (L-1):</b> yoklama iş parçacığı ({@value #POLL_THREAD}) ve parça koşucuları ({@value #RUNNER_PREFIX}N, ilk
 * parçada doğar, 60 sn boşta kalınca ölür) yalnız {@link CertChunking#whenActivated} başlatıcısıyla — anahtar açılıştan beri
 * kapalıysa hiçbiri yoktur, hiçbir sorgu atılmaz, kapanış / yok etme ortak süreye dokunmaz. Anahtar sonradan kapatılırsa açık
 * koşuyu bitirir (sahiplenme boş dönene dek), sonra yalnız bellek okuyarak boşta bekler.
 *
 * <p><b>Bütçe (§3.4):</b> aynı anda en çok {@code max-chunks} parça ve {@code max-inflight-domains} (0 = 2 × görev havuzunun
 * GÜNCEL çekirdeği → 40) alan adı; uçuşta parça yoksa bir parça her zaman alınabilir (bütçeden büyük parça da ölçülür). Tarama
 * lideri BAŞKA canlı pod varken ({@code PodLiveness}, bilinmiyor = yok) yalnız {@code steal-after-ms}'ten (10 sn) eski
 * parçaları ve yarı bütçeyle ({@code leader-share-pct}) alır — izleme taramaları ve erişilebilirlik turu için havuzunu korur.
 *
 * <p><b>Yoklama:</b> son sahiplenme satır döndürdüyse {@code poll-ms} (250 ms), boş döndüyse {@code idle-poll-ms} (5 sn) sonra
 * yeniden; bir parça bitince ya da koordinatör kendi pod'unun işçisini dürtünce ({@link #poke()}) beklemeden. Kiralar
 * {@link CertChunking.Tuning#renewEveryMs()}'de (15 sn) bir TEK deyimle yenilenir — yaşayan işçi uzun parçayı kaybetmez.
 *
 * <p><b>Kapanış (§6.6):</b> {@code ContextClosedEvent} ({@code HIGHEST_PRECEDENCE + 3}: kiradan, aktarıcı bayrağından ve 3-3
 * işçisinden sonra, bildirim boşaltmasından önce) yalnız bayrak kaldırır ve TUTULAN HER parçayı (uçuştakiler dahil, deneme bir
 * geri) cin yardımcı iş parçacığında ({@value #RELEASE_THREAD}) PENDING'e bırakır — BEKLEMEZ, veritabanına gitmez; başka pod
 * onları hemen alır. Uçuştaki ölçümler ölen havuzda biter ve atılır. {@code @PreDestroy} koşucuları keser ({@code shutdownNow})
 * ve yoklama iş parçacığını ortak süreden ({@code ShutdownDeadline.waitMs(500)}) birleştirir; hiç başlamadıysa ortak süreye
 * dokunmaz.
 */
@Slf4j
@Component
public class CertChunkWorker {

    /** {@link CertChunking#whenActivated} başlatıcı adı. */
    public static final String STARTER = "cert-chunk-worker";
    /** Yoklama (sahiplenme + kira yenileme) iş parçacığı — kalıcı ad. */
    public static final String POLL_THREAD = "cert-chunk-poll";
    /** Parça koşucuları {@code cert-chunk-1..max-chunks} — kalıcı ad öneki. */
    public static final String RUNNER_PREFIX = "cert-chunk-";
    /** Kapanışta parçaları bırakan cin yardımcı — kalıcı ad. */
    public static final String RELEASE_THREAD = "cert-chunk-release";
    /** Yok etmede yoklama iş parçacığının en çok beklenmesi (ms; ortak süreden düşer). */
    public static final long SHUTDOWN_JOIN_CAP_MS = 500L;
    /** Koşucu iş parçacığı bu kadar (sn) boşta kalınca ölür. */
    static final long RUNNER_IDLE_SECONDS = 60L;
    /** Tamamlama yazımının en çok deneme sayısı (veritabanı hatasında; kapanışta denenmez). */
    static final int COMPLETE_ATTEMPTS = 3;
    /** Tamamlama yeniden denemesinin taban beklemesi (ms; deneme sayısıyla çarpılır). */
    static final long COMPLETE_RETRY_MS = 500L;
    /** Uyarı günlüğü hız sınırı (ms). */
    static final long WARN_EVERY_MS = 60_000L;

    /** Tutulan (sahiplenilmiş, ölçülen) bir parça (bellek). */
    static final class Held {
        final ClaimedChunk chunk;
        /** Kapanışta bırakıldı: sonucu YAZILMAZ. */
        volatile boolean released;

        Held(ClaimedChunk chunk) {
            this.chunk = chunk;
        }
    }

    /** Tamamlama sonucu. */
    enum Outcome { COMPLETED, LATE, FAILED, DISCARDED }

    private final CertSweepStore store;
    private final CertChunking certChunking;
    private final Supplier<CertChunkRunner> runner;
    private final BooleanSupplier leaderHint;
    private final BooleanSupplier anotherLivePod;
    private final String owner;
    private final LongSupplier nanoClock;
    private final CertChunking.Tuning tuning;

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    private final Map<Long, Held> held = new ConcurrentHashMap<>();
    private final AtomicInteger inflightDomains = new AtomicInteger();
    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong late = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong failedChunks = new AtomicLong();
    private final AtomicLong domainsMeasured = new AtomicLong();
    private final AtomicLong claimErrors = new AtomicLong();
    private final AtomicLong renewErrors = new AtomicLong();
    private final AtomicLong completeErrors = new AtomicLong();
    private final AtomicLong leasesLost = new AtomicLong();
    private final AtomicLong warnAt = new AtomicLong(Long.MIN_VALUE);

    private volatile boolean started;
    private volatile boolean closing;
    private volatile boolean terminated;
    private volatile ExecutorService pool;
    private volatile Thread pollThread;
    /** Son sahiplenme boş döndü (anahtar kapalıyken sorgusuz boşta beklemenin koşulu). Başlangıçta bir kez denenir. */
    private volatile boolean lastClaimEmpty;
    /** Sonraki sahiplenme denemesinin en erken anı (ns; {@code Long.MIN_VALUE} = hemen). */
    private volatile long nextClaimNano = Long.MIN_VALUE;
    private volatile long lastRenewNano = Long.MIN_VALUE;
    /** Koordinatör dürttü ya da bir parça bitti — sonraki adım beklemeden sahiplenir. */
    private final AtomicBoolean poked = new AtomicBoolean();
    private volatile int lastBudget = -1;
    private volatile boolean lastLeaderYielding;
    private volatile int lastChunkSize;
    private volatile Long lastClaimAtMs;

    /** Spring kurucusu — işbirlikçiler tembel (sağlayıcı) çözülür; kurucu sorgu / iş parçacığı / günlük üretmez (L-1). */
    @Autowired
    public CertChunkWorker(CertSweepStore store, CertChunking certChunking, ObjectProvider<CertChunkRunner> runner,
                           ObjectProvider<SchedulerService> scheduler, ObjectProvider<PodLiveness> liveness) {
        this(store, certChunking, runner::getIfAvailable,
                () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sweepLeaderHint();
                },
                () -> {
                    PodLiveness p = liveness.getIfAvailable();
                    return p != null && p.anotherLivePod();
                },
                SchedulerService.instanceId(), System::nanoTime);
    }

    /** Test / elle kurulum. */
    public CertChunkWorker(CertSweepStore store, CertChunking certChunking, Supplier<CertChunkRunner> runner,
                           BooleanSupplier leaderHint, BooleanSupplier anotherLivePod, String owner, LongSupplier nanoClock) {
        this.store = store;
        this.certChunking = certChunking;
        this.runner = runner == null ? () -> null : runner;
        this.leaderHint = leaderHint == null ? () -> false : leaderHint;
        this.anotherLivePod = anotherLivePod == null ? () -> false : anotherLivePod;
        this.owner = owner == null ? "?" : owner;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.tuning = certChunking == null ? CertChunking.Tuning.DEFAULTS : certChunking.tuning();
    }

    /** Tembel başlatıcı + durum kaynağı kaydı — yalnız bellek (sorgu / iş parçacığı / günlük yok, L-1). */
    @PostConstruct
    public void register() {
        if (certChunking == null) return;
        certChunking.registerWorkerStatus(this::status);
        certChunking.whenActivated(STARTER, this::start);
    }

    // ── Başlatma / döngü ────────────────────────────────────────────────────────────────────────────────────────────

    /** Başlatıcı: koşucu havuzunu kurar (iş parçacıkları ilk parçada doğar) ve yoklama iş parçacığını başlatır — bir kez. */
    public synchronized void start() {
        if (started || closing) return;
        if (!tuning.workerEnabled()) {
            log.info("Parçalı sertifika taraması işçisi bu pod'da kapalı (site.monitor.checks.cert-chunks.worker.enabled=false)"
                    + " — parça almaz; kilidi kazanırsa turu yine koordine eder");
            return;
        }
        started = true;
        pool = newPool(tuning.maxChunks());
        Thread t = new Thread(this::loop, POLL_THREAD);
        t.setDaemon(true);
        pollThread = t;
        t.start();
        log.info("Parçalı sertifika taraması işçisi başladı (en çok {} parça, kira {} sn, yoklama {} / {} ms)", tuning.maxChunks(),
                tuning.leaseMs() / 1000, tuning.pollMs(), tuning.idlePollMs());
    }

    /** Test: yoklama iş parçacığı olmadan kurar ({@link #tick} elle çağrılır); {@code executor} null → gerçek koşucu havuzu. */
    synchronized void startForTest(ExecutorService executor) {
        if (started) return;
        started = true;
        pool = executor != null ? executor : newPool(tuning.maxChunks());
    }

    private static ExecutorService newPool(int size) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor ex = new ThreadPoolExecutor(size, size, RUNNER_IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread th = new Thread(r, RUNNER_PREFIX + seq.incrementAndGet());
                    th.setDaemon(true);
                    return th;
                });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /**
     * Koordinatör kendi pod'unun işçisini dürter (koşu yazıldıktan hemen sonra; bellek) — yoklama iş parçacığı beklemeden
     * sahiplenmeyi dener. Başlamamış işçide etkisiz.
     */
    public void poke() {
        if (!started) return;
        poked.set(true);
        Thread p = pollThread;
        if (p != null) LockSupport.unpark(p);
    }

    private void loop() {
        while (!terminated && !closing) {
            long sleepMs;
            try {
                sleepMs = tick();
            } catch (Throwable e) {
                warn("Parçalı tarama işçisi yoklama adımı düştü (sonraki adımda yeniden denenecek): {}", e.getClass().getSimpleName());
                sleepMs = 1_000L;
            }
            if (terminated || closing) break;
            if (poked.get()) continue;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(1L, sleepMs)));
        }
    }

    /**
     * Bir yoklama adımı: (vakti geldiyse) kira yenileme → (izin ve bütçe varsa, vakti geldiyse) sahiplenme.
     *
     * @return sonraki adıma dek uyunacak süre (ms)
     */
    long tick() {
        boolean wasPoked = poked.getAndSet(false);   // her adımda tüketilir — döngü dürtmeyle boşa dönmesin
        if (closingNow()) return tuning.idlePollMs();
        long now = nanoClock.getAsLong();
        maybeRenew(now);
        long renewCap = held.isEmpty() ? Long.MAX_VALUE : tuning.renewEveryMs();
        // Anahtar kapalı ve elde parça yok, son sahiplenme boştu: yalnız bellek okuması (sorgu yok).
        if (!(certChunking != null && certChunking.enabled()) && held.isEmpty() && lastClaimEmpty) {
            nextClaimNano = Long.MIN_VALUE;
            return tuning.idlePollMs();
        }
        if (!claimAllowed()) return Math.min(renewCap, held.isEmpty() ? tuning.idlePollMs() : tuning.pollMs());
        if (!wasPoked && nextClaimNano != Long.MIN_VALUE && now - nextClaimNano < 0) {
            return Math.min(renewCap, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nextClaimNano - now)));
        }
        int got = claimRound();
        long wait;
        if (got > 0) {
            wait = tuning.pollMs();
        } else if (got == 0) {
            wait = tuning.idlePollMs();
        } else {
            // bütçe dolu (ya da lider payı 0): bir parça bitince dürtülür; kira için uyanılır
            wait = lastClaimEmpty ? tuning.idlePollMs() : tuning.pollMs();
        }
        nextClaimNano = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(wait);
        return Math.min(renewCap, wait);
    }

    /** Sahiplenme izni (bellek): kapanmıyor, bu pod işçi, ölçme gövdesi (paket B) var ve kapanmıyor. */
    boolean claimAllowed() {
        if (closingNow() || !tuning.workerEnabled()) return false;
        CertChunkRunner r = runnerOrNull();
        if (r == null) return false;
        try {
            return !r.closing();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Bir sahiplenme turu: bütçe ve parça sınırı izin verdikçe birer parça.
     *
     * @return sahiplenilen parça sayısı; {@code -1} = denenmedi (bütçe / parça sınırı dolu ya da lider payı 0)
     */
    int claimRound() {
        CertChunkRunner r = runnerOrNull();
        if (r == null) return -1;
        int core = safeCore(r);
        boolean yielding = leaderYielding();
        int budget = yielding ? tuning.leaderBudget(core) : tuning.budget(core);
        lastBudget = budget;
        lastLeaderYielding = yielding;
        if (budget <= 0 || !canTakeMore(budget)) return -1;
        long createdBefore = yielding ? store.nowMs() - tuning.stealAfterMs() : Long.MAX_VALUE;
        int got = 0;
        // her parça öncesi yeniden: ölçüm gövdesi bu arada kapanmaya başlamış olabilir (kısa liste → atılır, yenisi alınmaz)
        while (claimAllowed() && canTakeMore(budget)) {
            Optional<ClaimedChunk> c;
            try {
                c = store.claim(owner, tuning.leaseMs(), createdBefore);
            } catch (RuntimeException e) {
                claimErrors.incrementAndGet();
                warn("Parçalı tarama parçası sahiplenilemedi (sonraki yoklamada yeniden denenecek): {}", e.getClass().getSimpleName());
                lastClaimEmpty = true;
                return got;
            }
            if (c.isEmpty()) {
                lastClaimEmpty = true;
                return got;
            }
            lastClaimEmpty = false;
            got++;
            submit(c.get());
        }
        return got;
    }

    /** Bir parça daha alınabilir mi (bellek): kapanmıyor, parça sınırı altında; uçuşta parça yoksa her zaman, varsa bütçe. */
    private boolean canTakeMore(int budget) {
        if (closingNow()) return false;
        int chunks = held.size();
        if (chunks >= tuning.maxChunks()) return false;
        if (chunks == 0) return true;
        int estimate = Math.max(tuning.chunkSize(), lastChunkSize);
        return inflightDomains.get() + estimate <= budget;
    }

    private boolean leaderYielding() {
        if (!safe(leaderHint)) return false;
        return safe(anotherLivePod);
    }

    private void submit(ClaimedChunk chunk) {
        Held h = new Held(chunk);
        held.put(chunk.id(), h);
        inflightDomains.addAndGet(chunk.domainCount());
        claimed.incrementAndGet();
        lastClaimAtMs = chunk.claimedMs();
        lastChunkSize = chunk.domainCount();
        if (closingNow()) {
            // kapanış sahiplenme sırasında başladı: kapanış dinleyicisi bu parçayı görmemiş olabilir — hemen bırak
            dropHeld(h);
            releaseNow(List.of(chunk.id()));
            return;
        }
        try {
            pool.execute(() -> runChunk(h));
        } catch (RejectedExecutionException | NullPointerException e) {
            dropHeld(h);
            releaseNow(List.of(chunk.id()));
        }
    }

    /** Koşucu gövdesi: parçayı klasik toplamayla ölçer, kapanmıyorsa sonucu yazar. */
    void runChunk(Held h) {
        ClaimedChunk chunk = h.chunk;
        Outcome outcome = Outcome.DISCARDED;
        try {
            List<Map<String, Object>> results = null;
            Throwable failure = null;
            CertChunkRunner r = runnerOrNull();
            if (!h.released && !closingNow()) {
                try {
                    if (r == null) throw new IllegalStateException("CertChunkRunner yok");
                    results = r.checkDomains(chunk.rows(), chunk.runId());
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (h.released || closingNow()) {
                discarded.incrementAndGet();
                return;
            }
            ChunkCompletion c = completion(chunk, results, failure, r);
            if (c == null) {
                discarded.incrementAndGet();
                return;
            }
            domainsMeasured.addAndGet(chunk.domainCount());
            outcome = completeWithRetry(c, h);
            switch (outcome) {
                case COMPLETED -> completed.incrementAndGet();
                case LATE -> {
                    late.incrementAndGet();
                    log.debug("Parçalı tarama parçası geç tamamlandı — kira kaybedildi ya da koşu kapandı, sonuç atıldı ({})", chunk);
                }
                case DISCARDED -> discarded.incrementAndGet();
                case FAILED -> { }
            }
        } finally {
            dropHeld(h);
            poked.set(true);   // bütçe boşaldı — yoklama beklemeden yeniden sahiplenebilir
            Thread p = pollThread;
            if (p != null) LockSupport.unpark(p);
        }
    }

    /**
     * Tamamlamayı kurar: ölçüm istisnası → her alan adı {@code failed} işaretli (koordinatör yeniden ölçer); eksik liste
     * (kapanış) → null (yazılmaz); aksi hâlde {@link ChunkCompletion#of}.
     */
    private ChunkCompletion completion(ClaimedChunk chunk, List<Map<String, Object>> results, Throwable failure,
                                       CertChunkRunner r) {
        if (failure == null && results != null && results.size() == chunk.domainCount()) {
            long ne;
            try {
                ne = r == null ? 0L : r.networkErrorCount(results);
            } catch (RuntimeException e) {
                ne = 0L;
            }
            try {
                return ChunkCompletion.of(chunk, owner, results, ne);
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (failure == null) {
            // Kısa liste: toplama kapanışla erken durdu — tamamlanmaz (kapanış zaten bırakır).
            if (closingNow() || safeClosing(r)) return null;
            failure = new IllegalStateException("eksik sonuç");
        }
        failedChunks.incrementAndGet();
        List<Map<String, Object>> marks = new ArrayList<>(chunk.domainCount());
        for (int i = 0; i < chunk.domainCount(); i++) marks.add(CheckJobRunner.failed(failure));
        return ChunkCompletion.of(chunk, owner, marks, 0L);
    }

    /** Tamamlamayı yazar; veritabanı hatasında {@value #COMPLETE_ATTEMPTS} kez (kapanışta denenmez). */
    private Outcome completeWithRetry(ChunkCompletion c, Held h) {
        for (int attempt = 1; ; attempt++) {
            if (h.released || closingNow()) return Outcome.DISCARDED;
            try {
                return store.complete(c) ? Outcome.COMPLETED : Outcome.LATE;
            } catch (RuntimeException e) {
                completeErrors.incrementAndGet();
                if (attempt >= COMPLETE_ATTEMPTS) {
                    warn("Parçalı tarama parçasının sonucu yazılamadı ({} deneme) — kira dolunca yeniden ölçülür: {}", attempt,
                            e.getClass().getSimpleName());
                    return Outcome.FAILED;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(COMPLETE_RETRY_MS * attempt));
                if (Thread.currentThread().isInterrupted()) return Outcome.DISCARDED;
            }
        }
    }

    /** Elde tutulan parçaların kiralarını (vakti geldiyse) tek deyimle yeniler. */
    void maybeRenew(long nowNano) {
        if (held.isEmpty()) return;
        long last = lastRenewNano;
        if (last != Long.MIN_VALUE && nowNano - last < TimeUnit.MILLISECONDS.toNanos(tuning.renewEveryMs())) return;
        lastRenewNano = nowNano;
        List<Long> ids = new ArrayList<>();
        for (Held h : held.values()) if (!h.released) ids.add(h.chunk.id());
        if (ids.isEmpty()) return;
        try {
            int n = store.renew(owner, ids, tuning.leaseMs());
            if (n < ids.size()) {
                leasesLost.addAndGet(ids.size() - n);
                log.debug("Parçalı tarama: {} parçanın kirası yenilenemedi (iptal edildi ya da başka işçiye geçti)", ids.size() - n);
            }
        } catch (RuntimeException e) {
            renewErrors.incrementAndGet();
            warn("Parçalı tarama parça kiraları yenilenemedi (sonraki adımda yeniden denenecek): {}", e.getClass().getSimpleName());
        }
    }

    private void dropHeld(Held h) {
        if (held.remove(h.chunk.id(), h)) inflightDomains.addAndGet(-h.chunk.domainCount());
    }

    // ── Kapanış ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Kapanış (kiradan, aktarıcı bayrağından ve 3-3 işçisinden sonra): yalnız bayrak — yeni sahiplenme yok; TUTULAN her parça
     * (uçuştakiler dahil) cin yardımcı iş parçacığında PENDING'e bırakılır. Kapanış iş parçacığı BEKLEMEZ, veritabanına gitmez.
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    public void onContextClosed(ContextClosedEvent event) {
        if (!thisContext(event == null ? null : event.getApplicationContext())) return;
        closing = true;
        if (!started) return;
        Thread poll = pollThread;
        if (poll != null) LockSupport.unpark(poll);
        List<Long> ids = takeHeldForRelease();
        if (ids.isEmpty()) return;
        Thread helper = new Thread(() -> releaseNow(ids), RELEASE_THREAD);
        helper.setDaemon(true);
        helper.start();
    }

    /** Tutulan parçaları "bırakıldı" işaretler (sonuçları yazılmaz) ve kimliklerini döner — yalnız bellek. */
    List<Long> takeHeldForRelease() {
        List<Long> ids = new ArrayList<>();
        for (Held h : held.values()) {
            if (h.released) continue;
            h.released = true;
            ids.add(h.chunk.id());
        }
        return ids;
    }

    private void releaseNow(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            int n = store.release(owner, ids);
            released.addAndGet(n);
        } catch (RuntimeException e) {
            log.debug("Parçalı tarama parçaları bırakılamadı (kiraları dolunca başka pod alır): {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Yok etme: koşucuları keser ({@code shutdownNow}; sonuçları zaten bırakılmış — yazılmaz) ve yoklama iş parçacığını ortak
     * süreden en çok {@value #SHUTDOWN_JOIN_CAP_MS} ms birleştirir ({@code join(0)} asla). Hiç başlamadıysa ortak süreye dokunmaz.
     */
    @PreDestroy
    public void destroy() {
        closing = true;
        if (!started) return;
        terminated = true;
        ExecutorService ex = pool;
        if (ex != null) ex.shutdownNow();
        Thread poll = pollThread;
        if (poll == null) return;
        LockSupport.unpark(poll);
        long budget = ShutdownDeadline.waitMs(SHUTDOWN_JOIN_CAP_MS);
        if (budget <= 0) return;
        try {
            poll.join(budget);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Okumalar ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Bu pod'un işçi sayaçları (bellek; Sistem Sağlığı / ölçümler — paket C). Asla fırlatmaz, sorgu atmaz. */
    public CertSweepStatus.Worker status() {
        int budget = lastBudget;
        if (budget < 0) {
            CertChunkRunner r = runnerOrNull();
            budget = r == null ? 0 : tuning.budget(safeCore(r));
        }
        boolean stale;
        try {
            stale = started && store != null && store.clockStale();
        } catch (RuntimeException e) {
            stale = false;
        }
        return new CertSweepStatus.Worker(tuning.workerEnabled(), started, held.size(), Math.max(0, inflightDomains.get()), budget,
                lastLeaderYielding, claimed.get(), completed.get(), late.get(), released.get(), discarded.get(),
                domainsMeasured.get(), lastClaimAtMs, stale);
    }

    /** Ölçüm istisnası yüzünden {@code failed} işaretli tamamlanan parça (bellek). */
    public long failedChunks() {
        return failedChunks.get();
    }

    /** Sahiplenme / yenileme / tamamlama veritabanı hataları (bellek). */
    public long[] errors() {
        return new long[] {claimErrors.get(), renewErrors.get(), completeErrors.get()};
    }

    /** Yenilenemeyen kira (iptal / başka işçi) sayısı (bellek). */
    public long leasesLost() {
        return leasesLost.get();
    }

    /** Test: uçuştaki parça sayısı. */
    int heldCount() {
        return held.size();
    }

    /** Test: başladı mı. */
    boolean started() {
        return started;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    private boolean closingNow() {
        if (closing || terminated) return true;
        return certChunking != null && certChunking.closing();
    }

    private CertChunkRunner runnerOrNull() {
        try {
            return runner.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean safeClosing(CertChunkRunner r) {
        if (r == null) return false;
        try {
            return r.closing();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static int safeCore(CertChunkRunner r) {
        try {
            return r.executorCoreSize();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean thisContext(ApplicationContext source) {
        return applicationContext == null || source == null || source == applicationContext;
    }

    private void warn(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        long last = warnAt.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_EVERY_MS) && warnAt.compareAndSet(last, now)) log.warn(fmt, args);
        else log.debug(fmt, args);
    }
}
