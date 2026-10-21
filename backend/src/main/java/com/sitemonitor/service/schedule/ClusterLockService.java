package com.sitemonitor.service.schedule;

import com.sitemonitor.service.SchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Küme geneli iş kilidi ({@code scheduler_lock}) — YENİLENEN kilit (2026-10-21, çok pod R1-A, MP-02).
 *
 * <p><b>Neden.</b> {@code scheduler_lock} satırının süresi ({@code locked_until}) alınışta sabitlenip hiç uzatılmıyordu: kilit
 * süresinden (sertifika taraması 10 dk) uzun süren bir iş, kilit "süresi dolmuş" görünürken sürmeye devam ediyor, başka
 * pod'un bir sonraki denemesi satırı silip AYNI işi eşzamanlı başlatıyordu (iki sertifika taraması → çift alarm, çift
 * e-posta). Ölen pod'un kilidi de süre dolana kadar işi tüm kümede durduruyordu (izleyici için 10 dk "değerlendirme
 * durdu" → dış uç 503).
 *
 * <p><b>Model.</b> Alış BUGÜNKÜ SQL ve anlamla aynı: süresi dolmuş satır silinir, sonra INSERT — birincil anahtar karar
 * verir (aynı sahip de ikinci kez alamaz). Tutulan her kilit bu sınıfın belleğinde bir {@link Hold}'dur; tek bir daemon
 * yenileyici iş parçacığı her {@link #RENEW_PERIOD_MS} ms'de {@code UPDATE … SET locked_until = şimdi + TTL WHERE name = ?
 * AND locked_by = <ben>} yazar. 0 satır → kilit KAYBEDİLDİ ({@link State#LOST}, bir kez WARN): başka pod almış ya da yönetici
 * zorla bırakmış — {@link #held(String)} artık false döner; uzun iş (sertifika taraması) sonuç kaydetmeden önce buna bakar.
 * Yenileme veritabanı hatası verirse kilit son yazılan süreye kadar tutulmuş sayılır, sonra {@link #held} false (güvenli
 * taraf). Sert tavan ({@link #hardCapFor}, varsayılan 3 sa; gece temizliği ve ping özet yeniden hesabı 4 sa) dolunca
 * yenileme DURUR: askıda kalmış bir iş kümeyi sonsuza dek kilitlemez, TTL sonra kilit kendiliğinden düşer.
 *
 * <p><b>Saat ve biçim.</b> {@code locked_until} bugünkü gibi UTC {@code yyyy-MM-ddTHH:mm:ss} metnidir — eski (yenilemesiz)
 * sürümle yeni sürüm yuvarlanan dağıtımda aynı satırı aynı kuralla okur. Zaman {@link #clock}'tan (üretimde duvar saati;
 * testlerde ortak saat).
 *
 * <p><b>Sahip.</b> Kurucuya verilir: üretimde JVM'in örnek kimliği ({@code SchedulerService.instanceId()}), testlerde aynı
 * JVM'deki iki "pod" iki AYRI sahip olur ({@code MultiPodTestSupport}).
 *
 * <p><b>Geri düşüş.</b> Kilit TABLOSU yoksa ({@link BadSqlGrammarException}, ilk açılış) kilit "alınmış" sayılır (bugünkü
 * bilinçli tek-pod davranışı) ve yenilenmez; başka her veritabanı hatası "alınamadı" (bu tur atlanır — D2 kararı).
 */
@Slf4j
@Service
public class ClusterLockService {

    /** Yenileyici turu (ms) — en kısa kilit süresinin (2 dk) en az dörtte biri, böylece TTL dolmadan ≥ 3 deneme olur. */
    public static final long RENEW_PERIOD_MS = 30_000L;

    /** Yenilenen kilitlerin süresi: yenileme varken uzun TTL gerekmez; ölen pod'un kilidi en geç bu sürede düşer. */
    public static final Duration RENEWED_TTL = Duration.ofMinutes(3);

    /** Sert tavanın varsayılanı — bundan uzun süren iş artık yenilenmez (askıda kalmış iş kümeyi kilitlemesin). */
    public static final Duration DEFAULT_HARD_CAP = Duration.ofHours(3);

    /** Uzun gece işleri için sert tavan. */
    public static final Duration LONG_HARD_CAP = Duration.ofHours(4);

    /** Uzun tavanlı iş kilitleri (ad kalıcıdır — G6). */
    static final Set<String> LONG_JOBS = Set.of("nightly-cleanup", "ping-rollup-rebuild");

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String SQL_DELETE_EXPIRED = "DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?";
    static final String SQL_INSERT = "INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)";
    static final String SQL_RENEW = "UPDATE scheduler_lock SET locked_until = ? WHERE name = ? AND locked_by = ?";
    static final String SQL_RELEASE = "DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?";

    /** Kilidin bu pod'daki durumu. */
    public enum State {
        /** Tutuluyor (yenileniyor). */
        HELD,
        /** Yenileme 0 satır buldu: başka pod aldı ya da satır silindi. */
        LOST,
        /** Sert tavan doldu: artık yenilenmiyor, son süreye kadar tutulmuş sayılır. */
        CAPPED,
        /** Bırakıldı. */
        RELEASED
    }

    /**
     * Bu pod'un tuttuğu bir kilit. {@code untilMs} son BAŞARIYLA yazılan bitiştir — kilit en geç o ana dek kesin bu pod'dadır.
     * Durum geçişleri ve yenileme UPDATE'i nesnenin kendi kilidi altında (yenileme ile bırakma yarışmaz).
     */
    public static final class Hold {
        private final String name;
        private final long ttlMs;
        private final long acquiredAtMs;
        private final long hardDeadlineMs;
        private final boolean legacy;
        private volatile long untilMs;
        private volatile State state = State.HELD;
        private volatile boolean renewWarned;

        Hold(String name, long ttlMs, long acquiredAtMs, long hardDeadlineMs, long untilMs, boolean legacy) {
            this.name = name;
            this.ttlMs = ttlMs;
            this.acquiredAtMs = acquiredAtMs;
            this.hardDeadlineMs = hardDeadlineMs;
            this.untilMs = untilMs;
            this.legacy = legacy;
        }

        public String name() { return name; }
        public long ttlMs() { return ttlMs; }
        public long acquiredAtMs() { return acquiredAtMs; }
        public long hardDeadlineMs() { return hardDeadlineMs; }
        public long untilMs() { return untilMs; }
        public State state() { return state; }
        /** Kilit tablosu yokken (eski tek pod geri düşüşü) verilen sahte tutuş — yenilenmez, hep tutulmuş sayılır. */
        public boolean legacy() { return legacy; }
    }

    private final JdbcTemplate jdbc;
    private final String owner;
    private final LongSupplier clock;
    private final boolean backgroundRenewer;
    private final ConcurrentHashMap<String, Hold> holds = new ConcurrentHashMap<>();
    private final AtomicLong renewWarnAt = new AtomicLong();
    private volatile ScheduledExecutorService renewer;
    private volatile boolean closed;
    /** Kapanış başladı ({@code ContextClosedEvent}): YENİ kilit verilmez; tutulanlar yenilenmeye devam eder (süren iş bitsin). */
    private volatile boolean acquiringStopped;

    /** Üretim: sahip = bu JVM'in örnek kimliği, duvar saati, arka planda yenileyici. */
    @Autowired
    public ClusterLockService(JdbcTemplate jdbc) {
        this(jdbc, SchedulerService.instanceId(), System::currentTimeMillis, true);
    }

    /** Testler / çok pod düzeneği: aynı veritabanında ayrı sahip; yenileme yalnız elle ({@link #renewAll()}). */
    public ClusterLockService(JdbcTemplate jdbc, String owner) {
        this(jdbc, owner, System::currentTimeMillis, false);
    }

    /**
     * @param backgroundRenewer true → ilk kilitte bir daemon yenileyici başlar; false → yenileme yalnız {@link #renewAll()}
     *                          ile (testler ortak saati ilerletip turu elle koşturur)
     */
    public ClusterLockService(JdbcTemplate jdbc, String owner, LongSupplier clock, boolean backgroundRenewer) {
        this.jdbc = jdbc;
        this.owner = owner;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.backgroundRenewer = backgroundRenewer;
    }

    /** {@code locked_by} değeri. */
    public String owner() {
        return owner;
    }

    /** İşin sert tavanı (ad kalıcı — gece temizliği ve ping özet yeniden hesabı uzun). */
    public static Duration hardCapFor(String name) {
        return name != null && LONG_JOBS.contains(name) ? LONG_HARD_CAP : DEFAULT_HARD_CAP;
    }

    /** Varsayılan TTL ({@link #RENEWED_TTL}) ve işin sert tavanıyla alır. */
    public Optional<Hold> tryAcquire(String name) {
        return tryAcquire(name, RENEWED_TTL, hardCapFor(name));
    }

    /**
     * Kilidi bu sahip adına almayı dener (bugünkü SQL: süresi dolmuşu sil, sonra INSERT).
     *
     * @param ttl     her yazışta {@code locked_until = şimdi + ttl}
     * @param hardCap alıştan bu kadar sonra yenileme durur
     * @return alındıysa tutuş; kilit başka sahipte (ya da bu sahipte zaten) ya da geçici DB hatası → boş
     */
    public Optional<Hold> tryAcquire(String name, Duration ttl, Duration hardCap) {
        if (name == null || name.isBlank()) return Optional.empty();
        if (acquiringStopped || closed) {
            log.debug("Dağıtık kilit '{}' verilmedi — pod kapanıyor (yeni iş başlamaz) [owner={}]", name, owner);
            return Optional.empty();
        }
        long now = clock.getAsLong();
        long ttlMs = Math.max(1_000L, ttl == null ? RENEWED_TTL.toMillis() : ttl.toMillis());
        long capMs = Math.max(ttlMs, hardCap == null ? DEFAULT_HARD_CAP.toMillis() : hardCap.toMillis());
        long until = now + ttlMs;
        try {
            jdbc.update(SQL_DELETE_EXPIRED, name, iso(now));
            jdbc.update(SQL_INSERT, name, owner, iso(until));
        } catch (DuplicateKeyException e) {
            return Optional.empty();   // kilit başka pod'da (ya da bu pod'un başka işinde)
        } catch (BadSqlGrammarException e) {
            // Kilit TABLOSU yok (ilk açılışta ddl-auto henüz koşmadı) — bilinçli tek-pod geri düşüşü: kilitsiz koş.
            log.warn("Dağıtık kilit tablosu yok — '{}' kilitsiz koşuyor (HA zayıf): {}", name, e.getMessage());
            Hold h = new Hold(name, ttlMs, now, now + capMs, Long.MAX_VALUE, true);
            holds.put(name, h);
            return Optional.of(h);
        } catch (Exception e) {
            // D2: geçici DB hatası (kilitlenme, zaman aşımı, bağlantı kopması) → bu tur ATLANIR (çift koşu, bir turu
            // kaçırmaktan pahalı; işler periyodik, sonraki tur telafi eder).
            log.warn("Dağıtık kilit '{}' alınamadı — bu tur atlanıyor (güvenli taraf): {}", name, e.getMessage());
            return Optional.empty();
        }
        Hold h = new Hold(name, ttlMs, now, now + capMs, until, false);
        Hold previous = holds.put(name, h);
        if (previous != null && previous != h) {
            synchronized (previous) {
                if (previous.state == State.HELD || previous.state == State.CAPPED) previous.state = State.LOST;   // satırı artık yeni tutuşun
            }
        }
        ensureRenewer();
        return Optional.of(h);
    }

    /**
     * Kilit ŞU AN kesin bu pod'da mı: tutuş var, kaybedilmedi ve son yazılan bitiş geçmedi (yenileme veritabanı hatasıyla
     * aksadıysa bitiş geçince false — başka pod almış olabilir). Tablo yokken verilen tutuş hep true.
     */
    public boolean held(String name) {
        Hold h = name == null ? null : holds.get(name);
        if (h == null) return false;
        if (h.legacy) return h.state == State.HELD;
        State s = h.state;
        return (s == State.HELD || s == State.CAPPED) && clock.getAsLong() < h.untilMs;
    }

    /** Bu pod'un tuttuğu kilidin tutuşu (yoksa boş). */
    public Optional<Hold> hold(String name) {
        return Optional.ofNullable(name == null ? null : holds.get(name));
    }

    /** Ada göre bırakır (tutuş yoksa yine de bu sahibin satırını siler — eski bırakma çağrısıyla aynı). */
    public boolean release(String name) {
        if (name == null) return false;
        Hold h = holds.get(name);
        if (h != null) return release(h);
        return deleteRow(name);
    }

    /**
     * Tutuşu bırakır: durum RELEASED (yenileyici artık dokunmaz); satır yalnız bu tutuş hâlâ GÜNCEL ise ve bu sahipteyse
     * silinir. Güncel değilse (yönetici zorla bıraktı, aynı pod yeni bir koşu için kilidi yeniden aldı) satıra dokunulmaz —
     * eski koşunun {@code finally}'si yeni koşunun kilidini silmez.
     */
    public boolean release(Hold hold) {
        if (hold == null) return false;
        synchronized (hold) {
            hold.state = State.RELEASED;
        }
        boolean current = holds.remove(hold.name, hold);
        if (!current) return false;
        if (hold.legacy) return true;
        return deleteRow(hold.name);
    }

    /**
     * Bellekteki tutuşu satıra dokunmadan unutur (yönetici satırı zorla sildi): {@link #held} artık false — yarıda kalan uzun
     * iş sonucunu yazmaz.
     */
    public void forget(String name) {
        if (name == null) return;
        Hold h = holds.remove(name);
        if (h != null) {
            synchronized (h) {
                h.state = State.LOST;
            }
        }
    }

    /**
     * Kapanış: bu pod'un tuttuğu TÜM kilitleri bırakır (zamanlayıcı durduktan sonra; ölen pod'un kilidi işi süre boyunca
     * durdurmasın). @return silinen satır sayısı
     */
    public int releaseAllOwn() {
        int n = 0;
        for (Hold h : new ArrayList<>(holds.values())) {
            if (release(h) && !h.legacy) n++;
        }
        if (n > 0) log.info("Kapanış: bu pod'un {} iş kilidi bırakıldı [owner={}]", n, owner);
        return n;
    }

    /**
     * Bir yenileme turu: tutulan her kilidin bitişini {@code şimdi + TTL}'ye çeker. 0 satır → LOST (bir kez WARN); sert
     * tavan dolduysa CAPPED (bir kez WARN, artık yenilenmez); veritabanı hatası → tutuş değişmez (son bitişe dek geçerli).
     *
     * @return yenilenen kilit sayısı
     */
    public int renewAll() {
        int renewed = 0;
        for (Hold h : new ArrayList<>(holds.values())) {
            if (renewOne(h)) renewed++;
        }
        return renewed;
    }

    private boolean renewOne(Hold h) {
        synchronized (h) {
            if (h.legacy || h.state != State.HELD) return false;
            long now = clock.getAsLong();
            if (now >= h.hardDeadlineMs) {
                h.state = State.CAPPED;
                log.warn("Dağıtık kilit '{}' sert tavana ({} dk) ulaştı — artık yenilenmiyor; iş askıda kalmış olabilir, kilit "
                        + "TTL dolunca kendiliğinden düşer [owner={}]", h.name, (h.hardDeadlineMs - h.acquiredAtMs) / 60_000L, owner);
                return false;
            }
            long until = now + h.ttlMs;
            int rows;
            try {
                rows = jdbc.update(SQL_RENEW, iso(until), h.name, owner);
            } catch (Exception e) {
                long last = renewWarnAt.get();
                if (now - last >= 60_000L && renewWarnAt.compareAndSet(last, now)) {
                    log.warn("Dağıtık kilit '{}' yenilenemedi (son süreye kadar tutulmuş sayılır): {}", h.name, e.toString());
                }
                return false;
            }
            if (rows == 1) {
                h.untilMs = until;
                return true;
            }
            h.state = State.LOST;
            if (!h.renewWarned) {
                h.renewWarned = true;
                log.warn("Dağıtık kilit '{}' KAYBEDİLDİ — satır artık bu pod'da değil (başka pod aldı ya da zorla bırakıldı); "
                        + "uzun iş sonucunu yazmayacak [owner={}]", h.name, owner);
            }
            return false;
        }
    }

    /** System Health: bu pod'un tuttuğu kilitler (ad, durum, bitiş). */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hold h : holds.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.name);
            m.put("state", h.state.name());
            m.put("until", h.legacy ? null : iso(h.untilMs));
            m.put("acquired_at", iso(h.acquiredAtMs));
            out.add(m);
        }
        return out;
    }

    /**
     * Kapanışın ilk anı (2026-10-21, çok pod R1-A): bundan sonra YENİ kilit verilmez — Spring zamanlayıcısı kapanışta
     * {@code @Scheduled} tıklarını ~40 sn daha tetikler (aşama zaman aşımı + bekleme), ölmekte olan pod yeni bir tarama /
     * rapor / tur başlatmasın. Tutulan kilitler yenilenmeye DEVAM eder (süren iş adımını bitirir), {@link #releaseAllOwn}
     * ile bırakılır. Geri alınmaz.
     */
    public void stopAcquiring() {
        acquiringStopped = true;
    }

    /** Kapanış başladı mı (yeni kilit verilmiyor). */
    public boolean acquiringStopped() {
        return acquiringStopped || closed;
    }

    /** Kapanış (bean yok edilirken): yenileyiciyi durdurur, kalan kilitleri bırakır (idempotent). */
    @jakarta.annotation.PreDestroy
    public void close() {
        closed = true;
        ScheduledExecutorService ex = renewer;
        renewer = null;
        if (ex != null) ex.shutdownNow();
        releaseAllOwn();
    }

    private boolean deleteRow(String name) {
        try {
            return jdbc.update(SQL_RELEASE, name, owner) > 0;
        } catch (Exception e) {
            log.warn("Dağıtık kilit '{}' bırakılamadı (TTL dolunca düşer): {}", name, e.getMessage());
            return false;
        }
    }

    private void ensureRenewer() {
        if (!backgroundRenewer || renewer != null || closed) return;
        synchronized (this) {
            if (renewer != null || closed) return;
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cluster-lock-renewer");
                t.setDaemon(true);
                return t;
            });
            ex.scheduleWithFixedDelay(() -> {
                // Her şey yakalanır: zamanlanmış görev bir kez fırlatırsa ScheduledExecutorService onu bir daha koşturmaz.
                try {
                    renewAll();
                } catch (Throwable t) {
                    log.warn("Dağıtık kilit yenileme turu hata verdi (sonraki turda yeniden): {}", t.toString());
                }
            }, RENEW_PERIOD_MS, RENEW_PERIOD_MS, TimeUnit.MILLISECONDS);
            renewer = ex;
        }
    }

    static String iso(long epochMs) {
        return ISO.format(Instant.ofEpochMilli(epochMs));
    }
}
