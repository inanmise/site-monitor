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
 *       (gerçek zaman), sonra geçer (pod kapanıyor; zamanlanmış sonuçlar zaten çitli).</li>
 * </ul>
 *
 * <p><b>Uygulama (J-3).</b> {@link #apply} yükü yalnız kapı hâlâ bu belirteçle LOADING iken, uçuşta değerlendirme kalmayınca
 * (en çok {@link #APPLY_QUIESCE_MS}) ve kapının izleyicisi TUTULURKEN gövdeyi koşturur — LOADING'de yeni giriş izleyiciyi
 * beklediği için gövde ile hiçbir değerlendirme iç içe geçmez. Gövde yalnız harita yazar (MOS boş bellek denetimini de
 * orada yapar); birleştirme asla denenmez.
 *
 * <p><b>Uçuştaki sayım.</b> Kapı NONE iken giren her üst düzey değerlendirme sayılır — kurulumdan HEMEN önce girip
 * kurulumdan sonra belleğe yazan değerlendirme de ("araya sızma"): uygulama onun bitmesini bekler, sonra MOS belleği boş
 * bulmaz ve yükü atar ({@code evaluated_before_load}). İç içe çağrı ({@code handleDnsSweep → handleSweepResults}) iş
 * parçacığı yerel derinlikle tanınır ve yeniden sayılmaz / beklemez.
 *
 * <p><b>Her bekleme sınırlıdır.</b> Bekleme dilimleri {@link #WAIT_SLICE_MS}; enjekte saat takılsa / geri sarılsa da
 * gerçek zamanlı üst sınır ({@code son tarih − kurulum + HANDOVER_WAIT_MS}) yüklemeyi sona erdirir. Kesme (interrupt)
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
    /** HANDOVER'da bir giriş en çok bu kadar bekler (ms, gerçek zaman; {@code beginHandover}'dan itibaren). */
    public static final long HANDOVER_WAIT_MS = 1_000L;
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

    private volatile State state = State.NONE;
    private volatile long token;
    private volatile long deadlineMs;
    private volatile long hardDeadlineNanos;
    private volatile long handoverDeadlineNanos;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    // izleyici altında:
    private int waiters;
    private long firstWaitAtMs = -1L;
    private long endedToken;
    private String endedReason;

    private final Ticket ticket = this::exit;

    /**
     * @param clock     MOS saati (ms) — son tarih ve bekleme ölçüsü bununla
     * @param onExpired bir bekleyen ya da belirteç okuması yüklemenin süresinin dolduğunu anladığında, izleyici DIŞINDA bir
     *                  kez çağrılır (null = çağrılmaz); istisnası yutulur
     */
    public EvalLoadGate(LongSupplier clock, Consumer<Ended> onExpired) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.onExpired = onExpired;
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
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, deadlineMs - now) + HANDOVER_WAIT_MS);
        this.waiters = 0;
        this.firstWaitAtMs = -1L;
        this.state = State.LOADING;
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
     * sonra sayar. İç içe çağrı (bu iş parçacığı zaten içeride) beklemez, yeniden sayılmaz. Asla fırlatmaz.
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
        Ended expired = slowEnter();
        d[0] = 1;
        fireExpired(expired);
        return ticket;
    }

    private Ended slowEnter() {
        long startMs = clock.getAsLong();
        long startNanos = System.nanoTime();
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
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (waitedMs >= SLOW_WAIT_LOG_MS && log.isDebugEnabled()) {
            log.debug("Değerlendirme devir kapısında {} ms bekledi (durum {})", waitedMs, state);
        }
        return expired;
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
     * son tarih geçtiyse {@code timeout}; ayar kapandıysa {@code setting_turned_off}; uçuştaki değerlendirmeler
     * {@link #APPLY_QUIESCE_MS} (ve son tarih) içinde bitmezse {@code evaluation_busy}. Sonra izleyici TUTULURKEN
     * {@code body} koşar: null = uygulandı, aksi hâlde atma nedeni. İstisna = {@code read_failed} (gövde yarım yazmamalı).
     * Kesme = {@code recorder_closing}. Asla fırlatmaz.
     */
    public ApplyOutcome apply(long token, BooleanSupplier enabled, Supplier<String> body) {
        synchronized (this) {
            if (state != State.LOADING || this.token != token || token <= 0) return staleLocked(token);
            if (pastDeadlineLocked()) return endedOutcome(EvalLoadReport.REASON_TIMEOUT);
            if (!enabledSafe(enabled)) return endedOutcome(EvalLoadReport.REASON_SETTING_TURNED_OFF);
            long quiesceEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(APPLY_QUIESCE_MS);
            while (inFlight.get() > 0) {
                long leftNanos = quiesceEnd - System.nanoTime();
                long leftClock = deadlineMs - clock.getAsLong();
                if (leftNanos <= 0 || leftClock <= 0) return endedOutcome(EvalLoadReport.REASON_EVALUATION_BUSY);
                if (waitSlice(Math.min(WAIT_SLICE_MS, Math.min(ceilMs(leftNanos), leftClock)))) {
                    Thread.currentThread().interrupt();
                    if (state != State.LOADING || this.token != token) return staleLocked(token);
                    return endedOutcome(EvalLoadReport.REASON_RECORDER_CLOSING);
                }
                if (state != State.LOADING || this.token != token) return staleLocked(token);
            }
            if (!enabledSafe(enabled)) return endedOutcome(EvalLoadReport.REASON_SETTING_TURNED_OFF);
            String why;
            try {
                why = body == null ? null : body.get();
            } catch (RuntimeException e) {
                why = EvalLoadReport.REASON_READ_FAILED;
            }
            Ended e = endLoadingLocked(why);
            return new ApplyOutcome(why == null, false, why, e == null ? 0L : e.maxWaitMs(), e == null ? 0 : e.waiters());
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
     * Uçuştaki değerlendirmelerin bitmesini en çok {@code maxMs} (gerçek zaman) bekler.
     *
     * @return uçuşta değerlendirme kalmadıysa true
     */
    public boolean awaitQuiescent(long maxMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, maxMs));
        synchronized (this) {
            while (inFlight.get() > 0) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                if (waitSlice(Math.min(WAIT_SLICE_MS, ceilMs(left)))) {
                    Thread.currentThread().interrupt();
                    return inFlight.get() <= 0;
                }
            }
            return true;
        }
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
