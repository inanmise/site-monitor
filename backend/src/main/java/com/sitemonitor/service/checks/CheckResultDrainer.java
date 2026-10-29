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
 *
 * <p><b>İnceleme düzeltmeleri (2026-10-29, FX-EVAL).</b>
 * <ul>
 *   <li><b>Boru hattı sağlığı (DH-M2 a/b):</b> {@link #canDrain()} = kuruldu ∧ kapanmıyor ∧ bu liderliğin GAINED'i görüldü ∧
 *       işbirlikçiler çözüldü ∧ son BAŞARILI alma (yoksa GAINED anı) {@code max(30 sn, 10 × drain-ms)} içinde. Zamanlayıcının
 *       {@code distributedMode(T)} kuralı bunu ister — boşaltamayan liderde tür klasikte kalır (sonuçlar yığılmaz).</li>
 *   <li><b>Taban (DH-M1 a):</b> anahtar KAPALI görüldükten sonra (ya da etkinleşme açılıştan sonra geldiyse) ilk açık görüşte
 *       {@code floorMs = DbClock.nowMs()}; kontrolü bundan ÖNCE SAHİPLENİLMİŞ (başlamış) işlerin sonuçları atılır
 *       ({@code droppedObsolete}) — kapalı dönemde klasik tarama daha yeni gözlemleri değerlendirdi. Kapanırken BEKLEYEN (PENDING)
 *       ve yeniden açıldıktan sonra sahiplenilen iş taze bir ölçümdür ve uygulanır (son inceleme F2: oluşturma anıyla
 *       karşılaştırma o izlemenin yeni dönemini kaybettiriyordu — açık iş yeni gönderimi engellediği için).</li>
 *   <li><b>Klasik sonra (DH-M1 b):</b> anahtar açıkken klasik gövdenin başlattığı her zamanlanmış kontrolün anı izleme başına
 *       tutulur ({@link #noteClassicCheck}); o andan ÖNCE sahiplenilmiş iş sonucu atılır ({@code droppedSuperseded}).</li>
 *   <li><b>Klasik bekletme (DH-M1 c):</b> {@link #holdClassic()} — GAINED sonrası birikim boşalana dek klasik gövde dönemi
 *       harcamadan döner; bu sürede döngü {@value #CATCHUP_POLL_MS} ms'de bir yoklar (yükleme biter bitmez alır).</li>
 *   <li><b>Devirde kayıp (DH-L1):</b> alınmış satırlar kapanış başladıktan sonra (çit kapalı) değerlendirilemediyse bilet
 *       kapanmadan {@link #lostInHandover()} işaretlenir — temiz devir işareti yazılmaz (halef yalnız gözlemleri yükler).</li>
 *   <li><b>Tavan (DH-L2):</b> bekletme tavanı yükleme kapısı açıldıktan sonraki ilk boşaltma adımından ölçülür.</li>
 *   <li><b>Dönem (DH-L3):</b> bir adımın iki partisi arasında liderlik olayı olduysa adım durur.</li>
 * </ul>
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
    /** GAINED sonrası birikim boşalırken (bekletme penceresinde) döngünün yoklama aralığı (ms; DH-M1 c). */
    public static final long CATCHUP_POLL_MS = 200L;
    /** Alma sağlığı penceresinin alt sınırı (ms; {@link #canDrain()} — {@code max(bu, 10 × drain-ms)}). */
    public static final long TAKE_HEALTH_MIN_MS = 30_000L;

    /**
     * Bu pod'un liderlik görüntüsü (dinleyici yazar, boşaltıcı yalnız {@code caughtUp} / {@code watermarkId} /
     * {@code eligibleAtMs} / {@code loadSeen} için CAS'lar).
     *
     * @param epoch        her liderlik olayında artar
     * @param gained       son olay GAINED (bu liderlik için 3-2 kapısı kurulmuş ya da kurulmayacak)
     * @param caughtUp     GAINED'den sonra DONE birikimi boşaltıldı (ya da lider değil)
     * @param gainedAtMs   GAINED olayının anı (zamanlayıcı saati; yoksa -1)
     * @param watermarkId  GAINED'den sonraki ilk dolu almanın en büyük kimliği (0 = henüz yok)
     * @param eligibleAtMs GAINED'den sonra yükleme kapısını geçen ilk boşaltma adımının anı (liderlik saati; -1 = henüz yok)
     * @param loadSeen     bu liderlikte bir boşaltma adımı 3-2 yüklemesini sürerken gördü
     */
    record LeaderState(long epoch, boolean gained, boolean caughtUp, long gainedAtMs, long watermarkId, long eligibleAtMs,
                       boolean loadSeen) {
        static final LeaderState INITIAL = new LeaderState(0L, false, true, -1L, 0L, -1L, false);

        LeaderState withCaughtUp() {
            return new LeaderState(epoch, gained, true, gainedAtMs, watermarkId, eligibleAtMs, loadSeen);
        }

        LeaderState withWatermark(long id) {
            return new LeaderState(epoch, gained, false, gainedAtMs, id, eligibleAtMs, loadSeen);
        }

        LeaderState withEligible(long atMs) {
            return new LeaderState(epoch, gained, caughtUp, gainedAtMs, watermarkId, atMs, loadSeen);
        }

        LeaderState withLoadSeen() {
            return new LeaderState(epoch, gained, caughtUp, gainedAtMs, watermarkId, eligibleAtMs, true);
        }
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
     * @param droppedObsolete   anahtar yeniden açılmadan ÖNCE gönderilmiş işin sonucu olduğu için atılan (FX-EVAL DH-M1 a)
     * @param droppedSuperseded aynı izlemenin daha yeni klasik kontrolünden önce sahiplenildiği için atılan (DH-M1 b)
     * @param droppedHandover   kapanış başladıktan sonra (çit kapalı) değerlendirilmeden kalan alınmış satır (DH-L1)
     * @param classicHolds      {@link #holdClassic()} true döndü (klasik gövde dönem harcamadan döndü)
     * @param canDrain          {@link #canDrain()} şu an
     * @param lostInHandover    devirde alınmış sonuç kayboldu — temiz devir işareti yazılmaz
     */
    public record Status(boolean started, boolean draining, boolean caughtUp, boolean holdingManual, long taken, long waves,
                         long applied, long droppedStale, long outOfOrder, long applyFailures, long takeFailures,
                         long loadWaits, long manualHolds, long lastTakeAtMs, long watermarkId, String lastFailure,
                         long droppedObsolete, long droppedSuperseded, long droppedHandover, long classicHolds,
                         boolean canDrain, boolean lostInHandover) {

        /** 3-3 C sürümünün 16 bileşenli kurucusu (paket D testleri) — yeni alanlar 0 / false. */
        public Status(boolean started, boolean draining, boolean caughtUp, boolean holdingManual, long taken, long waves,
                      long applied, long droppedStale, long outOfOrder, long applyFailures, long takeFailures,
                      long loadWaits, long manualHolds, long lastTakeAtMs, long watermarkId, String lastFailure) {
            this(started, draining, caughtUp, holdingManual, taken, waves, applied, droppedStale, outOfOrder, applyFailures,
                    takeFailures, loadWaits, manualHolds, lastTakeAtMs, watermarkId, lastFailure, 0L, 0L, 0L, 0L, false, false);
        }
    }

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

    // ── FX-EVAL (inceleme 2026-10-29) ──
    private final AtomicLong droppedObsolete = new AtomicLong();
    private final AtomicLong droppedSuperseded = new AtomicLong();
    private final AtomicLong droppedHandover = new AtomicLong();
    private final AtomicLong classicHolds = new AtomicLong();
    /** Son BAŞARILI alma (boş dahil) — liderlik saati; her liderlik olayında sıfırlanır (DH-M2). */
    private volatile long lastTakeOkAtMs = Long.MIN_VALUE;
    /** Anahtar bu pod'da KAPALI görüldü — sonraki ilk açık görüşte taban konur (DH-M1 a). */
    private volatile boolean seenOff;
    /** Açılış hazır olduğunda anahtar açık mıydı (null = bilinmiyor — elle kurulum / olay yok). */
    private volatile Boolean onAtReady;
    /** Etkinleşme açılıştan SONRA (anahtar sonradan açıldı) → ilk açık görüşte taban konur. */
    private volatile boolean floorPending;
    /** Taban (DB saati, ms): bundan ÖNCE OLUŞTURULMUŞ işlerin sonuçları atılır; {@code Long.MIN_VALUE} = yok. */
    private volatile long floorMs = Long.MIN_VALUE;
    private final Object floorLock = new Object();
    /** (tür:izleme) → klasik gövdenin o izlemeyi en son kontrole başlattığı an (DB saati) — liderlik olayında temizlenir. */
    private final Map<String, Long> classicStarts = new ConcurrentHashMap<>();
    /** Kapanış başladıktan sonra alınmış satır değerlendirilemedi (devir işareti temiz yazılmamalı; DH-L1). */
    private volatile boolean lostInHandover;
    private final AtomicBoolean collaboratorWarned = new AtomicBoolean();
    // ── /FX-EVAL ──

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
     * için GAINED görüldü ∧ DONE birikimi henüz boşaltılmadı ∧ {@code max(liderlik başlangıcı, yükleme kapısını geçen ilk
     * boşaltma adımı)}'ndan bu yana {@value #HOLD_MANUAL_MAX_MS} ms geçmedi (FX-EVAL DH-L2; bkz. {@code holdingFor}). Anahtar
     * açılıştan beri kapalıyken TEK volatile okuma, false (K-1). Asla fırlatmaz, veritabanına gitmez.
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

    /**
     * Bekletme penceresi (DH-L2): tavan {@value #HOLD_MANUAL_MAX_MS} ms, ölçü başlangıcı {@code max(liderlik başlangıcı, yükleme
     * kapısını geçen ilk boşaltma adımı)}. O adım henüz yoksa ve bu liderlikte yükleme sürerken görüldüyse tavana yüklemenin
     * en uzun süresi ({@link EvalLoadGate#LOAD_TIMEOUT_MS}) eklenir — yavaş bir TAM yüklemeden hemen sonra elle kutu, bekleyen
     * zamanlanmış sonuçlardan ÖNCE boşaltılmasın.
     */
    private boolean holdingFor(LeaderState st) {
        if (!st.gained() || st.caughtUp()) return false;
        if (distributedChecks == null || !distributedChecks.anyDistributed()) return false;
        long since = leaderSince(st);
        if (since < 0) return false;
        long cap = HOLD_MANUAL_MAX_MS;
        if (st.eligibleAtMs() >= 0) since = Math.max(since, st.eligibleAtMs());
        else if (st.loadSeen()) cap += EvalLoadGate.LOAD_TIMEOUT_MS;
        long age = leaderClock.getAsLong() - since;
        return age >= 0 && age < cap;
    }

    // ── Zamanlayıcının kapıları (FX-EVAL, inceleme 2026-10-29) — yalnız bellek, asla fırlatmaz ─────────────────────────

    /**
     * Klasik tarama gövdesi bu turda dönem HARCAMADAN dönsün mü (DH-M1 c): {@link #holdManual()} ile aynı pencere (GAINED
     * sonrası, birikim boşalmadı, tavan içinde) VE bu pod gerçekten boşaltabiliyor (işbirlikçiler çözüldü). Böylece devirden
     * önce ölçülmüş bekleyen bir DOWN, devirden sonra klasik ölçülen sağlıklı sonuçtan ÖNCE değerlendirilir. Anahtar açılıştan
     * beri kapalıyken tek volatile okuma, false (K-1).
     */
    public boolean holdClassic() {
        if (!started) return false;
        try {
            if (closing) return false;
            LeaderState st = state.get();
            return holdingFor(st) && collaboratorsResolved();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Zamanlayıcı bir klasik turu bu bekletme yüzünden atladı (yalnız sayaç). */
    public void noteClassicHeld() {
        classicHolds.incrementAndGet();
    }

    /**
     * Bu pod sonuçları şu an boşaltabiliyor mu (DH-M2) — {@code SchedulerService.distributedMode(T)} ister: kuruldu ∧ kapanmıyor
     * ∧ bu liderliğin GAINED olayı görüldü ∧ işbirlikçiler çözüldü ∧ son BAŞARILI alma (henüz yoksa GAINED anı)
     * {@code max(}{@value #TAKE_HEALTH_MIN_MS}{@code  ms, 10 × drain-ms)} içinde. Yanlış → tür klasik (gönderilen işlerin
     * sonuçları yığılmasın, izleme sessizce durmasın).
     */
    public boolean canDrain() {
        if (!started) return false;
        try {
            if (closing) return false;
            LeaderState st = state.get();
            if (!st.gained() || !collaboratorsResolved()) return false;
            long since = Math.max(st.gainedAtMs(), lastTakeOkAtMs);
            if (since == Long.MIN_VALUE || since < 0) return false;
            return leaderClock.getAsLong() - since < takeHealthWindowMs();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Klasik gövde bu izlemenin zamanlanmış kontrolünü başlattı (DH-M1 b): anahtar açıkken anı (DB saati) tutulur; o andan önce
     * sahiplenilmiş iş sonucu boşaltıcıda atılır. Anahtar kapalı / hiç kurulmadı → yazılmaz (K-1).
     */
    public void noteClassicCheck(String type, Long monitorId) {
        if (!started || type == null || monitorId == null) return;
        try {
            if (closing || distributedChecks == null || !distributedChecks.anyDistributed()) return;
            if (classicStarts.size() >= MAX_TRACKED_KEYS) classicStarts.clear();
            classicStarts.put(type + ":" + monitorId, clock().nowMs());
        } catch (RuntimeException e) {
            // yalnız savunma bilgisi — kaybı karar değiştirmez
        }
    }

    /**
     * Dağıtık kontrolün açık / kapalı görüldüğünü bildirir (yalnız bellek; gönderim tıkı ve ayar dinleyicisi). Kapalı → bir
     * sonraki açık görüşte taban konur ({@link #applyFloorIfPending()}).
     */
    public void noteSwitch(boolean on) {
        if (!on) seenOff = true;
    }

    /**
     * Bekleyen tabanı koyar (DH-M1 a): anahtar kapalı görüldükten / sonradan etkinleştikten sonraki ilk açık görüş. Gönderim
     * tıkı her göndermeden ÖNCE çağırır — taban her yeni işin oluşturma anından küçük/eşit. Arka plan iş parçacıkları çağırır
     * (DB saati çapası gerekebilir; istek iş parçacığında değil).
     */
    public void applyFloorIfPending() {
        if (!seenOff && !floorPending) return;
        synchronized (floorLock) {
            if (!seenOff && !floorPending) return;
            long f;
            try {
                f = clock().nowMs();
            } catch (RuntimeException e) {
                return;   // sonraki çağrıda yeniden
            }
            floorMs = Math.max(floorMs, f);
            seenOff = false;
            floorPending = false;
            log.debug("Dağıtık kontroller yeniden açık görüldü — bundan önce gönderilmiş işlerin sonuçları atılacak (taban {})",
                    f);
        }
    }

    /**
     * Devirde (kapanış başladıktan sonra) alınmış sonuç değerlendirilemedi mi (DH-L1) — {@code SchedulerService.onContextClosed}
     * bu true iken temiz devir işaretini yazdırmaz. Asla sıfırlanmaz (pod kapanıyor).
     */
    public boolean lostInHandover() {
        return lostInHandover;
    }

    private boolean collaboratorsResolved() {
        if (mos.get() == null || store.get() == null || applier.get() == null) return false;
        ClusterScheduleService cs = schedule.get();
        return cs != null && cs.owner() != null;
    }

    private long takeHealthWindowMs() {
        return Math.max(TAKE_HEALTH_MIN_MS, 10L * tuning().drainMs());
    }

    /** Açılış hazır olduğunda anahtar açık mıydı (yalnız bellek; {@code DistributedChecks.onReady}'den ÖNCE koşar). */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onReady(org.springframework.boot.context.event.ApplicationReadyEvent event) {
        if (applicationContext != null && event != null && event.getApplicationContext() != applicationContext) return;
        try {
            onAtReady = distributedChecks != null && distributedChecks.anyDistributed();
        } catch (RuntimeException e) {
            onAtReady = null;
        }
    }

    /** Ana anahtar / tür listesi değişti (kayıt ya da 10 sn yenileme) — yalnız bellek: kapalı görüldü mü. */
    @EventListener
    public void onSettingsChanged(com.sitemonitor.service.AppSettingsChangedEvent event) {
        if (event == null || distributedChecks == null) return;
        if (!event.touches(DistributedChecks.KEY_ENABLED) && !event.touches(DistributedChecks.KEY_TYPES)) return;
        try {
            noteSwitch(distributedChecks.anyDistributed());
        } catch (RuntimeException e) {
            // yalnız bellek
        }
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
        state.getAndUpdate(p -> new LeaderState(p.epoch() + 1, gained, !gained, gained ? event.atMs() : -1L, 0L, -1L, false));
        lastApplied.clear();
        classicStarts.clear();
        lastTakeOkAtMs = Long.MIN_VALUE;
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
        // FX-EVAL DH-M1 a: etkinleşme açılıştan SONRA geldi (anahtar açılışta kapalıydı) → kapalı dönemin sonuçlarına taban.
        if (Boolean.FALSE.equals(onAtReady)) floorPending = true;
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
            sleepMs = more ? YIELD_MS : nextSleepMs();
        }
    }

    /**
     * Sonraki adıma dek bekleme (DH-M1 c): GAINED sonrası birikim boşalırken (bekletme penceresi içinde) {@value #CATCHUP_POLL_MS}
     * ms — 3-2 yüklemesi biter bitmez alınsın, klasik / elle değerlendirme bekleyen sonuçtan önce gelmesin; aksi hâlde
     * {@code drain-ms}. Pencere sınırlı (≤ tavan) — yoklama sonsuza sürmez.
     */
    private long nextSleepMs() {
        try {
            if (holdingFor(state.get())) return Math.min(CATCHUP_POLL_MS, tuning().drainMs());
        } catch (RuntimeException e) {
            // yalnız bellek
        }
        return tuning().drainMs();
    }

    /**
     * Bir döngü adımı (test dikişi; üretimde yalnız {@value #THREAD_NAME} iş parçacığı çağırır). Bütçe dolduğunda kuyruk
     * hâlâ doluysa true (kısa aradan sonra sürer), aksi hâlde false.
     */
    public boolean drainOnce() {
        if (distributedChecks == null || !distributedChecks.anyDistributed()) {   // bellek — anahtar kapalı
            if (distributedChecks != null) noteSwitch(false);
            return false;
        }
        applyFloorIfPending();   // FX-EVAL DH-M1 a: kapalı görüldükten sonraki ilk açık görüş (takipçide de)
        SchedulerService s = scheduler.get();
        if (s == null || closing || s.sideJobsStopping() || !s.sweepLeaderHint()) return false;
        LeaderState st = state.get();
        if (!st.gained()) return false;   // GAINED dinleyicisi henüz koşmadı (3-2 kapısı kurulmadan bilet alınmaz)
        MonitoringOutageService m = mos.get();
        CheckJobStore q = store.get();
        CheckResultApplier ap = applier.get();
        ClusterScheduleService cs = schedule.get();
        String owner = cs == null ? null : cs.owner();
        if (m == null || q == null || ap == null || owner == null) {
            // FX-EVAL DH-M2 b: eskiden SESSİZDİ — anahtar açık, bu pod lider, ama boşaltamıyor (tür klasikte kalır)
            if (collaboratorWarned.compareAndSet(false, true)) {
                log.warn("Dağıtık kontrol sonuç boşaltıcısı çalışamıyor — eksik bileşen: {} (türler klasik taramada kalır)",
                        missingNames(m, q, ap, cs, owner));
            }
            return false;
        }
        if (loadPending(m)) {
            loadWaits.incrementAndGet();
            markLoadSeen(st);
            return false;
        }
        markEligible(st);   // DH-L2: bekletme tavanı yükleme kapısı açıldıktan sonraki ilk adımdan ölçülür
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
            // DH-L3: iki parti arasında liderlik olayı (LOST → GAINED) olduysa yeni dönem SESSİZCE benimsenmez — adım durur;
            // sonraki adım GAINED kapısını (st.gained()) ve 3-2 yüklemesini baştan sorar.
            if (state.get().epoch() != st.epoch()) return false;
        }
    }

    private static String missingNames(MonitoringOutageService m, CheckJobStore q, CheckResultApplier ap,
                                       ClusterScheduleService cs, String owner) {
        List<String> out = new ArrayList<>();
        if (m == null) out.add("MonitoringOutageService");
        if (q == null) out.add("CheckJobStore");
        if (ap == null) out.add("CheckResultApplier");
        if (cs == null) out.add("ClusterScheduleService");
        else if (owner == null) out.add("kira sahibi");
        return String.join(", ", out);
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
            if (state.get().epoch() == st.epoch()) lastTakeOkAtMs = leaderClock.getAsLong();   // DH-M2: alma sağlıklı
            if (rows == null || rows.isEmpty()) return 0;
            taken.addAndGet(rows.size());
            noteWatermark(st, rows);
            applyRows(rows, ap, s);
            // DH-L1: satırlar alındı (kuyruktan silindi); kapanış bu partide başladıysa çit (isSweepLeader = false) kalanları
            // sessizce düşürmüş olabilir — bilet KAPANMADAN işaretlenir, devir işareti temiz yazılmaz (halef gözlemleri yükler).
            if (s.sideJobsStopping()) lostInHandover = true;
            return rows.size();
        }
    }

    private void applyRows(List<CheckResultRow> rows, CheckResultApplier ap, SchedulerService s) {
        List<CheckResultRow> sorted = new ArrayList<>(rows.size());
        for (CheckResultRow r : rows) if (r != null) sorted.add(r);
        sorted.sort(Comparator.comparingLong(CheckResultRow::id));
        long now = clock().nowMs();
        long staleMs = tuning().resultStaleMs();
        long floor = floorMs;
        List<CheckResultRow> keep = new ArrayList<>(sorted.size());
        for (CheckResultRow r : sorted) {
            long age = r.ageMs(now);
            if (age > staleMs) {
                droppedStale.incrementAndGet();
                log.debug("Dağıtık kontrol sonucu bayat ({} sn), değerlendirilmeden atıldı: {}", age / 1000, r);
                continue;
            }
            long claimed = r.claimedMs() != null ? r.claimedMs() : r.createdMs();
            // DH-M1 a: kontrolü anahtar yeniden açılmadan ÖNCE başlamış iş — kapalı dönemde klasik tarama daha yeni gözlem yaptı.
            // Kapanırken bekleyen, açıldıktan sonra sahiplenilen iş taze ölçümdür (F2) — uygulanır.
            if (floor != Long.MIN_VALUE && claimed < floor) {
                droppedObsolete.incrementAndGet();
                log.debug("Dağıtık kontrol sonucu anahtar yeniden açılmadan önce başlamıştı, atıldı: {}", r);
                continue;
            }
            // DH-M1 b: aynı izlemenin klasik zamanlanmış kontrolü bu işten SONRA başladı — eski gözlem yenisinin üstüne yazılmaz.
            Long classicAt = classicStarts.get(r.monitorKey());
            if (classicAt != null && claimed < classicAt) {
                droppedSuperseded.incrementAndGet();
                log.debug("Dağıtık kontrol sonucu aynı izlemenin daha yeni klasik kontrolünden önce sahiplenilmişti, atıldı: {}", r);
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
        List<List<CheckResultRow>> all = waves(keep);
        for (int i = 0; i < all.size(); i++) {
            List<CheckResultRow> wave = all.get(i);
            if (s.sideJobsStopping()) {   // DH-L1: çit kapandı — kalan dalgalar zaten düşerdi; sayılır, işaretlenir
                int left = 0;
                for (int j = i; j < all.size(); j++) left += all.get(j).size();
                droppedHandover.addAndGet(left);
                lostInHandover = true;
                return;
            }
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
        state.compareAndSet(cur, cur.withCaughtUp());
    }

    private void noteWatermark(LeaderState st, List<CheckResultRow> rows) {
        LeaderState cur = state.get();
        if (cur.epoch() != st.epoch() || cur.caughtUp() || cur.watermarkId() != 0L) return;
        long max = 0L;
        for (CheckResultRow r : rows) if (r != null) max = Math.max(max, r.id());
        if (max > 0L) state.compareAndSet(cur, cur.withWatermark(max));
    }

    /** DH-L2: yükleme kapısını geçen ilk adımın anı (dönem başına bir kez). */
    private void markEligible(LeaderState st) {
        LeaderState cur = state.get();
        if (cur.epoch() != st.epoch() || cur.eligibleAtMs() >= 0) return;
        state.compareAndSet(cur, cur.withEligible(Math.max(0L, leaderClock.getAsLong())));
    }

    /** DH-L2: bu liderlikte yükleme sürerken görüldü (tavana yükleme süresi eklenir). */
    private void markLoadSeen(LeaderState st) {
        LeaderState cur = state.get();
        if (cur.epoch() != st.epoch() || cur.loadSeen()) return;
        state.compareAndSet(cur, cur.withLoadSeen());
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
                manualHolds.get(), lastTakeAtMs.get(), st.watermarkId(), lastFailure.get(), droppedObsolete.get(),
                droppedSuperseded.get(), droppedHandover.get(), classicHolds.get(), canDrain(), lostInHandover);
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
