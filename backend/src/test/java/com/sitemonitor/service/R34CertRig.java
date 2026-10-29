package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.certsweep.CertChunkWorker;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertChunkSpec;
import com.sitemonitor.service.certsweep.CertRunCoordinator;
import com.sitemonitor.service.certsweep.CertRunSpec;
import com.sitemonitor.service.certsweep.CertSweepFakes;
import com.sitemonitor.service.certsweep.CertSweepSchemaPatch;
import com.sitemonitor.service.certsweep.CertSweepStatus;
import com.sitemonitor.service.certsweep.CertSweepStore;
import com.sitemonitor.service.certsweep.ChunkCompletion;
import com.sitemonitor.service.certsweep.ChunkState;
import com.sitemonitor.service.certsweep.ClaimedChunk;
import com.sitemonitor.service.certsweep.JdbcCertSweepStore;
import com.sitemonitor.service.certsweep.R34WorkerAccess;
import com.sitemonitor.service.checks.DbClock;
import com.sitemonitor.service.health.ClusterHealthSchemaPatch;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.outage.CertNetworkOutageService;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-4 paket C2 — PARÇALI SERTİFİKA TARAMASI kümesi düzeneği (tasarım R3-4 §9.3 / §9.4; 3-3'ün
 * {@link R33ClusterRig} deseni).
 *
 * <p>{@link MultiPodTestSupport} üstünde (TEK H2, ortak saat) pod başına GERÇEK parçalar: kendi {@code SchedulerService}'i
 * (gerçek {@code cert-check} kilidi — pod başına {@code ClusterLockService}, kendi sahibi; gerçek tur gövdesi
 * {@code runCheckForDomains} / {@code runCheck}), kendi {@link JdbcCertSweepStore}'u (taşınabilir sahiplenme yolu), kendi
 * {@link CertChunkWorker}'ı, kendi {@link CertRunCoordinator}'ı ve {@link SchedulerCertChunks} ölçücüsü (gerçek klasik toplama
 * {@code gatherCertResults}); gerçek {@link CertNetworkOutageService} (H2 satırı, toplu kesinti kararı) ve gerçek
 * {@link ClusterHealthStore} ({@code scan_runs}) — ikisi de çağrıları kaydeden casus. Kaydeden sahteler:
 * {@code CertificateService} ({@code saveResult} çağrı anındaki haritayı kopyalar, sonra üretimdeki gibi haritaya
 * {@code deployment_status} yazar), {@code EscalationService}.
 *
 * <p>Pod başına SAHTE denetleyici: {@code checkAsync} yanıtı {@link #oracle}'dan — pod'dan BAĞIMSIZ (aynı alan adı → aynı
 * sonuç), böylece parçalı turun karar dökümü tek pod klasik turunkiyle karşılaştırılabilir. {@link #brokenEgress}'teki pod her
 * alan adını ağ zaman aşımı olarak ölçer (çıkış eşliği bozuk pod — ikinci görüş).
 *
 * <p><b>İki kip.</b> Belirlenimci (varsayılan): işçiler iş parçacığı açmaz ({@code startForTest} + bekletilen havuz); koordinatörün
 * 500 ms'lik uykusu {@link #step(long)}'tir — ortak saati ilerletir, canlı pod'ların kilitlerini 30 sn'de bir yeniler (üretimdeki
 * yenileyici), her işçiye bir yoklama adımı attırır, sonra sahiplenilen parçaları ölçtürür. Gerçek (kapasite, {@code wall}):
 * duvar saati, gerçek işçi iş parçacıkları, pod başına gerçek {@code certCheckExecutor}, denetleyici gecikmesi
 * {@link #latency}.
 *
 * <p><b>Ölüm benzetimi.</b> {@link Node#dead}: pod artık adım atmaz, kilidini yenilemez; deposu artık yazmaz (sahiplenme boş,
 * yenileme / tamamlama / bırakma veritabanına HİÇ gitmez — süreç ölmüş gibi). {@link Node#onMeasure} ölçümün ortasında ölüm
 * içindir (ölçüm sürer, tamamlama yazılmaz). Koordinatör ölümü: tur ayrı iş parçacığında koşar ve uykusunda DONDURULUR
 * ({@link Node#freezeWhen}) — nabız, kilit yenilemesi, kayıt yok.
 */
final class R34CertRig implements AutoCloseable {

    /** Koordinatörün bekleme adımı (ms) — üretimdeki {@link CertChunking#COORDINATOR_WAIT_POLL_MS}. */
    static final long STEP_MS = CertChunking.COORDINATOR_WAIT_POLL_MS;
    /** Kilit yenileyicisinin aralığı (ms; üretimdeki {@code cluster-lock-renewer} 30 sn). */
    static final long RENEW_EVERY_MS = 30_000L;
    static final String ADMIN = "admin@example.com";
    static final String CRON = "0 0 * * * *";

    /** Üretimdeki {@code network_outage_events} kolonları ({@code SchedulerNetworkOutageMultiPodTest} ile aynı). */
    static final String NETWORK_OUTAGE_DDL = "CREATE TABLE IF NOT EXISTS network_outage_events ("
            + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
            + "detected_at VARCHAR(255) NOT NULL, resolved_at VARCHAR(255), "
            + "network_errors INTEGER, total_checks INTEGER, error_rate DOUBLE PRECISION, threshold DOUBLE PRECISION, "
            + "duration_ms BIGINT, status VARCHAR(16) NOT NULL, source VARCHAR(32), "
            + "alert_mail_status VARCHAR(255), alert_mail_claimed_ms BIGINT, alert_mail_attempts INTEGER, "
            + "resolved_mail_status VARCHAR(255), resolved_mail_claimed_ms BIGINT, resolved_mail_attempts INTEGER, "
            + "last_confirmed_at VARCHAR(30))";

    /** Denetleyici kâhini: (ölçen pod, klasik sıra, alan adı) → sonuç haritası (her çağrıda YENİ harita). */
    @FunctionalInterface
    interface Oracle {
        Map<String, Object> result(int pod, int index, String domain);
    }

    /** {@code checkAsync} çağrısı. */
    record Check(int pod, String domain, Integer port, boolean useProxy, String tlsMode, Integer timeout, long atMs) {
        String args() {
            return domain + "|" + port + "|" + useProxy + "|" + tlsMode + "|" + timeout;
        }
    }

    /** Bir işçinin parça sahiplenmesi. */
    record Claim(int pod, long chunkId, String runId, int chunkNo, int attempts, int domains, long atMs) {}

    /** Bir işçinin tamamlama denemesi ({@code accepted} = satır yazıldı). */
    record Completion(int pod, long chunkId, int checked, int networkErrors, boolean accepted, long atMs) {}

    final MultiPodTestSupport mp;
    final boolean wall;
    final LongSupplier clock;
    final AtomicBoolean switchOn = new AtomicBoolean(true);
    final AppSettingsService settings;
    final EmailNotificationService email;
    final CertificateInventoryRepository inventoryRepo;
    /**
     * Senaryoların ayarı: 40 alan adı bütçesi (eski varsayılan 2 × çekirdek), en çok 4 parça — senaryolar adım başına parça sayısıyla
     * yazıldı; yeni varsayılan (6 × çekirdek, lider ≤ çekirdek; C2 B-1) {@code CertChunksCapacityTest} ve işçi testlerinde.
     */
    volatile CertChunking.Tuning tuning = new CertChunking.Tuning(true, 20, 40, 4, 40, 60_000L, 10_000L, 50, 250L, 5_000L);

    final List<Map<String, Object>> rows = new CopyOnWriteArrayList<>();
    final Map<String, Integer> indexOf = new ConcurrentHashMap<>();
    final Map<String, String> issuers = new ConcurrentHashMap<>();
    volatile Oracle oracle = (pod, i, d) -> defaultResult(i, d);
    final Set<Integer> brokenEgress = ConcurrentHashMap.newKeySet();
    /** Gerçek kip: denetleyici gecikmesi (klasik sıra → ms); null = yok. */
    volatile java.util.function.IntToLongFunction latency;

    final Queue<Check> checks = new ConcurrentLinkedQueue<>();
    final Queue<Claim> claims = new ConcurrentLinkedQueue<>();
    final Queue<Completion> completions = new ConcurrentLinkedQueue<>();
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<LongConsumer> stepHooks = new CopyOnWriteArrayList<>();
    final AtomicInteger seq = new AtomicInteger();
    final AtomicLong steps = new AtomicLong();
    /** Adım tavanı — bir mekanizma bozulduğunda (bozma sınaması) sonsuz bekleme yerine hızlı düşüş. */
    volatile long maxSteps = 10_000L;
    private long lastRenew;

    R34CertRig() {
        this(MultiPodTestSupport.create(), false);
    }

    R34CertRig(MultiPodTestSupport mp, boolean wall) {
        this.mp = mp;
        this.wall = wall;
        this.clock = wall ? System::currentTimeMillis : mp::now;
        this.lastRenew = clock.getAsLong();
        mp.jdbc().execute(NETWORK_OUTAGE_DDL);
        new ClusterHealthSchemaPatch(mp.jdbc()).apply();
        new CertSweepSchemaPatch(mp.jdbc()).apply();
        settings = mp.mock(AppSettingsService.class);
        when(settings.getBoolean(eq(CertChunking.KEY_ENABLED), anyBoolean())).thenAnswer(i -> switchOn.get());
        email = mp.mock(EmailNotificationService.class);
        lenient().when(email.sendSystemAdminNetworkAlert(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble()))
                .thenReturn("SENT");
        lenient().when(email.sendSystemAdminNetworkResolved(anyString(), any(), any(), anyLong(), anyInt(), anyInt(), anyDouble()))
                .thenReturn("SENT");
        inventoryRepo = mp.mock(CertificateInventoryRepository.class);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenAnswer(i -> inventoryEntities());
        ThreadPoolTaskExecutor shared = mp.mock(ThreadPoolTaskExecutor.class);
        doAnswer(i -> 20).when(shared).getCorePoolSize();   // üretimdeki görev havuzu çekirdeği → işçi bütçesi 40, lider 20
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Envanter ve kâhin
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    static String domain(int i) {
        return String.format("c%04d.example.com", i);
    }

    /** {@code n} alan adlık envanter (klasik sıra = alan adı sırası = dizin); yayın kuruluşu dizine göre karışık. */
    R34CertRig inventory(int n) {
        rows.clear();
        indexOf.clear();
        issuers.clear();
        for (int i = 0; i < n; i++) {
            String d = domain(i);
            rows.add(CertSweepGoldenHarness.row(d, i % 9 == 4 ? 8443 : 443, i % 7 == 3, i % 5 == 1 ? "browser" : "",
                    i % 11 == 6 ? 15 : null, null));
            indexOf.put(d, i);
            issuers.put(d, "CN=CA-" + (i * 7 % 13));
        }
        return this;
    }

    /** Klasik vade listesinin kopyası. */
    List<Map<String, Object>> rowsCopy() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(new LinkedHashMap<>(r));
        return out;
    }

    List<CertificateInventory> inventoryEntities() {
        List<CertificateInventory> out = new ArrayList<>();
        long id = 1;
        for (Map<String, Object> r : rows) {
            CertificateInventory c = new CertificateInventory();
            c.setId(id++);
            c.setDomain((String) r.get("domain"));
            c.setPort((Integer) r.get("port"));
            c.setUseProxy((Boolean) r.get("use_proxy"));
            String mode = (String) r.get("tls_mode");
            c.setTlsMode(mode == null || mode.isEmpty() ? null : mode);
            c.setTimeoutSeconds((Integer) r.get("timeout_seconds"));
            c.setActive(true);
            out.add(c);
        }
        return out;
    }

    /** Varsayılan kâhin: çoğu geçerli, bazıları uyarı / SSL / DNS / çözülemeyen ad, ~%1 ağ hatası (eşiğin çok altında). */
    static Map<String, Object> defaultResult(int i, String d) {
        if (i % 97 == 13) return CertSweepGoldenHarness.networkError(d, "Connection timeout after 6s");
        if (i % 17 == 3) return CertSweepGoldenHarness.warning(d, 10 + i % 20);
        if (i % 29 == 5) return CertSweepGoldenHarness.sslError(d);
        if (i % 31 == 7) return CertSweepGoldenHarness.dnsError(d);
        if (i % 37 == 11) return CertSweepGoldenHarness.unresolvable(d);
        return CertSweepGoldenHarness.valid(d, 40 + i % 300, i % 2 == 0, 100L + i);
    }

    /** Sağlıklı kâhin (ağ hatası yok). */
    static Map<String, Object> healthyResult(int i, String d) {
        if (i % 17 == 3) return CertSweepGoldenHarness.warning(d, 10 + i % 20);
        return CertSweepGoldenHarness.valid(d, 40 + i % 300, i % 2 == 0, 100L + i);
    }

    /** Bir ölçüm: kâhin (ya da bozuk çıkışlı pod'da ağ zaman aşımı) + kayıt + ölçüm kancası. */
    Map<String, Object> measure(Node n, String domain, Integer port, boolean useProxy, String tlsMode, Integer timeout) {
        checks.add(new Check(n.index, domain, port, useProxy, tlsMode, timeout, clock.getAsLong()));
        Runnable hook = n.onMeasure;
        if (hook != null) hook.run();
        Integer idx = indexOf.get(domain);
        int i = idx == null ? -1 : idx;
        java.util.function.IntToLongFunction lat = latency;
        if (lat != null && i >= 0) {
            long ms = lat.applyAsLong(i);
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (brokenEgress.contains(n.index)) return CertSweepGoldenHarness.networkError(domain, "Connection timeout after 6s");
        return oracle.result(n.index, i, domain);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Pod'lar
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Pod seçenekleri. */
    static final class Opts {
        boolean legacy;            // 20.170.0 pod'u: 3-4 parçası YOK (koordinatör alanı null, işçi yok)
        boolean leader;            // tarama lideri ipucu (işçinin lider payı)
        boolean workerOff;         // bu pod'da işçi kapalı (CERT_CHUNK_WORKER_ENABLED=false)
        boolean realThreads;       // gerçek işçi iş parçacığı + gerçek görev havuzu (kapasite)
        boolean lazy;              // üretimdeki tembel başlatma: işçi YALNIZ anahtar açık görülünce gerçek iş parçacığıyla başlar
        int executorThreads = 20;  // gerçek kip: certCheckExecutor çekirdeği
        CertChunking.Tuning tuning;

        Opts legacy() { legacy = true; return this; }
        Opts leader() { leader = true; return this; }
        Opts workerOff() { workerOff = true; return this; }
        Opts realThreads() { realThreads = true; return this; }
        Opts lazy() { lazy = true; return this; }
        Opts tuning(CertChunking.Tuning t) { tuning = t; return this; }
    }

    /** Bir pod ve 3-4 parçaları ({@code legacy} ise yalnız SS). */
    final class Node {
        final int index;
        final boolean legacy;
        MultiPodTestSupport.Pod pod;
        CertificateCheckerService checker;
        CertificateService certService;
        EscalationService escalation;
        CertNetworkOutageService outage;
        ClusterHealthStore health;
        JdbcCertSweepStore realStore;
        NodeStore store;
        CertChunking chunking;
        CertChunkWorker worker;
        CertRunCoordinator coordinator;
        SchedulerCertChunks runner;
        ThreadPoolTaskExecutor executor;
        final HeldPool pool = new HeldPool();
        final AtomicBoolean leader = new AtomicBoolean();
        final AtomicBoolean otherLive = new AtomicBoolean(true);
        /** Kararlar (kayıt, önbellek, kesinti, alarm, tarama durumu) — sıralı, çağrı anındaki kopyalar. */
        final List<Map<String, Object>> calls = Collections.synchronizedList(new ArrayList<>());
        /** Ölü pod: adım atmaz, kilidini yenilemez; deposu artık veritabanına yazmaz. */
        volatile boolean dead;
        /** İşçi adım atmaz (ölmeden — ör. kendi işçisi kapalı koordinatör). */
        volatile boolean paused;
        /** Sahiplenilen parçalar bekletilir (yavaş parça); false olunca sonraki adımda koşar. */
        volatile boolean holdRuns;
        /** Koordinatör uykusunda donma koşulu (ölüm); null = yok. */
        volatile BooleanSupplier freezeWhen;
        volatile boolean frozen;
        final CountDownLatch thaw = new CountDownLatch(1);
        final CountDownLatch frozenSignal = new CountDownLatch(1);
        /** Bu pod'un her ölçümünde (ör. ölçüm ortasında ölüm). */
        volatile Runnable onMeasure;
        /** İşçi kendi gerçek iş parçacığında koşar (adımlar ona dokunmaz). */
        boolean realThreadsWorker;

        Node(int index, boolean legacy) {
            this.index = index;
            this.legacy = legacy;
        }

        SchedulerService ss() {
            return pod.scheduler();
        }

        String owner() {
            return pod.owner();
        }

        /** Pod'u öldürür: adımlar durur, bekleyen parça işleri atılır, depo yazmaz. */
        void kill() {
            dead = true;
            pool.drop();
        }

        long saves() {
            return calls.stream().filter(c -> "saveResult".equals(c.get("call"))).count();
        }

        List<String> savedDomains() {
            List<String> out = new ArrayList<>();
            synchronized (calls) {
                for (Map<String, Object> c : calls) {
                    if ("saveResult".equals(c.get("call"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> r = (Map<String, Object>) c.get("result");
                        out.add((String) r.get("domain"));
                    }
                }
            }
            return out;
        }

        long callsOf(String name) {
            return calls.stream().filter(c -> name.equals(c.get("call"))).count();
        }

        CertSweepStatus.Worker workerStatus() {
            return worker == null ? null : worker.status();
        }

        @Override
        public String toString() {
            return "pod" + index;
        }
    }

    Node node(int n) {
        return node(n, new Opts());
    }

    /** n. pod'u kurar (sahte denetleyici, kaydeden hizmetler, gerçek kesinti / sağlık deposu casusları, 3-4 parçaları). */
    Node node(int n, Opts o) {
        Node node = new Node(n, o.legacy);
        node.leader.set(o.leader);
        CertificateCheckerService checker = mock(CertificateCheckerService.class);
        if (o.realThreads) {
            ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
            ex.setCorePoolSize(o.executorThreads);
            ex.setMaxPoolSize(o.executorThreads);
            ex.setQueueCapacity(5_000);
            ex.setThreadNamePrefix("r34-cert-check-" + n + "-");
            ex.setDaemon(true);
            ex.initialize();
            node.executor = ex;
        }
        when(checker.checkAsync(anyString(), anyInt(), anyBoolean(), any(), any())).thenAnswer(i -> {
            String d = i.getArgument(0);
            Integer port = i.getArgument(1);
            boolean proxy = i.getArgument(2);
            String mode = i.getArgument(3);
            Integer timeout = i.getArgument(4);
            if (node.executor == null) {
                return CompletableFuture.completedFuture(measure(node, d, port, proxy, mode, timeout));
            }
            return CompletableFuture.supplyAsync(() -> measure(node, d, port, proxy, mode, timeout), node.executor);
        });
        node.checker = checker;
        CertificateService cs = mock(CertificateService.class);
        doAnswer(i -> {
            Map<String, Object> m = i.getArgument(0);
            record(node, "saveResult", "result", CertSweepGoldenHarness.deepCopy(m));
            // CertificateService.saveResult, determineDeploymentStatus sonucunu HARİTAYA yazar (CS:89-90)
            m.put("deployment_status", "valid".equals(m.get("status")) ? "SIM-OK" : "SIM-" + m.get("status"));
            return null;
        }).when(cs).saveResult(any());
        doAnswer(i -> {
            record(node, "evictAllCaches");
            return null;
        }).when(cs).evictAllCaches();
        node.certService = cs;
        EscalationService esc = mock(EscalationService.class);
        doAnswer(i -> {
            record(node, "processResults", "results", CertSweepGoldenHarness.deepCopy(i.getArgument(0)));
            return null;
        }).when(esc).processResults(anyList());
        when(esc.resolveVerifiedStaleCertAlerts(anyList())).thenAnswer(i -> {
            List<Map<String, Object>> l = i.getArgument(0);
            record(node, "resolveVerifiedStaleCertAlerts", "results", CertSweepGoldenHarness.deepCopy(l));
            return (int) l.stream().filter(m -> "valid".equals(m.get("status"))).count();
        });
        node.escalation = esc;

        CertNetworkOutageService realOutage = new CertNetworkOutageService(mp.jdbc(), email, settings)
                .withSystemAdminEmailDefault(ADMIN);
        ReflectionTestUtils.setField(realOutage, "clock", clock);
        CertNetworkOutageService outage = Mockito.spy(realOutage);
        doAnswer(i -> {
            record(node, "onRun", "networkErrors", i.getArgument(0), "total", i.getArgument(1), "rate", i.getArgument(2),
                    "suspected", i.getArgument(3), "minErrors", i.getArgument(4), "threshold", i.getArgument(5));
            return i.callRealMethod();
        }).when(outage).onRun(anyLong(), anyInt(), anyDouble(), anyBoolean(), anyInt(), anyDouble());
        node.outage = outage;
        ClusterHealthStore health = Mockito.spy(new ClusterHealthStore(mp.jdbc()));
        doAnswer(i -> {
            record(node, "recordScanRun", "kind", i.getArgument(0), "runId", i.getArgument(1), "instanceId", i.getArgument(2),
                    "total", i.getArgument(5), "warnings", i.getArgument(6), "errors", i.getArgument(7),
                    "failure", i.getArgument(8));
            return i.callRealMethod();
        }).when(health).recordScanRun(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        node.health = health;

        mp.use(CertificateCheckerService.class, checker);
        mp.use(CertificateService.class, cs);
        mp.use(EscalationService.class, esc);
        node.pod = mp.pod(n, s -> {
            ReflectionTestUtils.setField(s, "certNetworkOutage", outage);
            ReflectionTestUtils.setField(s, "clusterHealthStore", health);
            ReflectionTestUtils.setField(s, "systemAdminEmail", ADMIN);
            ReflectionTestUtils.setField(s, "networkMinErrors", 3);
            ReflectionTestUtils.setField(s, "networkErrorRateThreshold", 0.5);
            ReflectionTestUtils.setField(s, "lockTtlMinutes", 10);
            ReflectionTestUtils.setField(s, "certCheckMaxWaitSeconds", 900L);
            ReflectionTestUtils.setField(s, "hourlyLockWaitMs", 0L);
            ReflectionTestUtils.setField(s, "hourlyLockRetryMs", 0L);
            if (node.executor != null) ReflectionTestUtils.setField(s, "certCheckExecutor", node.executor);
            if (wall) {
                ReflectionTestUtils.setField(s, "scheduleClock", clock);
                ReflectionTestUtils.setField(s, "lockService", new com.sitemonitor.service.schedule.ClusterLockService(
                        mp.jdbc(), MultiPodTestSupport.owner(n), clock, false));
            }
        });
        nodes.add(node);
        if (o.legacy) return node;

        CertChunking.Tuning t = o.tuning != null ? o.tuning : tuning;
        if (o.workerOff) {
            t = new CertChunking.Tuning(false, t.chunkSize(), t.minDomains(), t.maxChunks(), t.maxInflightDomains(), t.leaseMs(),
                    t.stealAfterMs(), t.leaderSharePct(), t.pollMs(), t.idlePollMs());
        }
        CertChunking cc = new CertChunking(settings, t);
        node.chunking = cc;
        DbClock dbClock = dbClock();
        JdbcCertSweepStore real = new JdbcCertSweepStore(mp.jdbc(), dbClock, Boolean.FALSE);
        real.markTablesReady(true);
        node.realStore = real;
        NodeStore store = new NodeStore(node, real);
        node.store = store;
        SchedulerCertChunks runner = new SchedulerCertChunks(node.ss());
        node.runner = runner;
        LongSupplier nanos = wall ? System::nanoTime : () -> TimeUnit.MILLISECONDS.toNanos(mp.now());
        CertChunkWorker worker = new CertChunkWorker(store, cc, () -> runner, node.leader::get, node.otherLive::get, node.owner(),
                nanos);
        node.worker = worker;
        node.realThreadsWorker = o.realThreads || o.lazy;
        if (!o.realThreads && !o.lazy && t.workerEnabled()) R34WorkerAccess.startForTest(worker, node.pool);
        worker.register();   // durum kaynağı + tembel başlatıcı (belirlenimci kipte zaten başlamış — no-op)
        LongSupplier mono = wall ? () -> System.nanoTime() / 1_000_000L : mp::now;
        CertRunCoordinator coordinator = CertSweepFakes.coordinator(cc, store, runner, node.otherLive::get,
                names -> issuersFor(names), node.owner(), mono, ms -> coordinatorSleep(node, ms));
        node.coordinator = coordinator;
        if (o.realThreads) coordinator.setLocalWorkerPoke(worker::poke);   // üretimdeki bağlama (işçi start)
        ReflectionTestUtils.setField(node.ss(), "certRunCoordinator", coordinator);
        cc.onReady(null);   // hazır olayı: anahtar açıksa tembel başlatıcılar (gerçek kipte işçi iş parçacığı)
        return node;
    }

    /** Anahtar değişti (başka pod'un kaydı 10 sn yenilemesinde görüldü) — her pod'un {@link CertChunking}'i görür. */
    void settingsChanged() {
        for (Node n : nodes) {
            if (n.chunking != null) {
                n.chunking.onSettingsChanged(new AppSettingsChangedEvent(Set.of(CertChunking.KEY_ENABLED), "refresh"));
            }
        }
    }

    Map<String, String> issuersFor(List<String> names) {
        Map<String, String> m = new HashMap<>();
        for (String n : names) {
            String i = issuers.get(n);
            if (i != null) m.put(n, i);
        }
        return m;
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

    private void record(Node node, String call, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("call", call);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        m.put("#", seq.incrementAndGet());
        m.put("@", System.nanoTime());
        node.calls.add(m);
    }

    /** Pod'un {@code call} adlı İLK kararının anı (System.nanoTime; yoksa -1). */
    long firstCallNanos(Node node, String call) {
        synchronized (node.calls) {
            for (Map<String, Object> c : node.calls) if (call.equals(c.get("call"))) return (Long) c.get("@");
        }
        return -1L;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Sürüş (belirlenimci)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Koordinatörün 500 ms uykusu: donma koşulu tutarsa donar (ölü koordinatör), aksi hâlde bir küme adımı. */
    void coordinatorSleep(Node n, long ms) {
        if (wall) {
            try {
                Thread.sleep(Math.max(1L, ms / 10));   // kapasite: zaman 1:10
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (n.dead) return;   // dondurulup çözülmüş ölü koordinatör: adım atmaz
        BooleanSupplier f = n.freezeWhen;
        if (f != null && f.getAsBoolean()) {
            n.frozen = true;
            n.dead = true;
            n.frozenSignal.countDown();
            try {
                n.thaw.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        step(ms);
    }

    void step() {
        step(STEP_MS);
    }

    /** Bir küme adımı: saat ilerler, kilitler yenilenir, her canlı işçi bir yoklama adımı atar, sahiplenilen parçalar ölçülür. */
    synchronized void step(long ms) {
        if (!wall) mp.advance(ms);
        long now = clock.getAsLong();
        if (now - lastRenew >= RENEW_EVERY_MS) {
            for (Node n : nodes) if (!n.dead && !n.frozen) n.pod.locks().renewAll();
            lastRenew = now;
        }
        for (Node n : nodes) {
            if (n.worker == null || n.dead || n.paused || n.frozen) continue;
            if (!n.worker.status().started() || n.realThreadsWorker) continue;   // başlamamış (kapalı / tembel) ya da kendi iş parçacığında
            R34WorkerAccess.tick(n.worker);
        }
        for (Node n : nodes) {
            if (n.dead || n.holdRuns) continue;
            n.pool.runAll(n);
        }
        long k = steps.incrementAndGet();
        for (LongConsumer h : stepHooks) h.accept(now);
        if (k > maxSteps) throw new AssertionError("adım tavanı aşıldı (" + maxSteps + ") — beklenen olay gerçekleşmedi");
    }

    /** {@code ms} süre boyunca adımlar. */
    void steps(long ms) {
        long until = clock.getAsLong() + ms;
        int guard = 0;
        while (clock.getAsLong() < until && guard++ < 100_000) step();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Turlar
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Tam tur — admin "Şimdi çalıştır" / açılış ({@code runCheck}: envanterden, önbellek boşaltır). */
    void runFull(Node n) {
        n.ss().runCheck();
    }

    /** Saatlik tur gövdesi ({@code scheduledHourlyCheckNow}: önce kilit, sonra cron turu, vadesi gelenler). */
    void runHourly(Node n) {
        invoke(n.ss(), "scheduledHourlyCheckNow", new Class<?>[] {String.class}, CRON);
    }

    /** Bayat süpürmenin gövdesi ({@code checkStaleInventoryRound}: {@code latest_checks} tazeleri dışında kalanlar). */
    void runStale(Node n) {
        invoke(n.ss(), "checkStaleInventoryRound", new Class<?>[0]);
    }

    /** Tur gövdesi doğrudan (verilen vade listesi, tür). */
    void runDomains(Node n, List<Map<String, Object>> domains, boolean evict, String kind) {
        invoke(n.ss(), "runCheckForDomains", new Class<?>[] {List.class, boolean.class, SchedulerService.LockTicket.class,
                String.class}, domains, evict, null, kind);
    }

    static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Turu ayrı iş parçacığında başlatır (koordinatör ölümü / eşzamanlılık); test sonunda {@code join}. */
    Thread runAsync(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Okumalar
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    List<Map<String, Object>> runRows() {
        return mp.jdbc().queryForList("SELECT run_id, kind, coordinator, state, outcome, domains_total, chunks_total, "
                + "chunk_size, created_ms, heartbeat_ms, finished_ms, takeovers, reclaims, second_opinions, workers_json "
                + "FROM cert_sweep_runs ORDER BY created_ms, run_id");
    }

    Map<String, Object> runRow(String runId) {
        for (Map<String, Object> r : runRows()) if (runId.equals(r.get("run_id"))) return r;
        return null;
    }

    Map<String, Object> onlyRun() {
        List<Map<String, Object>> rs = runRows();
        if (rs.size() != 1) throw new AssertionError("tam bir parçalı koşu satırı bekleniyordu: " + rs);
        return rs.get(0);
    }

    int chunkRows() {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks", Integer.class);
        return n == null ? 0 : n;
    }

    List<Map<String, Object>> chunks(String runId) {
        return mp.jdbc().queryForList("SELECT id, chunk_no, state, attempts, claimed_by, worker, avoid_worker, domain_count "
                + "FROM cert_sweep_chunks WHERE run_id = ? ORDER BY chunk_no", runId);
    }

    int ongoingOutages() {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM network_outage_events WHERE status = 'ONGOING'", Integer.class);
        return n == null ? 0 : n;
    }

    int scanRuns(String kind) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM scan_runs WHERE kind = ?", Integer.class, kind);
        return n == null ? 0 : n;
    }

    Map<String, Object> scanRun(String kind) {
        List<Map<String, Object>> l = mp.jdbc().queryForList("SELECT * FROM scan_runs WHERE kind = ?", kind);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Alan adı → kaç pod'da kaç kez KAYDEDİLDİ (tüm pod'lar). */
    Map<String, Integer> saveCounts() {
        Map<String, Integer> out = new TreeMap<>();
        for (Node n : nodes) for (String d : n.savedDomains()) out.merge(d, 1, Integer::sum);
        return out;
    }

    /** Pod → ölçülen alan adı sayısı. */
    Map<Integer, Integer> checksByPod() {
        Map<Integer, Integer> out = new TreeMap<>();
        for (Check c : checks) out.merge(c.pod(), 1, Integer::sum);
        return out;
    }

    /** {@code checkAsync} bağımsız değişkenlerinin çoklu kümesi (sıralı). */
    List<String> checkArgs() {
        List<String> out = new ArrayList<>();
        for (Check c : checks) out.add(c.args());
        Collections.sort(out);
        return out;
    }

    /** Klasik vade listesinin beklenen {@code checkAsync} çoklu kümesi. */
    List<String> expectedCheckArgs() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String mode = (String) r.get("tls_mode");
            out.add(r.get("domain") + "|" + r.get("port") + "|" + r.get("use_proxy") + "|"
                    + (mode == null || mode.isBlank() ? null : mode) + "|" + r.get("timeout_seconds"));
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Pod'un KARAR dökümü — kayıt (çağrı anındaki harita), önbellek, kesinti kararı, alarm listeleri, tarama durumu — tek satır
     * / çağrı, kanonik JSON (Integer ≡ Long, tur kimliği {@code <run>}, sahip {@code <owner>}).
     */
    List<String> decisions(Node n) {
        List<Map<String, Object>> cl;
        synchronized (n.calls) {
            cl = new ArrayList<>(n.calls);
        }
        Set<String> runIds = new java.util.HashSet<>();
        for (Map<String, Object> c : cl) {
            if ("recordScanRun".equals(c.get("call")) && c.get("runId") != null) runIds.add((String) c.get("runId"));
            if ("saveResult".equals(c.get("call"))) {
                @SuppressWarnings("unchecked")
                Object r = ((Map<String, Object>) c.get("result")).get("run_id");
                if (r != null) runIds.add(String.valueOf(r));
            }
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : cl) {
            Map<String, Object> copy = new LinkedHashMap<>(c);
            copy.remove("#");
            copy.remove("@");
            if (copy.containsKey("instanceId")) copy.put("instanceId", "<owner>");
            out.add(canon(copy, runIds));
        }
        return out;
    }

    /** Kanonik tek satır JSON: Integer / Long aynı, tam sayı değerli ondalık {@code x.0}, tur kimlikleri {@code <run>}. */
    static String canon(Object v, Set<String> runIds) {
        StringBuilder sb = new StringBuilder();
        canon(sb, v, runIds);
        return sb.toString();
    }

    private static void canon(StringBuilder sb, Object v, Set<String> runIds) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            sb.append(v);
        } else if (v instanceof Number num) {
            double d = num.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d).append(".0");
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                canon(sb, e.getValue(), runIds);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                canon(sb, o, runIds);
            }
            sb.append(']');
        } else {
            String s = String.valueOf(v);
            for (String r : runIds) if (!r.isEmpty()) s = s.replace(r, "<run>");
            sb.append('"').append(s.replace("\"", "\\\"")).append('"');
        }
    }

    /** İki karar dökümünün ilk farkını okunur verir (null = eşit). */
    static String firstDifference(List<String> expected, List<String> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                String e = expected.get(i);
                String a = actual.get(i);
                int k = 0;
                while (k < Math.min(e.length(), a.length()) && e.charAt(k) == a.charAt(k)) k++;
                int from = Math.max(0, k - 120);
                return "satır " + i + " farklı (karakter " + k + "):\n  klasik: …" + e.substring(from, Math.min(e.length(), k + 200))
                        + "\n  parçalı: …" + a.substring(from, Math.min(a.length(), k + 200));
            }
        }
        if (expected.size() != actual.size()) {
            return "satır sayısı farklı: klasik " + expected.size() + ", parçalı " + actual.size() + " — klasik çağrılar "
                    + names(expected) + ", parçalı " + names(actual);
        }
        return null;
    }

    private static List<String> names(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            int i = l.indexOf("\"call\":\"");
            if (i >= 0) out.add(l.substring(i + 8, l.indexOf('"', i + 8)));
        }
        return out;
    }

    /**
     * Aynı envanter + kâhin + kurulumla TEK pod (20.170.0 benzeri, 3-4 parçası yok) klasik turun karar dökümü — taze
     * veritabanında. {@code setup} kâhini / envanteri bu düzeneğe uygular; {@code run} turu koşturur.
     */
    static List<String> classicDecisions(java.util.function.Consumer<R34CertRig> setup,
                                         java.util.function.BiConsumer<R34CertRig, Node> run) {
        try (R34CertRig c = new R34CertRig()) {
            setup.accept(c);
            Node n = c.node(1, new Opts().legacy());
            run.accept(c, n);
            return c.decisions(n);
        }
    }

    /** Tüm gerçek iş parçacıklarını durdurur ve veritabanını kapatır. */
    @Override
    public void close() {
        for (Node n : nodes) {
            n.thaw.countDown();
            if (n.worker != null) {
                try {
                    n.worker.onContextClosed(null);
                    n.worker.destroy();
                } catch (RuntimeException ignored) {
                    // kapanış yarışı
                }
            }
            if (n.executor != null) n.executor.shutdown();
        }
        mp.close();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Depo sarmalayıcısı (ölü pod + kayıt) ve bekletilen havuz
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Pod'un deposu: gerçek {@link JdbcCertSweepStore}'a iletir; sahiplenme / tamamlamaları kaydeder. Pod ÖLÜYKEN işçi yüzü
     * veritabanına HİÇ gitmez (süreç yok): sahiplenme boş, yenileme / bırakma 0, tamamlama false.
     */
    final class NodeStore implements CertSweepStore {
        final Node node;
        final CertSweepStore d;
        final AtomicInteger claimCalls = new AtomicInteger();

        NodeStore(Node node, CertSweepStore d) {
            this.node = node;
            this.d = d;
        }

        @Override public long nowMs() { return d.nowMs(); }
        @Override public boolean clockStale() { return d.clockStale(); }
        @Override public boolean tablesReady() { return d.tablesReady(); }
        @Override public List<CertSweepStatus.Run> settleOrphans() { return d.settleOrphans(); }
        @Override public int pruneRuns(long olderThanMs, int limit) { return d.pruneRuns(olderThanMs, limit); }
        @Override public void createRun(CertRunSpec spec) { d.createRun(spec); }
        @Override public int addChunks(String runId, List<CertChunkSpec> chunks) { return d.addChunks(runId, chunks); }
        @Override public boolean heartbeat(String runId) { return d.heartbeat(runId); }
        @Override public List<ChunkState> chunkStates(String runId) { return d.chunkStates(runId); }
        @Override public Map<Long, String> readResults(String runId, Collection<Long> ids) { return d.readResults(runId, ids); }
        @Override public boolean cancel(String runId, long chunkId) { return d.cancel(runId, chunkId); }

        @Override
        public boolean markDeciding(String runId, String workersJson, int takeovers, int reclaims, int secondOpinions) {
            return d.markDeciding(runId, workersJson, takeovers, reclaims, secondOpinions);
        }

        @Override
        public boolean finish(String runId, String state, String outcome, boolean atShutdown) {
            return d.finish(runId, state, outcome, atShutdown);
        }

        @Override
        public Optional<ClaimedChunk> claim(String owner, long leaseMs, long createdBeforeMs) {
            if (node.dead) return Optional.empty();
            claimCalls.incrementAndGet();
            Optional<ClaimedChunk> c = d.claim(owner, leaseMs, createdBeforeMs);
            c.ifPresent(k -> claims.add(new Claim(node.index, k.id(), k.runId(), k.chunkNo(), k.attempts(), k.domainCount(),
                    clock.getAsLong())));
            return c;
        }

        @Override
        public int renew(String owner, Collection<Long> chunkIds, long leaseMs) {
            if (node.dead) return 0;
            return d.renew(owner, chunkIds, leaseMs);
        }

        @Override
        public boolean complete(ChunkCompletion c) {
            if (node.dead) return false;
            boolean ok = d.complete(c);
            completions.add(new Completion(node.index, c.id(), c.checked(), c.networkErrors(), ok, clock.getAsLong()));
            return ok;
        }

        @Override
        public int release(String owner, Collection<Long> chunkIds) {
            if (node.dead) return 0;
            return d.release(owner, chunkIds);
        }

        @Override public CertSweepStatus status(int lastRuns) { return d.status(lastRuns); }
    }

    /** Sahiplenilen parça işlerini kuyrukta tutar; {@link #runAll(Node)} adımın sonunda koşturur, {@link #drop()} atar. */
    static final class HeldPool extends AbstractExecutorService {
        final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        volatile boolean shut;

        @Override
        public void execute(Runnable command) {
            if (shut) throw new RejectedExecutionException("kapalı");
            queued.add(command);
        }

        /** Kuyruktaki işleri sırayla koşturur; pod bu arada ölürse kalanlar atılır. */
        int runAll(Node n) {
            int k = 0;
            Runnable r;
            while ((r = queued.poll()) != null) {
                if (n.dead) {
                    queued.clear();
                    break;
                }
                r.run();
                k++;
            }
            return k;
        }

        int drop() {
            int k = queued.size();
            queued.clear();
            return k;
        }

        int size() {
            return queued.size();
        }

        @Override
        public void shutdown() {
            shut = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shut = true;
            List<Runnable> out = new ArrayList<>(queued);
            queued.clear();
            return out;
        }

        @Override
        public boolean isShutdown() {
            return shut;
        }

        @Override
        public boolean isTerminated() {
            return shut && queued.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
