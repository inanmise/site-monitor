package com.sitemonitor.service.evalstate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Devirde yükleme kapısı (2026-10-28, çok pod Sürüm 3-2 B; tasarım R3-2 §3.2) — {@code MonitoringOutageService}'in TEK
 * değerlendirme kapısı. Saf sınıf: veritabanı yok, Spring yok; yalnız bellek ve sınırlı beklemeler (J-2).
 *
 * <p><b>Durumlar.</b>
 * <ul>
 *   <li>{@link State#NONE} — kapı açık. Giriş = bir {@link AtomicInteger} artırma + bir volatile okuma (kilit yok, günlük
 *       yok). Ayar KAPALIYKEN kapı hiç kurulmaz → hep bu yol (J-1).</li>
 *   <li>{@link State#LOADING} — liderlik alındı (GAINED), kaydedicinin yazıcı iş parçacığı önceki sahibin durumunu okuyor.
 *       Her giriş yükleme bitene (uygulandı / atıldı / atlandı) ya da son tarihe ({@link #LOAD_TIMEOUT_MS}, MOS saati) dek
 *       bekler; son tarih geçince kapı NONE olur ve yükleme {@code timeout} sayılır (geç gelen uygulama reddedilir).</li>
 *   <li>{@link State#HANDOVER} — zarif kapanışta devir boşaltması: yeni girişler en çok {@link #HANDOVER_WAIT_MS} bekler
 *       (gerçek zaman), sonra geçer (pod kapanıyor; zamanlanmış sonuçlar zaten çitli). Bu sürede BAŞLAYAN zincir adımı
 *       ({@link #enterChainStep}) belleğe dokunmadan çıkar.</li>
 * </ul>
 *
 * <p><b>Uygulama (J-3).</b> {@link #apply} yükü yalnız kapı hâlâ bu belirteçle LOADING iken, uçuşta değerlendirme kalmayınca
 * (en çok {@link #APPLY_QUIESCE_MS}) ve kapının izleyicisi TUTULURKEN gövdeyi koşturur — LOADING'de yeni giriş izleyiciyi
 * beklediği için gövde ile hiçbir değerlendirme iç içe geçmez. Gövde yalnız harita yazar (MOS boş bellek denetimini de
 * orada yapar); birleştirme asla denenmez. Kurulumdan sonra (gövdeden önce ya da gövde sürerken) bir liderlik olayı
 * BAŞLADIYSA (enjekte liderlik sırası değişti) yük {@code leadership_changed} sayılır: dinleyici kapıyı sıfırlamadan ÖNCE
 * söker (izleyiciyi, yani gövdenin bitmesini bekler), gövdenin yazdığını sıfırlama siler (inceleme 2026-10-28, L-1).
 *
 * <p><b>Uçuştaki sayım.</b> Kapı NONE iken giren her üst düzey değerlendirme sayılır — kurulumdan HEMEN önce girip
 * kurulumdan sonra belleğe yazan değerlendirme de ("araya sızma"): kurulum anında uçuşta değerlendirme varsa bu
 * hatırlanır ve uygulama onun bitmesini bekleyip yükü atar ({@code evaluated_before_load}) — değerlendirme belleğe yalnız
 * SİLME yazmış olsa bile (inceleme L-3). İç içe çağrı ({@code handleDnsSweep → handleSweepResults}) iş parçacığı yerel
 * derinlikle tanınır ve yeniden sayılmaz / beklemez.
 *
 * <p><b>Zincir adımları (inceleme 2026-10-28, M-1 yol 2).</b> Teyit / kurtarma zincirinin adımı (yeniden kontrol ve
 * ardından gelen bellek değişikliği) kapıdan geçmez, beklemez; ama {@link #enterChainStep} ile sayılır: devirde
 * {@link #awaitQuiescent} hem girişlerin hem zincir adımlarının bitmesini bekler — saniyeler süren bir yeniden kontrol
 * sürerken işaret yazılmaz (ardılı yalnız gözlemleri alır). HANDOVER'da başlayan adım çıkar (ardılı zincirleri yeniden
 * başlatır, karar D-2).
 *
 * <p><b>Her bekleme sınırlıdır.</b> Bekleme dilimleri {@link #WAIT_SLICE_MS}; enjekte saat takılsa / geri sarılsa da
 * gerçek zamanlı üst sınır ({@code son tarih − kurulum + HARD_CAP_SLACK_MS}) yüklemeyi sona erdirir. Kesme (interrupt)
 * beklemeyi bitirir, bayrak geri konur.
 *
 * <p><b>Bitiş tek kez.</b> Bir belirtecin LOADING'i tam bir kez biter ({@link Ended}: uygulandı / atıldı / atlandı /
 * zaman aşımı / liderlik değişti) — rapor ve günlük satırı yükleme başına bir (çağıran yazar). Zaman aşımı bir bekleyen ya
 * da belirteç okuması sırasında anlaşılırsa {@code onExpired} izleyici DIŞINDA çağrılır.
 */
public final class EvalLoadGate {

    private static final Logger log = LoggerFactory.getLogger(EvalLoadGate.class);

    /** Kapı durumu. */
    public enum State { NONE, LOADING, HANDOVER }

    /** Kurulumdan itibaren yükleme son tarihi (ms, MOS saati) — tasarım §3.2. */
    public static final long LOAD_TIMEOUT_MS = 20_000L;
    /** Uygulama, uçuştaki değerlendirmelerin bitmesini en çok bu kadar bekler (ms). */
    public static final long APPLY_QUIESCE_MS = 5_000L;
    /**
     * HANDOVER'da bir giriş en çok bu kadar bekler (ms, gerçek zaman; {@code beginHandover}'dan itibaren) = devir bütçesi
     * ({@link EvalStateSink#HANDOVER_BUDGET_MS}: durulma + boşaltma) + 1 sn pay: bekleyen giriş işaretle kiranın
     * bırakılması (RELEASED) ARASINDA koşamaz (inceleme 2026-10-28, kaydedici L-1). RELEASED'in sökmesi kapıyı daha erken
     * açar; yalnız kapanan pod'un istekleri bekler (kapanış iş parçacığı değil — kapanış bütçesi etkilenmez).
     */
    public static final long HANDOVER_WAIT_MS = EvalStateSink.HANDOVER_BUDGET_MS + 1_000L;
    /** LOADING'in gerçek zamanlı üst sınırı = son tarih + bu pay (enjekte saat takılsa da yükleme biter). */
    static final long HARD_CAP_SLACK_MS = 1_000L;
    /** Bekleme dilimi (ms) — enjekte saatin ilerlemesi ve kaçırılan uyarı en geç bu sürede görülür. */
    static final long WAIT_SLICE_MS = 50L;
    /** Bu kadar uzun bekleyen giriş DEBUG satırı bırakır (ms). */
    static final long SLOW_WAIT_LOG_MS = 1_000L;

    /** Giriş bileti — {@code try (var t = gate.enter()) { … }}; {@link #close()} asla fırlatmaz. */
    public interface Ticket extends AutoCloseable {
        @Override
        void close();
    }

    /** Kapıdan geçmeyen (boş toplu iş) çağrının bileti — hiçbir şey yapmaz. */
    public static final Ticket NO_TICKET = () -> { };

    /**
     * Bir belirtecin LOADING'inin bitişi.
     *
     * @param token     biten yükleme belirteci
     * @param reason    {@link EvalLoadReport} neden kodu (uygulandıysa / atlandıysa null)
     * @param maxWaitMs ilk bekleyen girişin beklediği süre (MOS saati; bekleyen yoksa 0)
     * @param waiters   bu yüklemede bekleyen üst düzey değerlendirme sayısı
     */
    public record Ended(long token, String reason, long maxWaitMs, int waiters) {}

    /**
     * {@link #apply} sonucu.
     *
     * @param applied gövde uygulandı (rapor = yükün katmanı)
     * @param stale   belirteç artık güncel değil — bu yükleme başka yolla zaten bitti ve raporlandı (çağıran rapor YAZMAZ)
     * @param reason  atıldıysa neden kodu ({@link EvalLoadReport}); uygulandıysa null
     */
    public record ApplyOutcome(boolean applied, boolean stale, String reason, long maxWaitMs, int waiters) {}

    private final LongSupplier clock;
    private final Consumer<Ended> onExpired;
    private final LongSupplier leadershipSeq;
    private final long applyQuiesceMs;

    private volatile State state = State.NONE;
    private volatile long token;
    private volatile long deadlineMs;
    private volatile long hardDeadlineNanos;
    private volatile long handoverDeadlineNanos;
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Sayılan zincir adımları (teyit / kurtarma yeniden kontrolü + ardından gelen bellek değişikliği). */
    private final AtomicInteger chainSteps = new AtomicInteger();
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    // izleyici altında:
    private int waiters;
    private long firstWaitAtMs = -1L;
    private long endedToken;
    private String endedReason;
    /** Kurulum anında uçuşta değerlendirme vardı ("araya sızma") — uygulama yükü atar (inceleme L-3). */
    private boolean slipIn;
    /** Kurulum anındaki liderlik sırası — uygulama gövdeden önce ve sonra karşılaştırır (inceleme L-1). */
    private long armSeq;

    private final Ticket ticket = this::exit;
    private final Ticket chainTicket = this::exitChainStep;

    /**
     * @param clock     MOS saati (ms) — son tarih ve bekleme ölçüsü bununla
     * @param onExpired bir bekleyen ya da belirteç okuması yüklemenin süresinin dolduğunu anladığında, izleyici DIŞINDA bir
     *                  kez çağrılır (null = çağrılmaz); istisnası yutulur
     */
    public EvalLoadGate(LongSupplier clock, Consumer<Ended> onExpired) {
        this(clock, onExpired, null);
    }

    /**
     * @param leadershipSeq liderlik olaylarının sırası (MOS dinleyicisi her olayın BAŞINDA artırır; null = hiç değişmez) —
     *                      kurulumdan sonra değiştiyse uygulama {@code leadership_changed} olur
     */
    public EvalLoadGate(LongSupplier clock, Consumer<Ended> onExpired, LongSupplier leadershipSeq) {
        this(clock, onExpired, leadershipSeq, APPLY_QUIESCE_MS);
    }

    /** Testler: uygulamanın durulma beklemesi üst sınırı ayarlanabilir. */
    EvalLoadGate(LongSupplier clock, Consumer<Ended> onExpired, LongSupplier leadershipSeq, long applyQuiesceMs) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.onExpired = onExpired;
        this.leadershipSeq = leadershipSeq == null ? () -> 0L : leadershipSeq;
        this.applyQuiesceMs = Math.max(0L, applyQuiesceMs);
    }

    // ── Kurulum / sökme (liderlik dinleyicisi; yalnız bellek) ──────────────────────────────────────────────────────

    /**
     * LOADING'e geçer (GAINED). Hâlâ LOADING olan eski bir belirteç varsa {@code leadership_changed} ile biter ve döner.
     *
     * @param token      yeni yükleme belirteci (&gt; 0)
     * @param deadlineMs son tarih (MOS saati)
     * @return biten eski yükleme ya da null
     */
    public synchronized Ended arm(long token, long deadlineMs) {
        Ended prev = endLoadingLocked(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        long now = clock.getAsLong();
        this.token = token;
        this.deadlineMs = deadlineMs;
        this.hardDeadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, deadlineMs - now) + HARD_CAP_SLACK_MS);
        this.waiters = 0;
        this.firstWaitAtMs = -1L;
        this.armSeq = readSeq();
        this.state = State.LOADING;
        // L-3: durum yazıldıktan SONRA okunur — NONE görüp geçen her giriş burada görünür (sayım okumadan önce artar).
        this.slipIn = inFlight.get() > 0;
        notifyAll();
        return prev;
    }

    /**
     * Kapıyı açar (LOST / RELEASED ya da GAINED'de kurulum reddi): LOADING ise {@code leadership_changed} ile biter ve
     * döner; HANDOVER da biter (lider bırakıldı). Bekleyenler uyanır.
     */
    public synchronized Ended disarm() {
        Ended prev = endLoadingLocked(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        if (state == State.HANDOVER) {
            state = State.NONE;
            notifyAll();
        }
        return prev;
    }

    // ── Giriş ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Değerlendirme girişi. NONE: sayar ve hemen döner. LOADING / HANDOVER: kapı açılana ya da son tarihe dek bekler,
     * sonra sayar. İç içe çağrı (bu iş parçacığı zaten içeride) beklemez, yeniden sayılmaz. İstisna fırlatmaz; yalnız
     * zaman aşımı bildiriminden bir {@link Error} geçebilir — o zaman sayım ve derinlik geri alınır (inceleme L-2: aksi
     * hâlde sayaç kalıcı ≥ 1 kalır, iş parçacığı sonsuza dek "iç içe" sayılırdı).
     */
    public Ticket enter() {
        int[] d = depth.get();
        if (d[0] > 0) {
            d[0]++;
            return ticket;
        }
        inFlight.incrementAndGet();
        if (state == State.NONE) {
            d[0] = 1;
            return ticket;
        }
        leave();
        long startNanos = System.nanoTime();
        Ended expired = slowEnter();   // sayımı izleyici altında SON adım olarak yapar — ardından fırlatabilecek bir şey yok
        d[0] = 1;
        try {
            logSlowWait(startNanos);
            fireExpired(expired);
        } catch (Throwable t) {
            d[0] = 0;
            leave();
            throw t;
        }
        return ticket;
    }

    private Ended slowEnter() {
        long startMs = clock.getAsLong();
        Ended expired = null;
        boolean interrupted = false;
        boolean counted = false;
        synchronized (this) {
            while (true) {
                State s = state;
                if (s == State.NONE) break;
                if (s == State.HANDOVER) {
                    long left = handoverDeadlineNanos - System.nanoTime();
                    if (left <= 0) break;   // pod kapanıyor — geç (zamanlanmış sonuç zaten çitli)
                    if (waitSlice(Math.min(WAIT_SLICE_MS, ceilMs(left)))) { interrupted = true; break; }
                    continue;
                }
                if (!counted) {
                    counted = true;
                    waiters++;
                    if (firstWaitAtMs < 0) firstWaitAtMs = startMs;
                }
                if (pastDeadlineLocked()) {
                    expired = endLoadingLocked(EvalLoadReport.REASON_TIMEOUT);
                    break;
                }
                long left = Math.max(1L, deadlineMs - clock.getAsLong());
                if (waitSlice(Math.min(WAIT_SLICE_MS, left))) { interrupted = true; break; }
            }
            inFlight.incrementAndGet();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return expired;
    }

    private void logSlowWait(long startNanos) {
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (waitedMs >= SLOW_WAIT_LOG_MS && log.isDebugEnabled()) {
            log.debug("Değerlendirme devir kapısında {} ms bekledi (durum {})", waitedMs, state);
        }
    }

    private void exit() {
        int[] d = depth.get();
        if (d[0] > 1) {
            d[0]--;
            return;
        }
        d[0] = 0;
        leave();
    }

    private void leave() {
        if (inFlight.decrementAndGet() <= 0 && state != State.NONE) {
            synchronized (this) {
                notifyAll();
            }
        }
    }

    // ── Zincir adımları (inceleme 2026-10-28, M-1 yol 2) ─────────────────────────────────────────────────────────────

    /**
     * Teyit / kurtarma zinciri adımının girişi — beklemez, kapıdan geçmez; yalnız sayılır, böylece devirde
     * {@link #awaitQuiescent} yeniden kontrolün ve ardından gelen bellek değişikliğinin bitmesini de bekler. Kapı
     * HANDOVER'daysa adım SAYILMAZ ve null döner: adım belleğe dokunmadan çıkmalıdır (pod kapanıyor; yeni lider zincirleri
     * yeniden başlatır, karar D-2). Ayar kapalıyken kapı hiç HANDOVER olmaz → her zaman bilet (bir sayaç artırma + bir
     * volatile okuma). Asla fırlatmaz.
     *
     * @return bilet ({@code try (bilet) { … }}) ya da null = HANDOVER, adım çıkmalı
     */
    public Ticket enterChainStep() {
        chainSteps.incrementAndGet();
        if (state == State.HANDOVER) {   // sayım okumadan önce artar — devrin durulma beklemesi bu adımı görür ya da adım devri
            exitChainStep();
            return null;
        }
        return chainTicket;
    }

    private void exitChainStep() {
        if (chainSteps.decrementAndGet() <= 0 && state != State.NONE) {
            synchronized (this) {
                notifyAll();
            }
        }
    }

    // ── Yükleme belirteci / bitiş ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Bekleyen yüklemenin belirteci; LOADING değilse ya da süresi dolduysa 0 (dolduysa yükleme şimdi {@code timeout} ile
     * biter ve {@code onExpired} çağrılır). Yalnız bellek.
     */
    public long loadingToken() {
        if (state != State.LOADING) return 0L;
        Ended expired = null;
        long t;
        synchronized (this) {
            if (state != State.LOADING) return 0L;
            if (pastDeadlineLocked()) {
                expired = endLoadingLocked(EvalLoadReport.REASON_TIMEOUT);
                t = 0L;
            } else {
                t = token;
            }
        }
        fireExpired(expired);
        return t;
    }

    /** Süresi dolmuş bir yükleme varsa şimdi bitirir (rapor anında güncel olsun). */
    public void expireIfDue() {
        loadingToken();
    }

    /**
     * Kaydedici / yükleyici bu belirteç için hiçbir şey yüklemeyecek: kapı hâlâ bu belirteçle LOADING ise açılır.
     *
     * @return bitiş (çağıran raporu yazar) ya da null = belirteç eski (yok sayılır)
     */
    public synchronized Ended skip(long token) {
        if (state != State.LOADING || this.token != token || token <= 0) return null;
        return endLoadingLocked(null);
    }

    /**
     * Yükü uygular (tasarım §3.3 adım 1–4 ve 7; adım 5–6 = {@code body}): belirteç hâlâ LOADING değilse eski;
     * son tarih geçtiyse {@code timeout} (bekleme sırasında da: MOS saati son tarihi ya da gerçek zamanlı üst sınır —
     * inceleme C-1); ayar kapandıysa {@code setting_turned_off}; uçuştaki değerlendirmeler {@link #APPLY_QUIESCE_MS}
     * içinde bitmezse {@code evaluation_busy}; kurulum anında uçuşta değerlendirme vardıysa {@code evaluated_before_load}
     * (inceleme L-3); kurulumdan sonra bir liderlik olayı başladıysa {@code leadership_changed} (inceleme L-1). Sonra izleyici
     * TUTULURKEN {@code body} koşar: null = uygulandı, aksi hâlde atma nedeni. Gövde sürerken bir liderlik olayı başladıysa
     * yine {@code leadership_changed} — olayın dinleyicisi sökmede bu izleyiciyi bekler ve ardından belleği sıfırlar (gövdenin
     * yazdığı silinir). İstisna = {@code read_failed} (gövde yarım yazmamalı). Kesme = {@code recorder_closing}. Asla
     * fırlatmaz.
     */
    public ApplyOutcome apply(long token, BooleanSupplier enabled, Supplier<String> body) {
        synchronized (this) {
            if (state != State.LOADING || this.token != token || token <= 0) return staleLocked(token);
            if (pastDeadlineLocked()) return endedOutcome(EvalLoadReport.REASON_TIMEOUT);
            if (!enabledSafe(enabled)) return endedOutcome(EvalLoadReport.REASON_SETTING_TURNED_OFF);
            long quiesceEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(applyQuiesceMs);
            while (inFlight.get() > 0) {
                if (pastDeadlineLocked()) return endedOutcome(EvalLoadReport.REASON_TIMEOUT);
                long leftNanos = quiesceEnd - System.nanoTime();
                if (leftNanos <= 0) return endedOutcome(EvalLoadReport.REASON_EVALUATION_BUSY);
                long leftClock = Math.max(1L, deadlineMs - clock.getAsLong());
                if (waitSlice(Math.min(WAIT_SLICE_MS, Math.min(ceilMs(leftNanos), leftClock)))) {
                    Thread.currentThread().interrupt();
                    if (state != State.LOADING || this.token != token) return staleLocked(token);
                    return endedOutcome(EvalLoadReport.REASON_RECORDER_CLOSING);
                }
                if (state != State.LOADING || this.token != token) return staleLocked(token);
            }
            if (!enabledSafe(enabled)) return endedOutcome(EvalLoadReport.REASON_SETTING_TURNED_OFF);
            if (slipIn) return endedOutcome(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
            if (leadershipChangedLocked()) return endedOutcome(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
            String why;
            try {
                why = body == null ? null : body.get();
            } catch (RuntimeException e) {
                why = EvalLoadReport.REASON_READ_FAILED;
            }
            if (why == null && leadershipChangedLocked()) why = EvalLoadReport.REASON_LEADERSHIP_CHANGED;
            Ended e = endLoadingLocked(why);
            return new ApplyOutcome(why == null, false, why, e == null ? 0L : e.maxWaitMs(), e == null ? 0 : e.waiters());
        }
    }

    /** Kurulumdan sonra bir liderlik olayı başladı mı (sıra okunamadıysa: evet — güvenli taraf). */
    private boolean leadershipChangedLocked() {
        long cur = readSeq();
        return cur == SEQ_UNREADABLE || armSeq == SEQ_UNREADABLE || cur != armSeq;
    }

    private static final long SEQ_UNREADABLE = Long.MIN_VALUE;

    private long readSeq() {
        try {
            return leadershipSeq.getAsLong();
        } catch (RuntimeException e) {
            return SEQ_UNREADABLE;
        }
    }

    private ApplyOutcome staleLocked(long t) {
        String why = t == endedToken && endedReason != null ? endedReason : EvalLoadReport.REASON_LEADERSHIP_CHANGED;
        return new ApplyOutcome(false, true, why, 0L, 0);
    }

    private ApplyOutcome endedOutcome(String reason) {
        Ended e = endLoadingLocked(reason);
        return new ApplyOutcome(false, false, reason, e == null ? 0L : e.maxWaitMs(), e == null ? 0 : e.waiters());
    }

    private static boolean enabledSafe(BooleanSupplier s) {
        if (s == null) return false;
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** İzleyici altında: LOADING ise biter (NONE + uyarı) ve bitişi döner; değilse null. */
    private Ended endLoadingLocked(String reason) {
        if (state != State.LOADING) return null;
        long now = clock.getAsLong();
        long maxWait = firstWaitAtMs < 0 ? 0L : Math.max(0L, now - firstWaitAtMs);
        Ended e = new Ended(token, reason, maxWait, waiters);
        state = State.NONE;
        endedToken = token;
        endedReason = reason;
        notifyAll();
        return e;
    }

    private boolean pastDeadlineLocked() {
        return clock.getAsLong() >= deadlineMs || System.nanoTime() - hardDeadlineNanos >= 0;
    }

    private void fireExpired(Ended e) {
        if (e == null || onExpired == null) return;
        try {
            onExpired.accept(e);
        } catch (RuntimeException ex) {
            log.debug("Devir kapısı zaman aşımı bildirimi hata verdi: {}", ex.toString());
        }
    }

    // ── Devir (zarif kapanış) ───────────────────────────────────────────────────────────────────────────────────────

    /** NONE → HANDOVER (yeni girişler en çok {@link #HANDOVER_WAIT_MS} bekler). LOADING'de değişmez (girişler zaten bekler). */
    public synchronized void beginHandover() {
        if (state != State.NONE) return;
        handoverDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDOVER_WAIT_MS);
        state = State.HANDOVER;
    }

    /**
     * Uçuştaki değerlendirmelerin VE zincir adımlarının ({@link #enterChainStep}) bitmesini en çok {@code maxMs} (gerçek
     * zaman, ikisi için tek bütçe) bekler. Ayar kapalıyken çağrılmaz (devir adımı koşmaz).
     *
     * @return uçuşta değerlendirme ve zincir adımı kalmadıysa true (false = devir işaretsiz boşaltılır)
     */
    public boolean awaitQuiescent(long maxMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, maxMs));
        synchronized (this) {
            while (!quiescent()) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                if (waitSlice(Math.min(WAIT_SLICE_MS, ceilMs(left)))) {
                    Thread.currentThread().interrupt();
                    return quiescent();
                }
            }
            return true;
        }
    }

    private boolean quiescent() {
        return inFlight.get() <= 0 && chainSteps.get() <= 0;
    }

    /** HANDOVER → NONE (tekrar çağrılabilir). */
    public synchronized void endHandover() {
        if (state == State.HANDOVER) {
            state = State.NONE;
            notifyAll();
        }
    }

    // ── Tanı ──────────────────────────────────────────────────────────────────────────────────────────────────────

    public State state() {
        return state;
    }

    /** Uçuştaki üst düzey değerlendirme sayısı. */
    public int inFlight() {
        return inFlight.get();
    }

    /** Sayılan (sürmekte olan) zincir adımı sayısı. */
    public int chainStepsInFlight() {
        return chainSteps.get();
    }

    /** Güncel / son yüklemenin belirteci (durumdan bağımsız; tanı / test). */
    public long currentToken() {
        return token;
    }

    /** Güncel yüklemede bekleyen üst düzey değerlendirme sayısı. */
    public synchronized int waiters() {
        return waiters;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────────

    /** İzleyici altında en çok {@code ms} bekler; kesildiyse true (bayrak temizlenmiş olur — çağıran geri koyar). */
    private boolean waitSlice(long ms) {
        try {
            wait(Math.max(1L, ms));
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    private static long ceilMs(long nanos) {
        return Math.max(1L, (nanos + 999_999L) / 1_000_000L);
    }
}
