package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageResourceIssueRepository;
import com.sitemonitor.repository.PageSpeedCheckRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PageSpeedResourceRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.service.checks.CheckJobRow;
import com.sitemonitor.service.checks.CheckJobStore;
import com.sitemonitor.service.checks.CheckResultCodec;
import com.sitemonitor.service.checks.CheckResultRow;
import com.sitemonitor.service.checks.CheckWorkerRegistry;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.page.HttpPhaseProbe;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Çok pod Sürüm 3-3 paket B testlerinin ortak düzeneği: {@code SchedulerService} {@link ClassicSweepGoldenTest} gibi kurulur
 * (en geniş kurucu, sahte bağımlılıklar, alan enjeksiyonu), sekiz türün sabit denetleyici fikstürleri (altın kayıtlardakilerin
 * eşi), sahte iş kuyruğu / işçi kaydı ve {@code MonitoringOutageService} çağrılarının normalleştirilmiş dökümü.
 */
final class R33SweepHarness {

    final MonitoringOutageService outage;
    final EscalationService escalation = mock(EscalationService.class);
    final ActivityLogService activity = mock(ActivityLogService.class);
    final AppSettingsService appSettings = mock(AppSettingsService.class);
    final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    final PortCheckerService portChecker = mock(PortCheckerService.class);
    final PortMonitorRepository portRepo = mock(PortMonitorRepository.class);
    final PortCheckRepository portCheckRepo = mock(PortCheckRepository.class);
    final KeywordCheckerService keywordChecker = mock(KeywordCheckerService.class);
    final KeywordMonitorRepository keywordRepo = mock(KeywordMonitorRepository.class);
    final KeywordResultRepository keywordResultRepo = mock(KeywordResultRepository.class);
    final KeywordHeaderSecrets keywordHeaders = mock(KeywordHeaderSecrets.class);
    final HttpCheckerService httpChecker = mock(HttpCheckerService.class);
    final HttpMonitorRepository httpRepo = mock(HttpMonitorRepository.class);
    final HttpCheckRepository httpCheckRepo = mock(HttpCheckRepository.class);
    final PingCheckerService pingChecker = mock(PingCheckerService.class);
    final PingMonitorRepository pingRepo = mock(PingMonitorRepository.class);
    final PingCheckRepository pingCheckRepo = mock(PingCheckRepository.class);
    final DnsCheckerService dnsChecker = mock(DnsCheckerService.class);
    final DnsMonitorRepository dnsRepo = mock(DnsMonitorRepository.class);
    final DnsRecordRepository dnsRecordRepo = mock(DnsRecordRepository.class);
    final PageCheckerService pageChecker = mock(PageCheckerService.class);
    final PageMonitorRepository pageRepo = mock(PageMonitorRepository.class);
    final PageCheckRepository pageCheckRepo = mock(PageCheckRepository.class);
    final PageResourceIssueRepository pageIssueRepo = mock(PageResourceIssueRepository.class);
    final PageSpeedCheckerService pageSpeedChecker = mock(PageSpeedCheckerService.class);
    final PageSpeedMonitorRepository pageSpeedRepo = mock(PageSpeedMonitorRepository.class);
    final PageSpeedCheckRepository pageSpeedCheckRepo = mock(PageSpeedCheckRepository.class);
    final PageSpeedResourceRepository pageSpeedResourceRepo = mock(PageSpeedResourceRepository.class);
    final ScriptedCheckerService scriptedChecker = mock(ScriptedCheckerService.class);
    final ScriptedMonitorRepository scriptedRepo = mock(ScriptedMonitorRepository.class);
    final ScriptedCheckRepository scriptedCheckRepo = mock(ScriptedCheckRepository.class);

    /** Ayar geçersiz kılmaları (anahtar → değer); yoksa çağıranın varsayılanı. */
    final Map<String, Object> settings = new ConcurrentHashMap<>();
    /** Zamanlama saati (ms) — {@code scheduleClock}. */
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final SchedulerService scheduler;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    R33SweepHarness() throws Exception {
        this(false);
    }

    /**
     * {@code realMos} → GERÇEK {@code MonitoringOutageService} (zamanlanmış sonuç çiti yok, teyit yürütücüsü görevleri
     * BEKLETİR / koşmaz, bastırma saati = {@link #clock}); pencere / tur aralığı testleri için. Aksi hâlde sahte.
     */
    R33SweepHarness(boolean realMos) throws Exception {
        outage = realMos ? realMos() : mock(MonitoringOutageService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(i -> settings.getOrDefault(i.getArgument(0), i.getArgument(1)));
        lenient().when(appSettings.getInt(anyString(), anyInt()))
                .thenAnswer(i -> settings.getOrDefault(i.getArgument(0), i.getArgument(1)));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any()))
                .thenAnswer(i -> settings.getOrDefault(i.getArgument(0), i.getArgument(1)));

        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(EscalationService.class, escalation);
        provided.put(ActivityLogService.class, activity);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(CertificateInventoryRepository.class, inventoryRepo);
        provided.put(JdbcTemplate.class, jdbc);
        provided.put(PortCheckerService.class, portChecker);
        provided.put(PortMonitorRepository.class, portRepo);
        provided.put(PortCheckRepository.class, portCheckRepo);
        provided.put(KeywordCheckerService.class, keywordChecker);
        provided.put(KeywordMonitorRepository.class, keywordRepo);
        provided.put(KeywordResultRepository.class, keywordResultRepo);
        provided.put(HttpCheckerService.class, httpChecker);
        provided.put(HttpMonitorRepository.class, httpRepo);
        provided.put(HttpCheckRepository.class, httpCheckRepo);
        provided.put(PingCheckerService.class, pingChecker);
        provided.put(PingMonitorRepository.class, pingRepo);
        provided.put(PingCheckRepository.class, pingCheckRepo);
        provided.put(DnsCheckerService.class, dnsChecker);
        provided.put(DnsMonitorRepository.class, dnsRepo);
        provided.put(DnsRecordRepository.class, dnsRecordRepo);
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(scheduler, "sweepLockTtlMinutes", 2);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", keywordHeaders);
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageMonitorRepo", pageRepo);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", pageCheckRepo);
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", pageIssueRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckerService", pageSpeedChecker);
        ReflectionTestUtils.setField(scheduler, "pageSpeedMonitorRepo", pageSpeedRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckRepo", pageSpeedCheckRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedResourceRepo", pageSpeedResourceRepo);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckerService", scriptedChecker);
        ReflectionTestUtils.setField(scheduler, "scriptedMonitorRepo", scriptedRepo);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckRepo", scriptedCheckRepo);
        ReflectionTestUtils.setField(scheduler, "scheduleClock", (java.util.function.LongSupplier) clock::get);

        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("inv-host.example.com");
        inv.setActive(true);
        lenient().when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
    }

    private MonitoringOutageService realMos() {
        com.sitemonitor.repository.AlertEventRepository alerts = mock(com.sitemonitor.repository.AlertEventRepository.class);
        lenient().when(alerts.findOpenByDomainIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());
        MonitoringOutageService mos = new MonitoringOutageService(alerts, escalation, jdbc, dnsRecordRepo, dnsRepo,
                appSettings, mock(com.sitemonitor.repository.NetworkOutageEventRepository.class));
        ReflectionTestUtils.setField(mos, "portAlertEnabled", true);
        ReflectionTestUtils.setField(mos, "dnsAlertEnabled", true);
        ReflectionTestUtils.setField(mos, "pingAlertEnabled", true);
        ReflectionTestUtils.setField(mos, "keywordAlertEnabled", true);
        ReflectionTestUtils.setField(mos, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(mos, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(mos, "confirmAttempts", 3);
        ReflectionTestUtils.setField(mos, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(mos, "confirmExecutor", new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override
            public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay,
                                                                   java.util.concurrent.TimeUnit unit) {
                return null;   // teyit zinciri adımları bu testlerde koşmaz
            }
        });
        ReflectionTestUtils.setField(mos, "suppressionClock", (java.util.function.LongSupplier) clock::get);
        return mos;
    }

    // ── dağıtık altyapı ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Anahtarı açık / kapalı bir {@link DistributedChecks} bağlar (tür listesi {@code types}; null = varsayılan sekiz). */
    DistributedChecks bindDistributed(boolean enabled, String types) {
        settings.put(DistributedChecks.KEY_ENABLED, enabled);
        if (types != null) settings.put(DistributedChecks.KEY_TYPES, types);
        if (ReflectionTestUtils.getField(scheduler, "taskSchedulerProvider") == null) {
            // Varsayılan: sahte zamanlayıcı (görev koşmaz) — gönderim tıkı "kurulu" sayılır, testler tıkı elle çağırır.
            org.springframework.scheduling.TaskScheduler ts = mock(org.springframework.scheduling.TaskScheduler.class);
            Mockito.doReturn(mock(java.util.concurrent.ScheduledFuture.class)).when(ts)
                    .scheduleWithFixedDelay(any(Runnable.class), any(java.time.Instant.class), any(java.time.Duration.class));
            org.springframework.beans.factory.support.StaticListableBeanFactory bf =
                    new org.springframework.beans.factory.support.StaticListableBeanFactory();
            bf.addBean("taskScheduler", ts);
            ReflectionTestUtils.setField(scheduler, "taskSchedulerProvider",
                    bf.getBeanProvider(org.springframework.scheduling.TaskScheduler.class));
        }
        DistributedChecks dc = new DistributedChecks(appSettings, DistributedChecks.Tuning.DEFAULTS);
        scheduler.setDistributedChecks(dc);
        if (ReflectionTestUtils.getField(scheduler, "checkResultDrainer") == null) {
            // FX-EVAL (DH-M2): distributedMode boşaltıcının sağlığını da ister — varsayılan: boşaltabilen sahte boşaltıcı
            drainer = new StubDrainer(dc);
            ReflectionTestUtils.setField(scheduler, "checkResultDrainer", drainer);
        }
        dc.onReady(null);   // açılış hazır: anahtar açıksa tembel başlatıcı (gönderim tıkı) burada kurulur
        return dc;
    }

    /** {@link #bindDistributed} kurduğu sahte boşaltıcı (yoksa null). */
    StubDrainer drainer;

    /**
     * Boşaltma sağlığı test eden sahte boşaltıcı: {@link #canDrain()} = {@link #healthy}; klasik bekletme yok; gerçek
     * {@code noteClassicCheck} / taban mantığı iş parçacığı kurulmadığı için ({@code started} false) hiç yazmaz.
     */
    static final class StubDrainer extends com.sitemonitor.service.checks.CheckResultDrainer {
        volatile boolean healthy = true;
        volatile boolean holdClassic;
        final List<String> classicNotes = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicLong floorCalls = new AtomicLong();
        final List<Boolean> switchNotes = new java.util.concurrent.CopyOnWriteArrayList<>();

        StubDrainer(DistributedChecks dc) {
            super(dc, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null);
        }

        @Override
        public boolean canDrain() {
            return healthy;
        }

        @Override
        public boolean holdClassic() {
            return holdClassic;
        }

        @Override
        public void noteClassicCheck(String type, Long monitorId) {
            classicNotes.add(type + ":" + monitorId);
        }

        @Override
        public void applyFloorIfPending() {
            floorCalls.incrementAndGet();
        }

        @Override
        public void noteSwitch(boolean on) {
            switchNotes.add(on);
        }
    }

    /** Sahte işçi kaydı: tür → başka canlı işçi sayısı (yoksa 0; {@link CheckWorkerRegistry#UNKNOWN} verilebilir). */
    static class FakeRegistry implements CheckWorkerRegistry {
        final Map<String, Integer> live = new ConcurrentHashMap<>();
        final AtomicLong reads = new AtomicLong();

        @Override
        public int liveOtherWorkers(String type) {
            reads.incrementAndGet();
            return live.getOrDefault(type, 0);
        }

        @Override
        public Set<String> localCapabilities() {
            return Set.of();
        }

        @Override
        public List<WorkerView> workers() {
            return List.of();
        }
    }

    /** Sahte kuyruk: {@code dispatch} çağrılarını kaydeder, {@code reap} sırayla verilen satırları döndürür. */
    static final class FakeStore implements CheckJobStore {
        final List<Map.Entry<String, List<Long>>> dispatched = new ArrayList<>();
        final List<Long> leases = new ArrayList<>();
        final List<String> dispatchers = new ArrayList<>();
        final List<CheckJobRow> toReap = new ArrayList<>();
        RuntimeException dispatchFailure;
        int calls;

        @Override
        public synchronized int dispatch(String type, Collection<Long> monitorIds, long leaseMs, String dispatcher) {
            calls++;
            if (dispatchFailure != null) throw dispatchFailure;
            dispatched.add(Map.entry(type, List.copyOf(monitorIds)));
            leases.add(leaseMs);
            dispatchers.add(dispatcher);
            return monitorIds.size();
        }

        @Override
        public List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs) {
            calls++;
            return List.of();
        }

        @Override
        public boolean[] complete(List<Completion> completions) {
            calls++;
            return new boolean[completions.size()];
        }

        @Override
        public int release(String owner, Collection<Long> ids) {
            calls++;
            return 0;
        }

        @Override
        public List<CheckResultRow> take(int limit, String leaseOwner) {
            calls++;
            return List.of();
        }

        @Override
        public synchronized List<CheckJobRow> reap(int limit) {
            calls++;
            List<CheckJobRow> out = new ArrayList<>(toReap);
            toReap.clear();
            return out;
        }

        @Override
        public QueueStats stats() {
            calls++;
            return new QueueStats(0, List.of(), null, null, 0);
        }

        synchronized Map<String, List<Long>> byType() {
            Map<String, List<Long>> out = new LinkedHashMap<>();
            for (var e : dispatched) out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            return out;
        }
    }

    // ── sonuç satırı ─────────────────────────────────────────────────────────────────────────────────────────────────

    private final AtomicLong rowIds = new AtomicLong();

    /** İşçinin yolu: gövde → kodlayıcı (JSON) → değerlendiricinin aldığı satır. */
    CheckResultRow workerRow(String type, long monitorId) {
        Map<String, Object> result = scheduler.runCheckJob(type, monitorId);
        CheckResultCodec.Encoded enc = CheckResultCodec.encode(result);
        long id = rowIds.incrementAndGet();
        return new CheckResultRow(id, type, monitorId, clock.get(), clock.get(), clock.get(), "worker-x", enc.json(),
                enc.truncated());
    }

    /** Elle kurulmuş satır (değerlendirici testleri için). */
    CheckResultRow row(String type, long monitorId, Map<String, Object> result) {
        CheckResultCodec.Encoded enc = CheckResultCodec.encode(result);
        return new CheckResultRow(rowIds.incrementAndGet(), type, monitorId, clock.get(), clock.get(), clock.get(),
                "worker-x", enc.json(), enc.truncated());
    }

    // ── MOS dökümü ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code handleSweepResults} / {@code handleDnsSweep} çağrıları — her biri {@code "<alertType>"} ya da {@code "DNS"} başlıklı,
     * kalemler kanonik JSON (anahtarlar sıralı, sayı türü fark etmez) olarak sıralanmış.
     */
    List<String> mosCalls() {
        List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(outage).getInvocations());
        inv.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        List<String> out = new ArrayList<>();
        for (Invocation i : inv) {
            String name = i.getMethod().getName();
            if ("handleSweepResults".equals(name)) {
                Object[] a = i.getArguments();
                boolean manual = a.length > 2 && Boolean.TRUE.equals(a[2]);
                out.add(a[0] + (manual ? "(manual)" : "") + " " + canonicalList((List<?>) a[1]));
            } else if ("handleDnsSweep".equals(name)) {
                Object[] a = i.getArguments();
                out.add("DNS failure=" + canonicalList((List<?>) a[0]) + " slow=" + canonicalList((List<?>) a[1])
                        + " changes=" + canonicalList((List<?>) a[2]) + " unexpected=" + canonicalList((List<?>) a[3])
                        + " inconsistent=" + canonicalList((List<?>) a[4]));
            }
        }
        return out;
    }

    static String canonicalList(List<?> items) {
        List<String> parts = new ArrayList<>();
        if (items != null) for (Object o : items) parts.add(ClassicSweepGoldenTest.Json.write(canonical(ClassicSweepGoldenTest.norm(o, 0))));
        parts.sort(Comparator.naturalOrder());
        return parts.toString();
    }

    /** Haritaları anahtar sırasına sokar (JSON gidiş-dönüşünde ekleme sırası anlam taşımaz). */
    static Object canonical(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), canonical(e.getValue()));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(canonical(o));
            return out;
        }
        return v;
    }

    void clearMos() {
        Mockito.clearInvocations(outage);
    }

    // ── fikstürler (altın kayıtların eşi) ────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <M> void serve(JpaRepository repo, List<M> all, Function<M, Long> idOf) {
        lenient().when(repo.findById(any())).thenAnswer(i -> all.stream()
                .filter(m -> idOf.apply(m).equals(((Number) i.getArgument(0)).longValue())).findFirst());
        lenient().when(repo.findAllById(anyIterable())).thenAnswer(i -> {
            List<Long> ids = new ArrayList<>();
            for (Object o : (Iterable<?>) i.getArgument(0)) ids.add(((Number) o).longValue());
            return all.stream().filter(m -> ids.contains(idOf.apply(m))).toList();
        });
        lenient().when(repo.findAll()).thenReturn(all);
    }

    static PortMonitor port(long id, String host, int p, boolean standalone) {
        PortMonitor m = new PortMonitor();
        m.setId(id);
        m.setName("Port " + id);
        m.setHost(host);
        m.setPort(p);
        m.setStandalone(standalone);
        m.setTeamId(standalone ? 7L : null);
        m.setIntervalSeconds(60);
        return m;
    }

    List<PortMonitor> portFixture() {
        PortMonitor a = port(1, "a.example.com", 443, true);
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(500);
        PortMonitor b = port(2, "b.example.com", 8443, true);
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(500);
        b.setProtocol("UDP");
        PortMonitor c = port(3, "c.example.com", 22, true);
        c.setSlowResponseEnabled(true);
        PortMonitor d = port(4, "inv-host.example.com", 443, false);
        PortMonitor e = port(5, "gone-host.example.com", 443, false);
        PortMonitor f = port(6, "f.example.com", 25, true);
        f.setNotifyEmail(false);
        f.setNotifyWebhook(false);
        f.setAlertLevel("CRITICAL");
        f.setNotificationGroupId(33L);
        f.setConfirmAttempts(5);
        f.setConfirmIntervalSeconds(10);
        f.setRecoveryChecks(2);
        f.setRecoveryIntervalSeconds(20);
        f.setProtocol(null);
        PortMonitor g = port(7, "boom.example.com", 80, true);
        List<PortMonitor> all = List.of(a, b, c, d, e, f, g);
        lenient().when(portRepo.findByActiveTrue()).thenReturn(all);
        serve(portRepo, all, PortMonitor::getId);
        List<Object[]> rows = new ArrayList<>();
        for (PortMonitor m : all) rows.add(new Object[]{m.getId(), m.getIntervalSeconds(), m.getStandalone(), m.getHost()});
        lenient().when(portRepo.findCheckDispatchRows()).thenReturn(rows);
        lenient().when(portChecker.check(any(PortMonitor.class))).thenAnswer(i -> {
            PortMonitor m = i.getArgument(0);
            return switch (m.getId().intValue()) {
                case 1 -> map("open", true, "response_ms", 120L);
                case 2 -> map("open", true, "response_ms", 900L);
                case 3 -> map("open", false, "error", "Connection refused", "response_ms", 15L);
                case 4 -> map("open", false, "error", "Connect timed out");
                case 6 -> map("open", true, "response_ms", 40L);
                default -> throw new IllegalStateException("soket açılamadı");
            };
        });
        return all;
    }

    static KeywordMonitor keyword(long id, String url, String kw) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(id);
        m.setName("Keyword " + id);
        m.setUrl(url);
        m.setKeyword(kw);
        m.setTeamId(9L);
        m.setIntervalSeconds(60);
        return m;
    }

    List<KeywordMonitor> keywordFixture() {
        KeywordMonitor a = keyword(1, "https://a.example.com/", "Hoş geldiniz");
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(500);
        KeywordMonitor b = keyword(2, "https://b.example.com/", "Ödeme");
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(500);
        KeywordMonitor c = keyword(3, "https://c.example.com/", "x");
        c.setSlowResponseEnabled(true);
        KeywordMonitor d = keyword(4, "c-yok", "y");
        KeywordMonitor e = keyword(5, "https://e.example.com/", "z".repeat(55));
        e.setMatchOperator("LTE");
        e.setMatchCount(1);
        e.setNotificationGroupId(12L);
        e.setNotifyWebhook(false);
        e.setAlertLevel("HIGH");
        KeywordMonitor f = keyword(6, "https://boom.example.com/", "q");
        List<KeywordMonitor> all = List.of(a, b, c, d, e, f);
        lenient().when(keywordRepo.findByActiveTrue()).thenReturn(all);
        serve(keywordRepo, all, KeywordMonitor::getId);
        lenient().when(keywordRepo.findCheckDispatchRows()).thenReturn(idRows(all, KeywordMonitor::getId, KeywordMonitor::getIntervalSeconds));
        lenient().when(keywordHeaders.effectiveHeaders(any(KeywordMonitor.class))).thenReturn(null);
        lenient().when(keywordChecker.check(anyString(), anyString(), anyInt(), any(), anyBoolean(), anyBoolean(),
                any(KeywordCheckerService.Expectation.class))).thenAnswer(i -> {
            String url = i.getArgument(0);
            return switch (url) {
                case "https://a.example.com/" -> map("found", true, "count", 3, "http_status", 200, "response_ms", 100L,
                        "snippet", "… Hoş geldiniz …");
                case "https://b.example.com/" -> map("found", false, "count", 0, "http_status", 200, "response_ms", 900L);
                case "https://c.example.com/" -> map("found", false, "http_status", 503, "response_ms", 30L,
                        "error", "HTTP 503");
                case "c-yok" -> map("found", false, "error", "URL'de host yok", "config_error", true);
                case "https://e.example.com/" -> map("found", true, "count", 2, "http_status", 200, "response_ms", 50L);
                default -> throw new IllegalStateException("bağlantı kurulamadı");
            };
        });
        return all;
    }

    static <M> List<Object[]> idRows(List<M> all, Function<M, Long> id, Function<M, Integer> interval) {
        List<Object[]> rows = new ArrayList<>();
        for (M m : all) rows.add(new Object[]{id.apply(m), interval.apply(m)});
        return rows;
    }

    static HttpMonitor http(long id, String url) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id);
        m.setName("HTTP " + id);
        m.setUrl(url);
        m.setTeamId(4L);
        m.setIntervalSeconds(60);
        return m;
    }

    List<HttpMonitor> httpFixture() {
        HttpMonitor a = http(1, "https://a.example.com/health");
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(1000);
        HttpMonitor b = http(2, "https://b.example.com/health");
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(1000);
        b.setMethod("HEAD");
        HttpMonitor c = http(3, "https://c.example.com/health");
        c.setSlowResponseEnabled(true);
        HttpMonitor d = http(4, "c-yok-http");
        HttpMonitor e = http(5, "https://e.example.com/api");
        e.setNotifyEmail(false);
        e.setNotificationGroupId(21L);
        HttpMonitor f = http(6, "https://boom.example.com/");
        List<HttpMonitor> all = List.of(a, b, c, d, e, f);
        lenient().when(httpRepo.findByActiveTrue()).thenReturn(all);
        serve(httpRepo, all, HttpMonitor::getId);
        lenient().when(httpRepo.findCheckDispatchRows()).thenReturn(idRows(all, HttpMonitor::getId, HttpMonitor::getIntervalSeconds));
        lenient().when(httpChecker.check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean())).thenAnswer(i -> {
            String url = i.getArgument(0);
            return switch (url) {
                case "https://a.example.com/health" -> map("ok", true, "http_status", 200, "response_ms", 200L);
                case "https://b.example.com/health" -> map("ok", true, "http_status", 200, "response_ms", 4000L);
                case "https://c.example.com/health" -> map("ok", false, "http_status", 503, "response_ms", 80L,
                        "error", "HTTP 503 Service Unavailable");
                case "c-yok-http" -> map("ok", false, "error", "URL'de host yok", "config_error", true);
                case "https://e.example.com/api" -> map("ok", false, "http_status", 200, "response_ms", 60L,
                        "error", "JSON doğrulaması başarısız: $.status", "json_assertion_failed", true);
                default -> throw new IllegalStateException("TLS el sıkışması düştü");
            };
        });
        return all;
    }

    static PingMonitor ping(long id, String host) {
        PingMonitor m = new PingMonitor();
        m.setId(id);
        m.setName("Ping " + id);
        m.setHost(host);
        m.setTeamId(3L);
        m.setIntervalSeconds(60);
        return m;
    }

    List<PingMonitor> pingFixture() {
        PingMonitor a = ping(1, "10.0.0.1");
        a.setSlowResponseEnabled(true);
        PingMonitor b = ping(2, "10.0.0.2");
        b.setSlowResponseEnabled(true);
        PingMonitor c = ping(3, "10.0.0.3");
        c.setSlowResponseEnabled(true);
        PingMonitor d = ping(4, "10.0.0.4");
        PingMonitor e = ping(5, "10.0.0.5");
        e.setSlowResponseEnabled(true);
        e.setIpVersion("v6");
        e.setNotifyEmail(false);
        PingMonitor f = ping(6, "10.0.0.6");
        List<PingMonitor> all = List.of(a, b, c, d, e, f);
        lenient().when(pingRepo.findByActiveTrue()).thenReturn(all);
        serve(pingRepo, all, PingMonitor::getId);
        lenient().when(pingRepo.findCheckDispatchRows()).thenReturn(idRows(all, PingMonitor::getId, PingMonitor::getIntervalSeconds));
        lenient().when(pingChecker.check(anyString(), any(), anyInt(), anyInt())).thenAnswer(i -> {
            String host = i.getArgument(0);
            return switch (host) {
                case "10.0.0.1" -> map("up", true, "rtt_ms", 20L, "packet_loss", 0);
                case "10.0.0.2" -> map("up", true, "rtt_ms", 11L, "packet_loss", 0);
                case "10.0.0.3" -> map("up", false, "packet_loss", 100, "error", "Yanıt yok (%100 paket kaybı)");
                case "10.0.0.4" -> map("up", false, "na", true, "error", "ICMP bu ortamda kullanılamıyor (yetki/binary)");
                case "10.0.0.5" -> map("up", true, "rtt_ms", 30L, "packet_loss", 25);
                default -> throw new IllegalStateException("alt süreç başlatılamadı");
            };
        });
        lenient().when(pingCheckRepo.slowBaseline(eq(1L), anyString(), anyString())).thenReturn(baseline(10.0, 5L));
        lenient().when(pingCheckRepo.slowBaseline(eq(2L), anyString(), anyString())).thenReturn(baseline(10.0, 5L));
        lenient().when(pingCheckRepo.slowBaseline(eq(5L), anyString(), anyString())).thenReturn(baseline(10.0, 2L));
        return all;
    }

    private static List<Object[]> baseline(double avg, long samples) {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{avg, samples});
        return out;
    }

    static DnsMonitor dns(long id, String domain, String type, boolean standalone) {
        DnsMonitor m = new DnsMonitor();
        m.setId(id);
        m.setName("DNS " + id);
        m.setDomain(domain);
        m.setRecordType(type);
        m.setStandalone(standalone);
        m.setTeamId(standalone ? 5L : null);
        m.setIntervalSeconds(300);
        return m;
    }

    private static DnsRecord prevRecord(String value) {
        DnsRecord r = new DnsRecord();
        r.setId(99L);
        r.setValue(value);
        return r;
    }

    List<DnsMonitor> dnsFixture() {
        DnsMonitor a = dns(1, "a.example.com", "A", true);
        a.setExpectedValue("1.2.3.4");
        DnsMonitor b = dns(2, "b.example.com", "A", true);
        DnsMonitor c = dns(3, "inv-host.example.com", "MX", false);
        DnsMonitor d = dns(4, "gone.example.com", "A", false);
        DnsMonitor e = dns(5, "e.example.com", "A", true);
        e.setPropagationCheck(true);
        e.setExpectedValue("9.9.9.9\n8.8.8.8");
        e.setNotifyWebhook(false);
        e.setAlertLevel("CRITICAL");
        e.setNotificationGroupId(44L);
        DnsMonitor f = dns(6, "f.example.com", "A", true);
        f.setDnsChangeAlertEnabled(false);
        DnsMonitor g = dns(7, "g.example.com", "A", true);
        g.setSlowThresholdMs(5000);
        DnsMonitor h = dns(8, "boom.example.com", "TXT", true);
        List<DnsMonitor> all = List.of(a, b, c, d, e, f, g, h);
        lenient().when(dnsRepo.findByActiveTrue()).thenReturn(all);
        serve(dnsRepo, all, DnsMonitor::getId);
        List<Object[]> rows = new ArrayList<>();
        for (DnsMonitor m : all) rows.add(new Object[]{m.getId(), m.getIntervalSeconds(), m.getStandalone(), m.getDomain()});
        lenient().when(dnsRepo.findCheckDispatchRows()).thenReturn(rows);
        lenient().when(dnsChecker.check(anyString(), anyString())).thenAnswer(i -> {
            String domain = i.getArgument(0);
            return switch (domain) {
                case "a.example.com" -> map("success", true, "values", List.of("1.2.3.4"), "ttl", 300L, "response_ms", 30L);
                case "b.example.com" -> map("success", true, "values", List.of("5.6.7.8"), "ttl", 60L, "response_ms", 2000L);
                case "inv-host.example.com" -> map("success", false, "values", List.of(), "error", "NXDOMAIN",
                        "response_ms", 12L);
                case "e.example.com" -> map("success", true, "values", List.of("9.9.9.9", "7.7.7.7"), "response_ms", 40L);
                case "f.example.com" -> map("success", true, "values", List.of("3.3.3.3"), "response_ms", 20L);
                case "g.example.com" -> map("success", true, "values", List.of("2.2.2.2", "3.3.3.3"), "response_ms", 25L);
                default -> throw new IllegalStateException("çözümleyici yanıt vermedi");
            };
        });
        Map<String, String> perResolver = new LinkedHashMap<>();
        perResolver.put("8.8.8.8", "9.9.9.9");
        perResolver.put("1.1.1.1", "7.7.7.7");
        lenient().when(dnsChecker.checkPropagation(eq("e.example.com"), eq("A"), anyList()))
                .thenAnswer(i -> map("inconsistent", true, "per_resolver", perResolver));
        lenient().when(dnsRecordRepo.findLatestScheduledSuccessful(anyLong())).thenAnswer(i -> {
            long id = i.getArgument(0);
            if (id == 2L) return Optional.of(prevRecord("1.2.3.4"));
            if (id == 6L) return Optional.of(prevRecord("4.4.4.4"));
            if (id == 7L) return Optional.of(prevRecord("1.1.1.1\n2.2.2.2"));
            return Optional.empty();
        });
        return all;
    }

    static PageMonitor page(long id, String url) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setName("Sayfa " + id);
        m.setUrl(url);
        m.setTeamId(6L);
        m.setIntervalSeconds(300);
        return m;
    }

    private static PageCheckerService.PageCheckResult pageResult(String status, boolean main, Integer http, int broken,
                                                                  int timeouts, int mixed, String error,
                                                                  List<PageCheckerService.ResourceIssue> issues) {
        return new PageCheckerService.PageCheckResult(status, main, http, 350L, 12, broken, timeouts, mixed, 1,
                "hash-" + status, 4096L, error, issues);
    }

    List<PageMonitor> pageFixture() {
        PageMonitor a = page(1, "https://a.example.com/");
        PageMonitor b = page(2, "https://b.example.com/");
        PageMonitor c = page(3, "https://c.example.com/");
        PageMonitor d = page(4, "c-yok-page");
        PageMonitor e = page(5, "https://crawl-home.example.com/");
        e.setMode("SITE_CRAWL");
        PageMonitor f = page(6, "https://crawl-none.example.com/");
        f.setMode("SITE_CRAWL");
        PageMonitor g = page(7, "https://g.example.com/");
        g.setAlertTimeout(false);
        g.setNotifyEmail(false);
        PageMonitor h = page(8, "https://boom.example.com/");
        List<PageMonitor> all = List.of(a, b, c, d, e, f, g, h);
        lenient().when(pageRepo.findByActiveTrue()).thenReturn(all);
        serve(pageRepo, all, PageMonitor::getId);
        lenient().when(pageRepo.findCheckDispatchRows()).thenReturn(idRows(all, PageMonitor::getId, PageMonitor::getIntervalSeconds));
        PageCheckerService.ResourceIssue broken = new PageCheckerService.ResourceIssue(
                "https://b.example.com/app.js", "SCRIPT", "https://b.example.com/urunler", "BROKEN", true, 404, 120L);
        PageCheckerService.ResourceIssue timeout = new PageCheckerService.ResourceIssue(
                "https://cdn.example.net/x.css", "STYLESHEET", "https://g.example.com/", "TIMEOUT", false, null, 4000L);
        lenient().when(pageChecker.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean())).thenAnswer(i -> {
            String url = i.getArgument(0);
            return switch (url) {
                case "https://a.example.com/", "https://crawl-home.example.com/", "https://crawl-none.example.com/" ->
                        pageResult("OK", true, 200, 0, 0, 0, null, List.of());
                case "https://b.example.com/" -> pageResult("DEGRADED", true, 200, 1, 0, 0, null, List.of(broken));
                case "https://c.example.com/" -> pageResult("DOWN", false, 502, 0, 0, 0, "HTTP 502", List.of());
                case "c-yok-page" -> pageResult("CONFIG_ERROR", false, null, 0, 0, 0, "URL'de host yok", List.of());
                case "https://g.example.com/" -> pageResult("DEGRADED", true, 200, 0, 1, 0, null, List.of(timeout));
                default -> throw new IllegalStateException("sayfa alınamadı");
            };
        });
        lenient().when(escalation.openAlertContext(eq("https://crawl-home.example.com/"), eq(EscalationService.TYPE_PAGE_INTEGRITY)))
                .thenReturn(map("integrity_scope", "HOME"));
        lenient().when(escalation.openAlertContext(eq("https://crawl-none.example.com/"), eq(EscalationService.TYPE_PAGE_INTEGRITY)))
                .thenReturn(null);
        return all;
    }

    static PageSpeedMonitor pageSpeed(long id, String url) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(id);
        m.setName("Hız " + id);
        m.setUrl(url);
        m.setTeamId(8L);
        m.setIntervalSeconds(1800);
        m.setMaxLoadMs(3000);
        m.setMaxPageKb(500);
        return m;
    }

    private static PageSpeedCheckerService.Result speed(String status, Integer code, long totalMs, long bytes,
                                                        boolean truncated, List<String> breached, String error,
                                                        List<PageSpeedCheckerService.Measured> resources) {
        return new PageSpeedCheckerService.Result(status, code, 180L, 400L, totalMs, bytes, 14, 0, false, truncated,
                breached, error, resources, new HttpPhaseProbe.Phases(5, 10, 20, 145, null), 0);
    }

    List<PageSpeedMonitor> pageSpeedFixture() {
        PageSpeedMonitor a = pageSpeed(1, "https://a.example.com/");
        PageSpeedMonitor b = pageSpeed(2, "https://b.example.com/");
        PageSpeedMonitor c = pageSpeed(3, "https://c.example.com/");
        PageSpeedMonitor d = pageSpeed(4, "c-yok-speed");
        PageSpeedMonitor e = pageSpeed(5, "https://boom.example.com/");
        e.setNotifyWebhook(false);
        List<PageSpeedMonitor> all = List.of(a, b, c, d, e);
        lenient().when(pageSpeedRepo.findByActiveTrue()).thenReturn(all);
        serve(pageSpeedRepo, all, PageSpeedMonitor::getId);
        lenient().when(pageSpeedRepo.findCheckDispatchRows()).thenReturn(idRows(all, PageSpeedMonitor::getId, PageSpeedMonitor::getIntervalSeconds));
        List<PageSpeedCheckerService.Measured> res = List.of(
                new PageSpeedCheckerService.Measured("https://b.example.com/hero.jpg", "image", 900_000L, 300L, 200,
                        false, false, false),
                new PageSpeedCheckerService.Measured("https://cdn.example.net/app.js", "script", 120_000L, 90L, 200,
                        true, false, true));
        lenient().when(pageSpeedChecker.check(any(PageSpeedMonitor.class))).thenAnswer(i -> {
            PageSpeedMonitor m = i.getArgument(0);
            return switch (m.getId().intValue()) {
                case 1 -> speed("OK", 200, 1200L, 200_000L, false, List.of(), null, List.of());
                case 2 -> speed("SLOW", 200, 5200L, 1_020_000L, true, List.of("LOAD", "SIZE"), null, res);
                case 3 -> speed("DOWN", 503, 90L, 0L, false, List.of(), "HTTP 503", List.of());
                case 4 -> speed("CONFIG_ERROR", null, 0L, 0L, false, List.of(), "URL'de host yok", List.of());
                default -> throw new IllegalStateException("ölçüm düştü");
            };
        });
        return all;
    }

    static ScriptedMonitor scripted(long id, String name) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id);
        m.setName(name);
        m.setScript("export default function () {}");
        m.setScriptVersion("v" + id);
        m.setTeamId(2L);
        m.setIntervalSeconds(300);
        return m;
    }

    private static ScriptedCheckerService.ScriptedResult k6(String status, boolean ok, Long durationMs, Integer passed,
                                                           Integer failed, String checksJson, String tail, String error) {
        return new ScriptedCheckerService.ScriptedResult(status, ok, durationMs, status.equals("SKIPPED") ? -1 : 0,
                passed, failed, durationMs, 80L, 120L, checksJson, tail, error, false,
                ScriptedCheckerService.Phases.EMPTY);
    }

    List<ScriptedMonitor> scriptedFixture() {
        ScriptedMonitor a = scripted(1, "Giriş akışı");
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(1000);
        ScriptedMonitor b = scripted(2, "Sepet akışı");
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(1000);
        ScriptedMonitor c = scripted(3, "Ödeme akışı");
        c.setSlowResponseEnabled(true);
        ScriptedMonitor d = scripted(4, "Atlanan akış");
        ScriptedMonitor e = scripted(5, "Boş akış");
        e.setNotifyEmail(false);
        ScriptedMonitor f = scripted(6, "Düşen gelecek");
        ScriptedMonitor g = scripted(7, "Düşen kapalı");
        List<ScriptedMonitor> all = List.of(a, b, c, d, e, f, g);
        lenient().when(scriptedRepo.findByActiveTrue()).thenReturn(all);
        serve(scriptedRepo, all, ScriptedMonitor::getId);
        lenient().when(scriptedRepo.findCheckDispatchRows()).thenReturn(idRows(all, ScriptedMonitor::getId, ScriptedMonitor::getIntervalSeconds));
        String failedJson = "[{\"name\":\"durum 200\",\"passed\":true},{\"name\":\"gövde ok\",\"passed\":false}]";
        Function<ScriptedMonitor, ScriptedCheckerService.ScriptedResult> result = m -> switch (m.getId().intValue()) {
            case 1 -> k6("PASS", true, 500L, 3, 0, "[]", null, null);
            case 2 -> k6("PASS", true, 3000L, 3, 0, "[]", null, null);
            case 3 -> k6("FAIL", false, 800L, 1, 1, failedJson, "level=error msg=\"request timeout\"", "request timeout");
            case 4 -> k6(ScriptedCheckerService.STATUS_SKIPPED, false, null, null, null, null, null,
                    "k6 havuzu dolu — kontrol atlandı (sıra beklemesi aşıldı)");
            case 5 -> k6("NO_CHECKS", false, 300L, 0, 0, "[]", null, null);
            case 6 -> throw new IllegalStateException("k6 süreci çöktü");
            default -> k6("FAIL", false, 900L, 0, 2, failedJson, null, "HTTP 500");
        };
        lenient().when(scriptedChecker.submit(any(ScriptedMonitor.class))).thenAnswer(i -> {
            ScriptedMonitor m = i.getArgument(0);
            try {
                return CompletableFuture.completedFuture(result.apply(m));
            } catch (IllegalStateException ex) {
                return CompletableFuture.failedFuture(ex);
            }
        });
        lenient().when(scriptedChecker.run(any(ScriptedMonitor.class))).thenAnswer(i -> result.apply(i.getArgument(0)));
        return all;
    }
}
