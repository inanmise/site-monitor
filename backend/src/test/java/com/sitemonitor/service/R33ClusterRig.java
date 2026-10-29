package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.checks.CheckJobSchemaPatch;
import com.sitemonitor.service.checks.CheckResultApplier;
import com.sitemonitor.service.checks.CheckResultDrainer;
import com.sitemonitor.service.checks.CheckResultRow;
import com.sitemonitor.service.checks.CheckWorker;
import com.sitemonitor.service.checks.CheckWorkerRegistryImpl;
import com.sitemonitor.service.checks.DbClock;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.checks.JdbcCheckJobStore;
import com.sitemonitor.service.checks.R33WorkerAccess;
import com.sitemonitor.service.checks.SchedulerCheckJobs;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 paket D2 — DAĞITIK KONTROL kümesi düzeneği (tasarım R3-3 §9.3 / §9.4).
 *
 * <p>{@link MultiPodTestSupport} üstünde, pod başına GERÇEK parçalar: kendi {@code SchedulerService}'i (gönderici, iş gövdeleri,
 * değerlendirici — paket B), kendi {@link MonitoringOutageService}'i ({@link MonitoringMultiPodKit#podWithMos}: çit = pod'un
 * liderliği, liderlik olayı → sıfırlama), kendi {@link JdbcCheckJobStore} / {@link CheckWorkerRegistryImpl} / {@link CheckWorker}
 * (paket A) ve {@link CheckResultDrainer} (paket C); ortak H2 + ortak saat (DB saati = ortak saat). Kira GERÇEK
 * ({@code monitor_check_schedule}). Uygulama gövdesi gerçek {@link SchedulerCheckJobs} bağdaştırıcısıdır; düzenek yalnız sayar.
 *
 * <p>Pod başına SAHTE denetleyici: {@link PortCheckerService} / {@link PingCheckerService} yanıtı {@link #oracle}
 * ({@code (pod, tür, izleme, an) → up}) — sonuç pod'dan bağımsızdır, böylece "hangi pod denetlerse denetlesin aynı sonuç" ve
 * klasik tek pod koşusuyla karar tutanağı karşılaştırılabilir. Her denetleyici çağrısı {@link #checks} kaydına düşer; çağrı
 * {@code MonitoringOutageService} içinden geliyorsa (teyit / kurtarma zincirinin yeniden kontrolü) {@code recheck} işaretlidir —
 * dönem kontrolleri (işçinin {@code runCheckJob}'u, klasik 1. aşama) asla oradan gelmez.
 *
 * <p>İşçiler iş parçacığı açmaz: {@link R33WorkerAccess#startForTest} + satır içi (ya da BEKLETİLEN, {@link HeldExecutor}) havuz;
 * {@link #step()} bir 5 sn'lik adımı sırayla koşturur (kayıt nabızları → gönderim tıkı → işçi adımları + yazım → boşaltma →
 * 30 sn'de bir klasik giriş tıkları). Kapasite testi aynı parçaları gerçek iş parçacıklarıyla başlatır.
 */
final class R33ClusterRig {

    /** Sürüş adımı (ms) — üretimdeki gönderim tıkı ({@code site.monitor.checks.dispatch-ms}). */
    static final long STEP_MS = 5_000L;
    /** Klasik giriş tıkı (port / ping {@code @Scheduled} 30 sn). */
    static final long ENTRY_TICK_MS = 30_000L;

    final MultiPodTestSupport mp;
    final long periodMs;
    final long t0;
    /** Düzenek saati: ortak test saati ({@code mp}) ya da duvar saati (kapasite testi, gerçek iş parçacıkları). */
    final LongSupplier clock;
    final LongSupplier nanos;
    final boolean wall;
    /** SS / boşaltıcının gördüğü kuyruk (ölçüm sarmalayıcısı için); null = ham kuyruk. */
    volatile java.util.function.UnaryOperator<com.sitemonitor.service.checks.CheckJobStore> storeWrapper;
    /** İşçinin bir işi koşturmaya başladığı an (tür, izleme) — kapasite ölçümü için. */
    volatile java.util.function.BiConsumer<String, Long> onRunStart;
    final AtomicBoolean switchOn = new AtomicBoolean(true);
    final AtomicReference<String> types = new AtomicReference<>("port,ping");
    final AppSettingsService dcSettings = mock(AppSettingsService.class);
    volatile DistributedChecks.Tuning tuning = DistributedChecks.Tuning.DEFAULTS;

    final List<PortMonitor> ports = new CopyOnWriteArrayList<>();
    final List<PingMonitor> pings = new CopyOnWriteArrayList<>();
    final List<ScriptedMonitor> scripteds = new CopyOnWriteArrayList<>();

    /** Sonuç kâhini — pod'dan bağımsız olmalı (aynı izleme, aynı dönem → aynı sonuç). */
    volatile Oracle oracle = (pod, type, id, nowMs) -> true;
    /** Denetleyici gecikmesi (ms; kapasite testi) — null = gecikme yok. */
    volatile Latency latency;

    final Queue<Check> checks = new ConcurrentLinkedQueue<>();
    final Queue<Eval> evals = new ConcurrentLinkedQueue<>();
    final Queue<Run> runs = new ConcurrentLinkedQueue<>();
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    /** Sürüş sırası (işçi adımları); boşsa {@link #nodes} sırası. */
    final List<Node> workerOrder = new CopyOnWriteArrayList<>();
    AlarmStore alarms;
    long steps;

    @FunctionalInterface
    interface Oracle {
        boolean up(int pod, String type, long monitorId, long nowMs);
    }

    @FunctionalInterface
    interface Latency {
        long sleepMs(int pod, String type, long monitorId);
    }

    /** Denetleyici çağrısı (dönem kontrolü ya da MOS içinden yeniden kontrol). */
    record Check(int pod, String type, long monitorId, long period, boolean recheck, long atMs) {}

    /** Değerlendirilen sonuç satırı (uygulayıcıya giden). */
    record Eval(int pod, String type, long monitorId, long jobId, long period, String worker, long createdMs) {}

    /** İşçinin koşturduğu iş ({@code runCheckJob}). */
    record Run(int pod, String type, long monitorId, long period, long atMs) {}

    R33ClusterRig(MultiPodTestSupport mp, long periodMs) {
        this(mp, periodMs, false);
    }

    /** {@code wall} → duvar saati (SS zamanlama saati, kilit saati, MOS saatleri, DB saati, nano saat) — gerçek iş parçacıkları. */
    R33ClusterRig(MultiPodTestSupport mp, long periodMs, boolean wall) {
        this.mp = mp;
        this.periodMs = periodMs;
        this.wall = wall;
        this.clock = wall ? System::currentTimeMillis : mp::now;
        this.nanos = wall ? System::nanoTime : () -> TimeUnit.MILLISECONDS.toNanos(mp.now());
        this.t0 = clock.getAsLong();
        new CheckJobSchemaPatch(mp.jdbc()).apply();
        when(dcSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i ->
                DistributedChecks.KEY_ENABLED.equals(i.getArgument(0)) ? switchOn.get() : i.getArgument(1));
        when(dcSettings.getString(anyString(), any())).thenAnswer(i ->
                DistributedChecks.KEY_TYPES.equals(i.getArgument(0)) ? types.get() : i.getArgument(1));
        installRepositories();
    }

    long period(long atMs) {
        return Math.floorDiv(atMs - t0, periodMs);
    }

    long period() {
        return period(clock.getAsLong());
    }

    // ── İzlemeler ───────────────────────────────────────────────────────────────────────────────────────────────────

    static PortMonitor port(long id, int intervalSeconds) {
        PortMonitor m = new PortMonitor();
        m.setId(id);
        m.setName("Port " + id);
        m.setHost("h" + id + ".example.com");
        m.setPort(443);
        m.setProtocol("TCP");
        m.setStandalone(true);
        m.setTeamId(7L);
        m.setActive(true);
        m.setIntervalSeconds(intervalSeconds);
        return m;
    }

    static PingMonitor ping(long id, int intervalSeconds) {
        PingMonitor m = new PingMonitor();
        m.setId(id);
        m.setName("Ping " + id);
        m.setHost("p" + id + ".example.com");
        m.setTeamId(7L);
        m.setActive(true);
        m.setIntervalSeconds(intervalSeconds);
        return m;
    }

    static ScriptedMonitor scripted(long id, int intervalSeconds) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id);
        m.setName("Akış " + id);
        m.setScript("export default function () {}");
        m.setScriptVersion("v1");
        m.setTeamId(7L);
        m.setActive(true);
        m.setIntervalSeconds(intervalSeconds);
        return m;
    }

    private void installRepositories() {
        PortMonitorRepository portRepo = mp.mock(PortMonitorRepository.class);
        when(portRepo.findByActiveTrue()).thenAnswer(i -> ports.stream().filter(m -> Boolean.TRUE.equals(m.getActive())).toList());
        when(portRepo.findAll()).thenAnswer(i -> List.copyOf(ports));
        when(portRepo.findById(any())).thenAnswer(i -> byId(ports, i.getArgument(0), PortMonitor::getId));
        when(portRepo.findAllById(anyIterable())).thenAnswer(i -> allById(ports, i.getArgument(0), PortMonitor::getId));
        when(portRepo.findCheckDispatchRows()).thenAnswer(i -> {
            List<Object[]> rows = new ArrayList<>();
            for (PortMonitor m : ports) {
                if (Boolean.TRUE.equals(m.getActive())) {
                    rows.add(new Object[]{m.getId(), m.getIntervalSeconds(), m.getStandalone(), m.getHost()});
                }
            }
            return rows;
        });
        PingMonitorRepository pingRepo = mp.mock(PingMonitorRepository.class);
        when(pingRepo.findByActiveTrue()).thenAnswer(i -> pings.stream().filter(m -> Boolean.TRUE.equals(m.getActive())).toList());
        when(pingRepo.findAll()).thenAnswer(i -> List.copyOf(pings));
        when(pingRepo.findById(any())).thenAnswer(i -> byId(pings, i.getArgument(0), PingMonitor::getId));
        when(pingRepo.findAllById(anyIterable())).thenAnswer(i -> allById(pings, i.getArgument(0), PingMonitor::getId));
        when(pingRepo.findCheckDispatchRows()).thenAnswer(i -> {
            List<Object[]> rows = new ArrayList<>();
            for (PingMonitor m : pings) {
                if (Boolean.TRUE.equals(m.getActive())) rows.add(new Object[]{m.getId(), m.getIntervalSeconds()});
            }
            return rows;
        });
        ScriptedMonitorRepository scriptedRepo = mp.mock(ScriptedMonitorRepository.class);
        when(scriptedRepo.findByActiveTrue()).thenAnswer(i -> scripteds.stream().filter(m -> Boolean.TRUE.equals(m.getActive())).toList());
        when(scriptedRepo.findAll()).thenAnswer(i -> List.copyOf(scripteds));
        when(scriptedRepo.findById(any())).thenAnswer(i -> byId(scripteds, i.getArgument(0), ScriptedMonitor::getId));
        when(scriptedRepo.findAllById(anyIterable())).thenAnswer(i -> allById(scripteds, i.getArgument(0), ScriptedMonitor::getId));
        when(scriptedRepo.findCheckDispatchRows()).thenAnswer(i -> {
            List<Object[]> rows = new ArrayList<>();
            for (ScriptedMonitor m : scripteds) {
                if (Boolean.TRUE.equals(m.getActive())) rows.add(new Object[]{m.getId(), m.getIntervalSeconds()});
            }
            return rows;
        });
    }

    private static <M> Optional<M> byId(List<M> all, Object id, java.util.function.Function<M, Long> idOf) {
        if (!(id instanceof Number n)) return Optional.empty();
        return all.stream().filter(m -> idOf.apply(m) == n.longValue()).findFirst();
    }

    private static <M> List<M> allById(List<M> all, Iterable<?> ids, java.util.function.Function<M, Long> idOf) {
        Set<Long> wanted = new java.util.HashSet<>();
        for (Object o : ids) if (o instanceof Number n) wanted.add(n.longValue());
        return all.stream().filter(m -> wanted.contains(idOf.apply(m))).toList();
    }

    // ── Sahte denetleyiciler (pod başına; mp.use ile pod kurulmadan ÖNCE) ────────────────────────────────────────────

    /** n. pod'un sahte denetleyicilerini paylaşılan bağımlılık olarak koyar — {@code podWithMos(mp, n)}'den ÖNCE çağrılmalı. */
    void installCheckers(int pod) {
        PortCheckerService port = mock(PortCheckerService.class);
        when(port.check(any(PortMonitor.class))).thenAnswer(i -> {
            PortMonitor m = i.getArgument(0);
            boolean up = observe(pod, CheckJobTypesNames.PORT, m.getId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("open", up);
            r.put("response_ms", up ? 20L : 5L);
            if (!up) r.put("error", "Connection refused");
            return r;
        });
        mp.use(PortCheckerService.class, port);
        PingCheckerService ping = mock(PingCheckerService.class);
        when(ping.check(anyString(), any(), anyInt(), anyInt())).thenAnswer(i -> {
            String host = i.getArgument(0);
            long id = Long.parseLong(host.substring(1, host.indexOf('.')));
            boolean up = observe(pod, CheckJobTypesNames.PING, id);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("up", up);
            if (up) {
                r.put("rtt_ms", 10L);
                r.put("packet_loss", 0);
            } else {
                r.put("packet_loss", 100);
                r.put("error", "Yanıt yok (%100 paket kaybı)");
            }
            return r;
        });
        mp.use(PingCheckerService.class, ping);
    }

    /** Kâhine sorar, kaydeder, (varsa) gecikmeyi uygular. */
    boolean observe(int pod, String type, long monitorId) {
        long now = clock.getAsLong();
        boolean recheck = StackWalker.getInstance().walk(s -> s.anyMatch(f ->
                f.getClassName().equals(MonitoringOutageService.class.getName())));
        checks.add(new Check(pod, type, monitorId, period(now), recheck, now));
        Latency l = latency;
        if (l != null) {
            long ms = l.sleepMs(pod, type, monitorId);
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return oracle.up(pod, type, monitorId, now);
    }

    /** Tür adları (CheckJobTypes ile aynı; test okunurluğu için). */
    static final class CheckJobTypesNames {
        static final String PORT = com.sitemonitor.service.checks.CheckJobTypes.PORT;
        static final String PING = com.sitemonitor.service.checks.CheckJobTypes.PING;
        static final String SCRIPTED = com.sitemonitor.service.checks.CheckJobTypes.SCRIPTED;

        private CheckJobTypesNames() {
        }
    }

    // ── Pod'lar ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Pod seçenekleri. */
    static final class Opts {
        boolean legacy;            // 20.169.0 pod'u: 3-3 parçası YOK (alanlar null = bugünkü davranış)
        boolean held;              // işler bekletilen havuzda (elle koşturulur)
        boolean icmp = true;
        boolean k6 = true;
        int k6Permits = 2;
        boolean immediateChains = true;
        boolean scripted;          // SS'e senaryo bağımlılıkları (denetleyici sahtesi bu pod'a)
        boolean offBoot;           // üretimdeki tembel başlatıcılar (register) — anahtar kapalıysa hiçbir şey başlamaz
        boolean realThreads;       // işçi / kayıt / boşaltıcı GERÇEK iş parçacıklarıyla (kapasite testi)
        org.springframework.jdbc.core.JdbcTemplate jdbc;   // 3-3 parçalarının JDBC'si (null = ortak; ör. kayıtlı veri kaynağı)

        Opts legacy() { legacy = true; return this; }
        Opts held() { held = true; return this; }
        Opts noIcmp() { icmp = false; return this; }
        Opts k6Permits(int n) { k6Permits = n; return this; }
        Opts scripted() { scripted = true; return this; }
        Opts realChains() { immediateChains = false; return this; }
        Opts offBoot() { offBoot = true; return this; }
        Opts realThreads() { realThreads = true; immediateChains = false; return this; }
        Opts jdbc(org.springframework.jdbc.core.JdbcTemplate j) { jdbc = j; return this; }
    }

    /** Bir pod ve 3-3 parçaları ({@code legacy} ise yalnız SS + MOS). */
    final class Node {
        final int index;
        final MonitoringMultiPodKit.PodWithMos p;
        final boolean legacy;
        final AtomicBoolean icmp = new AtomicBoolean(true);
        final AtomicBoolean k6 = new AtomicBoolean(true);
        final AtomicInteger k6Permits = new AtomicInteger(2);
        DistributedChecks dc;
        JdbcCheckJobStore store;
        CheckWorkerRegistryImpl registry;
        CheckWorker worker;
        CheckResultDrainer drainer;
        CheckResultApplier applier;
        HeldExecutor held;
        ScriptedCheckerService scriptedChecker;
        /** Ölü pod: hiçbir adım koşmaz (nabız, işçi, boşaltma, giriş tıkları). */
        volatile boolean dead;
        /** Takılan pod: yalnız işçi ve kayıt adımları durur. */
        volatile boolean stalled;
        /** Boşaltıcı bu pod'da adım atmaz (ör. devirden önce DONE satırı kuyrukta beklesin). */
        volatile boolean drainPaused;
        /** Uygulayıcıdan ÖNCE koşan kanca (bekletme / sıra kaydı). */
        volatile Runnable beforeApply;

        Node(int index, MonitoringMultiPodKit.PodWithMos p, boolean legacy) {
            this.index = index;
            this.p = p;
            this.legacy = legacy;
        }

        SchedulerService ss() {
            return p.scheduler();
        }

        MonitoringOutageService mos() {
            return p.mos();
        }

        String owner() {
            return p.pod().owner();
        }

        boolean active3x() {
            return !legacy && !dead;
        }

        @Override
        public String toString() {
            return "pod" + index;
        }
    }

    /** n. pod'u kurar (sahte denetleyiciler + MOS'lu pod + 3-3 parçaları). */
    Node node(int n, Opts o) {
        installCheckers(n);
        MonitoringMultiPodKit.PodWithMos p = MonitoringMultiPodKit.podWithMos(mp, n);
        return attach(n, p, o);
    }

    Node node(int n) {
        return node(n, new Opts());
    }

    /** Var olan bir pod'a (ör. 3-2 kümesinin düğümü) 3-3 parçalarını bağlar. */
    Node attach(int index, MonitoringMultiPodKit.PodWithMos p, Opts o) {
        Node n = new Node(index, p, o.legacy);
        n.icmp.set(o.icmp);
        n.k6.set(o.k6);
        n.k6Permits.set(o.k6Permits);
        MonitoringOutageService mos = p.mos();
        if (o.immediateChains) {
            ReflectionTestUtils.setField(mos, "confirmExecutor", MonitoringMultiPodKit.immediate());
            ReflectionTestUtils.setField(mos, "recoveryExecutor", MonitoringMultiPodKit.immediate());
        }
        mos.suppressionClock = clock;
        SchedulerService ss = p.scheduler();
        if (wall) {
            ReflectionTestUtils.setField(ss, "scheduleClock", clock);
            ReflectionTestUtils.setField(ss, "lockService",
                    new com.sitemonitor.service.schedule.ClusterLockService(mp.jdbc(), p.pod().owner(), clock, false));
            ReflectionTestUtils.setField(mos, "nowMs", clock);
        }
        if (o.scripted) {
            ScriptedCheckerService sc = mock(ScriptedCheckerService.class);
            when(sc.ensureProbed()).thenReturn(true);
            when(sc.isAvailable()).thenReturn(true);
            when(sc.freePermits()).thenAnswer(i -> n.k6Permits.get());
            when(sc.run(any(ScriptedMonitor.class))).thenAnswer(i -> {
                ScriptedMonitor m = i.getArgument(0);
                boolean up = observe(index, CheckJobTypesNames.SCRIPTED, m.getId());
                return new ScriptedCheckerService.ScriptedResult(up ? "PASS" : "FAIL", up, 300L, 0, up ? 1 : 0, up ? 0 : 1,
                        300L, 50L, 80L, "[]", null, up ? null : "HTTP 500", false, ScriptedCheckerService.Phases.EMPTY);
            });
            n.scriptedChecker = sc;
            ReflectionTestUtils.setField(ss, "scriptedCheckerService", sc);
            ReflectionTestUtils.setField(ss, "scriptedMonitorRepo", mp.mock(ScriptedMonitorRepository.class));
            ReflectionTestUtils.setField(ss, "scriptedCheckRepo", mp.mock(ScriptedCheckRepository.class));
        }
        nodes.add(n);
        if (o.legacy) return n;

        DistributedChecks dc = new DistributedChecks(dcSettings, tuning);
        n.dc = dc;
        ss.setDistributedChecks(dc);   // gönderim tıkının tembel başlatıcısı
        TaskScheduler ts = mock(TaskScheduler.class);
        Mockito.doReturn(mock(ScheduledFuture.class)).when(ts)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        ReflectionTestUtils.setField(ss, "taskSchedulerProvider", provider(TaskScheduler.class, ts));
        DbClock clock = dbClock();
        LongSupplier nano = nanoClock();
        org.springframework.jdbc.core.JdbcTemplate jdbc3x = o.jdbc != null ? o.jdbc : mp.jdbc();
        JdbcCheckJobStore store = new JdbcCheckJobStore(jdbc3x, clock, Boolean.FALSE, nano);
        n.store = store;
        java.util.function.UnaryOperator<com.sitemonitor.service.checks.CheckJobStore> wrap = storeWrapper;
        com.sitemonitor.service.checks.CheckJobStore seen = wrap == null ? store : wrap.apply(store);
        AtomicReference<CheckWorker> workerRef = new AtomicReference<>();
        CheckWorkerRegistryImpl registry = new CheckWorkerRegistryImpl(jdbc3x, clock, dc, p.pod().owner(),
                ss::sweepLeaderHint, n.icmp::get, n.icmp::get, n.k6::get, n.k6::get, () -> true,
                () -> {
                    CheckWorker w = workerRef.get();
                    return w == null ? CheckWorkerRegistryImpl.LocalStats.IDLE : w.localStats();
                },
                () -> "r33-test", nano);
        n.registry = registry;
        // FX-QUEUE QW-L1: ping ilk ICMP sondası dönmeden ilan edilmez — üretimdeki sonda iş parçacığının ilk turunun yerine
        registry.probeIcmpNow();
        ss.setCheckJobInfrastructure(seen, registry);
        SchedulerCheckJobs adapter = new SchedulerCheckJobs(provider(SchedulerService.class, ss));
        CheckJobRunner runner = (type, monitorId) -> {
            long at = this.clock.getAsLong();
            java.util.function.BiConsumer<String, Long> hook = onRunStart;
            if (hook != null) hook.accept(type, monitorId);
            runs.add(new Run(index, type, monitorId, period(at), at));
            return adapter.runCheckJob(type, monitorId);
        };
        CheckResultApplier applier = wave -> {
            Runnable hook = n.beforeApply;
            if (hook != null) hook.run();
            for (CheckResultRow r : wave) {
                evals.add(new Eval(index, r.type(), r.monitorId(), r.id(), period(r.createdMs()), r.worker(), r.createdMs()));
            }
            adapter.apply(wave);
        };
        n.applier = applier;
        CheckWorker worker = new CheckWorker(store, registry, dc, () -> runner, ss::sweepLeaderHint, ss::sideJobsStopping,
                n.k6Permits::get, clock, p.pod().owner(), nano);
        workerRef.set(worker);
        n.worker = worker;
        if (o.held) n.held = new HeldExecutor();
        if (!o.offBoot && !o.realThreads) R33WorkerAccess.startForTest(worker, o.held ? n.held : new InlineExecutor());
        CheckResultDrainer drainer = new CheckResultDrainer(dc, () -> ss, () -> mos, () -> seen, () -> applier,
                p.pod()::schedule, () -> clock);
        drainer.setLeaderClock(this.clock);
        p.pod().events().listen(SweepLeadershipChangedEvent.class, drainer::onSweepLeadershipChanged);   // MOS'tan SONRA
        if (!o.offBoot && !o.realThreads) {
            ReflectionTestUtils.setField(drainer, "started", true);   // iş parçacığı yerine adımlar elle (drainOnce)
        }
        ReflectionTestUtils.setField(ss, "checkResultDrainer", drainer);
        n.drainer = drainer;
        if (o.offBoot) {   // üretimdeki @PostConstruct kayıtları: anahtar açık görülene dek hiçbiri başlamaz
            registry.register();
            worker.register();
            drainer.registerStarter();
        }
        dc.onReady(null);   // açılış hazır: anahtar açıksa gönderim tıkının başlatıcısı (sahte zamanlayıcı) koşar
        if (o.realThreads) {
            registry.start();
            worker.start();
            drainer.start();
        }
        return n;
    }

    /** Gerçek iş parçacıklı parçaları durdurur (kapanış yüzü + yok etme). */
    void stop(Node n) {
        if (n.legacy) return;
        n.worker.onContextClosed(null);
        n.registry.onContextClosed(null);
        n.drainer.onContextClosed(null);
        n.worker.destroy();
        n.drainer.destroy();
    }

    DbClock dbClock() {
        return new DbClock() {
            @Override
            public long nowMs() {
                return clock.getAsLong();
            }

            @Override
            public boolean stale() {
                return false;
            }
        };
    }

    LongSupplier nanoClock() {
        return nanos;
    }

    static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean(type.getSimpleName(), bean);
        return bf.getBeanProvider(type);
    }

    /** Anahtar değişti (başka pod'un kaydı 10 sn yenilemesinde görüldü) — her pod'un {@code DistributedChecks}'i görür. */
    void settingsChanged() {
        for (Node n : nodes) {
            if (n.dc != null) {
                n.dc.onSettingsChanged(new AppSettingsChangedEvent(
                        Set.of(DistributedChecks.KEY_ENABLED, DistributedChecks.KEY_TYPES), "refresh"));
            }
        }
    }

    // ── Sürüş ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Tüm canlı 3-3 pod'larının kayıt turu (nabız + okuma). */
    void tickRegistries() {
        for (Node n : nodes) if (n.active3x() && !n.stalled) n.registry.tick();
    }

    /** Bir 5 sn'lik adım; saati {@link #STEP_MS} ilerletir. */
    void step() {
        tickRegistries();
        if (steps % (ENTRY_TICK_MS / STEP_MS) == 0) entryTicks();
        for (Node n : nodes) if (n.active3x()) n.ss().dispatchCheckJobs();   // yalnız lider gönderir
        for (Node n : order()) {
            if (!n.active3x() || n.stalled) continue;
            R33WorkerAccess.tick(n.worker);
            R33WorkerAccess.flush(n.worker);
        }
        drainAll();
        steps++;
        mp.advance(STEP_MS);
    }

    void steps(int count) {
        for (int i = 0; i < count; i++) step();
    }

    /** Dönem sınırına (sonraki dönemin başı) kadar adım atar. */
    void untilNextPeriod() {
        long p = period();
        int guard = 0;
        while (period() == p && guard++ < 10_000) step();
    }

    /** Klasik giriş tıkları ({@code @Scheduled} port / ping / senaryo): dağıtık kipte yalnız lider görevleri. */
    void entryTicks() {
        for (Node n : nodes) {
            if (n.dead) continue;
            n.ss().runPortChecks();
            n.ss().runPingChecks();
            if (n.scriptedChecker != null) n.ss().runScriptedChecks();
        }
    }

    /** Her canlı 3-3 pod'unun boşaltıcısı (yalnız lider alır). */
    void drainAll() {
        for (Node n : nodes) {
            if (!n.active3x() || n.drainPaused) continue;
            int guard = 0;
            while (n.drainer.drainOnce() && guard++ < 50) {
                // bütçe doldu ve parti dolu döndü: sürdür
            }
        }
    }

    List<Node> order() {
        return workerOrder.isEmpty() ? nodes : workerOrder;
    }

    // ── Okumalar ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Dönem kontrolleri (yeniden kontroller hariç): "tür:izleme:dönem" → kontrol eden pod'lar. */
    Map<String, List<Integer>> periodChecks() {
        Map<String, List<Integer>> out = new java.util.TreeMap<>();
        for (Check c : checks) {
            if (c.recheck()) continue;
            out.computeIfAbsent(c.type() + ":" + c.monitorId() + ":" + c.period(), k -> new ArrayList<>()).add(c.pod());
        }
        return out;
    }

    /** "tür:izleme:dönem" → değerlendirme sayısı (satırın oluşturulduğu dönem). */
    Map<String, Integer> evaluations() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (Eval e : evals) out.merge(e.type() + ":" + e.monitorId() + ":" + e.period(), 1, Integer::sum);
        return out;
    }

    int jobRows() {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_jobs", Integer.class);
        return n == null ? 0 : n;
    }

    int jobRows(String state) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_jobs WHERE state = ?", Integer.class, state);
        return n == null ? 0 : n;
    }

    /** Görülen en büyük iş kimliği: kuyruktaki satırlar ve değerlendirilenler (alınan satır tablodan silinir). */
    long maxJobIdSeen() {
        long max = maxJobId();
        for (Eval e : evals) max = Math.max(max, e.jobId());
        return max;
    }

    long maxJobId() {
        Long n = mp.jdbc().queryForObject("SELECT COALESCE(MAX(id), 0) FROM monitor_check_jobs", Long.class);
        return n == null ? 0 : n;
    }

    // ── Bekletilen / satır içi havuzlar ───────────────────────────────────────────────────────────────────────────────

    /** İşi çağıran iş parçacığında hemen koşturur. */
    static class InlineExecutor extends AbstractExecutorService {
        volatile boolean shut;

        @Override
        public void execute(Runnable command) {
            if (shut) throw new RejectedExecutionException("kapalı");
            command.run();
        }

        @Override
        public void shutdown() {
            shut = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shut = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shut;
        }

        @Override
        public boolean isTerminated() {
            return shut;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** İşleri kuyrukta tutar; test {@link #runAll()} ile koşturur ya da {@link #drop()} ile atar (pod öldü). */
    static final class HeldExecutor extends InlineExecutor {
        final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        /** false → işler yine satır içi koşar (bekletme kapandı). */
        final AtomicBoolean holding = new AtomicBoolean(true);

        @Override
        public void execute(Runnable command) {
            if (shut) throw new RejectedExecutionException("kapalı");
            if (holding.get()) queued.add(command);
            else command.run();
        }

        int runAll() {
            int n = 0;
            Runnable r;
            while ((r = queued.poll()) != null) {
                r.run();
                n++;
            }
            return n;
        }

        int drop() {
            int n = queued.size();
            queued.clear();
            return n;
        }
    }

    // ── Benzetilmiş alarm deposu ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code alert_events} benzetimi: {@code EscalationService.processConfirmedOutage} açar (ya da açık olanı yeniden uyarır),
     * {@code resolveMonitoringAlertsForDomain} / {@code resolveOpenAlertsSilently} kapatır, {@code findOpenByDomainIn} açıkları
     * döner. Her karar dönem etiketiyle {@link #decisions}'a düşer — klasik ve dağıtık koşuların karar tutanağı.
     */
    static final class AlarmStore {
        final Map<String, AlertEvent> open = new ConcurrentHashMap<>();
        final List<String> decisions = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong ids = new AtomicLong(1_000L);

        AlarmStore(MultiPodTestSupport mp, java.util.function.LongUnaryOperator periodOf) {
            AlertEventRepository repo = mp.mock(AlertEventRepository.class);
            when(repo.findOpenByDomainIn(anyCollection())).thenAnswer(i -> {
                Collection<?> domains = i.getArgument(0);
                List<AlertEvent> out = new ArrayList<>();
                for (AlertEvent e : open.values()) if (domains.contains(e.getDomain())) out.add(e);
                return out;
            });
            EscalationService esc = mp.mock(EscalationService.class);
            doAnswer(i -> {
                String domain = i.getArgument(0);
                String type = i.getArgument(1);
                Map<String, Object> ctx = i.getArgument(3);
                boolean existed = open.containsKey(type + "|" + domain);
                open.computeIfAbsent(type + "|" + domain, k -> event(domain, type, ctx));
                decisions.add("p" + periodOf.applyAsLong(mp.now()) + " " + (existed ? "realert" : "open") + " " + type + " "
                        + domain);
                return null;
            }).when(esc).processConfirmedOutage(anyString(), anyString(), any(), any());
            doAnswer(i -> {
                String domain = i.getArgument(0);
                String type = i.getArgument(1);
                open.remove(type + "|" + domain);
                decisions.add("p" + periodOf.applyAsLong(mp.now()) + " resolve " + type + " " + domain);
                return null;
            }).when(esc).resolveMonitoringAlertsForDomain(anyString(), anyString());
            doAnswer(i -> {
                String domain = i.getArgument(0);
                Collection<?> types = i.getArgument(1);
                for (Object t : types) open.remove(t + "|" + domain);
                decisions.add("p" + periodOf.applyAsLong(mp.now()) + " silent " + types + " " + domain);
                return null;
            }).when(esc).resolveOpenAlertsSilently(anyString(), anyCollection(), anyString());
        }

        AlertEvent event(String domain, String type, Map<String, Object> ctx) {
            AlertEvent e = new AlertEvent();
            e.setId(ids.incrementAndGet());
            e.setDomain(domain);
            e.setAlertType(type);
            e.setAlertLevel("WARNING");
            e.setAcknowledged(false);
            e.setResolved(false);
            Object team = ctx == null ? null : ctx.get("team_id");
            if (team instanceof Number t) e.setTeamId(t.longValue());
            Object mid = ctx == null ? null : ctx.get("monitor_id");
            e.setContextJson("{\"monitor_id\":" + mid + ",\"team_id\":" + team + ",\"standalone\":true}");
            return e;
        }

        /** Dönem → o dönemin kararları (sıralı; dalga sırası klasik turdan farklı olabilir, çokküme eşit olmalı). */
        Map<String, List<String>> byPeriod() {
            Map<String, List<String>> out = new java.util.TreeMap<>();
            synchronized (decisions) {
                for (String d : decisions) {
                    String p = d.substring(0, d.indexOf(' '));
                    out.computeIfAbsent(p, k -> new ArrayList<>()).add(d);
                }
            }
            out.values().forEach(Collections::sort);
            return out;
        }
    }
}
