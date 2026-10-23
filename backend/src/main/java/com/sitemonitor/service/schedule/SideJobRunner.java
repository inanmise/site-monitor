package com.sitemonitor.service.schedule;

import com.sitemonitor.config.ShutdownDeadline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Pod başına yan iş koşucusu (2026-10-23, çok pod Sürüm 3-1 3e; tasarım R3-1 §3.2 / §3.4).
 *
 * <p><b>Neden.</b> 65 {@code @Scheduled} iş her pod'da TEK 8 iş parçacıklı havuzu paylaşır; uzun işler (saatlik / bayat
 * sertifika taraması, gece temizliği, raporlar, TLS profili) havuz iş parçacığını dakikalarca–saatlerce tutar ve tarama
 * liderinin izleme tıklarını geciktirir. Bu koşucu uzun işlerin GÖVDESİNİ ayrı, küçük bir havuzda
 * ({@code site.monitor.scheduler.side-jobs.threads}, vars. 3; açılışta okunur) koşturur ve liderin ertelediği cron
 * denemelerini zamanlar. Gövdeler kilit / tur / öğe sahiplenme çağrılarını AYNEN taşır — yalnız iş parçacığı değişir.
 *
 * <p><b>İş başına birleştirme:</b> aynı adlı işten en çok BİR örnek kuyrukta-ya-da-koşuyor olabilir; Spring iş sürerken sabit
 * gecikmeli / cron tıklarını tetiklemeye devam eder — ikinci gönderim atlanır (sayılır; saatte bir günlük). Kilit zaten
 * kümede ikinci koşuyu engelliyordu; birleştirme yerelde boşa kilit denemesini önler.
 *
 * <p><b>Kapanış:</b> {@code ContextClosedEvent} → yeni iş kabul edilmez, kuyrukta bekleyen başlamaz ({@link #accepting()} =
 * false). {@link #stopAndWait()} ({@code SchedulerService.onShutdown}, iş kilitleri bırakılmadan ÖNCE; ayrıca kendi
 * {@code @PreDestroy}'u) ertelenmiş denemeleri iptal eder, koşanları ortak yok etme süresi içinde
 * ({@link ShutdownDeadline#waitMs}, tavan {@value #SHUTDOWN_WAIT_CAP_MS} ms) bekler, sonra keser (interrupt).
 *
 * <p><b>Satır içi kip</b> ({@code threads = 0}, testler / elle kurulum): hiçbir şey kuyruğa alınmaz, ertelenmez —
 * {@code SchedulerService.offloadSideJob} false döner ve gövde çağıranın iş parçacığında koşar (bugünkü davranış).
 *
 * <p>{@link #status()} veritabanı okumaz, hiç fırlatmaz (Sistem Sağlığı {@code data.side_jobs}, paket B).
 */
@Slf4j
@Component
public class SideJobRunner {

    /** İş durumları ({@link Status.Job#state()}) — kalıcı (ekran / ölçü etiketi). */
    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_IDLE = "idle";

    /** Son sonuçlar ({@link Status.Job#lastOutcome()}) — kalıcı. */
    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_FAILED = "failed";
    /** Ertelenen denemede iş zaten yapılmıştı (tur başka pod'da alındı / {@code alreadyDone}) — gövde koşmadı. */
    public static final String OUTCOME_SKIPPED = "skipped";
    /** Kapanışta kesildi. */
    public static final String OUTCOME_INTERRUPTED = "interrupted";

    /** Kapanışta koşan işleri bekleme tavanı (ms) — ortak yok etme süresinden düşer. */
    public static final long SHUTDOWN_WAIT_CAP_MS = 5_000L;
    /** Kesilen (interrupt) işin adımını bırakması için ek bekleme tavanı (ms) — yine ortak yok etme süresinden düşer. */
    public static final long INTERRUPT_UNWIND_CAP_MS = 1_000L;
    /** İş parçacığı sayısının üst sınırı. */
    public static final int MAX_THREADS = 16;
    /** Birleştirme / kabul edilmeme günlüğünün iş başına en sık aralığı (ms). */
    static final long SKIP_LOG_INTERVAL_MS = 60L * 60 * 1000;

    /**
     * Bu pod'un yan iş durumu (asla fırlatmaz, veritabanı okumaz — canlı pod gözlemi önbellekten).
     *
     * @param threads            koşucu iş parçacığı sayısı (0 = satır içi koşu: testler / elle kurulum)
     * @param accepting          yeni iş kabul ediyor mu (kapanışta false)
     * @param leaderYieldEnabled lider erteleme / tur bırakma kuralları açık mı
     *                           ({@code site.monitor.scheduler.leader-yield.enabled})
     * @param leaderHint         bu pod tarama lideri mi ({@code SchedulerService.sweepLeaderHint()})
     * @param otherLivePod       başka canlı pod görüldü mü — null = bilinmiyor (okunamadı; erteleme YAPILMAZ)
     * @param jobs               iş başına satır (ad sırasıyla); hiç koşmamış iş listede olmayabilir
     */
    public record Status(int threads, boolean accepting, boolean leaderYieldEnabled, boolean leaderHint,
                         Boolean otherLivePod, List<Job> jobs) {

        public Status {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
        }

        /**
         * Bir işin durumu (zamanlar epoch ms; null = hiç).
         *
         * @param name             {@link SideJob#name()}
         * @param state            {@link #STATE_QUEUED} | {@link #STATE_RUNNING} | {@link #STATE_IDLE}
         * @param startedAtMs      süren / son koşunun başlangıcı
         * @param lastFinishedAtMs son koşunun bitişi
         * @param lastOutcome      {@link #OUTCOME_OK} | {@link #OUTCOME_FAILED} | {@link #OUTCOME_SKIPPED} |
         *                         {@link #OUTCOME_INTERRUPTED}; null = hiç bitmedi
         * @param runs             koşturulan gövde sayısı (birikimli, açılıştan beri)
         * @param deferredCount    lider olarak ertelenen tetik sayısı
         * @param yieldedCount     lider olarak başka pod'a bırakılan tur sayısı
         * @param skippedCount     atlanan (zaten yapılmış / birleştirilmiş / kabul edilmemiş) tetik sayısı
         */
        public record Job(String name, String state, Long startedAtMs, Long lastFinishedAtMs, String lastOutcome,
                          long runs, long deferredCount, long yieldedCount, long skippedCount) {}
    }

    /** Bir işin pod içi durumu. */
    private static final class JobState {
        final String name;
        /** Kuyrukta ya da koşuyor (birleştirme kapısı). */
        final AtomicBoolean busy = new AtomicBoolean();
        volatile String state = STATE_IDLE;
        volatile Long startedAt;
        volatile Long lastFinishedAt;
        volatile String lastOutcome;
        final AtomicLong runs = new AtomicLong();
        final AtomicLong deferred = new AtomicLong();
        final AtomicLong yielded = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong skipLogAt = new AtomicLong(Long.MIN_VALUE);
        /** Ertelenmiş (henüz başlamamış) deneme — {@link #runDeferredNow()} ve kapanış iptali için. */
        volatile Pending pending;

        JobState(String name) {
            this.name = name;
        }
    }

    /** Ertelenmiş deneme: zamanlanmış gelecek ve koşacak sarmalayıcı. */
    private record Pending(ScheduledFuture<?> future, Runnable task) {}

    private final int threads;
    private final ScheduledThreadPoolExecutor executor;
    private final LongSupplier clock;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private volatile boolean accepting = true;
    private volatile boolean stopped = false;
    private final AtomicBoolean stopCalled = new AtomicBoolean();
    private volatile SideJobPlacement placement;
    private volatile BooleanSupplier leaderHint = () -> false;

    /** {@code ContextClosedEvent}'in BU bağlamdan geldiğini ayırt etmek için (alt bağlamın kapanışı koşucuyu kapatmasın). */
    @Autowired(required = false)
    private ApplicationContext applicationContext;

    /** Satır içi koşucu (P0 iskeletiyle uyumlu; testler / elle kurulum). */
    public SideJobRunner() {
        this(0, System::currentTimeMillis);
    }

    @Autowired
    public SideJobRunner(@Value("${site.monitor.scheduler.side-jobs.threads:3}") int threads) {
        this(threads, System::currentTimeMillis);
    }

    /** Testler: iş parçacığı sayısı ve (durum zaman damgaları için) saat. */
    public SideJobRunner(int threads, LongSupplier clock) {
        this.threads = Math.max(0, Math.min(MAX_THREADS, threads));
        this.clock = clock == null ? System::currentTimeMillis : clock;
        if (this.threads > 0) {
            AtomicInteger seq = new AtomicInteger();
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "side-job-" + seq.incrementAndGet());
                t.setDaemon(false);
                return t;
            };
            ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(this.threads, tf);
            ex.setRemoveOnCancelPolicy(true);
            ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);   // kapanışta ertelenmiş denemeler iptal
            ex.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            this.executor = ex;
        } else {
            this.executor = null;
        }
    }

    /** Satır içi koşucu (fabrika). */
    public static SideJobRunner inline() {
        return new SideJobRunner();
    }

    /** Yerleşim kuralları (durum satırı için) — isteğe bağlı. */
    @Autowired(required = false)
    public void setPlacement(SideJobPlacement placement) {
        this.placement = placement;
    }

    /** Tarama liderliği ipucu ({@code SchedulerService.sweepLeaderHint}) — durum satırı için; {@code SchedulerService} bağlar. */
    public void bindLeaderHint(BooleanSupplier hint) {
        this.leaderHint = hint == null ? () -> false : hint;
    }

    public int threads() {
        return threads;
    }

    /** Satır içi kip mi (kuyruk / erteleme yok). */
    public boolean isInline() {
        return executor == null;
    }

    /** Yeni iş kabul ediyor mu (kapanışta false). */
    public boolean accepting() {
        return accepting && !stopped;
    }

    /** Erteleme / kuyruğa alma mümkün mü (satır içi değil ve kabul ediyor). */
    public boolean canOffload() {
        return executor != null && accepting();
    }

    /**
     * Gövdeyi koşucuya gönderir (hemen, sıradaki boş iş parçacığında). Aynı adlı iş kuyrukta / koşuyorsa atlanır.
     *
     * @return kabul edildi mi (false: birleştirildi, kapanıyor ya da satır içi kip)
     */
    public boolean submit(String name, Runnable body) {
        return enqueue(name, body, null, 0L, false);
    }

    /**
     * Liderin ertelediği cron denemesi: {@code delayMs} sonra, {@code alreadyDone} true ise gövde koşmaz (atlandı), değilse
     * gövde koşar. Kabul edilirse ertelenen sayacı artar.
     *
     * @return kabul edildi mi (false: birleştirildi, kapanıyor ya da satır içi kip)
     */
    public boolean defer(String name, long delayMs, BooleanSupplier alreadyDone, Runnable body) {
        boolean ok = enqueue(name, body, alreadyDone, Math.max(0L, delayMs), true);
        if (ok) state(name).deferred.incrementAndGet();
        return ok;
    }

    /** Lider olarak başka pod'a bırakılan tur (yalnız sayaç). */
    public void noteYielded(String name) {
        if (name != null) state(name).yielded.incrementAndGet();
    }

    /** Atlanan tetik (yalnız sayaç). */
    public void noteSkipped(String name) {
        if (name != null) state(name).skipped.incrementAndGet();
    }

    private boolean enqueue(String name, Runnable body, BooleanSupplier alreadyDone, long delayMs, boolean deferred) {
        if (name == null || body == null) return false;
        if (executor == null) return false;
        JobState st = state(name);
        if (!accepting()) {
            st.skipped.incrementAndGet();
            log.debug("Yan iş '{}' kabul edilmedi — pod kapanıyor", name);
            return false;
        }
        if (!st.busy.compareAndSet(false, true)) {
            st.skipped.incrementAndGet();
            logSkip(st, "Yan iş '{}' zaten kuyrukta / koşuyor — bu tetik birleştirildi (atlandı)");
            return false;
        }
        st.state = STATE_QUEUED;
        Runnable task = () -> runJob(st, body, alreadyDone);
        try {
            if (deferred) {
                ScheduledFuture<?> f = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                st.pending = new Pending(f, task);
            } else {
                executor.execute(task);
            }
            return true;
        } catch (RejectedExecutionException e) {
            st.state = STATE_IDLE;
            st.busy.set(false);
            st.skipped.incrementAndGet();
            log.debug("Yan iş '{}' kabul edilmedi (koşucu kapalı): {}", name, e.toString());
            return false;
        }
    }

    private void runJob(JobState st, Runnable body, BooleanSupplier alreadyDone) {
        st.pending = null;
        try {
            if (!accepting()) {                                   // ContextClosedEvent sonrası: kuyruktaki iş başlamaz
                st.skipped.incrementAndGet();
                log.debug("Yan iş '{}' başlatılmadı — pod kapanıyor", st.name);
                return;
            }
            if (alreadyDone != null && safe(alreadyDone, st.name)) {
                st.skipped.incrementAndGet();
                st.lastOutcome = OUTCOME_SKIPPED;
                st.lastFinishedAt = clock.getAsLong();
                log.debug("Yan iş '{}' — ertelenen denemede iş başka pod'da yapılmış, gövde koşmadı", st.name);
                return;
            }
            st.state = STATE_RUNNING;
            st.startedAt = clock.getAsLong();
            String outcome = OUTCOME_OK;
            try {
                body.run();
                if (Thread.currentThread().isInterrupted() && stopped) outcome = OUTCOME_INTERRUPTED;
            } catch (Throwable t) {
                if (stopped || Thread.currentThread().isInterrupted()) {
                    outcome = OUTCOME_INTERRUPTED;
                    log.info("Yan iş '{}' kapanışta kesildi: {}", st.name, t.toString());
                } else {
                    outcome = OUTCOME_FAILED;
                    log.warn("Yan iş '{}' başarısız: {}", st.name, t.toString(), t);
                }
            }
            st.runs.incrementAndGet();
            st.lastOutcome = outcome;
            st.lastFinishedAt = clock.getAsLong();
        } finally {
            st.state = STATE_IDLE;
            st.busy.set(false);
        }
    }

    /** {@code alreadyDone} hiç fırlatmaz: okunamazsa "yapılmadı" (gövde koşar — kendi kilidi / turu karar verir). */
    private static boolean safe(BooleanSupplier s, String name) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("Yan iş '{}' ön denetimi okunamadı — gövde koşuyor (kilit / tur karar verir): {}", name, e.toString());
            return false;
        }
    }

    private JobState state(String name) {
        return jobs.computeIfAbsent(name, JobState::new);
    }

    private void logSkip(JobState st, String message) {
        long now = System.currentTimeMillis();
        long last = st.skipLogAt.get();
        if ((last == Long.MIN_VALUE || now - last >= SKIP_LOG_INTERVAL_MS) && st.skipLogAt.compareAndSet(last, now)) {
            log.debug(message, st.name);
        } else {
            log.trace(message, st.name);
        }
    }

    /**
     * Ertelenmiş (henüz başlamamış) denemeleri ŞİMDİ, çağıranın iş parçacığında koşturur — test dikişi (gerçek 20 sn
     * beklenmeden sıra belirlenimci olsun). Zamanlayıcısı zaten başlamış deneme dokunulmaz.
     *
     * @return koşturulan deneme sayısı
     */
    public int runDeferredNow() {
        int n = 0;
        for (JobState st : jobs.values()) {
            Pending p = st.pending;
            if (p != null && p.future().cancel(false)) {
                p.task().run();
                n++;
            }
        }
        return n;
    }

    /**
     * Kuyrukta ya da koşan iş kalmayana dek bekler (en çok {@code timeoutMs}) — test dikişi. Ertelenmiş denemeler de "meşgul"
     * sayılır; önce {@link #runDeferredNow()} çağrılmalı.
     *
     * @return süre içinde boşaldı mı
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            boolean busy = false;
            for (JobState st : jobs.values()) {
                if (st.busy.get()) { busy = true; break; }
            }
            if (!busy) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Kapanışın İLK anı: yeni iş kabul edilmez, kuyruktaki iş başlamaz (koşanlar adımını bitirir). Yalnız BU bağlam. */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        if (applicationContext != null && event != null && event.getApplicationContext() != applicationContext) return;
        accepting = false;
    }

    /**
     * Koşucuyu durdurur (idempotent): kabul kesilir, ertelenmiş denemeler iptal edilir, koşan işler ortak yok etme süresi
     * içinde ({@code ShutdownDeadline.waitMs(SHUTDOWN_WAIT_CAP_MS)}) beklenir, sonra kesilir (interrupt). Çağıran
     * ({@code SchedulerService.onShutdown}) iş kilitlerini BUNDAN SONRA bırakır.
     */
    public void stopAndWait() {
        accepting = false;
        if (stopCalled.getAndSet(true)) return;                  // idempotent: @PreDestroy yedeği ortak süreyi yeniden harcamaz
        stopped = true;
        ScheduledThreadPoolExecutor ex = executor;
        if (ex == null || ex.isTerminated()) return;
        ex.shutdown();
        long waitMs = ShutdownDeadline.waitMs(SHUTDOWN_WAIT_CAP_MS);
        try {
            if (waitMs > 0 && ex.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Runnable> dropped = ex.shutdownNow();
        List<String> running = new ArrayList<>();
        for (JobState st : jobs.values()) if (STATE_RUNNING.equals(st.state)) running.add(st.name);
        if (!running.isEmpty() || !dropped.isEmpty()) {
            log.info("Yan iş koşucusu kapanışta kesildi — koşan: {}, başlamamış: {}", running, dropped.size());
        }
        // Kesilen iş adımını bırakıp kendi kilit tutuşunu (LockTicket) çözsün — çağıran iş kilitlerini BUNDAN SONRA bırakır.
        long unwindMs = ShutdownDeadline.waitMs(INTERRUPT_UNWIND_CAP_MS);
        try {
            if (unwindMs > 0) ex.awaitTermination(unwindMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @jakarta.annotation.PreDestroy
    void destroy() {
        stopAndWait();
    }

    /** Bu pod'un yan iş durumu — veritabanı okumaz, hiç fırlatmaz. */
    public Status status() {
        SideJobPlacement p = placement;
        boolean yield = false;
        Boolean other = null;
        try {
            if (p != null) {
                yield = p.yieldEnabled();
                other = p.cachedOtherLivePod();
            }
        } catch (RuntimeException ignored) {
            // durum satırı asla fırlatmaz
        }
        boolean hint;
        try {
            hint = leaderHint.getAsBoolean();
        } catch (RuntimeException e) {
            hint = false;
        }
        List<Status.Job> rows = new ArrayList<>();
        for (JobState st : jobs.values()) {
            rows.add(new Status.Job(st.name, st.state, st.startedAt, st.lastFinishedAt, st.lastOutcome, st.runs.get(),
                    st.deferred.get(), st.yielded.get(), st.skipped.get()));
        }
        rows.sort(Comparator.comparing(Status.Job::name));
        return new Status(threads, accepting(), yield, hint, other, rows);
    }
}
