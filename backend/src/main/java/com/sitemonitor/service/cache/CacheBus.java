package com.sitemonitor.service.cache;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 *   <li>{@link #bump(String...)}: işlem içindeyse işlem İŞLENDİKTEN sonra (geri alınan işlem yayın yapmaz), işlemsizse
 *       hemen. İstek iş parçacığında ad istek sonuna dek biriktirilir ve yanıt gövdesi yazılmadan önce TEK güncelleme ile
 *       yayınlanır ({@link #flushRequest()}, {@code CacheBusResponseAdvice}); yanıt {@value #RESPONSE_HEADER} başlığında
 *       yeni genel kuşağı taşır. Arka plan iş parçacıklarında yayın veri yolunun kendi iş parçacığında, kısa birleştirmeyle
 *       yapılır. Asla atmaz; başarısız yayın geri çekilerek yeniden denenir (sınırlı).</li>
 *   <li>Kendi daemon iş parçacığı ({@code cache-bus}, paylaşılan zamanlayıcı havuzu DEĞİL) {@code site.monitor.cache-bus.poll-ms}
 *       (2 sn) aralıkla {@code SELECT name, generation} okur; kuşağı DEĞİŞEN (kıyas {@code !=} — elle silinen / yeniden
 *       kurulan satır da görülür) her ad için kayıtlı yerel boşaltıcıyı yeniden yayın YAPMADAN çalıştırır.</li>
 *   <li>Yazdığını oku: istemci gördüğü en yüksek kuşağı GET'lerde {@value #REQUEST_HEADER} ile gönderir; uygulanmış kuşağı
 *       daha düşük olan pod yanıtlamadan önce TEK zorunlu yoklama ister ve en çok {@code site.monitor.cache-bus.min-gen-wait-ms}
 *       (500 ms) bekler ({@link #awaitGeneration(long)}, {@code CacheBusRequestFilter}). Başlık yoksa bugünkü davranış.</li>
 *   <li>Kendi yayınını yeniden uygulamaz: yayının döndürdüğü kuşaklar art arda geliyorsa (araya başka pod girmediyse) "görüldü"
 *       ilerletilir — yazan pod kendi önbelleğini ikinci kez boşaltmaz, ısıtıcı iki kez koşmaz.</li>
 * </ul>
 *
 * <p><b>Geriye uyum.</b> Tablo yoksa ({@code BadSqlGrammarException} — ilk açılışın ilk saniyeleri ya da yaması düşmüş
 * veritabanı) yol sessizce devre dışıdır: önbellekler TTL'le çalışır (bugünkü davranış). 20.165.1 pod'u ne yayın yapar ne
 * dinler — karışık dağıtımda o pod TTL'le kalır. Tek pod: kendi yayınları kendi önbelleğini boşaltmaz, istek başlığı
 * beklemeye yol açmaz — davranış aynı.
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
    /** İstek grubu bundan uzun beklerse (uzun toplu istek) adlar arka plana devredilir. */
    static final long REQUEST_HANDOFF_MS = 2_000L;
    /** Yoklama / yayın sorgularının üst süresi (sn) — asılan veritabanı kilidi sonsuza dek tutmasın. */
    static final int QUERY_TIMEOUT_SECONDS = 3;
    /** Kendi yayınını kaydetmek için kilit beklemesi (istek yolunda) — aşılırsa kayıt atlanır (zararsız: bir fazla boşaltma). */
    static final long OWN_RECORD_LOCK_MS = 50L;
    /** Başarısız arka plan yayınının en çok deneme sayısı (geri çekilme 1 → 30 sn); aşılırsa ad bırakılır (TTL'e düşer). */
    static final int MAX_ASYNC_ATTEMPTS = 12;
    static final long MAX_BACKOFF_MS = 30_000L;
    /** İlk başarılı yoklamada: pod'un kuruluşundan bu kadar önce ya da sonra yayınlanmış adlar boşaltılır (saat kayması payı). */
    static final long BASELINE_MARGIN_MS = 30_000L;
    /** Bir adın bekleyen kendi-yayın kümesinin üst sınırı. */
    static final int MAX_OWN_PENDING = 64;

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

    /** İş parçacığı eşgüdümü — {@link #signal} altında. */
    private final Object signal = new Object();
    private long pollsStarted;
    private long pollsCompleted;
    private boolean pollRequested;
    private final Set<String> asyncPending = new LinkedHashSet<>();
    private int asyncAttempts;
    private long asyncNotBeforeNs;
    private final ReentrantLock pollLock = new ReentrantLock();

    private volatile Thread worker;
    private volatile boolean stopped;
    private ApplicationContext context;

    /** Görünürlük (Sistem Sağlığı / testler). */
    private final AtomicLong bumps = new AtomicLong();
    private final AtomicLong bumpFailures = new AtomicLong();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong pollFailures = new AtomicLong();
    private final AtomicLong remoteEvictions = new AtomicLong();
    private final AtomicLong forcedWaits = new AtomicLong();
    private final AtomicLong droppedNames = new AtomicLong();
    private volatile long lastPollOkAtMs;
    private volatile boolean tableMissing;
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

    /** Bir adın YEREL boşaltıcısı — başka pod yayın yaptığında çalışır (yeniden yayın yapmamalı). */
    public void register(String name, Runnable localEvict) {
        if (!valid(name) || localEvict == null) return;
        evictors.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(localEvict);
    }

    /** Kayıtsız adlar için boşaltıcı çözücü (Spring önbellek yöneticisi: o pod'da oluşmuş her önbellek). null = yok. */
    public void addResolver(Function<String, Runnable> resolver) {
        if (resolver != null) resolvers.add(resolver);
    }

    /** Başka pod'un yayınıyla boşaltılan adlar (yoklama sonunda, boşaltıcılardan sonra). */
    public void onRemoteEvict(Consumer<Set<String>> listener) {
        if (listener != null) remoteListeners.add(listener);
    }

    // ── Yayın ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Adların önbelleği değişti — öteki pod'lar da boşaltsın. İşlem içindeyse işlem işlendikten sonra (geri alınırsa hiç),
     * istek iş parçacığındaysa yanıt yazılmadan önce, değilse arka planda. Asla atmaz; çağıranın akışını bozmaz.
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
     * İstek iş parçacığı → istek grubuna (yanıt yazılırken yayınlanır); değilse arka plan kuyruğuna. Veritabanına dokunmaz.
     * Uzun süren istekte (toplu işlem: parça parça işlenen binlerce kayıt) grup {@link #REQUEST_HANDOFF_MS}'den uzun
     * beklediyse adlar arka plan kuyruğuna devredilir — öteki pod'lar yanıtın sonunu beklemez.
     */
    void dispatch(Set<String> names) {
        if (names == null || names.isEmpty() || !enabled) return;
        RequestBatch batch = currentBatch(true);
        if (batch != null) {
            if (batch.add(names, System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(REQUEST_HANDOFF_MS))) {
                Set<String> early = batch.drain();
                if (!early.isEmpty()) enqueueAsync(early);
            }
            return;
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
            RequestBatch b = new RequestBatch();
            ra.setAttribute(batchAttr, b, RequestAttributes.SCOPE_REQUEST);
            // Güvenlik ağı: yanıt gövdesi yazılmadan biten istekte (void uç, akış) kalan adlar arka planda yayınlanır
            ra.registerDestructionCallback(batchAttr, () -> {
                Set<String> left = b.drain();
                if (!left.isEmpty()) enqueueAsync(left);
            }, RequestAttributes.SCOPE_REQUEST);
            return b;
        } catch (IllegalStateException requestNotActive) {
            return null;
        }
    }

    /** Bir isteğin bekleyen yayın adları + bu istekte dönen en yüksek genel kuşak. */
    static final class RequestBatch {
        private final Set<String> names = new LinkedHashSet<>();
        private long maxGen = -1;
        private long firstAddedNs;

        synchronized void add(Collection<String> n) { add(n, System.nanoTime(), Long.MAX_VALUE); }

        /** @return grup {@code handoffNs}'den uzun süredir bekliyor (devredilmeli) */
        synchronized boolean add(Collection<String> n, long nowNs, long handoffNs) {
            if (names.isEmpty()) firstAddedNs = nowNs;
            names.addAll(n);
            return nowNs - firstAddedNs > handoffNs;
        }

        synchronized Set<String> drain() {
            Set<String> out = new LinkedHashSet<>(names);
            names.clear();
            return out;
        }

        synchronized void recordGen(long g) { if (g > maxGen) maxGen = g; }

        synchronized long maxGen() { return maxGen; }
    }

    /**
     * Bu isteğin bekleyen yayınlarını TEK güncellemeyle yapar (yanıt gövdesi yazılmadan önce). İşlem dışında çağrılmalı.
     * @return bu istekte yayınlanan en yüksek genel kuşak; yayın yoksa / başarısızsa -1 (başarısız olan arka planda denenir)
     */
    public long flushRequest() {
        if (!enabled) return -1;
        RequestBatch b = currentBatch(false);
        if (b == null) return -1;
        Set<String> names = b.drain();
        if (!names.isEmpty()) {
            Long g = bumpNow(names);
            if (g == null) enqueueAsync(names);
            else b.recordGen(g);
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

    /** Bekleyen arka plan yayınlarını hemen yapar (test ve kapanış). */
    public void flushPending() {
        flushAsync(true);
    }

    private void flushAsync(boolean ignoreBackoff) {
        Set<String> names;
        synchronized (signal) {
            if (asyncPending.isEmpty()) return;
            if (!ignoreBackoff && System.nanoTime() - asyncNotBeforeNs < 0) return;
            names = new LinkedHashSet<>(asyncPending);
            asyncPending.clear();
        }
        Long g = bumpNow(names);
        synchronized (signal) {
            if (g != null) {
                asyncAttempts = 0;
                return;
            }
            asyncAttempts++;
            if (asyncAttempts > MAX_ASYNC_ATTEMPTS) {
                droppedNames.addAndGet(names.size());
                log.warn("Önbellek yolu: {} denemede yayınlanamadı, bırakıldı (öteki pod'lar TTL ile tazeler): {}",
                        MAX_ASYNC_ATTEMPTS, names);
                asyncAttempts = 0;
                return;
            }
            asyncPending.addAll(names);
            long backoff = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(5, asyncAttempts - 1));
            asyncNotBeforeNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff);
        }
    }

    /**
     * Yayını hemen yapar (çağıran iş parçacığında; işlem DIŞINDA). Önce {@code _global} satırı kilitlenir — tüm yayınlar
     * aynı sırayla kilitler, iki pod kilitlenmez. Ad satırı eksikse (elle silinmiş) işlem geri alınır ve yeniden denenir.
     * @return yeni genel kuşak; başarısızsa null (asla atmaz)
     */
    @Nullable
    Long bumpNow(Set<String> names) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(names));
        sorted.remove(GLOBAL);
        if (sorted.isEmpty()) return null;
        String now = ISO.format(Instant.ofEpochMilli(clock.getAsLong()));
        try {
            ensureRows(sorted);
            Map<String, Long> gens = tx != null ? tx.execute(status -> doBump(sorted, now)) : doBump(sorted, now);
            if (gens == null) return null;
            bumps.incrementAndGet();
            tableMissing = false;
            recordOwn(gens);
            return gens.get(GLOBAL);
        } catch (BadSqlGrammarException e) {
            tableMissing = true;
            bumpFailures.incrementAndGet();
            log.debug("Önbellek yolu tablosu yok (yayın atlandı): {}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            bumpFailures.incrementAndGet();
            warnRateLimited("Önbellek yolu yayını başarısız (" + names + "): " + e);
            return null;
        }
    }

    private Map<String, Long> doBump(List<String> names, String now) {
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

    /** Bir yoklama turu (iş parçacığı, satır içi zorunlu yoklama ve testler). @return başarılı mı */
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
        Set<String> changed = new LinkedHashSet<>();
        state.lock();
        try {
            List<Row> rows;
            try {
                rows = jdbc.query("SELECT name, generation, updated_at FROM cache_bus",
                        (rs, i) -> new Row(rs.getString(1), rs.getLong(2), rs.getString(3)));
            } catch (BadSqlGrammarException e) {
                tableMissing = true;
                pollFailures.incrementAndGet();
                log.debug("Önbellek yolu tablosu yok (yoklama atlandı): {}", e.getMessage());
                return false;
            } catch (RuntimeException e) {
                pollFailures.incrementAndGet();
                warnRateLimited("Önbellek yolu yoklanamadı (öteki pod'ların boşaltmaları TTL ile gelir): " + e);
                return false;
            }
            tableMissing = false;
            Map<String, Row> byName = new LinkedHashMap<>();
            for (Row r : rows) if (r.name() != null) byName.put(r.name(), r);
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
            for (String n : changed) evictLocal(n);
            appliedGlobal = seen.getOrDefault(GLOBAL, 0L);
            polls.incrementAndGet();
            lastPollOkAtMs = clock.getAsLong();
        } finally {
            state.unlock();
        }
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

    private void evictLocal(String name) {
        List<Runnable> list = new ArrayList<>(evictors.getOrDefault(name, new CopyOnWriteArrayList<>()));
        for (Function<String, Runnable> r : resolvers) {
            try {
                Runnable run = r.apply(name);
                if (run != null) list.add(run);
            } catch (RuntimeException e) {
                log.debug("Önbellek yolu çözücüsü hata verdi ({}): {}", name, e.toString());
            }
        }
        for (Runnable run : list) {
            try {
                run.run();
            } catch (RuntimeException e) {
                log.warn("Önbellek yolu: yerel boşaltma başarısız ({}): {}", name, e.toString());
            }
        }
    }

    /**
     * Yazdığını oku: istemcinin gördüğü genel kuşak bu pod'da uygulanmadıysa TEK yoklama ister (başka bir yoklama sürüyorsa
     * isteğin GELİŞİNDEN SONRA başlayan yoklamayı bekler) ve en çok {@code min-gen-wait-ms} bekler. Süre dolarsa istek yine
     * sunulur (bugünkü davranış). Asla atmaz.
     * @return kuşak uygulandı mı
     */
    public boolean awaitGeneration(long min) {
        if (!enabled || min <= 0) return true;
        if (appliedGlobal >= min) return true;
        if (stopped) return false;
        forcedWaits.incrementAndGet();
        long waitMs = Math.max(0, minGenWaitMs);
        Thread w = worker;
        try {
            if (w == null || !w.isAlive()) {
                // İş parçacığı yok (açılış öncesi / test): satır içi tek yoklama
                if (pollLock.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
                    try {
                        if (appliedGlobal < min) pollLocked();
                    } finally {
                        pollLock.unlock();
                    }
                }
                return appliedGlobal >= min;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
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
        if (!enabled || stopped || (worker != null && worker.isAlive())) return;
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
                if ((forced && now - lastPollNs >= gapNs) || now - nextPollNs >= 0) {
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
                // Önce yoklama, sonra yayın: açılışın ilk turunda taban kendi yayınımızdan ÖNCE alınır
                flushAsync(false);
                synchronized (signal) {
                    if (stopped) break;
                    long t = System.nanoTime();
                    long waitNs = nextPollNs - t;
                    if (pollRequested) waitNs = Math.min(waitNs, gapNs - (t - lastPollNs));
                    if (!asyncPending.isEmpty()) waitNs = Math.min(waitNs, asyncNotBeforeNs - t);
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

    /**
     * Kapanış: yeni yoklama / bekleme yok (CLAUDE.md "Things that bite": kapanışta yeni iş başlamaz); bekleyen arka plan
     * yayınları TEK denemeyle gönderilir (biten işin boşaltması öteki pod'lara ulaşsın — veritabanı hâlâ açık).
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        if (context != null && event != null && event.getApplicationContext() != context) return;
        stop(true);
    }

    /**
     * Yok etme: kapanış dinleyicilerinin (ör. {@code NotifyExecutorDrain}'in ≤ 20 sn boşaltması) sırasında biriken yayınlar
     * da TEK denemeyle gönderilir — veri kaynağı bu fasulyeden SONRA kapanır (bağımlılık). Başarısızlık yalnız DEBUG.
     */
    @PreDestroy
    void destroy() {
        stop(true);
    }

    void stop(boolean finalFlush) {
        stopped = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        synchronized (signal) { signal.notifyAll(); }
        if (finalFlush) {
            try {
                flushAsync(true);
            } catch (RuntimeException e) {
                log.debug("Önbellek yolu son yayını yapılamadı: {}", e.toString());
            }
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
        m.put("forced_waits", forcedWaits.get());
        m.put("dropped_names", droppedNames.get());
        synchronized (signal) { m.put("pending", asyncPending.size()); }
        return m;
    }

    /** Test kancası: bekleyen arka plan yayını var mı. */
    boolean hasPending() {
        synchronized (signal) { return !asyncPending.isEmpty(); }
    }
}
