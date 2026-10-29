package com.sitemonitor.service.checks;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.ScriptedCheckerService;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Dağıtık kontrol İŞÇİSİ (2026-10-29, çok pod Sürüm 3-3 paket A; tasarım R3-3 §2.5 / §3.2 / §5.6) — her pod'da (lider dahil)
 * PENDING kontrol işlerini sahiplenir, {@link CheckJobRunner#runCheckJob} ile koşturur, sonucu {@link CheckResultCodec} ile
 * kodlayıp {@link CheckJobStore#complete} ile yazar. Değerlendirme YAPMAZ (K-2: {@code MonitoringOutageService} çağrılmaz).
 *
 * <p><b>Tembel (K-1):</b> yoklama iş parçacığı ({@value #POLL_THREAD}) ve yuva havuzu ({@value #POOL_PREFIX}N, platform iş
 * parçacıkları, ilk işte doğar) yalnız {@link DistributedChecks#whenActivated} başlatıcısıyla — anahtar açılıştan beri kapalıysa
 * hiçbiri yoktur. Her döngü adımı önce {@link DistributedChecks#anyDistributed()} (bellek) okur; kapalıyken yeni iş
 * SAHİPLENMEZ (yalnız koşmakta olan işlerin sonuçları yazılır — iş kirasıyla beklemesin, başka pod'da yeniden koşmasın).
 *
 * <p><b>Sahiplenme (U-2):</b> en çok boş yuva kadar (pod içinde kuyruk yok). Tarama lideri, türü sahiplenebilen BAŞKA canlı işçi
 * varken o türlerde yuvalarının yalnız {@link DistributedChecks.Tuning#leaderSlots()} kadarını kullanır, sonra kalan boş
 * yuvalarla {@code steal-after-ms}'ten eski bekleyen işleri alır; yalnızken (ya da türü başka kimse alamıyorken) tüm yuvaları
 * kullanır. Senaryo (k6) işleri ayrı sahiplenmede, {@code min(boş yuva, boş k6 izni)} kadar — yuvada izin beklenmez. Yoklama
 * aralığı uyarlanır: iş bulunca {@code poll-ms}, boşta ikiye katlanarak en çok {@link DistributedChecks.Tuning#MAX_IDLE_POLL_MS}.
 *
 * <p><b>Zaman aşımı:</b> her iş {@link CheckJobRow#workerTimeoutMs()} (kira − 10 sn) içinde bitmezse yoklama adımı onu
 * {@link CheckJobRunner#workerTimeout()} ile tamamlar ({@code orTimeout} eşdeğeri, ek JDK iş parçacığı yok) ve ARDINDAN gövdenin
 * iş parçacığını KESER ({@code Thread.interrupt}, inceleme QW-L5 / DA-L3) — gövde (paket B) kesilmişse geçmiş satırını yazmaz
 * ({@link #currentJobAbandoned()} de aynı soruyu kesme bayrağından bağımsız yanıtlar); takılan gövde döndüğünde sonucu atılır,
 * yuvası gövde dönene dek dolu sayılır (kesmeyi yok sayan gövde yuvayı tutmayı sürdürür). Bir işe gönderilen kesme o işin
 * sonunda temizlenir — aynı havuz iş parçacığındaki sonraki işe sızmaz. Tamamlamalar {@value #FLUSH_MS} ms'de bir toplu
 * yazılır; belirteç uyuşmazsa (kira başka işçiye geçti) sonuç düşer ({@code late}).
 *
 * <p><b>Birikim tavanı</b> (inceleme QW-L10): yazılmayı bekleyen tamamlamalar {@value #MAX_PENDING_COMPLETIONS} sonuca ya da
 * {@value #MAX_PENDING_CHARS} karaktere ulaşınca veritabanına yazılabilene dek YENİ iş SAHİPLENİLMEZ (hız sınırlı WARN) — hiçbir
 * tamamlama sessizce düşmez. Koşan yuvalar tavanın üstüne en çok {@link DistributedChecks.Tuning#MAX_SLOTS} sonuç ekleyebilir;
 * yine de sert tavan aşılırsa en eski düşer, WARN + sayaçla ({@link #backlogDropped()}).
 *
 * <p><b>Kapanış (§5.6):</b> {@code ContextClosedEvent} ({@code HIGHEST_PRECEDENCE + 2}) yalnız bayrak (yeni sahiplenme yok) +
 * sahiplenilmiş ama BAŞLAMAMIŞ işler varsa onları daemon yardımcı iş parçacığında PENDING'e bırakır — BEKLEME YOK.
 * {@code @PreDestroy} koşan kontrolleri {@code ShutdownDeadline.waitMs(3_000)} bekler (ortak yok etme süresi), sonra kesilenlerin
 * sonucunu YAZMAZ ({@code shutdownNow}; iş CLAIMED kalır, kirası dolunca başka pod yeniden koşar) ve yoklama iş parçacığının son
 * tamamlama yazımını {@code ShutdownDeadline.waitMs(1_000)} bekler.
 */
@Slf4j
@Component
public class CheckWorker {

    public static final String STARTER = "check-worker";
    public static final String POLL_THREAD = "check-worker-poll";
    public static final String POOL_PREFIX = "check-worker-";
    public static final String RELEASE_THREAD = "check-worker-release";
    /** Tamamlama toplu yazım aralığı (ms) = yoklama adımının en uzun uykusu. */
    public static final long FLUSH_MS = 200L;
    /** Yok etmede koşan kontrollerin en çok beklenmesi (ms; ortak süreden düşer). */
    public static final long SHUTDOWN_WAIT_CAP_MS = 3_000L;
    /** Yok etmede son tamamlama yazımının en çok beklenmesi (ms; ortak süreden düşer). */
    public static final long FINAL_FLUSH_CAP_MS = 1_000L;
    /** Yazılmayı bekleyen tamamlama yumuşak tavanı: ulaşılınca yeni sahiplenme durur (birikim yazılana dek). */
    static final int MAX_PENDING_COMPLETIONS = 2_000;
    /** Yazılmayı bekleyen tamamlamaların toplam karakter yumuşak tavanı (≈ 32–64 MB): ulaşılınca yeni sahiplenme durur. */
    static final long MAX_PENDING_CHARS = 32_000_000L;
    /** Tek toplu tamamlamada en çok satır. */
    static final int FLUSH_BATCH = 500;
    /**
     * İki sahiplenme arası en kısa süre (ms) — istediği kadar alan bir turdan sonra yuva boşalınca {@code poll-ms} beklenmeden
     * yeniden sahiplenilir (inceleme D2-2: pod saniyede en çok {@code slots} iş alabiliyordu); her kontrol bitişine bir deyim
     * olmasın.
     */
    static final long MIN_CLAIM_GAP_MS = 50L;
    /** Sahiplenme gecikmesi p95 penceresi. */
    static final int LAG_WINDOW = 256;

    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int RELEASED = 2;
    private static final int FINISHED = 3;

    /** Bu iş parçacığında koşan iş (yalnız gövde süresince; {@link #currentJobAbandoned()}). */
    private static final ThreadLocal<JobTask> CURRENT = new ThreadLocal<>();

    /** Bir sahiplenilmiş iş (bellek). */
    static final class JobTask {
        final CheckJobRow row;
        final long deadlineNano;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        final AtomicBoolean done = new AtomicBoolean();
        /** Gövdeyi koşturan iş parçacığı (yalnız RUNNING iken; {@code this} kilidi altında yazılır / kesilir). */
        Thread thread;
        /** İş bırakıldı: zaman aşımıyla tamamlandı ya da yok etmede kesildi — gövde sonucu / geçmiş satırı yazmamalı. */
        volatile boolean abandoned;

        JobTask(CheckJobRow row, long deadlineNano) {
            this.row = row;
            this.deadlineNano = deadlineNano;
        }
    }

    /**
     * Bu iş parçacığında koşan kontrol işi bırakıldı mı (zaman aşımı ya da yok etmede kesme) — gövde (paket B) geçmiş satırını
     * yazmadan önce sorabilir; kesme bayrağı gövdedeki bir {@code catch} tarafından temizlense de doğru yanıtlar. İşçi dışında
     * (ör. klasik tarama iş parçacığı) her zaman false.
     */
    public static boolean currentJobAbandoned() {
        JobTask t = CURRENT.get();
        return t != null && t.abandoned;
    }

    /**
     * Bu iş parçacığı şu an bir kontrol işi gövdesi koşuyor mu (yalnız işçi yuvası; klasik tarama, teyit / kurtarma zinciri ve
     * elle kontrol iş parçacıklarında false) — klasikle ortak gövdeler bırakılma korumasını yalnız işte uygular.
     */
    public static boolean inCheckJob() {
        return CURRENT.get() != null;
    }

    /** İşçi durumu (System Health / ölçümler — paket D okur; bellek). */
    public record Status(boolean started, boolean workerEnabled, boolean closing, int slots, int busy, long claimed,
                         long completed, long late, long timeouts, long failed, long released, long claimErrors,
                         long completeErrors, long droppedAtShutdown, Long claimLagP95Ms, long pollDelayMs) {}

    private final CheckJobStore store;
    private final CheckWorkerRegistry registry;
    private final DistributedChecks distributedChecks;
    private final Supplier<CheckJobRunner> runner;
    private final BooleanSupplier leaderHint;
    private final BooleanSupplier stopping;
    private final IntSupplier scriptedFreePermits;
    private final DbClock clock;
    private final String owner;
    private final LongSupplier nanoClock;
    private final DistributedChecks.Tuning tuning;

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    private final Map<Long, JobTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<CheckJobStore.Completion> pending = new ConcurrentLinkedDeque<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger scriptedNotStarted = new AtomicInteger();
    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong late = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong claimErrors = new AtomicLong();
    private final AtomicLong completeErrors = new AtomicLong();
    private final AtomicLong droppedAtShutdown = new AtomicLong();
    private final AtomicLong interrupts = new AtomicLong();
    private final AtomicLong backlogPauses = new AtomicLong();
    private final AtomicLong backlogDropped = new AtomicLong();
    /** Kuyruktaki tamamlamaların toplam {@code resultJson} karakteri (yazılınca / düşünce düşülür). */
    private final AtomicLong pendingChars = new AtomicLong();
    private volatile int maxPendingCompletions = MAX_PENDING_COMPLETIONS;
    private volatile long maxPendingChars = MAX_PENDING_CHARS;
    private final AtomicLong warnAt = new AtomicLong(Long.MIN_VALUE);
    private final long[] lags = new long[LAG_WINDOW];
    private int lagCount;
    private int lagNext;

    private volatile boolean started;
    private volatile boolean closing;
    private volatile boolean cut;
    private volatile boolean terminated;
    private volatile ExecutorService pool;
    private volatile Thread pollThread;
    private volatile long nextClaimNano = Long.MIN_VALUE;
    private volatile long pollDelayMs;
    private volatile long lastFlushNano = Long.MIN_VALUE;
    /** Son tur istediği kadar aldı (kuyrukta daha çok iş olabilir) — yuva boşalınca beklemeden sahiplen (D2-2). */
    private volatile boolean claimHungry;
    /** Son sahiplenme turundan sonra bir yuva boşaldı. */
    private volatile boolean slotFreed;
    private volatile long lastClaimNano = Long.MIN_VALUE;
    /** Bu turda bir sahiplenme istediği sınır kadar döndü (yalnız yoklama iş parçacığı). */
    private boolean roundFilled;

    /** Spring kurucusu — işbirlikçiler tembel (sağlayıcı) çözülür. */
    @Autowired
    public CheckWorker(CheckJobStore store, CheckWorkerRegistry registry, DistributedChecks distributedChecks, DbClock clock,
                       ObjectProvider<CheckJobRunner> runner, ObjectProvider<SchedulerService> scheduler,
                       ObjectProvider<ScriptedCheckerService> scripted) {
        this(store, registry, distributedChecks, runner::getIfAvailable,
                () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sweepLeaderHint();
                },
                () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sideJobsStopping();
                },
                () -> {
                    ScriptedCheckerService s = scripted.getIfAvailable();
                    return s == null ? 0 : s.freePermits();
                },
                clock, SchedulerService.instanceId(), System::nanoTime);
    }

    /** Test / elle kurulum. */
    public CheckWorker(CheckJobStore store, CheckWorkerRegistry registry, DistributedChecks distributedChecks,
                       Supplier<CheckJobRunner> runner, BooleanSupplier leaderHint, BooleanSupplier stopping,
                       IntSupplier scriptedFreePermits, DbClock clock, String owner, LongSupplier nanoClock) {
        this.store = store;
        this.registry = registry;
        this.distributedChecks = distributedChecks;
        this.runner = runner == null ? () -> null : runner;
        this.leaderHint = leaderHint == null ? () -> false : leaderHint;
        this.stopping = stopping == null ? () -> false : stopping;
        this.scriptedFreePermits = scriptedFreePermits == null ? () -> 0 : scriptedFreePermits;
        this.clock = clock == null ? DbClock.SYSTEM : clock;
        this.owner = owner == null ? "?" : owner;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.tuning = distributedChecks == null ? DistributedChecks.Tuning.DEFAULTS : distributedChecks.tuning();
        this.pollDelayMs = tuning.pollMs();
    }

    /** Tembel başlatıcı kaydı — kurucu ve bu yöntem sorgu / iş parçacığı / günlük üretmez (K-1). */
    @PostConstruct
    public void register() {
        if (distributedChecks != null) distributedChecks.whenActivated(STARTER, this::start);
    }

    // ── Başlatma / döngü ────────────────────────────────────────────────────────────────────────────────────────────

    /** Başlatıcı: yuva havuzunu kurar (iş parçacıkları ilk işte doğar) ve yoklama iş parçacığını başlatır — bir kez. */
    public synchronized void start() {
        if (started || closing) return;
        if (!tuning.workerEnabled()) {
            log.info("Dağıtık kontrol işçisi bu pod'da kapalı (site.monitor.checks.worker.enabled=false) — kontrol işi almaz; "
                    + "lider olursa sonuçları yine değerlendirir");
            return;
        }
        started = true;
        pool = newPool(tuning.slots());
        Thread t = new Thread(this::loop, POLL_THREAD);
        t.setDaemon(true);
        pollThread = t;
        t.start();
        log.info("Dağıtık kontrol işçisi başladı (yuva: {}, liderken pay: {}, yoklama: {} ms)", tuning.slots(),
                tuning.leaderSlots(), tuning.pollMs());
    }

    /** Test: yoklama iş parçacığı olmadan kurar ({@link #tick} elle çağrılır); {@code executor} null → gerçek yuva havuzu. */
    synchronized void startForTest(ExecutorService executor) {
        if (started) return;
        started = true;
        pool = executor != null ? executor : newPool(tuning.slots());
    }

    private static ExecutorService newPool(int slots) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor ex = new ThreadPoolExecutor(slots, slots, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread th = new Thread(r, POOL_PREFIX + seq.incrementAndGet());
            th.setDaemon(true);
            return th;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    private void loop() {
        while (!terminated) {
            long sleepMs;
            try {
                sleepMs = tick();
            } catch (Throwable e) {
                warn("Dağıtık kontrol işçisi yoklama adımı düştü (sonraki adımda yeniden denenecek): {}",
                        e.getClass().getSimpleName());
                sleepMs = 1_000L;
            }
            if (terminated) break;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(1L, sleepMs)));
        }
        try {
            flush();
        } catch (Throwable e) {
            log.debug("Dağıtık kontrol işçisi son tamamlama yazımı düştü: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Bir yoklama adımı: zaman aşımı taraması → (gerekiyorsa) toplu tamamlama → (vakti geldiyse ve izin varsa) sahiplenme.
     *
     * @return sonraki adıma dek uyunacak süre (ms; iş varken ≤ {@link #FLUSH_MS}, boşta ve sahiplenme kapalıyken {@code poll-ms})
     */
    long tick() {
        long now = nanoClock.getAsLong();
        sweepTimeouts(now);
        if (!pending.isEmpty() && (lastFlushNano == Long.MIN_VALUE || now - lastFlushNano >= ms(FLUSH_MS)
                || pending.size() >= FLUSH_BATCH)) {
            flush();
        }
        if (!claimAllowed()) {
            pollDelayMs = tuning.pollMs();
            nextClaimNano = Long.MIN_VALUE;
            // Koşan / yazılmayı bekleyen iş yoksa (ör. anahtar sonradan kapatıldı) seyrek uyan — yalnız bellek okuması.
            return tasks.isEmpty() && pending.isEmpty() ? Math.max(FLUSH_MS, tuning.pollMs()) : FLUSH_MS;
        }
        if (nextClaimNano != Long.MIN_VALUE && now - nextClaimNano < 0) {
            if (!(claimHungry && slotFreed)) {
                return Math.max(1L, Math.min(FLUSH_MS, TimeUnit.NANOSECONDS.toMillis(nextClaimNano - now)));
            }
            // D2-2: dolu turdan sonra yuva boşaldı — poll-ms beklenmez, yalnız en kısa aralık
            long gap = lastClaimNano == Long.MIN_VALUE ? 0L : ms(MIN_CLAIM_GAP_MS) - (now - lastClaimNano);
            if (gap > 0) return Math.max(1L, Math.min(FLUSH_MS, TimeUnit.NANOSECONDS.toMillis(gap)));
        }
        slotFreed = false;   // turdan ÖNCE: tur sırasında boşalan yuva kaybolmasın
        lastClaimNano = now;
        int got = claimRound();
        long base = tuning.pollMs();
        if (got > 0) {
            pollDelayMs = base;
        } else if (got == 0) {
            long cap = Math.max(base, DistributedChecks.Tuning.MAX_IDLE_POLL_MS);
            pollDelayMs = Math.min(cap, Math.max(base, pollDelayMs) * 2);
        }
        nextClaimNano = nanoClock.getAsLong() + ms(got > 0 || got < 0 ? base : pollDelayMs);
        // D2-2: dolu turun yuvası tur dönmeden boşaldıysa en kısa aralıkla yeniden dene
        return claimHungry && slotFreed ? MIN_CLAIM_GAP_MS : FLUSH_MS;
    }

    /** Sahiplenme izni (bellek): kapanmıyor, anahtar açık, bu pod işçi, gövde (paket B) var. */
    boolean claimAllowed() {
        if (closing || terminated || safe(stopping)) return false;
        if (distributedChecks == null || !distributedChecks.anyDistributed()) return false;
        if (!tuning.workerEnabled()) return false;
        try {
            return runner.get() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Bir sahiplenme turu.
     *
     * @return sahiplenilen iş sayısı; {@code -1} = boş yuva yoktu (deneme yapılmadı)
     */
    int claimRound() {
        int free = tuning.slots() - running.get();
        if (free <= 0) return -1;   // yuvalar dolu: claimHungry korunur (boşalınca beklemeden sahiplen)
        roundFilled = false;
        int got = claimRoundInner(free);
        // got < 0: meşgul (birikim dolu ya da liderin payı dolu) — iç tur claimHungry'yi kendisi belirler
        if (got >= 0) claimHungry = got > 0 && roundFilled;
        return got;
    }

    private int claimRoundInner(int free) {
        if (backlogFull()) {
            claimHungry = false;   // birikim yazılana dek normal yoklama
            // Sonuçlar yazılamıyor (veritabanı): birikim yazılana dek yeni iş yok — hiçbir tamamlama sessizce düşmesin.
            backlogPauses.incrementAndGet();
            warn("Dağıtık kontrol sonuçları yazılamıyor — birikim dolu ({} sonuç, {} karakter); yazılana dek yeni iş alınmıyor",
                    pending.size(), pendingChars.get());
            return -1;
        }
        Set<String> caps = registry == null ? Set.of() : registry.localCapabilities();
        if (caps.isEmpty()) return 0;
        try {
            if (!safe(leaderHint)) return claimInto(caps, free, CheckJobStore.ANY_AGE);
            Set<String> solo = new LinkedHashSet<>();
            Set<String> shared = new LinkedHashSet<>();
            for (String t : caps) {
                if (registry.liveOtherWorkers(t) >= 1) shared.add(t);
                else solo.add(t);
            }
            int got = 0;
            if (!solo.isEmpty()) got += claimInto(solo, free, CheckJobStore.ANY_AGE);
            if (!shared.isEmpty()) {
                int normal = Math.min(tuning.slots() - running.get(), Math.max(0, tuning.leaderSlots() - running.get()));
                if (normal > 0) got += claimInto(shared, normal, CheckJobStore.ANY_AGE);
                int rest = tuning.slots() - running.get();
                if (rest > 0) got += claimInto(shared, rest, clock.nowMs() - tuning.stealAfterMs());
                // D2-2: payı dolu lider boşta değil, meşgul — yoklama uzamaz, son dolu turun "aç" bilgisi korunur
                if (got == 0 && normal <= 0) return -1;
            }
            return got;
        } catch (RuntimeException e) {
            claimErrors.incrementAndGet();
            warn("Dağıtık kontrol işleri sahiplenilemedi (sonraki yoklamada yeniden denenecek): {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Türleri sahiplenir (senaryo ayrı, izinle sınırlı) ve hemen yuvalara verir. */
    private int claimInto(Set<String> types, int limit, long createdBefore) {
        if (limit <= 0) return 0;
        Set<String> plain = new LinkedHashSet<>(types);
        boolean scripted = plain.remove(CheckJobTypes.SCRIPTED);
        int got = 0;
        if (!plain.isEmpty()) got += submitAll(store.claim(plain, limit, owner, createdBefore));
        if (scripted) {
            int permits = Math.max(0, safeInt(scriptedFreePermits) - scriptedNotStarted.get());
            int lim = Math.min(limit - got, permits);
            if (lim > 0) got += submitAll(store.claim(Set.of(CheckJobTypes.SCRIPTED), lim, owner, createdBefore));
        }
        if (got >= limit) roundFilled = true;   // istenen kadar geldi: kuyrukta daha çok iş olabilir (D2-2)
        return got;
    }

    private int submitAll(List<CheckJobRow> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        if (closing || terminated) {
            releaseNow(rows.stream().map(CheckJobRow::id).toList());
            return 0;
        }
        int n = 0;
        for (CheckJobRow row : rows) {
            claimed.incrementAndGet();
            recordLag(row.claimLagMs());
            JobTask t = new JobTask(row, nanoClock.getAsLong() + ms(row.workerTimeoutMs()));
            running.incrementAndGet();
            if (CheckJobTypes.SCRIPTED.equals(row.type())) scriptedNotStarted.incrementAndGet();
            tasks.put(row.id(), t);
            try {
                pool.execute(() -> runTask(t));
                n++;
            } catch (RejectedExecutionException e) {
                abandonQueued(t);
                releaseNow(List.of(row.id()));
            }
        }
        return n;
    }

    /** Yuva gövdesi: işi koşturur, sonucu (zaman aşımı ya da kesilme yoksa) tamamlama kuyruğuna koyar. */
    void runTask(JobTask t) {
        synchronized (t) {
            if (!t.state.compareAndSet(QUEUED, RUNNING)) return;   // kapanışta bırakıldı (sayaçları bırakan düştü)
            t.thread = Thread.currentThread();
        }
        if (CheckJobTypes.SCRIPTED.equals(t.row.type())) scriptedNotStarted.decrementAndGet();
        Map<String, Object> result;
        CURRENT.set(t);
        try {
            CheckJobRunner r = runner.get();
            result = r == null ? CheckJobRunner.failed(new IllegalStateException()) : r.runCheckJob(t.row.type(), t.row.monitorId());
        } catch (Throwable e) {
            result = CheckJobRunner.failed(e);
        } finally {
            CURRENT.remove();
            synchronized (t) {
                // Kesme yalnız bu kilit altında ve RUNNING iken gönderilir: FINISHED'dan sonra gelemez → temizlik güvenli,
                // bu işe gönderilen kesme aynı havuz iş parçacığındaki SONRAKİ işe sızmaz.
                t.thread = null;
                t.state.set(FINISHED);
                Thread.interrupted();
            }
            running.decrementAndGet();
            tasks.remove(t.row.id(), t);
            // D2-2: tur başladıktan sonra yuva boşaldı (tur dönmeden biten hızlı kontrol de sayılır — uyandırma kaybolmasın);
            // dolu turdan sonraysa yoklama iş parçacığı poll-ms beklemeden sahiplensin
            slotFreed = true;
            if (claimHungry) {
                Thread p = pollThread;
                if (p != null) LockSupport.unpark(p);
            }
        }
        if (!t.done.compareAndSet(false, true)) return;   // zaman aşımıyla zaten tamamlandı — geç gövde sonucu atılır
        if (cut) {
            droppedAtShutdown.incrementAndGet();
            return;
        }
        if (result != null && result.get(CheckJobRunner.KEY_FAILED) != null) failed.incrementAndGet();
        enqueue(t.row, result);
    }

    /**
     * Süresi dolan işleri {@code worker_timeout} ile tamamlar, SONRA gövdenin iş parçacığını keser (yuva gövde dönünce
     * boşalır; kesmeyi yok sayan gövde yuvayı tutar).
     */
    void sweepTimeouts(long nowNano) {
        if (tasks.isEmpty()) return;
        for (JobTask t : tasks.values()) {
            if (t.state.get() != RUNNING || nowNano - t.deadlineNano < 0) continue;
            if (!t.done.compareAndSet(false, true)) continue;
            timeouts.incrementAndGet();
            t.abandoned = true;
            if (!cut) enqueue(t.row, CheckJobRunner.workerTimeout());
            interruptBody(t);
        }
    }

    /** Hâlâ koşan gövdenin iş parçacığını keser (yalnız RUNNING iken, iş kilidi altında — başka işe gitmez). */
    private void interruptBody(JobTask t) {
        synchronized (t) {
            Thread th = t.thread;
            if (t.state.get() != RUNNING || th == null) return;
            th.interrupt();
        }
        interrupts.incrementAndGet();
    }

    /** Birikim yumuşak tavanında mı (sayı ya da karakter). */
    boolean backlogFull() {
        return pending.size() >= maxPendingCompletions || pendingChars.get() >= maxPendingChars;
    }

    private void enqueue(CheckJobRow row, Map<String, Object> result) {
        CheckResultCodec.Encoded e = CheckResultCodec.encode(result);
        CheckJobStore.Completion c = new CheckJobStore.Completion(row.id(), row.claimToken(), e.json(), e.truncated(), owner);
        pending.addLast(c);
        pendingChars.addAndGet(chars(c));
        // Sert tavan: sahiplenme yumuşak tavanda durduğundan koşan yuvalar en çok MAX_SLOTS sonuç ekleyebilir — buraya varmak
        // beklenmez; varılırsa en eski düşer, SESSİZ DEĞİL (WARN + sayaç; işin kirası dolunca başka pod yeniden koşar).
        long extraChars = (long) DistributedChecks.Tuning.MAX_SLOTS * CheckResultCodec.MAX_RESULT_CHARS;
        long softChars = maxPendingChars;
        long hardChars = softChars > Long.MAX_VALUE - extraChars ? Long.MAX_VALUE : softChars + extraChars;
        int softCount = maxPendingCompletions;
        int hardCount = softCount > Integer.MAX_VALUE - DistributedChecks.Tuning.MAX_SLOTS ? Integer.MAX_VALUE
                : softCount + DistributedChecks.Tuning.MAX_SLOTS;
        while (pending.size() > hardCount || pendingChars.get() > hardChars) {
            CheckJobStore.Completion old = pending.pollFirst();
            if (old == null) break;
            pendingChars.addAndGet(-chars(old));
            late.incrementAndGet();
            backlogDropped.incrementAndGet();
            warn("Dağıtık kontrol sonuç birikimi sert tavanı aştı — en eski sonuç düşürüldü (iş {}; kirası dolunca yeniden koşar)",
                    old.id());
        }
    }

    private static long chars(CheckJobStore.Completion c) {
        return c.resultJson() == null ? 0L : c.resultJson().length();
    }

    /** Bekleyen tamamlamaları toplu yazar; veritabanı hatasında satırlar sıraya geri konur (sonraki adım). */
    void flush() {
        lastFlushNano = nanoClock.getAsLong();
        while (!pending.isEmpty()) {
            List<CheckJobStore.Completion> batch = new ArrayList<>(Math.min(FLUSH_BATCH, pending.size()));
            CheckJobStore.Completion c;
            while (batch.size() < FLUSH_BATCH && (c = pending.pollFirst()) != null) batch.add(c);
            if (batch.isEmpty()) return;
            boolean[] ok;
            try {
                ok = store.complete(batch);
            } catch (RuntimeException e) {
                completeErrors.incrementAndGet();
                for (int i = batch.size() - 1; i >= 0; i--) pending.addFirst(batch.get(i));   // karakter sayacı değişmez
                warn("Dağıtık kontrol sonuçları yazılamadı (sonraki adımda yeniden denenecek): {}", e.getClass().getSimpleName());
                return;
            }
            for (CheckJobStore.Completion done : batch) pendingChars.addAndGet(-chars(done));
            for (int i = 0; i < batch.size(); i++) {
                if (ok != null && i < ok.length && ok[i]) {
                    completed.incrementAndGet();
                } else {
                    late.incrementAndGet();
                    log.debug("Dağıtık kontrol sonucu geç kaldı — kira başka işçiye geçmiş, sonuç atıldı (iş {})", batch.get(i).id());
                }
            }
        }
    }

    // ── Kapanış ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Kapanış (kira bırakıldıktan sonra): yalnız bayrak — yeni sahiplenme yok; sahiplenilmiş ama başlamamış iş varsa daemon
     * yardımcı iş parçacığında PENDING'e bırakılır. Kapanış iş parçacığı BEKLEMEZ, veritabanına gitmez.
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public void onContextClosed(ContextClosedEvent event) {
        if (!thisContext(event == null ? null : event.getApplicationContext())) return;
        closing = true;
        if (!started) return;
        Thread poll = pollThread;
        if (poll != null) LockSupport.unpark(poll);
        List<Long> notStarted = takeNotStarted();
        if (notStarted.isEmpty()) return;
        Thread helper = new Thread(() -> releaseNow(notStarted), RELEASE_THREAD);
        helper.setDaemon(true);
        helper.start();
    }

    /** Başlamamış işleri "bırakıldı" işaretler (artık başlamazlar) ve kimliklerini döner — yalnız bellek. */
    List<Long> takeNotStarted() {
        List<Long> ids = new ArrayList<>();
        for (JobTask t : tasks.values()) {
            if (t.state.compareAndSet(QUEUED, RELEASED)) {
                abandonQueued(t);
                ids.add(t.row.id());
            }
        }
        return ids;
    }

    private void abandonQueued(JobTask t) {
        if (tasks.remove(t.row.id(), t)) {
            running.decrementAndGet();
            if (CheckJobTypes.SCRIPTED.equals(t.row.type())) scriptedNotStarted.decrementAndGet();
        }
        t.state.set(RELEASED);
        t.done.set(true);
    }

    private void releaseNow(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            int n = store.release(owner, ids);
            released.addAndGet(n);
        } catch (RuntimeException e) {
            log.debug("Dağıtık kontrol işleri bırakılamadı (kiraları dolunca yeniden koşar): {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Yok etme: koşan kontrolleri ortak süreyle bekle ({@code ShutdownDeadline.waitMs(3_000)}), sonra kes ({@code shutdownNow}
     * — kesilenlerin sonucu YAZILMAZ: iş CLAIMED kalır, kirası dolunca başka pod yeniden koşar) ve yoklama iş parçacığının son
     * tamamlama yazımını {@code ShutdownDeadline.waitMs(1_000)} bekle (0 ise beklenmez).
     */
    @PreDestroy
    public void destroy() {
        closing = true;
        ExecutorService ex = pool;
        if (!started || ex == null) return;
        ex.shutdown();
        try {
            long wait = ShutdownDeadline.waitMs(SHUTDOWN_WAIT_CAP_MS);
            if (wait > 0) ex.awaitTermination(wait, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cut = true;
        for (JobTask t : tasks.values()) t.abandoned = true;   // kesilen gövde geçmiş satırını yazmasın
        ex.shutdownNow();
        terminated = true;
        Thread poll = pollThread;
        if (poll == null) return;
        LockSupport.unpark(poll);
        long join = ShutdownDeadline.waitMs(FINAL_FLUSH_CAP_MS);
        if (join <= 0) return;
        try {
            poll.join(join);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Okumalar ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Kayıt satırına giden yerel sayılar. */
    public CheckWorkerRegistryImpl.LocalStats localStats() {
        return new CheckWorkerRegistryImpl.LocalStats(tuning.slots(), Math.max(0, running.get()), completed.get(), late.get(),
                claimLagP95());
    }

    /** Durum (bellek). */
    public Status status() {
        return new Status(started, tuning.workerEnabled(), closing, tuning.slots(), Math.max(0, running.get()), claimed.get(),
                completed.get(), late.get(), timeouts.get(), failed.get(), released.get(), claimErrors.get(),
                completeErrors.get(), droppedAtShutdown.get(), claimLagP95(), pollDelayMs);
    }

    /** Son {@value #LAG_WINDOW} sahiplenmenin gecikme p95'i (ms; yoksa null). */
    public synchronized Long claimLagP95() {
        if (lagCount == 0) return null;
        long[] copy = Arrays.copyOf(lags, lagCount);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(0.95 * lagCount) - 1;
        return copy[Math.max(0, Math.min(lagCount - 1, idx))];
    }

    private synchronized void recordLag(long lag) {
        lags[lagNext] = Math.max(0L, lag);
        lagNext = (lagNext + 1) % LAG_WINDOW;
        if (lagCount < LAG_WINDOW) lagCount++;
    }

    /** Açılıştan beri zaman aşımında kesilen gövde sayısı (bellek; System Health / ölçümler — paket D). */
    public long interrupts() {
        return interrupts.get();
    }

    /** Birikim tavanı yüzünden atlanan sahiplenme turu (bellek). */
    public long backlogPauses() {
        return backlogPauses.get();
    }

    /** Sert tavanda düşen tamamlama (bellek; 0 beklenir). */
    public long backlogDropped() {
        return backlogDropped.get();
    }

    /** Yazılmayı bekleyen tamamlamaların toplam karakteri (bellek). */
    public long pendingChars() {
        return pendingChars.get();
    }

    /** Test: birikim yumuşak tavanları. */
    void backlogLimits(int maxCompletions, long maxChars) {
        this.maxPendingCompletions = Math.max(1, maxCompletions);
        this.maxPendingChars = Math.max(1L, maxChars);
    }

    /** Test: bekleyen tamamlama sayısı. */
    int pendingCompletions() {
        return pending.size();
    }

    /** Test: koşan iş sayısı. */
    int running() {
        return running.get();
    }

    /** Test: sonraki sahiplenme gecikmesi (ms). */
    long pollDelayMs() {
        return pollDelayMs;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int safeInt(IntSupplier s) {
        try {
            return s.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean thisContext(ApplicationContext source) {
        return applicationContext == null || source == null || source == applicationContext;
    }

    private void warn(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        long last = warnAt.get();
        if ((last == Long.MIN_VALUE || now - last >= 60_000L) && warnAt.compareAndSet(last, now)) log.warn(fmt, args);
        else log.debug(fmt, args);
    }
}
