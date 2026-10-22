package com.sitemonitor.service.cache;

import com.sitemonitor.config.ShutdownDeadline;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Pod'lar arası önbellek boşaltma veri yolu (2026-10-22, çok pod R2-A, MP-30; kullanıcı isteği: "2 pod şimdi, N sonra").
 *
 * <p><b>Sorun.</b> Her önbellek (Caffeine {@code cert-latest} 300 sn, {@code card-extras} 60 sn, nöbetçi 30 sn, erişim
 * paylaşımı 30 sn, bakım pencereleri 30 sn …) yalnız yazmayı işleyen pod'da boşalıyordu: öteki pod TTL dolana kadar eski
 * veriyi sunuyor — kart kırmızı kalıyor, geri alınan paylaşım 30 sn daha işliyor, alarm ESKİ nöbetçiye gidiyordu.
 *
 * <p><b>Nasıl.</b> Tablo {@code cache_bus(name PK, generation, updated_at)} ad başına bir sayaç tutar; {@code _global}
 * satırı her yayında birlikte artar (istemcinin gördüğü tek sayı).
 * <ul>
 *   <li>{@link #bump(String...)}: işlem içindeyse işlem TAMAMLANDIKTAN sonra (geri alınan işlem yayın yapmaz). İstek iş
 *       parçacığında adlar istek boyunca biriktirilir; yanıt gövdesi yazılmadan önce ({@code CacheBusResponseAdvice},
 *       {@link #flushRequest()}) veri yolu İŞ PARÇACIĞINA devredilir ve en çok {@value #REQUEST_FLUSH_WAIT_MS} ms kuşak
 *       beklenir — gelirse yanıt {@value #RESPONSE_HEADER} taşır, gelmezse başlıksız gider (istemci bugünkü gibi davranır),
 *       yayın yine yapılır. İstek iş parçacığı veritabanına HİÇ dokunmaz (havuz beklemesi / kilit isteği tıkamaz). 2 sn'den
 *       uzun bekleyen istek grubu (uzun toplu istek) iş parçacığına devredilir; istek bittikten sonra gelen ad doğrudan
 *       kuyruğa gider. Arka plan iş parçacıklarında yayın 50 ms birleştirmeyle yapılır. Asla atmaz; başarısız yayın geri
 *       çekilerek yeniden denenir (sınırlı).</li>
 *   <li>Kendi daemon iş parçacığı ({@code cache-bus}, paylaşılan zamanlayıcı havuzu DEĞİL) {@code site.monitor.cache-bus.poll-ms}
 *       (2 sn) aralıkla {@code SELECT name, generation} okur; kuşağı DEĞİŞEN (kıyas {@code !=} — elle silinen / yeniden
 *       kurulan satır da görülür) her ad için kayıtlı yerel boşaltıcıyı yeniden yayın YAPMADAN çalıştırır. Boşaltıcılar UCUZ
 *       olmalı (bayat işaretle / temizle); biri hata verirse o ad uygulanmamış sayılır ve sonraki yoklamada yeniden denenir.</li>
 *   <li>Yazdığını oku: istemci gördüğü en yüksek kuşağı GET'lerde {@value #REQUEST_HEADER} ile gönderir; uygulanmış kuşağı
 *       daha düşük olan pod yanıtlamadan önce TEK zorunlu yoklama ister ve en çok {@code site.monitor.cache-bus.min-gen-wait-ms}
 *       (500 ms) bekler ({@link #awaitGeneration(long)}, {@code CacheBusRequestFilter}). Başlık yoksa bugünkü davranış.</li>
 *   <li>Kendi yayınını yeniden uygulamaz: yayının döndürdüğü kuşaklar art arda geliyorsa (araya başka pod girmediyse) "görüldü"
 *       ilerletilir — yazan pod kendi önbelleğini ikinci kez boşaltmaz, ısıtıcı iki kez koşmaz.</li>
 *   <li>Kilit: her yayın önce {@code _global} satırını kilitler (tek sıra — iki pod kilitlenmez); PostgreSQL'de
 *       {@code SET LOCAL lock_timeout} {@value #LOCK_TIMEOUT_MS} ms — o satırı tutup ölen bir oturum yayınları en çok bu kadar
 *       bekletir, sonra geri çekilip yeniden denenir.</li>
 * </ul>
 *
 * <p><b>Geriye uyum.</b> Tablo yoksa ({@code BadSqlGrammarException} — yaması düşmüş veritabanı) yol sessizce devre dışıdır:
 * önbellekler TTL'le çalışır (bugünkü davranış); tablo {@value #TABLE_RECHECK_MS} ms'de bir yeniden denenir. 20.165.1 pod'u ne
 * yayın yapar ne dinler — karışık dağıtımda o pod TTL'le kalır. Tek pod: kendi yayınları kendi önbelleğini boşaltmaz, istek
 * başlığı beklemeye yol açmaz — davranış aynı.
 */
@Slf4j
@Service
public class CacheBus implements ApplicationContextAware {

    public static final String TABLE = "cache_bus";
    /** Ham DDL — açılış yaması ({@link CacheBusSchemaPatch}) ve H2 testleri AYNI tanımı kullanır. */
    public static final String DDL = "CREATE TABLE IF NOT EXISTS cache_bus ("
            + "name VARCHAR(60) NOT NULL PRIMARY KEY, "
            + "generation BIGINT NOT NULL, "
            + "updated_at VARCHAR(30))";
    /** Her yayında birlikte artan satır (istemcinin taşıdığı tek sayı). Ad kalıcıdır. */
    public static final String GLOBAL = "_global";
    /** Yanıt başlığı: bu istekte yapılan yayının genel kuşağı. */
    public static final String RESPONSE_HEADER = "X-Cache-Gen";
    /** İstek başlığı: istemcinin gördüğü en yüksek genel kuşak (GET / HEAD). */
    public static final String REQUEST_HEADER = "X-Cache-Min-Gen";
    /** Ad kuralı — kolon 60 karakter. */
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_-]{0,59}$");

    static final long DEFAULT_POLL_MS = 2_000L;
    static final long DEFAULT_MIN_GEN_WAIT_MS = 500L;
    /** Zorunlu yoklamalar arasındaki en kısa aralık — sahte büyük kuşaklı istekler veritabanını sürekli yoklatamasın. */
    static final long FORCED_POLL_GAP_MS = 100L;
    /** Arka plan yayınlarının birleştirme gecikmesi (ör. evictAllCaches'in 6 önbelleği TEK güncelleme). */
    static final long ASYNC_COALESCE_MS = 50L;
    /** İstek grubu bundan uzun beklerse (uzun toplu istek) adlar iş parçacığına devredilir. */
    static final long REQUEST_HANDOFF_MS = 2_000L;
    /** Yanıt yazılırken kuşak için en uzun bekleme — aşılırsa yanıt başlıksız gider (yayın yine yapılır). */
    static final long REQUEST_FLUSH_WAIT_MS = 250L;
    /** PostgreSQL {@code lock_timeout} (yayın işleminin içinde, SET LOCAL). */
    static final long LOCK_TIMEOUT_MS = 500L;
    /** Yoklama / yayın sorgularının üst süresi (sn) — asılan veritabanı kilidi sonsuza dek tutmasın. */
    static final int QUERY_TIMEOUT_SECONDS = 3;
    /** Kendi yayınını kaydetmek için kilit beklemesi — aşılırsa kayıt atlanır (zararsız: bir fazla boşaltma). */
    static final long OWN_RECORD_LOCK_MS = 50L;
    /** Başarısız arka plan yayınının en çok deneme sayısı (geri çekilme 1 → 30 sn); aşılırsa ad bırakılır (TTL'e düşer). */
    static final int MAX_ASYNC_ATTEMPTS = 12;
    static final long MAX_BACKOFF_MS = 30_000L;
    /** İlk başarılı yoklamada: pod'un kuruluşundan bu kadar önce ya da sonra yayınlanmış adlar boşaltılır (saat kayması payı). */
    static final long BASELINE_MARGIN_MS = 30_000L;
    /** Bir adın bekleyen kendi-yayın kümesinin üst sınırı. */
    static final int MAX_OWN_PENDING = 64;
    /** Tablo yokken veritabanına en sık bu aralıkla yeniden bakılır. */
    static final long TABLE_RECHECK_MS = 30_000L;
    /** Yok etmedeki son yayının tavanı (ortak kapanış süresi {@link ShutdownDeadline#waitMs} ile kısalır). */
    public static final long SHUTDOWN_FLUSH_CAP_MS = 2_000L;
    /** Açık istek grubu bundan eskiyse (tamamlanma geri çağrısı hiç gelmedi) iş parçacığı onu bırakır (sızıntı olmasın). */
    static final long BATCH_MAX_AGE_MS = 10 * 60_000L;
    /** Açık istek grubu varken iş parçacığının en uzun uykusu (devir denetimi). */
    static final long BATCH_CHECK_MS = 500L;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final @Nullable TransactionTemplate tx;
    private final String batchAttr = CacheBus.class.getName() + ".batch#" + Integer.toHexString(System.identityHashCode(this));

    @Value("${site.monitor.cache-bus.enabled:true}")
    volatile boolean enabled = true;
    @Value("${site.monitor.cache-bus.poll-ms:2000}")
    long pollMs = DEFAULT_POLL_MS;
    @Value("${site.monitor.cache-bus.min-gen-wait-ms:500}")
    long minGenWaitMs = DEFAULT_MIN_GEN_WAIT_MS;

    /** Saat (updated_at ve ilk yoklama penceresi) — testte ortak saat. */
    LongSupplier clock = System::currentTimeMillis;
    /** Kuruluş anı: ilk yoklamada bundan sonra (− pay) yayınlanmış adlar boşaltılır. */
    volatile long createdAtMs = System.currentTimeMillis();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> evictors = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Function<String, Runnable>> resolvers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Set<String>>> remoteListeners = new CopyOnWriteArrayList<>();

    /** Görülen kuşaklar + kendi bekleyen yayınlar + taban — {@link #state} altında. */
    private final ReentrantLock state = new ReentrantLock();
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, TreeSet<Long>> ownPending = new HashMap<>();
    private boolean baselined;
    /** Bu pod'un UYGULADIĞI genel kuşak (-1 = henüz yoklanmadı). */
    private volatile long appliedGlobal = -1;
    /** Varlığı bilinen satırlar (eklemeyi atla). */
    private final Set<String> knownRows = ConcurrentHashMap.newKeySet();
    /** Tamamlanmamış istek grupları — iş parçacığı 2 sn'yi aşanları devralır. */
    private final Set<RequestBatch> openBatches = ConcurrentHashMap.newKeySet();

    /** İş parçacığı eşgüdümü — {@link #signal} altında. */
    private final Object signal = new Object();
    private long pollsStarted;
    private long pollsCompleted;
    private boolean pollRequested;
    private final Set<String> asyncPending = new LinkedHashSet<>();
    private int asyncAttempts;
    private long asyncNotBeforeNs;
    /** Yanıt bekleyen istek(ler)in kuşak sözü — bekleyen adlarla AYNI kilit altında alınır, onları yayınlayan tur tamamlar. */
    private CompletableFuture<Long> urgent;
    private final ReentrantLock pollLock = new ReentrantLock();

    private volatile Thread worker;
    /** ContextClosedEvent: yoklama / bekleme yok (yayınlar sürer). */
    private volatile boolean closing;
    /** Yok etme: iş parçacığı durur. */
    private volatile boolean stopped;
    private ApplicationContext context;

    /** Görünürlük (Sistem Sağlığı / testler). */
    private final AtomicLong bumps = new AtomicLong();
    private final AtomicLong bumpFailures = new AtomicLong();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong pollFailures = new AtomicLong();
    private final AtomicLong remoteEvictions = new AtomicLong();
    private final AtomicLong evictorFailures = new AtomicLong();
    private final AtomicLong forcedWaits = new AtomicLong();
    private final AtomicLong droppedNames = new AtomicLong();
    private final AtomicLong headerTimeouts = new AtomicLong();
    private volatile long lastPollOkAtMs;
    private volatile boolean tableMissing;
    private volatile long tableMissingAtMs;
    /** Son veritabanı işlemi (yayın / yoklama) başarısız mıydı — yok etmede son yayın denenmez. */
    private volatile boolean lastDbFailed;
    private volatile Boolean postgres;
    private volatile long lastWarnAtMs;

    @Autowired
    public CacheBus(JdbcTemplate jdbc, ObjectProvider<PlatformTransactionManager> txManager) {
        this(jdbc, txManager == null ? null : txManager.getIfUnique());
    }

    /** Test / el kurulumu: işlem yöneticisi yoksa yayın iki ayrı ifadeyle yapılır (kendi-yayın tanıma en iyi çaba). */
    public CacheBus(JdbcTemplate jdbc, @Nullable PlatformTransactionManager txManager) {
        this.jdbc = boundedCopy(jdbc);
        if (txManager != null) {
            TransactionTemplate t = new TransactionTemplate(txManager);
            t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            t.setTimeout(QUERY_TIMEOUT_SECONDS * 2);
            this.tx = t;
        } else {
            this.tx = null;
        }
    }

    private static JdbcTemplate boundedCopy(JdbcTemplate src) {
        if (src == null || src.getDataSource() == null) return src;
        JdbcTemplate t = new JdbcTemplate(src.getDataSource());
        t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return t;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.context = ctx;
    }

    // ── Kayıt ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Bir adın YEREL boşaltıcısı — başka pod yayın yaptığında yoklama kilitleri altında çalışır: UCUZ olmalı (alanı boşalt /
     * bayat işaretle; veritabanı okuması YOK — gerekirse sonraki okumada tembel tazele) ve asla yeniden yayın yapmamalı. Hata
     * verirse ad uygulanmamış sayılır, sonraki yoklamada yeniden denenir.
     */
    public void register(String name, Runnable localEvict) {
        if (!valid(name) || localEvict == null) return;
        evictors.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(localEvict);
    }

    /** Kayıtsız adlar için boşaltıcı çözücü (Spring önbellek yöneticisi: o pod'da oluşmuş her önbellek). null = yok. */
    public void addResolver(Function<String, Runnable> resolver) {
        if (resolver != null) resolvers.add(resolver);
    }

    /** Başka pod'un yayınıyla boşaltılan adlar (yoklama sonunda, kilitler bırakıldıktan sonra). */
    public void onRemoteEvict(Consumer<Set<String>> listener) {
        if (listener != null) remoteListeners.add(listener);
    }

    // ── Yayın ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Adların önbelleği değişti — öteki pod'lar da boşaltsın. İşlem içindeyse işlem tamamlandıktan sonra (geri alınırsa hiç),
     * istek iş parçacığındaysa yanıt yazılırken, değilse arka planda. Asla atmaz; çağıranın akışını bozmaz, veritabanına dokunmaz.
     */
    public void bump(String... names) {
        if (!enabled || names == null || names.length == 0) return;
        Set<String> valid = new LinkedHashSet<>();
        for (String n : names) if (valid(n) && !GLOBAL.equals(n)) valid.add(n);
        if (valid.isEmpty()) return;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                    if (s instanceof AfterCompletion ac && ac.bus == this) {
                        ac.add(valid);
                        return;
                    }
                }
                AfterCompletion ac = new AfterCompletion(this);
                ac.add(valid);
                TransactionSynchronizationManager.registerSynchronization(ac);
                return;
            }
        } catch (RuntimeException e) {
            log.debug("Önbellek yolu: işlem eşlemesi kurulamadı, hemen yayınlanıyor: {}", e.toString());
        }
        dispatch(valid);
    }

    /**
     * İşlem sonu: işlem {@code afterCommit} içinde başka bir eşleme yayın isterse (ör. {@code TechErrorReportService
     * .analyticsChanged}) yeni eşleme {@code afterCompletion}'da yine çağrılır — bu yüzden {@code afterCommit} değil.
     * Geri alınan işlem yayın yapmaz; sonucu bilinmeyen işlem (UNKNOWN) yapar (fazla boşaltma zararsız).
     */
    static final class AfterCompletion implements TransactionSynchronization {
        final CacheBus bus;
        private final Set<String> names = new LinkedHashSet<>();

        AfterCompletion(CacheBus bus) { this.bus = bus; }

        synchronized void add(Collection<String> n) { names.addAll(n); }

        @Override
        public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) return;
            Set<String> copy;
            synchronized (this) { copy = new LinkedHashSet<>(names); }
            bus.dispatch(copy);
        }
    }

    /**
     * İstek iş parçacığı → istek grubuna (yanıt yazılırken iş parçacığına devredilir); değilse arka plan kuyruğuna.
     * Veritabanına dokunmaz. Uzun süren istekte grup {@link #REQUEST_HANDOFF_MS}'den uzun beklediyse adlar hemen devredilir;
     * istek tamamlandıktan sonra gelen ad (süzgeç zincirin dışında boşalttı) doğrudan kuyruğa gider.
     */
    void dispatch(Set<String> names) {
        if (names == null || names.isEmpty() || !enabled) return;
        RequestBatch batch = currentBatch(true);
        if (batch != null) {
            RequestBatch.Added r = batch.add(names, System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(REQUEST_HANDOFF_MS));
            if (r == RequestBatch.Added.KEPT) return;
            if (r == RequestBatch.Added.HANDOFF) {
                Set<String> early = batch.drain();
                if (!early.isEmpty()) enqueueAsync(early);
                return;
            }
            // CLOSED: istek bitmiş — kuyruğa
        }
        enqueueAsync(names);
    }

    /** İsteğin yayın grubu — istek yoksa ya da artık etkin değilse null. */
    private @Nullable RequestBatch currentBatch(boolean create) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra == null) return null;
        try {
            Object o = ra.getAttribute(batchAttr, RequestAttributes.SCOPE_REQUEST);
            if (o instanceof RequestBatch b) return b;
            if (!create) return null;
            RequestBatch b = new RequestBatch(System.nanoTime());
            ra.setAttribute(batchAttr, b, RequestAttributes.SCOPE_REQUEST);
            // İstek tamamlandı (gövdesiz yanıt, akış): grup kapanır, kalan adlar ve sonradan gelenler kuyruğa
            ra.registerDestructionCallback(batchAttr, () -> completeBatch(b), RequestAttributes.SCOPE_REQUEST);
            openBatches.add(b);
            return b;
        } catch (IllegalStateException requestNotActive) {
            return null;
        }
    }

    void completeBatch(RequestBatch b) {
        openBatches.remove(b);
        Set<String> left = b.close();
        if (!left.isEmpty()) enqueueAsync(left);
    }

    /** Bir isteğin bekleyen yayın adları + bu istekte dönen en yüksek genel kuşak. */
    static final class RequestBatch {
        enum Added { KEPT, HANDOFF, CLOSED }

        private final Set<String> names = new LinkedHashSet<>();
        private final long createdNs;
        private long maxGen = -1;
        private long firstAddedNs;
        private boolean closed;

        RequestBatch(long createdNs) { this.createdNs = createdNs; }

        RequestBatch() { this(System.nanoTime()); }

        /** @return KEPT (grupta), HANDOFF (grup {@code handoffNs}'den uzun bekliyor — devret) ya da CLOSED (istek bitmiş) */
        synchronized Added add(Collection<String> n, long nowNs, long handoffNs) {
            if (closed) return Added.CLOSED;
            if (names.isEmpty()) firstAddedNs = nowNs;
            names.addAll(n);
            return nowNs - firstAddedNs > handoffNs ? Added.HANDOFF : Added.KEPT;
        }

        synchronized Set<String> drain() {
            Set<String> out = new LinkedHashSet<>(names);
            names.clear();
            return out;
        }

        /** Bekleyen adlar en az {@code ageNs} yaşındaysa boşaltır (iş parçacığının devir denetimi); değilse boş. */
        synchronized Set<String> drainIfOlderThan(long nowNs, long ageNs) {
            if (names.isEmpty() || nowNs - firstAddedNs <= ageNs) return Set.of();
            return drain();
        }

        synchronized Set<String> close() {
            closed = true;
            return drain();
        }

        synchronized boolean closed() { return closed; }

        long createdNs() { return createdNs; }

        synchronized void recordGen(long g) { if (g > maxGen) maxGen = g; }

        synchronized long maxGen() { return maxGen; }
    }

    /**
     * Bu isteğin bekleyen yayınlarını iş parçacığına devreder ve en çok {@value #REQUEST_FLUSH_WAIT_MS} ms yeni kuşağı bekler
     * (yanıt gövdesi yazılmadan önce). İstek iş parçacığı veritabanına dokunmaz. İş parçacığı yoksa (açılış öncesi) adlar
     * kuyrukta kalır. Asla atmaz.
     * @return bu istekte yayınlanan en yüksek genel kuşak; bilinmiyorsa -1 (yanıt başlıksız; yayın yine yapılır)
     */
    public long flushRequest() {
        if (!enabled) return -1;
        RequestBatch b = currentBatch(false);
        if (b == null) return -1;
        Set<String> names = b.drain();
        if (names.isEmpty()) return b.maxGen();
        Thread w = worker;
        if (w == null || !w.isAlive() || stopped) {
            enqueueAsync(names);
            return b.maxGen();
        }
        CompletableFuture<Long> f;
        synchronized (signal) {
            asyncPending.addAll(names);
            if (urgent == null) urgent = new CompletableFuture<>();
            f = urgent;
            if (asyncAttempts == 0) asyncNotBeforeNs = System.nanoTime();   // birleştirme beklemesi yok (geri çekilme korunur)
            signal.notifyAll();
        }
        try {
            Long g = f.get(REQUEST_FLUSH_WAIT_MS, TimeUnit.MILLISECONDS);
            if (g != null) b.recordGen(g);
        } catch (java.util.concurrent.TimeoutException e) {
            headerTimeouts.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Önbellek yolu: istek yayını beklenemedi: {}", e.toString());
        }
        return b.maxGen();
    }

    /** Arka plan kuyruğu — veri yolu iş parçacığı kısa birleştirmeden sonra yayınlar. */
    void enqueueAsync(Set<String> names) {
        if (names == null || names.isEmpty() || !enabled) return;
        synchronized (signal) {
            if (asyncPending.isEmpty() && asyncAttempts == 0) {
                asyncNotBeforeNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_COALESCE_MS);
            }
            asyncPending.addAll(names);
            signal.notifyAll();
        }
        if (stopped && log.isDebugEnabled()) log.debug("Önbellek yolu kapandı — yayın yapılmadı: {}", names);
    }

    /** Bekleyen arka plan yayınlarını hemen yapar (test). */
    public void flushPending() {
        flushAsync(true);
    }

    private void flushAsync(boolean ignoreBackoff) {
        Set<String> names;
        CompletableFuture<Long> promise;
        synchronized (signal) {
            if (asyncPending.isEmpty()) {
                if (urgent != null) { urgent.complete(null); urgent = null; }
                return;
            }
            if (!ignoreBackoff && System.nanoTime() - asyncNotBeforeNs < 0) return;
            names = new LinkedHashSet<>(asyncPending);
            asyncPending.clear();
            promise = urgent;
            urgent = null;
        }
        Long g = null;
        try {
            g = bumpNow(names);
        } finally {
            if (promise != null) promise.complete(g);
        }
        synchronized (signal) {
            if (g != null) {
                asyncAttempts = 0;
                return;
            }
            asyncAttempts++;
            if (asyncAttempts > MAX_ASYNC_ATTEMPTS) {
                droppedNames.addAndGet(names.size());
                warnRateLimited("Önbellek yolu: " + MAX_ASYNC_ATTEMPTS + " denemede yayınlanamadı, bırakıldı (öteki pod'lar TTL ile "
                        + "tazeler): " + names);
                asyncAttempts = 0;
                return;
            }
            asyncPending.addAll(names);
            long backoff = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(5, asyncAttempts - 1));
            asyncNotBeforeNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff);
        }
    }

    /** Tablo yok sayılıyor ve yeniden bakma zamanı gelmedi mi (veritabanına boşuna gidilmez). */
    private boolean skipBecauseTableMissing() {
        return tableMissing && clock.getAsLong() - tableMissingAtMs < TABLE_RECHECK_MS;
    }

    private void markTableMissing(Exception e) {
        if (!tableMissing) log.debug("Önbellek yolu tablosu yok (TTL ile devam; {} ms'de bir yeniden bakılır): {}",
                TABLE_RECHECK_MS, e.getMessage());
        tableMissing = true;
        tableMissingAtMs = clock.getAsLong();
    }

    /** Açılış yaması tabloyu kurdu / buldu — yeniden bakma beklenmez. */
    public void tableReady() {
        tableMissing = false;
    }

    /**
     * Yayını hemen yapar (veri yolu iş parçacığında; işlem DIŞINDA). Önce {@code _global} satırı kilitlenir — tüm yayınlar aynı
     * sırayla kilitler, iki pod kilitlenmez; PostgreSQL'de kilit beklemesi {@value #LOCK_TIMEOUT_MS} ms ile sınırlı. Ad satırı
     * eksikse (elle silinmiş) işlem geri alınır ve yeniden denenir.
     * @return yeni genel kuşak; başarısızsa null (asla atmaz)
     */
    @Nullable
    Long bumpNow(Set<String> names) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(names));
        sorted.remove(GLOBAL);
        if (sorted.isEmpty()) return null;
        if (skipBecauseTableMissing()) {
            bumpFailures.incrementAndGet();
            return null;
        }
        String now = ISO.format(Instant.ofEpochMilli(clock.getAsLong()));
        try {
            ensureRows(sorted);
            boolean pg = isPostgres();
            Map<String, Long> gens = tx != null ? tx.execute(status -> doBump(sorted, now, pg)) : doBump(sorted, now, false);
            if (gens == null) return null;
            bumps.incrementAndGet();
            tableMissing = false;
            lastDbFailed = false;
            recordOwn(gens);
            return gens.get(GLOBAL);
        } catch (BadSqlGrammarException e) {
            markTableMissing(e);
            bumpFailures.incrementAndGet();
            lastDbFailed = true;
            return null;
        } catch (RuntimeException e) {
            bumpFailures.incrementAndGet();
            lastDbFailed = true;
            warnRateLimited("Önbellek yolu yayını başarısız (" + names + "): " + e);
            return null;
        }
    }

    private Map<String, Long> doBump(List<String> names, String now, boolean setLockTimeout) {
        if (setLockTimeout) jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT_MS + "ms'");
        int g = jdbc.update("UPDATE cache_bus SET generation = generation + 1, updated_at = ? WHERE name = ?", now, GLOBAL);
        if (g == 0) {
            knownRows.remove(GLOBAL);
            throw new IllegalStateException("cache_bus genel satırı yok");
        }
        Object[] args = new Object[names.size() + 1];
        args[0] = now;
        for (int i = 0; i < names.size(); i++) args[i + 1] = names.get(i);
        int n = jdbc.update("UPDATE cache_bus SET generation = generation + 1, updated_at = ? WHERE name IN (" + marks(names.size()) + ")",
                args);
        if (n < names.size()) {
            knownRows.removeAll(names);
            throw new IllegalStateException("cache_bus satırı eksik (" + n + "/" + names.size() + ")");
        }
        List<String> all = new ArrayList<>(names);
        all.add(GLOBAL);
        Map<String, Long> out = new HashMap<>();
        jdbc.query("SELECT name, generation FROM cache_bus WHERE name IN (" + marks(all.size()) + ")",
                rs -> { out.put(rs.getString(1), rs.getLong(2)); }, all.toArray());
        return out;
    }

    /** PostgreSQL mi (SET LOCAL lock_timeout yalnız orada) — bilinen yanıt saklanır, okunamazsa bu tur "hayır". */
    private boolean isPostgres() {
        Boolean p = postgres;
        if (p != null) return p;
        try {
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            p = product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
            postgres = p;
            return p;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Eksik satırları kuşak 0 ile ekler (işlem dışı; başka pod aynı anda eklediyse yinelenen anahtar = zaten var). */
    private void ensureRows(List<String> names) {
        List<String> want = new ArrayList<>();
        if (!knownRows.contains(GLOBAL)) want.add(GLOBAL);
        for (String n : names) if (!knownRows.contains(n)) want.add(n);
        if (want.isEmpty()) return;
        Set<String> present = new HashSet<>(jdbc.queryForList(
                "SELECT name FROM cache_bus WHERE name IN (" + marks(want.size()) + ")", String.class, want.toArray()));
        for (String n : want) {
            if (!present.contains(n)) {
                try {
                    jdbc.update("INSERT INTO cache_bus (name, generation, updated_at) VALUES (?, 0, NULL)", n);
                } catch (DataIntegrityViolationException alreadyThere) {
                    // başka pod aynı anda ekledi
                }
            }
            knownRows.add(n);
        }
    }

    /** Kendi yayınımızın kuşaklarını "görüldü"ye işler — yalnız art arda geliyorsa (araya başka pod girmediyse). */
    private void recordOwn(Map<String, Long> gens) {
        boolean locked = false;
        try {
            locked = state.tryLock(OWN_RECORD_LOCK_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) return;   // zararsız: sonraki yoklama bu adları bir kez fazla boşaltır
        try {
            if (!baselined) return;
            for (Map.Entry<String, Long> e : gens.entrySet()) {
                String name = e.getKey();
                TreeSet<Long> pend = ownPending.computeIfAbsent(name, k -> new TreeSet<>());
                pend.add(e.getValue());
                long cur = seen.getOrDefault(name, 0L);
                long start = cur;
                while (pend.remove(cur + 1)) cur++;
                if (cur != start) seen.put(name, cur);
                pend.headSet(cur, true).clear();
                if (pend.size() > MAX_OWN_PENDING) pend.clear();
            }
            appliedGlobal = seen.getOrDefault(GLOBAL, 0L);
        } finally {
            state.unlock();
        }
    }

    // ── Yoklama ──────────────────────────────────────────────────────────────────────────────────────────────

    private record Row(String name, long generation, String updatedAt) { }

    /** Bir yoklama turu (iş parçacığı ve testler). @return başarılı mı */
    public boolean pollNow() {
        pollLock.lock();
        try {
            return pollLocked();
        } finally {
            pollLock.unlock();
        }
    }

    private boolean pollLocked() {
        if (!enabled) return false;
        if (skipBecauseTableMissing()) {
            pollFailures.incrementAndGet();
            return false;
        }
        Set<String> changed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        state.lock();
        try {
            List<Row> rows;
            try {
                rows = jdbc.query("SELECT name, generation, updated_at FROM cache_bus",
                        (rs, i) -> new Row(rs.getString(1), rs.getLong(2), rs.getString(3)));
            } catch (BadSqlGrammarException e) {
                markTableMissing(e);
                pollFailures.incrementAndGet();
                lastDbFailed = true;
                return false;
            } catch (RuntimeException e) {
                pollFailures.incrementAndGet();
                lastDbFailed = true;
                warnRateLimited("Önbellek yolu yoklanamadı (öteki pod'ların boşaltmaları TTL ile gelir): " + e);
                return false;
            }
            tableMissing = false;
            lastDbFailed = false;
            Map<String, Row> byName = new LinkedHashMap<>();
            for (Row r : rows) if (r.name() != null) byName.put(r.name(), r);
            Map<String, Long> before = new HashMap<>(seen);
            boolean wasBaseline = !baselined;
            if (!baselined) {
                String since = ISO.format(Instant.ofEpochMilli(createdAtMs - BASELINE_MARGIN_MS));
                for (Row r : byName.values()) {
                    seen.put(r.name(), r.generation());
                    if (r.generation() > 0 && r.updatedAt() != null && r.updatedAt().compareTo(since) >= 0) changed.add(r.name());
                }
                baselined = true;
            } else {
                for (Row r : byName.values()) {
                    Long prev = seen.put(r.name(), r.generation());
                    if (prev == null ? r.generation() != 0 : prev.longValue() != r.generation()) changed.add(r.name());
                    TreeSet<Long> pend = ownPending.get(r.name());
                    if (pend != null) pend.headSet(r.generation(), true).clear();
                }
                for (Iterator<String> it = seen.keySet().iterator(); it.hasNext(); ) {
                    String n = it.next();
                    if (!byName.containsKey(n)) {   // satır silindi (elle / geri yükleme): güvenli taraf — boşalt
                        changed.add(n);
                        it.remove();
                        ownPending.remove(n);
                    }
                }
            }
            changed.remove(GLOBAL);
            // Boşaltıcılar kilit İÇİNDE ve uygulanan kuşak yayınlanmadan ÖNCE: bekleyen istek boşaltma bitmeden sunulmasın
            for (String n : changed) if (!evictLocal(n)) failed.add(n);
            if (!failed.isEmpty()) {
                // Başarısız ad uygulanmamış sayılır: görülen kuşağı geri alınır (sonraki yoklama yeniden dener), genel kuşak da
                // ilerlemez (bekleyen istek sunulmadan önce yeniden yoklatır)
                for (String n : failed) {
                    Long prev = before.get(n);
                    if (prev == null) seen.remove(n); else seen.put(n, prev);
                }
                Long prevGlobal = before.get(GLOBAL);
                if (prevGlobal == null) seen.remove(GLOBAL); else seen.put(GLOBAL, prevGlobal);
                if (wasBaseline) baselined = false;   // taban turu tekrarlanır (yeni adlar yeniden değerlendirilir)
            }
            appliedGlobal = baselined ? seen.getOrDefault(GLOBAL, 0L) : -1;
            polls.incrementAndGet();
            lastPollOkAtMs = clock.getAsLong();
        } finally {
            state.unlock();
        }
        changed.removeAll(failed);
        if (!changed.isEmpty()) {
            remoteEvictions.addAndGet(changed.size());
            Set<String> view = Set.copyOf(changed);
            for (Consumer<Set<String>> l : remoteListeners) {
                try {
                    l.accept(view);
                } catch (RuntimeException e) {
                    log.warn("Önbellek yolu dinleyicisi hata verdi: {}", e.toString());
                }
            }
        }
        return true;
    }

    /** @return bütün boşaltıcılar başarılı mı */
    private boolean evictLocal(String name) {
        List<Runnable> list = new ArrayList<>(evictors.getOrDefault(name, new CopyOnWriteArrayList<>()));
        for (Function<String, Runnable> r : resolvers) {
            try {
                Runnable run = r.apply(name);
                if (run != null) list.add(run);
            } catch (RuntimeException e) {
                log.debug("Önbellek yolu çözücüsü hata verdi ({}): {}", name, e.toString());
            }
        }
        boolean ok = true;
        for (Runnable run : list) {
            try {
                run.run();
            } catch (RuntimeException e) {
                ok = false;
                evictorFailures.incrementAndGet();
                warnRateLimited("Önbellek yolu: yerel boşaltma başarısız (" + name + ", sonraki yoklamada yeniden): " + e);
            }
        }
        return ok;
    }

    /**
     * Yazdığını oku: istemcinin gördüğü genel kuşak bu pod'da uygulanmadıysa TEK yoklama ister (başka bir yoklama sürüyorsa
     * isteğin GELİŞİNDEN SONRA başlayan yoklamayı bekler) ve en çok {@code min-gen-wait-ms} bekler. Süre dolarsa istek yine
     * sunulur (bugünkü davranış). İş parçacığı yoksa (açılış öncesi) ya da kapanış başladıysa beklemez — istek iş parçacığı
     * kendisi yoklamaz. Asla atmaz.
     * @return kuşak uygulandı mı
     */
    public boolean awaitGeneration(long min) {
        if (!enabled || min <= 0) return true;
        if (appliedGlobal >= min) return true;
        Thread w = worker;
        if (closing || stopped || w == null || !w.isAlive()) return false;
        forcedWaits.incrementAndGet();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, minGenWaitMs));
        try {
            synchronized (signal) {
                long ticket = pollsStarted + 1;
                pollRequested = true;
                signal.notifyAll();
                while (pollsCompleted < ticket && appliedGlobal < min) {
                    long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (leftMs <= 0) break;
                    signal.wait(Math.max(1, leftMs));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return appliedGlobal >= min;
    }

    // ── İş parçacığı ─────────────────────────────────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        start();
    }

    /** Yoklama iş parçacığını başlatır (idempotent; kapalıysa / durdurulduysa başlamaz). */
    public synchronized void start() {
        if (!enabled || stopped || closing || (worker != null && worker.isAlive())) return;
        Thread t = new Thread(this::runLoop, "cache-bus");
        t.setDaemon(true);
        worker = t;
        t.start();
        log.info("Önbellek yolu çalışıyor (yoklama {} ms, yazdığını-oku beklemesi en çok {} ms)", pollMs, minGenWaitMs);
    }

    private void runLoop() {
        long pollNs = TimeUnit.MILLISECONDS.toNanos(Math.max(100, pollMs));
        long gapNs = TimeUnit.MILLISECONDS.toNanos(FORCED_POLL_GAP_MS);
        long nextPollNs = System.nanoTime();
        long lastPollNs = System.nanoTime() - gapNs;
        while (!stopped) {
            try {
                long now = System.nanoTime();
                boolean forced;
                synchronized (signal) { forced = pollRequested; }
                if (!closing && ((forced && now - lastPollNs >= gapNs) || now - nextPollNs >= 0)) {
                    long ticket;
                    synchronized (signal) {
                        pollRequested = false;
                        ticket = ++pollsStarted;
                    }
                    lastPollNs = System.nanoTime();
                    try {
                        pollNow();
                    } finally {
                        synchronized (signal) {
                            pollsCompleted = ticket;
                            signal.notifyAll();
                        }
                    }
                    nextPollNs = System.nanoTime() + pollNs;
                }
                handOffAgedBatches(System.nanoTime());
                // Önce yoklama, sonra yayın: açılışın ilk turunda taban kendi yayınımızdan ÖNCE alınır
                flushAsync(false);
                synchronized (signal) {
                    if (stopped) break;
                    long t = System.nanoTime();
                    long waitNs = closing ? TimeUnit.MILLISECONDS.toNanos(BATCH_CHECK_MS) : nextPollNs - t;
                    if (pollRequested && !closing) waitNs = Math.min(waitNs, gapNs - (t - lastPollNs));
                    if (!asyncPending.isEmpty()) waitNs = Math.min(waitNs, asyncNotBeforeNs - t);
                    if (!openBatches.isEmpty()) waitNs = Math.min(waitNs, TimeUnit.MILLISECONDS.toNanos(BATCH_CHECK_MS));
                    if (waitNs > 0) signal.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(waitNs)));
                }
            } catch (InterruptedException e) {
                if (stopped) break;
            } catch (Throwable t) {
                warnRateLimited("Önbellek yolu döngüsü hata verdi: " + t);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    if (stopped) break;
                }
            }
        }
    }

    /** Uzun süren isteklerin 2 sn'yi aşan adlarını kuyruğa alır; tamamlanmayan (geri çağrısı gelmeyen) eski grupları bırakır. */
    void handOffAgedBatches(long nowNs) {
        if (openBatches.isEmpty()) return;
        long handoff = TimeUnit.MILLISECONDS.toNanos(REQUEST_HANDOFF_MS);
        long maxAge = TimeUnit.MILLISECONDS.toNanos(BATCH_MAX_AGE_MS);
        for (RequestBatch b : openBatches) {
            if (b.closed()) { openBatches.remove(b); continue; }
            if (nowNs - b.createdNs() > maxAge) {   // tamamlanma geri çağrısı hiç gelmedi: sızıntı olmasın
                completeBatch(b);
                continue;
            }
            Set<String> aged = b.drainIfOlderThan(nowNs, handoff);
            if (!aged.isEmpty()) enqueueAsync(aged);
        }
    }

    /**
     * Kapanış başladı: yeni yoklama ve yazdığını-oku beklemesi YOK (CLAUDE.md "Things that bite": kapanışta yeni iş başlamaz);
     * iş parçacığı yalnız bekleyen yayınları göndermeyi sürdürür (biten işin boşaltması öteki pod'lara ulaşsın). Bu dinleyici
     * hiç beklemez — ortak kapanış süresini tüketmez.
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        if (context != null && event != null && event.getApplicationContext() != context) return;
        closing = true;
        synchronized (signal) { signal.notifyAll(); }
    }

    /**
     * Yok etme: iş parçacığı durur; bekleyen yayın (kapanış dinleyicileri sırasında biten işler) TEK denemeyle, ortak kapanış
     * süresiyle sınırlı ({@link ShutdownDeadline#waitMs}, tavan {@value #SHUTDOWN_FLUSH_CAP_MS} ms) ayrı bir iş parçacığında
     * gönderilir. Tablo yoksa ya da son veritabanı işlemi başarısızsa denenmez (kapanışı boşuna bekletmesin). Veri kaynağı bu
     * fasulyeden SONRA kapanır (bağımlılık).
     */
    @PreDestroy
    void destroy() {
        stop(true);
    }

    void stop(boolean finalFlush) {
        closing = true;
        stopped = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        synchronized (signal) {
            if (urgent != null) { urgent.complete(null); urgent = null; }
            signal.notifyAll();
        }
        if (!finalFlush || !hasPending() || tableMissing || lastDbFailed) return;
        long budget = ShutdownDeadline.waitMs(SHUTDOWN_FLUSH_CAP_MS);
        if (budget <= 0) return;
        Thread t = new Thread(() -> {
            try {
                flushAsync(true);
            } catch (RuntimeException e) {
                log.debug("Önbellek yolu son yayını yapılamadı: {}", e.toString());
            }
        }, "cache-bus-final");
        t.setDaemon(true);
        t.start();
        try {
            t.join(budget);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────────────

    static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    /** Dakikada en çok bir WARN (veritabanı arızasında günlük taşmasın); aradakiler DEBUG. */
    private void warnRateLimited(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAtMs < 60_000L) {
            log.debug(message);
            return;
        }
        lastWarnAtMs = now;
        log.warn(message);
    }

    /** Bu pod'un uyguladığı genel kuşak (-1 = henüz yoklanmadı). */
    public long appliedGeneration() {
        return appliedGlobal;
    }

    /** Durum özeti (Sistem Sağlığı / tanı): sayaçlar, son başarılı yoklama, tablo var mı. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        Thread w = worker;
        m.put("running", w != null && w.isAlive() && !stopped);
        m.put("applied_generation", appliedGlobal);
        m.put("table_missing", tableMissing);
        m.put("last_poll_ok_at", lastPollOkAtMs == 0 ? null : ISO.format(Instant.ofEpochMilli(lastPollOkAtMs)));
        m.put("bumps", bumps.get());
        m.put("bump_failures", bumpFailures.get());
        m.put("polls", polls.get());
        m.put("poll_failures", pollFailures.get());
        m.put("remote_evictions", remoteEvictions.get());
        m.put("evictor_failures", evictorFailures.get());
        m.put("forced_waits", forcedWaits.get());
        m.put("header_timeouts", headerTimeouts.get());
        m.put("dropped_names", droppedNames.get());
        synchronized (signal) { m.put("pending", asyncPending.size()); }
        m.put("open_request_batches", openBatches.size());
        return m;
    }

    /** Test kancası: bekleyen arka plan yayını var mı. */
    boolean hasPending() {
        synchronized (signal) { return !asyncPending.isEmpty(); }
    }

    /** Test kancası: açık istek grubu sayısı. */
    int openBatchCount() {
        return openBatches.size();
    }
}
