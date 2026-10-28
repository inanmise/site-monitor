package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Süren teyit zincirleri listesi HER POD'da aynı (2026-10-28, çok pod Sürüm 3-2, paket C; kullanıcı kararı D-2, tasarım
 * R3-2 §6) — {@code GET /api/monitoring/confirmations} ("Teyit denemesi X/N", Sayfa izlemesi ayrıntısı 30 sn'de bir yoklar).
 *
 * <p>Teyit zincirleri yalnız tarama liderinin belleğindedir; istek takipçi pod'a düşerse liste BOŞ dönüyordu. Lider 3-1'den
 * beri zincirleri {@code monitor_confirmations} tablosuna aynalar; bu servis takipçide listeyi oradan okur:
 * <ul>
 *   <li><b>Ayar kapalı ({@link EvalStateReadSwitch}) ya da bu pod lider</b> → {@code MonitoringOutageService.activeConfirmations}
 *       AYNEN (20.168.1 ile birebir; veritabanı yok — J-1).</li>
 *   <li><b>Takipçi, ayar açık</b> → {@code '*'} kuşak satırı canlıysa (son nabız ≤ {@link #LIVE_MS}) o kuşağın ve yazanın
 *       ABANDONED olmayan zincir satırları, MOS'un harita biçimiyle aynı alanlarla; okuma {@link #MEMO_MS} boyunca pod başına
 *       ezberlenir (iki ifade / 2 sn), ifadeler {@value #QUERY_TIMEOUT_SECONDS} sn zaman aşımlı KENDİ şablonunda (J-2: yalnız
 *       istek iş parçacığı, karar iş parçacığı değil).</li>
 *   <li><b>Her hata</b> (tablo yok, zaman aşımı, bayat / yok {@code '*'}) → bellek (takipçide boş = bugünkü davranış).</li>
 * </ul>
 * Önceki kuşakların satırları okunmaz ({@code gen = '*'.gen AND writer = '*'.writer}); zarif devirde eski lider kendi
 * satırlarını ABANDONED yapar, çökmede halefin ilk CAS'ı işaretler (3-1). {@value #CHAIN_ROW_MAX_AGE_MS} ms'den uzun süredir
 * güncellenmeyen satır hayalettir, gösterilmez. Kalan boşluk: aynı liderin kuşağı liderlik değişmeden yenilenirse (kip
 * kapalı → açık, üst sınır taşması, çit onarımı) süren zincirin satırı yeni kuşakta yoktur — o zincir bitene dek (~1–2 dk)
 * takipçi listesinde görünmez (liderin belleğinde görünür). Sıra: oluşturulma anı (bellekte karma sırasıydı;
 * ekran türe göre süzer). Yetki ve takım süzgeci denetleyicide aynen kalır (aynı {@code domain} anahtarları).
 */
@Component
public class ActiveConfirmationsService {

    private static final Logger log = LoggerFactory.getLogger(ActiveConfirmationsService.class);

    /** Ezber süresi (ms) — pod başına iki ifade / 2 sn. */
    static final long MEMO_MS = 2_000L;
    /** Bir okuma sürerken başka istek bu yaştan genç ezberi kullanır (beklemez); daha eskiyse bellek. */
    static final long STALE_SERVE_MS = 10_000L;
    /** {@code '*'} satırının canlı sayıldığı en uzun nabız yaşı = 2 × kaydedici nabzı + 10 sn (130 sn). */
    static final long LIVE_MS = 2 * EvalStateRecorder.HEARTBEAT_MS + 10_000L;
    /** Okunan en çok zincir satırı. */
    static final int MAX_ROWS = 2_000;
    /**
     * Bu kadar süredir güncellenmeyen zincir satırı "hayalet" sayılır, listelenmez (inceleme 2026-10-28, L-2): bekleyen
     * sınırına takılıp yazılamayan bir zincir-sonu silmesi RUNNING satırı günlük budamaya dek (1 sa+) bırakıyordu. Canlı
     * zincirin iki yazımı arasındaki en uzun süre = deneme aralığı (en çok 600 sn, {@code clampInterval}) + bir yeniden
     * kontrol (k6 en çok 180 + 30 sn) ≈ 13,5 dk; 20 dk pay bırakır.
     */
    static final long CHAIN_ROW_MAX_AGE_MS = 20 * 60_000L;
    /** İfade zaman aşımı (sn) — istek iş parçacığı yavaş veritabanında en çok bu kadar bekler. */
    static final int QUERY_TIMEOUT_SECONDS = 3;
    /** Hata uyarısı en çok bu aralıkla (ms). */
    private static final long WARN_EVERY_MS = 10 * 60_000L;

    static final String SQL_GENERATION = "SELECT gen, writer, updated_ms FROM " + EvalStateSql.TYPE_STATE
            + " WHERE alert_type = ?";
    static final String SQL_CHAINS = "SELECT alert_type, domain, detail, attempt, total_attempts, first_failure_at, "
            + "next_attempt_ms FROM " + EvalStateSql.CONFIRMATIONS + " WHERE gen = ? AND writer = ? AND state <> '"
            + EvalStateSql.STATE_ABANDONED + "' AND updated_ms >= ? ORDER BY created_ms, key_hash LIMIT " + MAX_ROWS;

    /** MOS'un ISO biçimi ({@code yyyy-MM-ddTHH:mm:ss}, UTC). */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Function<String, List<Map<String, Object>>> memory;
    private final BooleanSupplier readEnabled;
    private final BooleanSupplier leaderHint;
    private final JdbcTemplate jdbc;
    private final LongSupplier clock;

    private final ReentrantLock readLock = new ReentrantLock();
    private volatile Snapshot snapshot;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE / 2);

    /** Ezberlenen tablo okuması; {@code rows == null} = canlı kuşak yok / okunamadı (bellek kullanılır). */
    private record Snapshot(long atMs, List<Map<String, Object>> rows) {}

    /** Spring: zamanlayıcı ve ayarlar TEMBEL (döngü kurulmaz). */
    @Autowired
    public ActiveConfirmationsService(MonitoringOutageService mos, ObjectProvider<SchedulerService> scheduler,
                                      ObjectProvider<AppSettingsService> settings, JdbcTemplate jdbc) {
        this(mos::activeConfirmations, EvalStateReadSwitch.supplier(settings), lazyLeaderHint(scheduler),
                EvalStateSql.withQueryTimeout(jdbc, QUERY_TIMEOUT_SECONDS), System::currentTimeMillis);
    }

    /**
     * Elle kurulum (testler): bellek listesi ({@code domainFilter → liste}), okuma ayarı, lider ipucu, şablon (çağıran zaman
     * aşımını kendisi verir) ve saat.
     */
    ActiveConfirmationsService(Function<String, List<Map<String, Object>>> memory, BooleanSupplier readEnabled,
                               BooleanSupplier leaderHint, JdbcTemplate jdbc, LongSupplier clock) {
        this.memory = memory;
        this.readEnabled = readEnabled;
        this.leaderHint = leaderHint;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Lider ipucu tembel; zamanlayıcı yoksa / çözülemezse true (= bellek, bugünkü davranış). */
    private static BooleanSupplier lazyLeaderHint(ObjectProvider<SchedulerService> provider) {
        return () -> {
            if (provider == null) return true;
            try {
                SchedulerService s = provider.getIfAvailable();
                return s == null || s.sweepLeaderHint();
            } catch (RuntimeException e) {
                return true;
            }
        };
    }

    /**
     * Süren teyit zincirleri ({@code domainFilter} null → tümü; büyük / küçük harf duyarsız eşitlik — MOS ile aynı yüklem).
     * Harita alanları MOS'unkiyle aynı: {@code alert_type, domain, detail, attempt, total_attempts, started_at,
     * next_attempt_at}.
     */
    public List<Map<String, Object>> list(String domainFilter) {
        if (!enabledSafe() || leaderSafe()) return memory.apply(domainFilter);
        List<Map<String, Object>> rows = cachedRows();
        if (rows == null) return memory.apply(domainFilter);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object d = r.get("domain");
            if (domainFilter != null && (d == null || !domainFilter.equalsIgnoreCase(d.toString()))) continue;
            out.add(r);
        }
        return out;
    }

    private boolean enabledSafe() {
        try {
            return readEnabled != null && readEnabled.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean leaderSafe() {
        try {
            return leaderHint == null || leaderHint.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 2 sn ezberli tablo okuması; okuma sürerken başka istek beklemez (taze ezber ya da bellek). */
    private List<Map<String, Object>> cachedRows() {
        long now = clock.getAsLong();
        Snapshot s = snapshot;
        if (fresh(s, now, MEMO_MS)) return s.rows();
        if (!readLock.tryLock()) return fresh(s, now, STALE_SERVE_MS) ? s.rows() : null;
        try {
            s = snapshot;
            now = clock.getAsLong();
            if (fresh(s, now, MEMO_MS)) return s.rows();
            List<Map<String, Object>> rows = readLive(now);
            snapshot = new Snapshot(now, rows);
            return rows;
        } finally {
            readLock.unlock();
        }
    }

    private static boolean fresh(Snapshot s, long now, long maxAgeMs) {
        return s != null && now >= s.atMs() && now - s.atMs() < maxAgeMs;
    }

    /** {@code '*'} canlıysa o kuşağın zincirleri; değilse ya da okunamazsa null. */
    List<Map<String, Object>> readLive(long now) {
        if (jdbc == null) return null;
        try {
            List<Object[]> gen = jdbc.query(SQL_GENERATION,
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getLong(3)}, EvalStateKeys.GENERATION_ROW);
            if (gen.isEmpty()) return null;
            long g = (Long) gen.get(0)[0];
            String writer = (String) gen.get(0)[1];
            long updatedMs = (Long) gen.get(0)[2];
            if (g <= 0 || writer == null || now - updatedMs > LIVE_MS) return null;
            List<Map<String, Object>> rows = jdbc.query(SQL_CHAINS, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("alert_type", rs.getString("alert_type"));
                m.put("domain", rs.getString("domain"));
                m.put("detail", rs.getString("detail"));
                m.put("attempt", rs.getInt("attempt"));
                m.put("total_attempts", rs.getInt("total_attempts"));
                m.put("started_at", rs.getString("first_failure_at"));
                long next = rs.getLong("next_attempt_ms");
                m.put("next_attempt_at", rs.wasNull() ? null : ISO.format(Instant.ofEpochMilli(next)));
                return Collections.unmodifiableMap(m);
            }, g, writer, now - CHAIN_ROW_MAX_AGE_MS);
            return List.copyOf(rows);
        } catch (BadSqlGrammarException e) {
            log.debug("Teyit zinciri tablosu henüz yok — liste bellekten: {}", e.toString());
            return null;
        } catch (RuntimeException e) {
            long t = System.currentTimeMillis();
            long last = lastWarnMs.get();
            if (t - last >= WARN_EVERY_MS && lastWarnMs.compareAndSet(last, t)) {
                log.warn("Süren teyit zincirleri tablodan okunamadı — liste bu pod'un belleğinden gösteriliyor: {}",
                        e.getClass().getSimpleName());
            }
            return null;
        }
    }
}
