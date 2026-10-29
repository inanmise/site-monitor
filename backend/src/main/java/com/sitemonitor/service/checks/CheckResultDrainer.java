package com.sitemonitor.service.checks;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.evalstate.EvalLoadGate;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Dağıtık kontrol SONUÇLARININ boşaltıcısı (2026-10-29, çok pod Sürüm 3-3 paket C; tasarım R3-3 §4.3 / §4.4 / §5.6) — tarama
 * liderinde işçilerin tamamladığı ({@code DONE}) {@code monitor_check_jobs} satırlarını {@code id} sırasında alır ve
 * {@link CheckResultApplier} (paket B) üzerinden değerlendirir. Tek değerlendirici kuralı (K-2): yalnız kira sahibi alır
 * (kira-bağlı {@link CheckJobStore#take}), her alma + uygulama turu bir {@link MonitoringOutageService#evalBatchTicket()}
 * bileti İÇİNDE ve bilet içinde {@code isSweepLeaderNow()} yeniden sorulduktan sonra koşar.
 *
 * <p><b>Kapalı ≡ v20.169.0 (K-1).</b> İş parçacığı {@code check-result-drain} TEMBEL kurulur: {@link DistributedChecks}
 * ana anahtarı açılıştan sonra İLK KEZ açık görünce ({@link DistributedChecks#whenActivated}). Anahtar hiç açılmadıysa iş
 * parçacığı yoktur, deyim yoktur, günlük yoktur; {@link #holdManual()} tek bir volatile okumayla false döner; liderlik
 * dinleyicisi yalnız bellek yazar; {@code @PreDestroy} ortak kapanış süresine HİÇ dokunmaz. Sonradan kapatılırsa döngü
 * {@link DistributedChecks#anyDistributed()} (bellek) okuyup uyur — kuyrukta kalan DONE satırları yeniden açılışta alınır ya da
 * bayat olarak atılır ({@code result-stale-ms}).
 *
 * <p><b>Döngü</b> (her {@code drain-ms}, varsayılan 2 sn): anahtar kapalı / kapanış / lider değil → uyu; bu liderlik için
 * GAINED olayı henüz görülmediyse → uyu (3-2 kapısı GAINED dinleyicisinde kurulur — olaydan önce bilet almak yüklemeyi
 * "araya sızma" ile attırırdı); devirde yükleme sürüyor ({@code evalLoadToken() > 0}) → uyu; sonra en çok
 * {@value #ITERATION_BUDGET_MS} ms boyunca partiler: bilet → {@code isSweepLeaderNow()} → en çok {@value #TAKE_LIMIT} satır al
 * → id sırala → bayatları at (bitişten {@code result-stale-ms}) → savunma süzgeci (izleme başına son uygulanan iş kimliğinden
 * küçük/eşit kimlik = sıra dışı, atılır; her liderlik olayında sıfırlanır) → DALGALAR (her (tür, izleme) için dalga başına en
 * çok bir sonuç, sırayla) → {@link CheckResultApplier#apply}. Bilet YALNIZ alma + uygulama boyunca tutulur: 3-2 temiz
 * devrinin 300 ms durulma beklemesi partiler arasında boşaltıcıyı boşta görür (koşan parti → NOT_QUIESCENT, tasarım gereği).
 *
 * <p><b>Elle kontrol gelen kutusuyla sıra (§4.4).</b> GAINED'den sonra (yükleme bittikten sonra) önce bekleyen zamanlanmış
 * sonuç birikimi boşaltılır: bir alma {@value #TAKE_LIMIT}'den az satır döndürünce (o anda DONE olan her satır alındı —
 * ilk geçişin en büyük kimliği {@link Status#watermarkId()} dahil) "yetişti" sayılır. O ana dek — en çok
 * {@value #HOLD_MANUAL_MAX_MS} ms (liderlik başlangıcından) — {@code SchedulerService.drainManualEvalInbox} gelen kutusunu
 * boşaltmaz ({@link #holdManual()}): devirden önce ölçülmüş zamanlanmış bir DOWN, devirden sonra gelen elle sağlıklı sonuçtan
 * ÖNCE değerlendirilsin (3-2'nin J-4 tehlikesi).
 *
 * <p><b>Kapanış.</b> {@code ContextClosedEvent}: yalnız bayrak (bekleme yok, veritabanı yok); döngü bir sonraki adımda
 * çıkar. {@code SchedulerService.onContextClosed}'tan sonra {@code sweepLeaderHint()} / {@code sideJobsStopping()} zaten yeni
 * almayı engeller. {@code @PreDestroy}: iş parçacığı yaşıyorsa en çok {@code ShutdownDeadline.waitMs}({@value
 * #SHUTDOWN_JOIN_CAP_MS}) beklenir (ortak yok etme süresi; bütçe Spring 91 / toplam 101 &lt; 120 değişmez), kesme yok —
 * daemon iş parçacığı.
 *
 * <p>Hiçbir günlük satırı {@code result_json} yazmaz ({@link CheckResultRow#toString()} sonucu içermez); istisnalardan yalnız
 * sınıf adı yazılır.
 */
@Slf4j
@Component
public class CheckResultDrainer {

    /** İş parçacığı adı — kalıcı (System Health, iş parçacığı testleri). */
    public static final String THREAD_NAME = "check-result-drain";
    /** Bir almanın en çok satırı (tasarım §4.3: parti ≈ 200 ms altında kalsın). */
    public static final int TAKE_LIMIT = 100;
    /** Bir döngü adımının parti bütçesi (ms) — sonra kısa ara verilir. */
    public static final long ITERATION_BUDGET_MS = 1_500L;
    /** Bütçe dolduğunda ve kuyruk hâlâ doluyken sonraki adımdan önceki kısa ara (ms). */
    static final long YIELD_MS = 100L;
    /** GAINED'den sonra elle kutunun bekletilebileceği en uzun süre (ms, liderlik başlangıcından). */
    public static final long HOLD_MANUAL_MAX_MS = 20_000L;
    /** {@code @PreDestroy} birleştirme tavanı (ms) — ortak kapanış süresinden düşer. */
    public static final long SHUTDOWN_JOIN_CAP_MS = 500L;
    /** Savunma süzgecinin izlediği en çok anahtar — aşılırsa süzgeç temizlenir (silinmiş izlemeler birikmesin). */
    static final int MAX_TRACKED_KEYS = 100_000;
    /** WARN hız sınırı (ms). */
    static final long WARN_EVERY_MS = 60_000L;
    /** Liderlik dinleyicisi sırası: MOS'tan ({@code LOWEST_PRECEDENCE − 1000}, 3-2 kapısını kurar) SONRA. */
    public static final int LEADERSHIP_LISTENER_ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * Bu pod'un liderlik görüntüsü (dinleyici yazar, boşaltıcı yalnız {@code caughtUp} / {@code watermarkId} için CAS'lar).
     *
     * @param epoch       her liderlik olayında artar
     * @param gained      son olay GAINED (bu liderlik için 3-2 kapısı kurulmuş ya da kurulmayacak)
     * @param caughtUp    GAINED'den sonra DONE birikimi boşaltıldı (ya da lider değil)
     * @param gainedAtMs  GAINED olayının anı (zamanlayıcı saati; yoksa -1)
     * @param watermarkId GAINED'den sonraki ilk dolu almanın en büyük kimliği (0 = henüz yok)
     */
    record LeaderState(long epoch, boolean gained, boolean caughtUp, long gainedAtMs, long watermarkId) {
        static final LeaderState INITIAL = new LeaderState(0L, false, true, -1L, 0L);
    }

    /**
     * Pod'un boşaltıcı durumu (System Health / ölçümler, paket D) — yalnız bellek.
     *
     * @param started        iş parçacığı kuruldu (anahtar bu açılışta bir kez açık görüldü)
     * @param draining       şu an boşaltmaya yetkili görünüyor (lider ipucu + GAINED görüldü + anahtar açık)
     * @param caughtUp       GAINED sonrası birikim boşaltıldı
     * @param holdingManual  elle gelen kutusu şu an bekletiliyor
     * @param taken          alınan satır (açılıştan beri)
     * @param waves          uygulanan dalga
     * @param applied        uygulayıcıya verilen sonuç
     * @param droppedStale   bayat olarak atılan
     * @param outOfOrder     savunma süzgecinin attığı (beklenen 0)
     * @param applyFailures  uygulayıcının fırlattığı dalga
     * @param takeFailures   almanın hata verdiği parti
     * @param loadWaits      3-2 yüklemesi yüzünden atlanan adım
     * @param manualHolds    {@link #holdManual()} true döndü
     * @param lastTakeAtMs   son alma denemesi (duvar saati; yoksa -1)
     * @param watermarkId    güncel liderliğin ilk geçiş kimliği (0 = yok)
     * @param lastFailure    son hatanın sınıf adı (yoksa null)
     */
    public record Status(boolean started, boolean draining, boolean caughtUp, boolean holdingManual, long taken, long waves,
                         long applied, long droppedStale, long outOfOrder, long applyFailures, long takeFailures,
                         long loadWaits, long manualHolds, long lastTakeAtMs, long watermarkId, String lastFailure) {}

    private final DistributedChecks distributedChecks;
    private final Supplier<SchedulerService> scheduler;
    private final Supplier<MonitoringOutageService> mos;
    private final Supplier<CheckJobStore> store;
    private final Supplier<CheckResultApplier> applier;
    private final Supplier<ClusterScheduleService> schedule;
    private final Supplier<DbClock> dbClock;

    /** {@link #holdManual()} saatı — zamanlayıcının saatiyle AYNI olmalı ({@code sweepLeaderSinceMs}); üretimde duvar saati. */
    private volatile LongSupplier leaderClock = System::currentTimeMillis;

    private final AtomicReference<LeaderState> state = new AtomicReference<>(LeaderState.INITIAL);
    /** (tür:izleme) → son uygulanan iş kimliği — savunma süzgeci; her liderlik olayında temizlenir. */
    private final Map<String, Long> lastApplied = new ConcurrentHashMap<>();

    private final AtomicBoolean startRequested = new AtomicBoolean();
    private volatile boolean started;
    private volatile boolean closing;
    private volatile Thread thread;
    private final AtomicBoolean wake = new AtomicBoolean();

    private final AtomicLong taken = new AtomicLong();
    private final AtomicLong waves = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong droppedStale = new AtomicLong();
    private final AtomicLong outOfOrder = new AtomicLong();
    private final AtomicLong applyFailures = new AtomicLong();
    private final AtomicLong takeFailures = new AtomicLong();
    private final AtomicLong loadWaits = new AtomicLong();
    private final AtomicLong manualHolds = new AtomicLong();
    private final AtomicLong lastTakeAtMs = new AtomicLong(-1L);
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private final AtomicLong warnAt = new AtomicLong(Long.MIN_VALUE / 2);
    private volatile long holdLoggedEpoch = -1L;

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    /** Spring kurucusu — işbirlikçiler tembel çözülür (zamanlayıcı bu sınıfı alan enjeksiyonuyla alır; döngü kurulmaz). */
    @Autowired
    public CheckResultDrainer(DistributedChecks distributedChecks,
                              ObjectProvider<SchedulerService> scheduler,
                              ObjectProvider<MonitoringOutageService> mos,
                              ObjectProvider<CheckJobStore> store,
                              ObjectProvider<CheckResultApplier> applier,
                              ObjectProvider<ClusterScheduleService> schedule,
                              ObjectProvider<DbClock> dbClock) {
        this(distributedChecks, lazy(scheduler), lazy(mos), lazy(store), lazy(applier), lazy(schedule), lazy(dbClock));
    }

    /**
     * Elle kurulum / testler. Her tedarikçi null dönebilir (o işbirlikçi yok → boşaltma yapılmaz); {@code dbClock} null →
     * {@link DbClock#SYSTEM}. Başlatıcı kaydı yapılmaz ({@link #registerStarter()} ya da {@link #start()} çağrılmalı).
     */
    public CheckResultDrainer(DistributedChecks distributedChecks, Supplier<SchedulerService> scheduler,
                              Supplier<MonitoringOutageService> mos, Supplier<CheckJobStore> store,
                              Supplier<CheckResultApplier> applier, Supplier<ClusterScheduleService> schedule,
                              Supplier<DbClock> dbClock) {
        this.distributedChecks = distributedChecks;
        this.scheduler = safe(scheduler);
        this.mos = safe(mos);
        this.store = safe(store);
        this.applier = safe(applier);
        this.schedule = safe(schedule);
        this.dbClock = safe(dbClock);
    }

    /** Spring: tembel başlatıcıyı kaydeder (yalnız bellek; anahtar kapalıysa hiç çalışmaz). */
    @PostConstruct
    public void registerStarter() {
        if (distributedChecks != null) distributedChecks.whenActivated(THREAD_NAME, this::start);
    }

    /** Test dikişi: {@link #holdManual()} saati (zamanlayıcının {@code scheduleClock}'u ile aynı olmalı). */
    public void setLeaderClock(LongSupplier clock) {
        this.leaderClock = clock == null ? System::currentTimeMillis : clock;
    }

    // ── Elle gelen kutusu kapısı (SchedulerService.drainManualEvalInbox) ────────────────────────────────────────────────

    /**
     * Elle kontrol gelen kutusu bu turda boşaltılmasın mı (§4.4): boşaltıcı bu açılışta kuruldu ∧ anahtar açık ∧ bu liderlik
     * için GAINED görüldü ∧ DONE birikimi henüz boşaltılmadı ∧ liderlik başlangıcından {@value #HOLD_MANUAL_MAX_MS} ms
     * geçmedi. Anahtar açılıştan beri kapalıyken TEK volatile okuma, false (K-1). Asla fırlatmaz, veritabanına gitmez.
     */
    public boolean holdManual() {
        if (!started) return false;
        try {
            if (closing) return false;
            LeaderState st = state.get();
            if (!holdingFor(st)) return false;
            manualHolds.incrementAndGet();
            if (holdLoggedEpoch != st.epoch()) {
                holdLoggedEpoch = st.epoch();
                log.debug("Elle kontrol gelen kutusu, devirden önceki zamanlanmış sonuçlar değerlendirilene dek bekletiliyor "
                        + "(en çok {} sn)", HOLD_MANUAL_MAX_MS / 1000);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean holdingFor(LeaderState st) {
        if (!st.gained() || st.caughtUp()) return false;
        if (distributedChecks == null || !distributedChecks.anyDistributed()) return false;
        long since = leaderSince(st);
        if (since < 0) return false;
        long age = leaderClock.getAsLong() - since;
        return age >= 0 && age < HOLD_MANUAL_MAX_MS;
    }

    private long leaderSince(LeaderState st) {
        SchedulerService s = scheduler.get();
        long since = s == null ? -1L : s.sweepLeaderSinceMs();
        return since >= 0 ? since : st.gainedAtMs();
    }

    // ── Liderlik / kapanış olayları (yalnız bellek) ──────────────────────────────────────────────────────────────────

    /**
     * Her liderlik olayı (GAINED / LOST / RELEASED): savunma süzgeci temizlenir, dönem artar; GAINED birikimi "henüz
     * boşaltılmadı" işaretler ve döngüyü uyandırır. MOS'un dinleyicisinden SONRA ({@value #LEADERSHIP_LISTENER_ORDER}) —
     * 3-2 yükleme kapısı bu noktada kurulmuş ya da kurulmayacaktır. Olay liderlik kilidi tutulurken eşzamanlı dağıtılır:
     * kısa, veritabanısız, günlüksüz.
     */
    @EventListener
    @Order(LEADERSHIP_LISTENER_ORDER)
    public void onSweepLeadershipChanged(SweepLeadershipChangedEvent event) {
        if (event == null) return;
        boolean gained = event.leader() && SweepLeadershipChangedEvent.GAINED.equals(event.reason());
        state.getAndUpdate(p -> new LeaderState(p.epoch() + 1, gained, !gained, gained ? event.atMs() : -1L, 0L));
        lastApplied.clear();
        if (gained) wakeUp();
    }

    /** Kapanış: yalnız bayrak + uyandırma (bekleme yok, veritabanı yok) — sonra alma yok, iş parçacığı kurulmaz. */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (applicationContext != null && event != null && event.getApplicationContext() != applicationContext) return;
        closing = true;
        wakeUp();
    }

    /** Yok etme: iş parçacığı yaşıyorsa ortak kapanış süresinden en çok {@value #SHUTDOWN_JOIN_CAP_MS} ms beklenir. */
    @PreDestroy
    public void destroy() {
        closing = true;
        Thread t = thread;
        if (t == null || !t.isAlive() || t == Thread.currentThread()) return;   // K-1: hiç kurulmadıysa ortak süreye dokunma
        wakeUp();
        long budget = ShutdownDeadline.waitMs(SHUTDOWN_JOIN_CAP_MS);
        if (budget <= 0) return;   // ortak süre bitti — join(0) = sonsuz bekleme, asla
        try {
            t.join(budget);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Başlatma ve döngü ───────────────────────────────────────────────────────────────────────────────────────────

    /** İş parçacığını bir kez kurar ({@link DistributedChecks} etkinleştirmesi çağırır); kapanış başladıysa kurmaz. */
    public void start() {
        if (closing || !startRequested.compareAndSet(false, true)) return;
        Thread t = new Thread(this::loop, THREAD_NAME);
        t.setDaemon(true);
        thread = t;
        try {
            t.start();
        } catch (RuntimeException | Error e) {   // ör. "unable to create native thread" — etkinleştirmeyi (ayar kaydı) düşürmesin
            thread = null;
            noteFailure(e);
            log.warn("Dağıtık kontrol sonuç boşaltıcısı başlatılamadı: {}", e.getClass().getSimpleName());
            return;
        }
        started = true;
        log.info("Dağıtık kontrol sonuç boşaltıcısı başladı ({} ms aralık, parti {}) — yalnız tarama liderinde değerlendirir",
                tuning().drainMs(), TAKE_LIMIT);
    }

    private void loop() {
        long sleepMs = tuning().drainMs();
        while (!closing) {
            sleep(sleepMs);
            if (closing) break;
            boolean more;
            try {
                more = drainOnce();
            } catch (RuntimeException | Error e) {   // iş parçacığı asla ölmez
                noteFailure(e);
                warn("Dağıtık kontrol sonuçları boşaltılamadı — sonraki turda yeniden denenecek: {}", e);
                more = false;
            }
            sleepMs = more ? YIELD_MS : tuning().drainMs();
        }
    }

    /**
     * Bir döngü adımı (test dikişi; üretimde yalnız {@value #THREAD_NAME} iş parçacığı çağırır). Bütçe dolduğunda kuyruk
     * hâlâ doluysa true (kısa aradan sonra sürer), aksi hâlde false.
     */
    public boolean drainOnce() {
        if (distributedChecks == null || !distributedChecks.anyDistributed()) return false;   // bellek — anahtar kapalı
        SchedulerService s = scheduler.get();
        if (s == null || closing || s.sideJobsStopping() || !s.sweepLeaderHint()) return false;
        LeaderState st = state.get();
        if (!st.gained()) return false;   // GAINED dinleyicisi henüz koşmadı (3-2 kapısı kurulmadan bilet alınmaz)
        MonitoringOutageService m = mos.get();
        CheckJobStore q = store.get();
        CheckResultApplier ap = applier.get();
        ClusterScheduleService cs = schedule.get();
        String owner = cs == null ? null : cs.owner();
        if (m == null || q == null || ap == null || owner == null) return false;
        if (loadPending(m)) {
            loadWaits.incrementAndGet();
            return false;
        }
        long begin = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(ITERATION_BUDGET_MS);
        while (true) {
            int n = batch(s, m, q, ap, owner, st);
            if (n < 0) return false;
            if (n < TAKE_LIMIT) {
                markCaughtUp(st);
                return false;
            }
            if (System.nanoTime() - begin >= budgetNanos) return true;
            if (closing || s.sideJobsStopping() || !s.sweepLeaderHint()) return false;
            st = state.get();
        }
    }

    /**
     * Bir parti: bilet → liderlik yeniden → kira-bağlı alma → süzme → dalgalar → uygulama.
     *
     * @return alınan satır sayısı; -1 = durmalı (liderlik / dönem / kapanış / yükleme / alma hatası)
     */
    private int batch(SchedulerService s, MonitoringOutageService m, CheckJobStore q, CheckResultApplier ap, String owner,
                      LeaderState st) {
        // Bilet ALINMADAN önce sor: kira tazelemesi bir liderlik olayı yayınlayabilir — bilet açıkken yayınlanan GAINED 3-2
        // yüklemesini "araya sızma" ile attırırdı. Bilet içindeki ikinci soru önbellekten yanıtlanır.
        if (!s.isSweepLeaderNow()) return -1;
        if (state.get().epoch() != st.epoch() || loadPending(m)) return -1;
        try (EvalLoadGate.Ticket ignored = m.evalBatchTicket()) {
            if (closing || s.sideJobsStopping()) return -1;
            if (!s.isSweepLeaderNow()) return -1;
            if (state.get().epoch() != st.epoch()) return -1;
            List<CheckResultRow> rows;
            lastTakeAtMs.set(System.currentTimeMillis());
            try {
                rows = q.take(TAKE_LIMIT, owner);
            } catch (RuntimeException e) {
                takeFailures.incrementAndGet();
                noteFailure(e);
                warn("Dağıtık kontrol sonuçları alınamadı — satırlar yerinde, sonraki turda yeniden denenecek: {}", e);
                return -1;
            }
            if (rows == null || rows.isEmpty()) return 0;
            taken.addAndGet(rows.size());
            noteWatermark(st, rows);
            applyRows(rows, ap);
            return rows.size();
        }
    }

    private void applyRows(List<CheckResultRow> rows, CheckResultApplier ap) {
        List<CheckResultRow> sorted = new ArrayList<>(rows.size());
        for (CheckResultRow r : rows) if (r != null) sorted.add(r);
        sorted.sort(Comparator.comparingLong(CheckResultRow::id));
        long now = clock().nowMs();
        long staleMs = tuning().resultStaleMs();
        List<CheckResultRow> keep = new ArrayList<>(sorted.size());
        for (CheckResultRow r : sorted) {
            long age = r.ageMs(now);
            if (age > staleMs) {
                droppedStale.incrementAndGet();
                log.debug("Dağıtık kontrol sonucu bayat ({} sn), değerlendirilmeden atıldı: {}", age / 1000, r);
                continue;
            }
            Long last = lastApplied.get(r.monitorKey());
            if (last != null && r.id() <= last) {
                outOfOrder.incrementAndGet();
                log.debug("Dağıtık kontrol sonucu sıra dışı (son uygulanan iş {}), atıldı: {}", last, r);
                continue;
            }
            keep.add(r);
        }
        if (keep.isEmpty()) return;
        if (lastApplied.size() > MAX_TRACKED_KEYS) lastApplied.clear();
        for (List<CheckResultRow> wave : waves(keep)) {
            try {
                ap.apply(wave);
                applied.addAndGet(wave.size());
            } catch (RuntimeException e) {
                applyFailures.incrementAndGet();
                noteFailure(e);
                warn("Dağıtık kontrol sonuç dalgası değerlendirilemedi (" + wave.size() + " sonuç): {}", e);
            }
            waves.incrementAndGet();
            for (CheckResultRow r : wave) lastApplied.merge(r.monitorKey(), r.id(), Math::max);
        }
    }

    /**
     * Kimlik sırasındaki satırları dalgalara böler: her (tür, izleme) anahtarı bir dalgada en çok bir kez; aynı izlemenin
     * k. sonucu k. dalgaya düşer. Dalgalar ve dalga içi sıra kimlik sırasını korur (izleme başına kontrol sırası).
     */
    static List<List<CheckResultRow>> waves(List<CheckResultRow> idOrdered) {
        List<List<CheckResultRow>> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (CheckResultRow r : idOrdered) {
            int k = seen.merge(r.monitorKey(), 1, Integer::sum) - 1;
            while (out.size() <= k) out.add(new ArrayList<>());
            out.get(k).add(r);
        }
        return out;
    }

    private void markCaughtUp(LeaderState st) {
        LeaderState cur = state.get();
        if (cur.epoch() != st.epoch() || cur.caughtUp()) return;
        state.compareAndSet(cur, new LeaderState(cur.epoch(), cur.gained(), true, cur.gainedAtMs(), cur.watermarkId()));
    }

    private void noteWatermark(LeaderState st, List<CheckResultRow> rows) {
        LeaderState cur = state.get();
        if (cur.epoch() != st.epoch() || cur.caughtUp() || cur.watermarkId() != 0L) return;
        long max = 0L;
        for (CheckResultRow r : rows) if (r != null) max = Math.max(max, r.id());
        if (max > 0L) state.compareAndSet(cur, new LeaderState(cur.epoch(), cur.gained(), false, cur.gainedAtMs(), max));
    }

    private static boolean loadPending(MonitoringOutageService m) {
        try {
            return m.evalLoadToken() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── Tanı ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Bu pod'un boşaltıcı durumu — yalnız bellek (paket D). */
    public Status status() {
        LeaderState st = state.get();
        boolean on = distributedChecks != null && distributedChecks.anyDistributed();
        SchedulerService s = scheduler.get();
        boolean draining = started && on && !closing && st.gained() && s != null && s.sweepLeaderHint();
        boolean holding;
        try {
            holding = started && !closing && holdingFor(st);
        } catch (RuntimeException e) {
            holding = false;
        }
        return new Status(started, draining, st.caughtUp(), holding, taken.get(), waves.get(), applied.get(),
                droppedStale.get(), outOfOrder.get(), applyFailures.get(), takeFailures.get(), loadWaits.get(),
                manualHolds.get(), lastTakeAtMs.get(), st.watermarkId(), lastFailure.get());
    }

    /** İş parçacığı kuruldu mu. */
    public boolean started() {
        return started;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    private DistributedChecks.Tuning tuning() {
        return distributedChecks == null ? DistributedChecks.Tuning.DEFAULTS : distributedChecks.tuning();
    }

    private DbClock clock() {
        DbClock c = dbClock.get();
        return c == null ? DbClock.SYSTEM : c;
    }

    private void wakeUp() {
        wake.set(true);
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    private void sleep(long ms) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, ms));
        while (!closing) {
            if (wake.getAndSet(false)) return;
            long left = deadline - System.nanoTime();
            if (left <= 0) return;
            LockSupport.parkNanos(this, left);
            if (Thread.interrupted() && closing) return;   // kesme bayrağı temizlenir — boş döngüye girilmez
        }
    }

    private void noteFailure(Throwable e) {
        lastFailure.set(e.getClass().getSimpleName());
    }

    /** Dakikada en çok bir WARN (yalnız istisna sınıf adı — ileti / sonuç yazılmaz); aradakiler DEBUG. */
    private void warn(String pattern, Throwable e) {
        long now = System.currentTimeMillis();
        long last = warnAt.get();
        String cls = e.getClass().getSimpleName();
        if (now - last >= WARN_EVERY_MS && warnAt.compareAndSet(last, now)) log.warn(pattern, cls);
        else log.debug(pattern, cls);
    }

    private static <T> Supplier<T> safe(Supplier<T> s) {
        if (s == null) return () -> null;
        return () -> {
            try {
                return s.get();
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    private static <T> Supplier<T> lazy(ObjectProvider<T> provider) {
        AtomicReference<T> ref = new AtomicReference<>();
        return () -> {
            T v = ref.get();
            if (v != null) return v;
            if (provider == null) return null;
            try {
                v = provider.getIfUnique();
            } catch (RuntimeException e) {
                return null;
            }
            if (v != null) ref.set(v);
            return v;
        };
    }
}
