package com.sitemonitor.it;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Çok pod test düzeneği (2026-10-21, çok pod planı P0) — AYNI veritabanını ve AYNI saati paylaşan birden çok "pod"u tek
 * JVM'de canlandırır. Her pod'un kendi bellek içi durumu, kendi sahip kimliği ({@link #owner(int)}) ve kendi olay
 * kaydedicisi vardır; {@code SchedulerClusterScheduleTest.pod(...)} kurulumunun paylaşılan hâli.
 *
 * <p><b>Ne kurar:</b>
 * <ul>
 *   <li>Taze bir H2 bellek içi veritabanı, PostgreSQL kipi ({@code MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE}; işlem
 *       dışı / autocommit — üretimdeki {@code JdbcTemplate} çağrıları gibi). İçinde {@code scheduler_lock}
 *       ({@link #SCHEDULER_LOCK_DDL}) ve {@code monitor_check_schedule} ({@link ClusterScheduleService#DDL}).</li>
 *   <li>Ortak, elle ilerletilen saat ({@link #clock()}, başlangıç {@link #T0}); pod'ların vade / kira kararları bunu
 *       okur ({@code SchedulerService.scheduleClock}).</li>
 *   <li>{@code SchedulerService}'in tüm kurucu bağımlılıkları için PAYLAŞILAN Mockito sahteleri ({@link #mock(Class)}):
 *       iki pod aynı depoyu / yoklayıcıyı görür — saplama bir kez yapılır. {@code JdbcTemplate} = ortak veritabanı,
 *       {@code ApplicationEventPublisher} = pod'a özel {@link RecordingEventPublisher}. {@link AppSettingsService}
 *       sahtesi her {@code getX(key, varsayılan)} çağrısında varsayılanı döner; {@code certCheckExecutor}
 *       ({@link ThreadPoolTaskExecutor}) işi çağıran iş parçacığında hemen koşturur.</li>
 * </ul>
 * Sahteler varsayılan cevaplıdır (saplama yok) — {@code MockitoExtension}'ın katı saplama denetimi düzeneği
 * etkilemez; testin kendi {@code when(...)} saplamaları her zamanki gibi denetlenir.
 *
 * <p><b>API özeti</b>
 * <ul>
 *   <li>{@link #create()} / {@link #create(long)} — yeni düzenek (her biri ayrı veritabanı); {@link #close()} siler.</li>
 *   <li>{@link #jdbc()}, {@link #dataSource()}, {@link #jdbcUrl()} — ortak veritabanı.</li>
 *   <li>{@link #clock()}, {@link #now()}, {@link #advance(long)}, {@link #clockSupplier()} — ortak saat.</li>
 *   <li>{@link #owner(int)} ({@code "pod1-00000001"}), {@link #host(int)} ({@code "pod1"}) — sahip kimlikleri;
 *       {@code host(n) + "-"} üretimdeki {@code HOSTNAME-<8 hex>} biçiminin öneki gibi davranır.</li>
 *   <li>{@link #pod(int)} — n. pod (ilk çağrıda kurulur, sonra aynısı): {@link Pod#scheduler()},
 *       {@link Pod#schedule()}, {@link Pod#events()}, {@link Pod#owner()}.</li>
 *   <li>{@link #newPod(String)} — aynı ada sahip ama BELLEĞİ BOŞ yeni bir örnek (yeniden başlatılmış pod; ezberlenmez).</li>
 *   <li>{@link #legacyPod()} — küme kaydı olmayan pod ({@code schedule() == null}; tek pod / eski davranış).</li>
 *   <li>{@link #schedule(String)} — yalnız {@code ClusterScheduleService} (kurulumsuz, hafif testler için).</li>
 *   <li>{@link #mock(Class)} — paylaşılan bağımlılık sahtesi; {@link #use(Class, Object)} — kendi örneğinle değiştir
 *       (yalnız SONRA kurulan pod'ları etkiler).</li>
 *   <li>{@link #lease(String)}, {@link #leaseOwner(String)}, {@link #schedulerLockHolder(String)} — DB'den okuma.</li>
 *   <li>{@link #race(Callable[])} — görevleri bir engelde aynı anda başlatır (iki pod aynı sahiplenmeye uzanır).</li>
 * </ul>
 *
 * <p><b>Örnek</b> (paket-özel {@code SchedulerService} yöntemlerini çağıran test {@code com.sitemonitor.service}
 * paketinde durur):
 * <pre>{@code
 * class MyMultiPodTest {
 *     MultiPodTestSupport mp = MultiPodTestSupport.create();
 *
 *     @AfterEach void close() { mp.close(); }
 *
 *     @Test void onlyTheLeaderProbes() {
 *         when(mp.mock(PortMonitorRepository.class).findByActiveTrue()).thenReturn(List.of(port));
 *         var a = mp.pod(1);
 *         var b = mp.pod(2);
 *         a.scheduler().runPortChecks();            // a kirayı alır ve yoklar
 *         b.scheduler().runPortChecks();            // takipçi: hiçbir şey yapmaz
 *         mp.advance(60_000);
 *         assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(MultiPodTestSupport.owner(1));
 *         assertThat(a.events().ofType(SweepLeadershipChangedEvent.class))
 *                 .extracting(SweepLeadershipChangedEvent::reason).containsExactly("GAINED");
 *         List<Boolean> won = mp.race(
 *                 () -> a.schedule().acquireLease("x", mp.now(), 1_000),
 *                 () -> b.schedule().acquireLease("x", mp.now(), 1_000));
 *         assertThat(won).containsExactlyInAnyOrder(true, false);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Sınırlar.</b> {@code scheduler_lock} satırını {@code SchedulerService} JVM başına SABİT örnek kimliğiyle
 * ({@code SchedulerService.instanceId()}) yazar ve kilit sürelerini DUVAR saatiyle hesaplar: aynı JVM'deki iki pod bu
 * tabloda AYNI sahiptir (biri tutarken diğerinin INSERT'i yine düşer, ama diğeri onun kilidini bırakabilir) ve
 * {@link #advance(long)} kilit süresini etkilemez. Kilit sahibine bağlı senaryolar için kilit servisinin kendi
 * sahip kimliğini kullan. Kurucu dışında enjekte edilen alanlar ({@code @Autowired(required = false)} yardımcılar,
 * {@code @Value} ayarları) kurulumda boş / 0 kalır — gerekiyorsa {@link #pod(int, Consumer)} ile ya da
 * {@code ReflectionTestUtils.setField} ile ver. Düzenek iş parçacığı güvenlidir; {@link #race} dışındaki yardımcılar
 * kendi iş parçacığı açmaz.
 */
public final class MultiPodTestSupport implements AutoCloseable {

    /** Varsayılan başlangıç anı: 2026-10-21 06:00 UTC (İstanbul 09:00). */
    public static final long T0 = Instant.parse("2026-10-21T06:00:00Z").toEpochMilli();

    /**
     * {@code scheduler_lock} — üretimde {@code TEXT} kolonlu ({@code SchedulerService.applySchemaPatches}); H2'de TEXT büyük
     * nesneye eşlenip birincil anahtar olamayabildiği için VARCHAR. Kilidin anlamı (INSERT tekilliği + {@code locked_until}
     * kıyası) aynı.
     */
    public static final String SCHEDULER_LOCK_DDL = "CREATE TABLE IF NOT EXISTS scheduler_lock("
            + "name VARCHAR(200) NOT NULL PRIMARY KEY, "
            + "locked_by VARCHAR(200) NOT NULL, "
            + "locked_until VARCHAR(40) NOT NULL)";

    /** {@link #race} görevlerinin engelde ve sonuçta en uzun bekleyişi (sn) — asılan test sonsuza dek beklemesin. */
    public static final int RACE_TIMEOUT_SECONDS = 30;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final String url;
    private final DriverManagerDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final AtomicLong clock;
    private final Map<Class<?>, Object> shared = new ConcurrentHashMap<>();
    private final Map<Integer, Pod> pods = new ConcurrentHashMap<>();

    private MultiPodTestSupport(long startMs) {
        this.url = "jdbc:h2:mem:multipodsupport" + SEQ.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        this.dataSource = new DriverManagerDataSource(url);
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = new AtomicLong(startMs);
        jdbc.execute(SCHEDULER_LOCK_DDL);
        jdbc.execute(ClusterScheduleService.DDL);
    }

    /** Yeni düzenek, saat {@link #T0}'da. */
    public static MultiPodTestSupport create() {
        return new MultiPodTestSupport(T0);
    }

    /** Yeni düzenek, saat verilen anda (epoch ms). */
    public static MultiPodTestSupport create(long startMs) {
        return new MultiPodTestSupport(startMs);
    }

    // ── Veritabanı ────────────────────────────────────────────────────────────────────────────────────────────

    /** Ortak veritabanı (her pod bunu kullanır). */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Ortak veritabanının veri kaynağı (ör. ikinci bir {@code JdbcTemplate} ya da kendi bağlantın için). */
    public DataSource dataSource() {
        return dataSource;
    }

    /** Ortak veritabanının JDBC adresi. */
    public String jdbcUrl() {
        return url;
    }

    // ── Saat ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Ortak saat (epoch ms) — doğrudan {@code set} / {@code addAndGet} de kullanılabilir. */
    public AtomicLong clock() {
        return clock;
    }

    /** Ortak saatin şimdiki değeri. */
    public long now() {
        return clock.get();
    }

    /** Ortak saati ilerletir; yeni değeri döner. */
    public long advance(long ms) {
        return clock.addAndGet(ms);
    }

    /** Ortak saati okuyan tedarikçi (bir servisin saat alanına vermek için). */
    public LongSupplier clockSupplier() {
        return clock::get;
    }

    // ── Kimlikler ─────────────────────────────────────────────────────────────────────────────────────────────

    /** n. pod'un sahip kimliği, ör. {@code owner(1) = "pod1-00000001"} (üretimdeki {@code HOSTNAME-<8 hex>} biçimi). */
    public static String owner(int n) {
        return host(n) + "-" + String.format("%08x", n);
    }

    /** n. pod'un "host" adı, ör. {@code "pod1"}; {@code host(n) + "-"} bu pod'un kimliklerinin önekidir. */
    public static String host(int n) {
        return "pod" + n;
    }

    // ── Paylaşılan bağımlılıklar ──────────────────────────────────────────────────────────────────────────────

    /**
     * Tüm pod'ların paylaştığı bağımlılık (ilk istekte sahte olarak oluşturulur). Saplama için:
     * {@code when(mp.mock(PortMonitorRepository.class).findByActiveTrue()).thenReturn(...)}.
     */
    @SuppressWarnings("unchecked")
    public <T> T mock(Class<T> type) {
        return (T) shared.computeIfAbsent(type, MultiPodTestSupport::createMock);
    }

    /** Paylaşılan bağımlılığı kendi örneğinle değiştirir — yalnız bundan SONRA kurulan pod'ları etkiler. */
    public <T> MultiPodTestSupport use(Class<T> type, T instance) {
        shared.put(type, instance);
        return this;
    }

    private static Object createMock(Class<?> type) {
        if (type == AppSettingsService.class) return Mockito.mock(type, Mockito.withSettings().defaultAnswer(FALLBACK_SETTINGS));
        if (type == ThreadPoolTaskExecutor.class) return Mockito.mock(type, Mockito.withSettings().defaultAnswer(INLINE_EXECUTOR));
        return Mockito.mock(type);
    }

    /** {@code getString/getInt/getBoolean/getDouble(key, varsayılan)} → varsayılan (ayar yok = üretim varsayılanı). */
    private static final Answer<Object> FALLBACK_SETTINGS = inv -> {
        Object[] args = inv.getArguments();
        if (inv.getMethod().getName().startsWith("get") && args.length == 2 && args[0] instanceof String) return args[1];
        return Mockito.RETURNS_DEFAULTS.answer(inv);
    };

    /** {@code execute(Runnable)} işi hemen, çağıran iş parçacığında koşturur (taramalar belirlenimci olsun). */
    private static final Answer<Object> INLINE_EXECUTOR = (InvocationOnMock inv) -> {
        if ("execute".equals(inv.getMethod().getName()) && inv.getArguments().length == 1
                && inv.getArgument(0) instanceof Runnable r) {
            r.run();
            return null;
        }
        return Mockito.RETURNS_DEFAULTS.answer(inv);
    };

    // ── Pod'lar ───────────────────────────────────────────────────────────────────────────────────────────────

    /** n. pod (sahip {@link #owner(int)}); ilk çağrıda kurulur, sonraki çağrılar AYNI örneği döner. */
    public Pod pod(int n) {
        return pods.computeIfAbsent(n, i -> build(i, owner(i), null));
    }

    /** n. pod; yalnız ilk kurulumda {@code customizer} {@code SchedulerService} örneğine uygulanır (ör. ayar alanları). */
    public Pod pod(int n, Consumer<SchedulerService> customizer) {
        return pods.computeIfAbsent(n, i -> build(i, owner(i), customizer));
    }

    /** Verilen sahip kimliğiyle YENİ bir pod — belleği boş (yeniden başlatılmış pod / yuvarlanan dağıtım); ezberlenmez. */
    public Pod newPod(String owner) {
        return build(-1, owner, null);
    }

    /**
     * Küme kaydı OLMAYAN pod ({@link Pod#schedule()} ve {@link Pod#owner()} null) — bean'siz eski davranış: her pod lider,
     * vade yalnız pod belleğinde. Ezberlenmez.
     */
    public Pod legacyPod() {
        RecordingEventPublisher events = new RecordingEventPublisher();
        return new Pod(-1, null, null, newScheduler(null, events), events);
    }

    /** Yalnız küme zamanlama kaydı (SchedulerService kurmadan): ortak veritabanında verilen sahip adına. */
    public ClusterScheduleService schedule(String owner) {
        return new ClusterScheduleService(jdbc, owner);
    }

    private Pod build(int index, String owner, Consumer<SchedulerService> customizer) {
        ClusterScheduleService store = new ClusterScheduleService(jdbc, owner);
        RecordingEventPublisher events = new RecordingEventPublisher();
        SchedulerService scheduler = newScheduler(store, events);
        if (customizer != null) customizer.accept(scheduler);
        return new Pod(index, owner, store, scheduler, events);
    }

    /**
     * {@code SchedulerService}'i kurucusuyla kurar: her parametre için paylaşılan bağımlılık ({@link #mock(Class)}),
     * {@code JdbcTemplate} = ortak veritabanı, {@code ApplicationEventPublisher} = pod'un kaydedicisi. Kurucu
     * değişse de (G2: değişmez) düzenek elle güncellenmez.
     */
    private SchedulerService newScheduler(ClusterScheduleService store, RecordingEventPublisher events) {
        Constructor<?> ctor = Arrays.stream(SchedulerService.class.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == JdbcTemplate.class) args[i] = jdbc;
            else if (t == ApplicationEventPublisher.class) args[i] = events;
            else if (t.isPrimitive() || t == String.class) {
                throw new IllegalStateException("MultiPodTestSupport: SchedulerService kurucusunda sahtelenemeyen parametre "
                        + t.getName() + " — düzeneği güncelle");
            } else args[i] = mock(t);
        }
        SchedulerService s;
        try {
            ctor.setAccessible(true);
            s = (SchedulerService) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MultiPodTestSupport: SchedulerService kurulamadı", e);
        }
        ReflectionTestUtils.setField(s, "certCheckExecutor", mock(ThreadPoolTaskExecutor.class));
        ReflectionTestUtils.setField(s, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(s, "scheduleClock", clockSupplier());
        if (store != null) ReflectionTestUtils.setField(s, "clusterSchedule", store);
        return s;
    }

    // ── Okumalar ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Kiranın anlık durumu ({@code lease:<name>} satırı; yoksa null). Ör. {@code lease("sweep-leader")}. */
    public ClusterScheduleService.Lease lease(String name) {
        return new ClusterScheduleService(jdbc, "multipod-reader").lease(name);
    }

    /** Kiranın sahibi (satır yoksa null) — süresi dolmuş olsa da satırdaki sahip. */
    public String leaseOwner(String name) {
        ClusterScheduleService.Lease l = lease(name);
        return l == null ? null : l.owner();
    }

    /** {@code scheduler_lock} satırının sahibi ({@code locked_by}); kilit yoksa null. */
    public String schedulerLockHolder(String lockName) {
        List<String> rows = jdbc.queryForList("SELECT locked_by FROM scheduler_lock WHERE name = ?", String.class, lockName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Yarış ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Görevleri ayrı iş parçacıklarında bir engelde AYNI ANDA başlatır ve sonuçlarını görev sırasıyla döner (iki pod aynı
     * satırı sahiplenmeye uzanır). Her bekleyiş {@link #RACE_TIMEOUT_SECONDS} ile sınırlı; bir görev fırlatırsa
     * {@link IllegalStateException} (nedeni görevin istisnası) fırlatılır.
     */
    @SafeVarargs
    public final <T> List<T> race(Callable<? extends T>... tasks) {
        return race(Arrays.asList(tasks));
    }

    /** {@link #race(Callable[])}'ın liste hâli. */
    public <T> List<T> race(List<? extends Callable<? extends T>> tasks) {
        int n = tasks.size();
        if (n == 0) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "multipod-race");
            t.setDaemon(true);
            return t;
        });
        try {
            CyclicBarrier start = new CyclicBarrier(n);
            List<Future<? extends T>> futures = new ArrayList<>(n);
            for (Callable<? extends T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                try {
                    results.add(futures.get(i).get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new IllegalStateException("yarış görevi " + i + " fırlattı", e.getCause());
                } catch (Exception e) {
                    throw new IllegalStateException("yarış görevi " + i + " tamamlanmadı", e);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Bellek içi veritabanını kapatır (sonraki erişim hata verir). */
    @Override
    public void close() {
        try {
            jdbc.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // zaten kapalı
        }
    }

    // ── Türler ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Bir pod: kendi {@code SchedulerService} (kendi belleği), kendi kira / zamanlama kaydı ({@code ClusterScheduleService},
     * sahibi {@link #owner()}) ve {@code SchedulerService}'in yayınladığı olayların kaydı.
     *
     * @param index {@link MultiPodTestSupport#pod(int)} numarası ({@link MultiPodTestSupport#newPod(String)} ve
     *              {@link MultiPodTestSupport#legacyPod()} için -1)
     */
    public record Pod(int index, String owner, ClusterScheduleService schedule, SchedulerService scheduler,
                      RecordingEventPublisher events) {
    }

    /**
     * Olayları kaydeden {@link ApplicationEventPublisher}. Yayın eşzamanlıdır: önce kaydedilir, sonra dinleyiciler
     * ({@link #listen(Class, Consumer)}) yayıncının iş parçacığında çağrılır — Spring'in varsayılan eşzamanlı dağıtımı gibi
     * (dinleyici istisnası yayıncıya gider).
     */
    public static final class RecordingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new CopyOnWriteArrayList<>();
        private final List<Consumer<Object>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
            for (Consumer<Object> l : listeners) l.accept(event);
        }

        /** Yayınlanan tüm olaylar (yayın sırasıyla; anlık kopya). */
        public List<Object> all() {
            return List.copyOf(events);
        }

        /** Verilen türdeki olaylar (yayın sırasıyla). */
        public <E> List<E> ofType(Class<E> type) {
            List<E> out = new ArrayList<>();
            for (Object e : events) if (type.isInstance(e)) out.add(type.cast(e));
            return out;
        }

        /** Kaydı temizler (dinleyiciler kalır). */
        public void clear() {
            events.clear();
        }

        /** Verilen türdeki olaylar için dinleyici ekler (ör. bir servisin {@code @EventListener} yöntemini bağlamak). */
        public <E> void listen(Class<E> type, Consumer<? super E> listener) {
            listeners.add(e -> {
                if (type.isInstance(e)) listener.accept(type.cast(e));
            });
        }
    }
}
