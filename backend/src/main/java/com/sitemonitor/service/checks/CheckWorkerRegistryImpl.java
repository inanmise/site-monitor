package com.sitemonitor.service.checks;

import com.sitemonitor.service.AppVersion;
import com.sitemonitor.service.PingCheckerService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.evalstate.EvalStateSql;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link CheckWorkerRegistry} — {@code monitor_check_workers} (2026-10-29, çok pod Sürüm 3-3 paket A; tasarım R3-3 §3.1 / §5.3
 * / §6).
 *
 * <p><b>Ne yapar (yalnız ana anahtar AÇIKKEN):</b> kendi iş parçacığında ({@value #THREAD}, daemon) {@link #HEARTBEAT_MS}'de
 * bir (1) bu pod işçiyse kendi satırını yazar (yetenekler, yuvalar, meşgul, tamamlanan, geç kalan, sahiplenme gecikmesi p95,
 * sürüm; PostgreSQL {@code ON CONFLICT (instance_id) DO UPDATE}, H2 UPDATE → {@code INSERT … ON CONFLICT DO NOTHING} →
 * UPDATE), (2) tüm satırları okuyup belleğe alır ({@link #liveOtherWorkers}, {@link #workers} bu önbellekten okur — çağıran iş
 * parçacığında sorgu YOK), (3) tarama lideriyse saatte bir 1 günden eski nabızlı satırları siler. Yerel yetenek yoklaması
 * ayrı iş parçacığında ({@value #PROBE_THREAD}): ICMP sondası ({@code PingCheckerService.probeIcmp()}, bloklayıcı) başlangıçta +
 * {@link #ICMP_PROBE_EVERY_MS}'de bir (ICMP "yok" tutma süresinden 60 sn kısa), k6 yoklaması
 * ({@code ScriptedCheckerService.ensureProbed()}) 30 sn'de bir.
 *
 * <p><b>ping yeteneği</b> (inceleme QW-L1): yalnız SON ICMP SONDASI başarılıysa VE {@code icmpAvailable()} (son 5 dk'da bir ping
 * sonucu "na" değil) ise ilan edilir. İlk sonda dönmeden ping YOKTUR (açılıştaki iyimserlik kalktı); sondanın bulduğu "yok"
 * KALICIDIR — "na" tutma süresi dolsa da bir sonraki BAŞARILI sondaya dek ping ilan edilmez / sahiplenilmez (ICMP'siz pod 5 dk'da
 * bir birkaç saniye ping almaz, lider o pencerede türü dağıtığa çevirmez).
 *
 * <p><b>Okuma hatası</b> (inceleme QW-L6): tek başarısız okuma önceki İYİ anlık görüntüyü korur — o görüntü {@link #LIVE_MS}'den
 * gençken ({@code liveOtherWorkers} onu en çok {@link #CACHE_STALE_MS} kullanır); ancak sonra {@link #UNKNOWN}. Böylece tek bir
 * veritabanı hıçkırığı her dağıtık türü klasiğe çevirip geri getirmez.
 *
 * <p><b>K-1:</b> iş parçacıkları {@link DistributedChecks#whenActivated} ile TEMBEL başlar — anahtar açılıştan beri kapalıysa
 * hiçbiri yoktur, hiçbir satır yazılmaz / okunmaz. Sonradan kapatılınca döngüler bellek okumasıyla boşta döner (yazma yok;
 * satır yaşlanır, lider canlı saymaz).
 *
 * <p><b>Canlı:</b> son nabız {@link #LIVE_MS} içinde (DB saati) VE {@code stopping} değil. "Başka" = örnek kimliği bu pod'un
 * değil VE pod adı da bu pod'un değil (aynı pod adının önceki süreci — SIGKILL / OOM sonrası — işçi sayılmaz; R3-1 FX-SIDE #3
 * kuralı). Okunamadı / henüz okunmadı / önbellek {@link #CACHE_STALE_MS}'ten eski → {@link #UNKNOWN} (çağıran klasik kipe düşer).
 *
 * <p><b>Kapanış:</b> {@code ContextClosedEvent} (sıra {@code HIGHEST_PRECEDENCE + 2}, kira bırakılıp ölçüm aktarıcısı
 * işaretlendikten sonra) yalnız bayrak + gerekiyorsa daemon yardımcı iş parçacığında "duruyor" satırı — BEKLEME YOK.
 */
@Slf4j
@Component
public class CheckWorkerRegistryImpl implements CheckWorkerRegistry {

    public static final String THREAD = "check-worker-registry";
    public static final String PROBE_THREAD = "check-worker-probe";
    public static final String STOP_THREAD = "check-worker-stop";
    /** Budama sıklığı (yalnız lider). */
    public static final long PRUNE_EVERY_MS = 3_600_000L;
    /** ICMP yeniden sonda aralığı = "na" tutma süresi − 60 sn (her tutma penceresinde en az bir sonda). */
    public static final long ICMP_PROBE_EVERY_MS = PingCheckerService.ICMP_NA_HOLD_MS - 60_000L;
    /** k6 yoklama aralığı ({@code ensureProbed} k6 yokken kendisi 5 dk'da bir yeniden dener). */
    public static final long K6_PROBE_EVERY_MS = 30_000L;
    /** Önbellek bundan eskiyse (kayıt iş parçacığı takıldı) sonuç {@link #UNKNOWN}. */
    public static final long CACHE_STALE_MS = 3 * READ_CACHE_MS;
    /** Okunan en çok satır. */
    static final int MAX_ROWS = 200;

    /** İşçinin kalp atışına koyduğu yerel sayılar ({@link CheckWorker#localStats()}). */
    public record LocalStats(int slots, int busy, long completed, long late, Long claimLagP95Ms) {
        public static final LocalStats IDLE = new LocalStats(0, 0, 0L, 0L, null);
    }

    /** Kayıt durumu (System Health / testler — paket D okur). */
    public record Status(boolean started, boolean closing, long heartbeats, long heartbeatFailures, long reads, long readFailures,
                         Long lastReadAgeMs, int knownWorkers, long pruned, boolean tableMissing) {}

    private record Row(String instanceId, String pod, Set<String> caps, int slots, int busy, long completed, long late,
                       Long claimLagP95Ms, long heartbeatMs, String version, boolean stopping) {}

    /** Son okuma: satırlar + okuma anı (DB saati) + monoton an; {@code ok=false} = okunamadı. */
    private record Snapshot(boolean ok, List<Row> rows, long readDbMs, long readNano) {}

    private final JdbcTemplate jdbc;
    private final DbClock clock;
    private final DistributedChecks distributedChecks;
    private final String selfId;
    private final BooleanSupplier leaderHint;
    private final BooleanSupplier icmp;
    private final BooleanSupplier icmpProbe;
    private final BooleanSupplier k6;
    private final BooleanSupplier k6Probe;
    private final BooleanSupplier runnerPresent;
    private final Supplier<LocalStats> localStats;
    private final Supplier<String> version;
    private final LongSupplier nanoClock;

    @Autowired(required = false)
    private ApplicationContext applicationContext;

    private volatile Snapshot cache;
    private volatile Boolean postgres;
    private volatile boolean started;
    private volatile boolean closing;
    private volatile long lastPruneNano = Long.MIN_VALUE;
    private volatile Thread loopThread;
    private volatile Thread probeThread;
    private volatile String cachedVersion;
    private volatile boolean tableMissing;
    /** Son ICMP sondasının sonucu: null = henüz sonda yok (ping ilan edilmez), TRUE / FALSE = son sonda. */
    private volatile Boolean icmpProbeOk;
    private final AtomicLong icmpProbes = new AtomicLong();
    private final AtomicLong keptAfterReadFailure = new AtomicLong();
    private final Object writeLock = new Object();
    private final AtomicLong heartbeats = new AtomicLong();
    private final AtomicLong heartbeatFailures = new AtomicLong();
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();
    private final AtomicLong pruned = new AtomicLong();
    private final AtomicLong warnAt = new AtomicLong(Long.MIN_VALUE);

    /** Spring kurucusu — her işbirlikçi tembel (sağlayıcı) çözülür; bağımlılık döngüsü yok. */
    @Autowired
    public CheckWorkerRegistryImpl(JdbcTemplate jdbc, DbClock clock, DistributedChecks distributedChecks,
                                   ObjectProvider<SchedulerService> scheduler, ObjectProvider<PingCheckerService> ping,
                                   ObjectProvider<ScriptedCheckerService> scripted, ObjectProvider<CheckJobRunner> runner,
                                   ObjectProvider<CheckWorker> worker, Environment environment) {
        this(EvalStateSql.withQueryTimeout(jdbc, CheckJobSql.QUERY_TIMEOUT_SECONDS), clock, distributedChecks,
                SchedulerService.instanceId(),
                () -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s != null && s.sweepLeaderHint();
                },
                () -> {
                    PingCheckerService p = ping.getIfAvailable();
                    return p != null && p.icmpAvailable();
                },
                () -> {
                    PingCheckerService p = ping.getIfAvailable();
                    return p != null && p.probeIcmp();
                },
                () -> {
                    ScriptedCheckerService s = scripted.getIfAvailable();
                    return s != null && s.isAvailable();
                },
                () -> {
                    ScriptedCheckerService s = scripted.getIfAvailable();
                    return s != null && s.ensureProbed();
                },
                () -> runner.getIfAvailable() != null,
                () -> {
                    CheckWorker w = worker.getIfAvailable();
                    return w == null ? LocalStats.IDLE : w.localStats();
                },
                () -> AppVersion.resolve(environment),
                System::nanoTime);
    }

    /** Test / elle kurulum: her yetenek ve sayı bir tedarikçi; {@code selfId} bu pod'un örnek kimliği. */
    public CheckWorkerRegistryImpl(JdbcTemplate jdbc, DbClock clock, DistributedChecks distributedChecks, String selfId,
                                   BooleanSupplier leaderHint, BooleanSupplier icmp, BooleanSupplier icmpProbe, BooleanSupplier k6,
                                   BooleanSupplier k6Probe, BooleanSupplier runnerPresent, Supplier<LocalStats> localStats,
                                   Supplier<String> version, LongSupplier nanoClock) {
        this.jdbc = jdbc;
        this.clock = clock == null ? DbClock.SYSTEM : clock;
        this.distributedChecks = distributedChecks;
        this.selfId = selfId == null ? "?" : selfId;
        this.leaderHint = leaderHint == null ? () -> false : leaderHint;
        this.icmp = icmp == null ? () -> false : icmp;
        this.icmpProbe = icmpProbe == null ? this.icmp : icmpProbe;
        this.k6 = k6 == null ? () -> false : k6;
        this.k6Probe = k6Probe == null ? this.k6 : k6Probe;
        this.runnerPresent = runnerPresent == null ? () -> false : runnerPresent;
        this.localStats = localStats == null ? () -> LocalStats.IDLE : localStats;
        this.version = version == null ? () -> "unknown" : version;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
    }

    /** Tembel başlatıcı kaydı — kurucu ve bu yöntem sorgu / iş parçacığı / günlük üretmez (K-1). */
    @PostConstruct
    public void register() {
        if (distributedChecks != null) distributedChecks.whenActivated(THREAD, this::start);
    }

    // ── Okumalar (yalnız bellek) ────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int liveOtherWorkers(String type) {
        Snapshot s = cache;
        if (s == null || !s.ok() || type == null) return UNKNOWN;
        long age = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - s.readNano());
        if (age < 0 || age > CACHE_STALE_MS) return UNKNOWN;
        long nowDb = s.readDbMs() + age;
        int n = 0;
        for (Row r : s.rows()) {
            if (isSelf(r.instanceId()) || !live(r, nowDb)) continue;
            if (r.caps().contains(type)) n++;
        }
        return n;
    }

    @Override
    public Set<String> localCapabilities() {
        if (distributedChecks == null || !distributedChecks.tuning().workerEnabled()) return Set.of();
        if (!safe(runnerPresent)) return Set.of();
        Set<String> caps = new LinkedHashSet<>(CheckJobTypes.ALL);
        if (!Boolean.TRUE.equals(icmpProbeOk) || !safe(icmp)) caps.remove(CheckJobTypes.PING);
        if (!safe(k6)) caps.remove(CheckJobTypes.SCRIPTED);
        return CheckJobTypes.canonical(caps);
    }

    @Override
    public List<WorkerView> workers() {
        Snapshot s = cache;
        if (s == null || !s.ok()) return List.of();
        long age = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - s.readNano()));
        long nowDb = s.readDbMs() + age;
        List<WorkerView> out = new ArrayList<>(s.rows().size());
        for (Row r : s.rows()) {
            out.add(new WorkerView(r.instanceId(), r.pod(), r.caps(), r.slots(), r.busy(), r.completed(), r.late(),
                    r.claimLagP95Ms(), r.heartbeatMs(), r.version(), r.stopping(), live(r, nowDb)));
        }
        return out;
    }

    /** Durum (bellek). */
    public Status status() {
        Snapshot s = cache;
        Long age = s == null ? null : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - s.readNano()));
        return new Status(started, closing, heartbeats.get(), heartbeatFailures.get(), reads.get(), readFailures.get(), age,
                s == null || !s.ok() ? 0 : s.rows().size(), pruned.get(), tableMissing);
    }

    /** Bu pod'un örnek kimliği. */
    public String selfId() {
        return selfId;
    }

    // ── Döngüler ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Başlatıcı ({@link DistributedChecks#whenActivated}) — kayıt ve yoklama iş parçacıklarını bir kez başlatır. */
    public synchronized void start() {
        if (started || closing) return;
        started = true;
        Thread t = new Thread(this::loop, THREAD);
        t.setDaemon(true);
        loopThread = t;
        t.start();
        if (distributedChecks != null && distributedChecks.tuning().workerEnabled()) {
            Thread p = new Thread(this::probeLoop, PROBE_THREAD);
            p.setDaemon(true);
            probeThread = p;
            p.start();
        }
    }

    private void loop() {
        while (!closing) {
            try {
                if (distributedChecks != null && distributedChecks.anyDistributed()) tick();
            } catch (Throwable e) {
                warn("Dağıtık kontrol işçi kaydı turu düştü (sonraki turda yeniden denenecek): {}", e.getClass().getSimpleName());
            }
            if (closing) break;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MS));
        }
    }

    private void probeLoop() {
        long nextIcmp = Long.MIN_VALUE;
        long nextK6 = Long.MIN_VALUE;
        while (!closing) {
            try {
                if (distributedChecks != null && distributedChecks.anyDistributed()) {
                    long now = nanoClock.getAsLong();
                    if (nextIcmp == Long.MIN_VALUE || now - nextIcmp >= 0) {
                        probeIcmpNow();
                        nextIcmp = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(ICMP_PROBE_EVERY_MS);
                    }
                    if (nextK6 == Long.MIN_VALUE || now - nextK6 >= 0) {
                        safe(k6Probe);
                        nextK6 = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(K6_PROBE_EVERY_MS);
                    }
                }
            } catch (Throwable e) {
                log.debug("Dağıtık kontrol yetenek yoklaması düştü: {}", e.getClass().getSimpleName());
            }
            if (closing) break;
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
        }
    }

    /**
     * Bir ICMP sondası (bloklayıcı; yoklama iş parçacığı — testler doğrudan çağırır): sonucu "son sonda" olarak saklar. Sondanın
     * "yok" sonucu bir sonraki başarılı sondaya dek kalır.
     *
     * @return sondanın sonucu
     */
    public boolean probeIcmpNow() {
        boolean ok = safe(icmpProbe);
        icmpProbeOk = ok;
        icmpProbes.incrementAndGet();
        return ok;
    }

    /** Son ICMP sondasının sonucu (null = henüz sonda yok). */
    public Boolean icmpProbeOk() {
        return icmpProbeOk;
    }

    /** Okuma hatasında önceki iyi görüntünün korunduğu tur sayısı (bellek). */
    public long keptAfterReadFailure() {
        return keptAfterReadFailure.get();
    }

    /** Bir kayıt turu (anahtar açıkken): kendi nabzı → okuma → (lider) budama. Testler doğrudan çağırır. */
    public void tick() {
        if (closing) return;
        if (distributedChecks != null && distributedChecks.tuning().workerEnabled()) heartbeat();
        refresh();
        pruneIfDue();
    }

    /** Kendi satırını yazar (kapanış başladıysa hiçbir şey). */
    void heartbeat() {
        LocalStats st;
        try {
            st = localStats.get();
        } catch (RuntimeException e) {
            st = LocalStats.IDLE;
        }
        if (st == null) st = LocalStats.IDLE;
        String caps = clip(String.join(",", localCapabilities()), CheckJobSql.CAPS_MAX);
        int slots = distributedChecks != null ? distributedChecks.tuning().slots() : st.slots();
        synchronized (writeLock) {
            if (closing) return;
            try {
                upsert(caps, slots, st, clock.nowMs(), false);
                heartbeats.incrementAndGet();
                tableMissing = false;
            } catch (BadSqlGrammarException e) {
                heartbeatFailures.incrementAndGet();
                tableMissing = !tableReadable();
                if (!tableMissing) warn("Dağıtık kontrol işçi nabzı yazılamadı: {}", e.getClass().getSimpleName());
            } catch (DataAccessException e) {
                heartbeatFailures.incrementAndGet();
                warn("Dağıtık kontrol işçi nabzı yazılamadı (sonraki turda yeniden denenecek): {}", e.getClass().getSimpleName());
            }
        }
    }

    private void upsert(String caps, int slots, LocalStats st, long nowMs, boolean stopping) {
        String pod = clip(podName(selfId), CheckJobSql.INSTANCE_MAX);
        String ver = clip(version(), CheckJobSql.VERSION_MAX);
        String id = clip(selfId, CheckJobSql.INSTANCE_MAX);
        SqlParameterValue p95 = new SqlParameterValue(Types.BIGINT, st.claimLagP95Ms());
        if (Boolean.TRUE.equals(postgres())) {
            jdbc.update(CheckJobSql.SQL_HEARTBEAT_PG, id, pod, caps, slots, st.busy(), st.completed(), st.late(), p95, nowMs,
                    ver, stopping);
            return;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            int n = jdbc.update(CheckJobSql.SQL_HEARTBEAT_UPDATE, pod, caps, slots, st.busy(), st.completed(), st.late(), p95,
                    nowMs, ver, stopping, id);
            if (n > 0) return;
            n = jdbc.update(CheckJobSql.SQL_HEARTBEAT_INSERT, id, pod, caps, slots, st.busy(), st.completed(), st.late(), p95,
                    nowMs, ver, stopping);
            if (n > 0) return;
        }
    }

    /**
     * Tüm satırları okuyup önbelleğe alır; hata → önceki İYİ görüntü {@link #LIVE_MS}'den gençse KORUNUR (okuma anı değişmez,
     * yaşlanmaya devam eder), değilse önbellek "okunamadı" ({@link #UNKNOWN}).
     */
    void refresh() {
        long nano = nanoClock.getAsLong();
        long nowDb = clock.nowMs();
        try {
            List<Row> rows = jdbc.query(CheckJobSql.SQL_WORKERS, CheckWorkerRegistryImpl::row, MAX_ROWS);
            cache = new Snapshot(true, List.copyOf(rows), nowDb, nano);
            reads.incrementAndGet();
            tableMissing = false;
        } catch (DataAccessException e) {
            readFailures.incrementAndGet();
            Snapshot prev = cache;
            long prevAge = prev == null ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(nano - prev.readNano());
            boolean kept = prev != null && prev.ok() && prevAge >= 0 && prevAge <= LIVE_MS;
            if (kept) {
                keptAfterReadFailure.incrementAndGet();
            } else {
                cache = new Snapshot(false, List.of(), nowDb, nano);
            }
            if (e instanceof BadSqlGrammarException && !tableReadable()) {
                tableMissing = true;
            } else if (kept) {
                warn("Dağıtık kontrol işçileri okunamadı — önceki okuma (en çok {} sn) kullanılıyor: {}",
                        TimeUnit.MILLISECONDS.toSeconds(LIVE_MS), e.getClass().getSimpleName());
            } else {
                warn("Dağıtık kontrol işçileri okunamadı — kontroller klasik kipte kalır: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Lider saatte bir, 1 günden eski nabızlı satırları siler. */
    void pruneIfDue() {
        if (!safe(leaderHint)) return;
        long nano = nanoClock.getAsLong();
        long last = lastPruneNano;
        if (last != Long.MIN_VALUE && TimeUnit.NANOSECONDS.toMillis(nano - last) < PRUNE_EVERY_MS) return;
        lastPruneNano = nano;
        try {
            int n = jdbc.update(CheckJobSql.SQL_PRUNE_WORKERS, clock.nowMs() - PRUNE_AFTER_MS);
            if (n > 0) {
                pruned.addAndGet(n);
                log.info("Dağıtık kontrol işçi kaydı: {} eski satır silindi", n);
            }
        } catch (DataAccessException e) {
            log.debug("Dağıtık kontrol işçi kaydı budanamadı: {}", e.getClass().getSimpleName());
        }
    }

    // ── Kapanış ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Kapanış: bayrak (yeni nabız yok) + başladıysa ve bu pod işçiyse "duruyor" satırı DAEMON yardımcı iş parçacığında —
     * kapanış iş parçacığı BEKLEMEZ, veritabanına gitmez. Başlamadıysa (anahtar açılıştan beri kapalı) hiçbir şey.
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public void onContextClosed(ContextClosedEvent event) {
        if (!thisContext(event == null ? null : event.getApplicationContext())) return;
        boolean wasStarted;
        synchronized (this) {
            if (closing) return;
            closing = true;
            wasStarted = started;
        }
        Thread loop = loopThread;
        if (loop != null) LockSupport.unpark(loop);
        Thread probe = probeThread;
        if (probe != null) LockSupport.unpark(probe);
        if (!wasStarted || distributedChecks == null || !distributedChecks.tuning().workerEnabled()) return;
        Thread helper = new Thread(this::writeStopping, STOP_THREAD);
        helper.setDaemon(true);
        helper.start();
    }

    /** Kendi satırını "duruyor" işaretler (yardımcı iş parçacığında). */
    void writeStopping() {
        synchronized (writeLock) {
            try {
                jdbc.update(CheckJobSql.SQL_MARK_STOPPING, clock.nowMs(), clip(selfId, CheckJobSql.INSTANCE_MAX));
            } catch (DataAccessException e) {
                log.debug("Dağıtık kontrol işçisi 'duruyor' işaretlenemedi: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Kapanış başladı mı. */
    public boolean closing() {
        return closing;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    private boolean live(Row r, long nowDb) {
        return !r.stopping() && r.heartbeatMs() >= nowDb - LIVE_MS;
    }

    /** Bu pod mu: aynı örnek kimliği ya da aynı pod adı (önceki süreç). */
    private boolean isSelf(String instanceId) {
        if (instanceId == null) return false;
        if (instanceId.equals(selfId)) return true;
        return Objects.equals(ClusterHealthStore.podOf(instanceId), ClusterHealthStore.podOf(selfId));
    }

    private static String podName(String instanceId) {
        String p = ClusterHealthStore.podOf(instanceId);
        return p == null ? instanceId : p;
    }

    private String version() {
        String v = cachedVersion;
        if (v != null) return v;
        try {
            v = version.get();
        } catch (RuntimeException e) {
            v = null;
        }
        if (v == null || v.isBlank()) v = "unknown";
        cachedVersion = v;
        return v;
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getString("instance_id"), rs.getString("pod"), caps(rs.getString("caps")), rs.getInt("slots"),
                rs.getInt("busy"), rs.getLong("completed"), rs.getLong("late"), nullableLong(rs, "claim_lag_p95_ms"),
                rs.getLong("heartbeat_ms"), rs.getString("version"), rs.getBoolean("stopping"));
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static Set<String> caps(String csv) {
        return CheckJobTypes.parseLenient(csv);
    }

    private boolean tableReadable() {
        try {
            jdbc.queryForList("SELECT 1 FROM " + CheckJobSql.WORKERS + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        } catch (DataAccessException other) {
            return true;
        }
    }

    private Boolean postgres() {
        Boolean p = postgres;
        if (p != null) return p;
        p = jdbc == null ? null : DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return p;
    }

    private static boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
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
