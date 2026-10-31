package com.sitemonitor.service.lockout;

import com.sitemonitor.service.schema.DeferredIndexBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Pod'lar arası ORTAK oran sınırı (2026-10-23, çok pod Sürüm 2a — MP-31). Tablo {@code rate_limit_counters}: anahtar başına
 * sabit pencere ({@link Limit#windowMs()}), pencere içinde {@link Limit#max()} denemeye ulaşınca {@link Limit#blockMs()}
 * kadar blok. Algoritma 20.165.1'in bellek içi giriş IP sınırıyla AYNI ({@code AuthController} haritaları): pencere ilk
 * denemeyle başlar ve {@code > windowMs} sonra sıfırlanır; {@code max}. deneme bloğu yazar; bloğu dolmuş satırın sayımı
 * sıfırdan başlar. Fark yalnız durumun YERİ: veritabanında, tüm pod'lar aynı sayımı ve aynı kalan süreyi görür (5 + 5
 * hata iki pod'da → 11. istek hangisine düşerse düşsün 429).
 *
 * <p><b>Yazım</b> tek satır, koşullu ve atomik. PostgreSQL: {@code INSERT … ON CONFLICT (limit_key) DO UPDATE … RETURNING}
 * (tek gidiş-dönüş; SET ifadeleri eski satırla değerlendirilir). Diğer (H2, testler) ya da türü okunamayan veritabanı:
 * koşullu UPDATE → yoksa {@code INSERT … ON CONFLICT DO NOTHING} → yarış kaybedildiyse UPDATE bir kez daha (benzersizlik
 * ihlali de "satır doğdu" sayılır). Okuma = birincil anahtarla tek SELECT.
 *
 * <p><b>Veritabanı hatası</b> girişi asla düşürmez (fail-open, {@code UnknownUserLockoutService} deseni): okuma hatası "DB
 * bloğu yok", yazma hatası bu pod'un BELLEK yedeğine sayılır — yani 20.165.1 davranışı. Bellek yedeği yalnız böyle bir
 * hatada dolar; kalan süre ikisinin büyüğüdür. Veritabanısız örnek ({@link #memoryOnly()}, bean'siz dilimli testler) yalnız
 * belleği kullanır.
 *
 * <p><b>Sınırlı büyüme.</b> Satır {@code expires_at}'ten sonra hiçbir kararı etkilemez; {@link #purge()} saatlik (her pod,
 * idempotent) onları siler. Bellek haritası {@link #MEMORY_MAX} ile sınırlı.
 */
@Slf4j
@Service
public class RateLimitCounterService {

    public static final String TABLE = "rate_limit_counters";
    /** Anahtar sütun genişliği. */
    public static final int KEY_MAX = 120;
    /** Bellek yedeğinin sert üst sınırı (aşılınca önce süresi dolanlar, yine fazlaysa hepsi atılır). */
    static final int MEMORY_MAX = 100_000;

    /** Sınır tanımı: pencere (ms), eşik (deneme), blok süresi (ms). */
    public record Limit(long windowMs, int max, long blockMs) {
        public Limit {
            windowMs = Math.max(1L, windowMs);
            max = Math.max(1, max);
            blockMs = Math.max(0L, blockMs);
        }
        long expiresAt(long now) { return now + Math.max(windowMs, blockMs); }
    }

    /** Bir yazımın sonucu: penceredeki deneme sayısı ve blok bitişi (0 = blok yok). {@code memory} = yedeğe yazıldı. */
    public record State(int attempts, long blockedUntil, boolean memory) {}

    private static final String RESET =
            "((r.blocked_until > 0 AND r.blocked_until <= :now) OR (:now - r.window_start > :window))";

    static final String SQL_SELECT = "SELECT blocked_until FROM " + TABLE + " WHERE limit_key = ?";

    /** H2 / bilinmeyen tür: satır varsa sayımı ilerletir (SET ifadeleri ESKİ satırla değerlendirilir). */
    static final String SQL_UPDATE = "UPDATE " + TABLE + " r SET "
            + "window_start = CASE WHEN " + RESET + " THEN :now ELSE r.window_start END, "
            + "attempts = CASE WHEN " + RESET + " THEN 1 ELSE r.attempts + 1 END, "
            + "blocked_until = CASE WHEN (CASE WHEN " + RESET + " THEN 1 ELSE r.attempts + 1 END) >= :max THEN :blockUntil "
            + "WHEN " + RESET + " THEN 0 ELSE r.blocked_until END, "
            + "expires_at = :expires, updated_at = :now "
            + "WHERE r.limit_key = :key";

    static final String SQL_INSERT = "INSERT INTO " + TABLE
            + " (limit_key, window_start, attempts, blocked_until, expires_at, updated_at) "
            + "VALUES (:key, :now, 1, :firstBlock, :expires, :now) ON CONFLICT DO NOTHING";

    /** PostgreSQL: tek ifadelik atomik upsert — çakışmada ESKİ satır {@code r} takma adıyla okunur. */
    static final String SQL_UPSERT_PG = "INSERT INTO " + TABLE + " AS r"
            + " (limit_key, window_start, attempts, blocked_until, expires_at, updated_at) "
            + "VALUES (:key, :now, 1, :firstBlock, :expires, :now) "
            + "ON CONFLICT (limit_key) DO UPDATE SET "
            + "window_start = CASE WHEN " + RESET + " THEN :now ELSE r.window_start END, "
            + "attempts = CASE WHEN " + RESET + " THEN 1 ELSE r.attempts + 1 END, "
            + "blocked_until = CASE WHEN (CASE WHEN " + RESET + " THEN 1 ELSE r.attempts + 1 END) >= :max THEN :blockUntil "
            + "WHEN " + RESET + " THEN 0 ELSE r.blocked_until END, "
            + "expires_at = :expires, updated_at = :now "
            + "RETURNING attempts, blocked_until";

    static final String SQL_READ_STATE = "SELECT attempts, blocked_until FROM " + TABLE + " WHERE limit_key = ?";
    static final String SQL_DELETE = "DELETE FROM " + TABLE + " WHERE limit_key = ?";
    static final String SQL_PURGE = "DELETE FROM " + TABLE + " WHERE expires_at < ?";
    /**
     * Bir denemeyi geri verir (2026-10-31, dbquery D-5): sayım bir azalır; sayım eşiğin altına inerse blok kalkar (geri verilen
     * deneme bloğu yazan denemeydiyse). SET ifadeleri ESKİ satırla değerlendirilir; sayım 0'ın altına inmez.
     */
    static final String SQL_RELEASE = "UPDATE " + TABLE + " SET attempts = attempts - 1, "
            + "blocked_until = CASE WHEN attempts - 1 < ? THEN 0 ELSE blocked_until END, updated_at = ? "
            + "WHERE limit_key = ? AND attempts > 0";

    /** {@code null} = yalnız bellek. */
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final LongSupplier clock;
    /** Üç durumlu tür: TRUE PostgreSQL, FALSE başka, null henüz bilinmiyor (okunamadı → taşınabilir yol). */
    private volatile Boolean postgres;

    /** Bellek yedeği (ve veritabanısız örnekte asıl depo). */
    private final ConcurrentHashMap<String, MemWindow> memory = new ConcurrentHashMap<>();

    private static final class MemWindow {
        long windowStart;
        int attempts;
        long blockedUntil;
        long expiresAt;
    }

    @Autowired
    public RateLimitCounterService(JdbcTemplate jdbc) {
        this(jdbc, System::currentTimeMillis);
    }

    /** Test: saat verilir; {@code jdbc == null} → yalnız bellek. */
    RateLimitCounterService(JdbcTemplate jdbc, LongSupplier clock) {
        this.jdbc = jdbc;
        this.named = jdbc == null ? null : new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Veritabanısız örnek — 20.165.1'in pod belleğindeki sınırı (bean'siz bağlam yedeği). */
    public static RateLimitCounterService memoryOnly() {
        return new RateLimitCounterService(null, System::currentTimeMillis);
    }

    /** Test kancası: türü sabitler (null = bilinmiyor → taşınabilir yol). */
    void setPostgres(Boolean p) { this.postgres = p; }

    /**
     * Kanonik anahtar: {@code <kapsam>:<değer>}; değer boşluklardan arındırılır. Sütuna sığmayan (istemci verili, ör. uzun
     * bir X-Forwarded-For) değer SHA-256 özetine indirgenir — aynı değer hep aynı anahtar, satır boyu sınırlı.
     */
    public static String key(String scope, String value) {
        String s = scope == null ? "" : scope;
        String v = value == null ? "?" : value.strip();
        if (v.isEmpty()) v = "?";
        String k = s + ":" + v;
        if (k.length() <= KEY_MAX) return k;
        return s + ":#" + sha256(v).substring(0, 40);
    }

    /** Kalan blok süresi (sn, yukarı yuvarlanır); blok yoksa 0. Veritabanı ve bellek yedeğinin BÜYÜĞÜ. */
    public long blockedSeconds(String key) {
        long now = clock.getAsLong();
        long dbUntil = 0L;
        if (jdbc != null && key != null) {
            try {
                Long u = jdbc.query(SQL_SELECT, rs -> rs.next() ? rs.getLong(1) : 0L, key);
                dbUntil = u == null ? 0L : u;
            } catch (Exception e) {
                log.warn("Oran sınırı durumu okunamadı — bellek yedeği: {}", e.getMessage());
            }
        }
        return remainingSeconds(Math.max(dbUntil, memoryBlockedUntil(key, now)), now);
    }

    /**
     * Bir deneme sayar (başarısız giriş vb.). Veritabanına yazılamazsa bellek yedeğine sayılır — çağıran asla istisna almaz.
     *
     * @return yazımdan sonraki durum (test / günlük için)
     */
    public State hit(String key, Limit limit) {
        long now = clock.getAsLong();
        if (key == null || limit == null) return new State(0, 0L, true);
        if (jdbc != null) {
            try {
                State s = Boolean.TRUE.equals(postgres()) ? upsertPostgres(key, limit, now) : upsertPortable(key, limit, now);
                if (s != null) return s;
            } catch (Exception e) {
                log.warn("Oran sınırı sayacı yazılamadı — bellek yedeği (bu pod): {}", e.getMessage());
            }
        }
        return hitMemory(key, limit, now);
    }

    /**
     * {@link #hit} ile sayılmış BİR denemeyi geri verir (2026-10-31, dbquery D-5: deneme hiç yürütülmedi — motor meşguldü).
     * Deneme nereye sayıldıysa ({@code taken.memory()}: bellek yedeği, değilse veritabanı) yalnız orada düşülür; sayım eşiğin
     * altına inerse blok kalkar. {@code taken == null} ya da anahtar / sınır yoksa hiçbir şey yapılmaz. Hata yutulur (geri
     * verilemeyen deneme yalnız fazladan sayılmış olur — sınır asla gevşemez).
     */
    public void release(String key, Limit limit, State taken) {
        if (key == null || limit == null || taken == null) return;
        if (taken.memory()) {
            memory.computeIfPresent(key, (k, x) -> {
                if (x.attempts > 0) {
                    x.attempts--;
                    if (x.attempts < limit.max()) x.blockedUntil = 0L;
                }
                return x;
            });
            return;
        }
        if (jdbc == null) return;
        try {
            jdbc.update(SQL_RELEASE, limit.max(), clock.getAsLong(), key);
        } catch (Exception e) {
            log.warn("Oran sınırı denemesi geri verilemedi: {}", e.getMessage());
        }
    }

    /** Sayımı ve bloğu siler (ör. gerçek oturum kuruldu). Hata yutulur. */
    public void clear(String key) {
        if (key == null) return;
        memory.remove(key);
        if (jdbc == null) return;
        try {
            jdbc.update(SQL_DELETE, key);
        } catch (Exception e) {
            log.warn("Oran sınırı sayacı silinemedi: {}", e.getMessage());
        }
    }

    /**
     * Saatlik temizlik (her pod; silmeler idempotent): süresi dolmuş satırlar + bellek yedeğinin süresi dolmuş kayıtları.
     *
     * @return silinen veritabanı satırı
     */
    @Scheduled(fixedDelayString = "${site.monitor.rate-limit.cleanup-interval-ms:3600000}",
               initialDelayString = "${site.monitor.rate-limit.cleanup-initial-delay-ms:600000}")
    public int purge() {
        long now = clock.getAsLong();
        pruneMemory(now);
        if (jdbc == null) return 0;
        try {
            int n = jdbc.update(SQL_PURGE, now);
            if (n > 0) log.debug("Oran sınırı sayaçları temizlendi: {} satır", n);
            return n;
        } catch (Exception e) {
            log.warn("Oran sınırı sayaçları temizlenemedi: {}", e.getMessage());
            return 0;
        }
    }

    /** Bellek yedeğinde süresi dolanları atar (yalnız bellek; {@link #purge()} de çağırır). */
    public void pruneMemory() {
        pruneMemory(clock.getAsLong());
    }

    // ── Veritabanı yolları ───────────────────────────────────────────────────────────────────────────────────────

    private MapSqlParameterSource params(String key, Limit limit, long now) {
        return new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("now", now)
                .addValue("window", limit.windowMs())
                .addValue("max", limit.max())
                .addValue("blockUntil", now + limit.blockMs())
                .addValue("firstBlock", 1 >= limit.max() ? now + limit.blockMs() : 0L)
                .addValue("expires", limit.expiresAt(now));
    }

    private State upsertPostgres(String key, Limit limit, long now) {
        List<State> rows = named.query(SQL_UPSERT_PG, params(key, limit, now),
                (rs, i) -> new State(rs.getInt(1), rs.getLong(2), false));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private State upsertPortable(String key, Limit limit, long now) {
        MapSqlParameterSource p = params(key, limit, now);
        if (named.update(SQL_UPDATE, p) == 1) return readState(key);
        try {
            if (named.update(SQL_INSERT, p) == 1) return new State(1, 1 >= limit.max() ? now + limit.blockMs() : 0L, false);
        } catch (DuplicateKeyException race) {
            // Başka pod aynı anda ilk satırı yazdı — güncellemeye geç
        }
        if (named.update(SQL_UPDATE, p) == 1) return readState(key);
        throw new IllegalStateException("oran sınırı satırı yazılamadı: " + key);
    }

    private State readState(String key) {
        List<State> rows = jdbc.query(SQL_READ_STATE, (rs, i) -> new State(rs.getInt(1), rs.getLong(2), false), key);
        return rows.isEmpty() ? new State(0, 0L, false) : rows.getFirst();
    }

    private Boolean postgres() {
        Boolean p = postgres;
        if (p != null) return p;
        p = DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return p;
    }

    // ── Bellek yedeği (20.165.1 algoritması) ─────────────────────────────────────────────────────────────────────

    private State hitMemory(String key, Limit limit, long now) {
        if (memory.size() > MEMORY_MAX) {
            pruneMemory(now);
            if (memory.size() > MEMORY_MAX) {
                log.warn("Oran sınırı bellek yedeği üst sınırı ({}) aşıldı — temizlendi", MEMORY_MAX);
                memory.clear();
            }
        }
        State[] out = new State[1];
        memory.compute(key, (k, cur) -> {
            MemWindow x = cur;
            if (x == null) {
                x = new MemWindow();
                x.windowStart = now;
            }
            boolean blockOver = x.blockedUntil > 0 && x.blockedUntil <= now;
            if (blockOver || now - x.windowStart > limit.windowMs()) {
                x.attempts = 0;
                x.windowStart = now;
                x.blockedUntil = 0L;
            }
            x.attempts++;
            if (x.attempts >= limit.max()) x.blockedUntil = now + limit.blockMs();
            x.expiresAt = limit.expiresAt(now);
            out[0] = new State(x.attempts, x.blockedUntil, true);
            return x;
        });
        return out[0];
    }

    private long memoryBlockedUntil(String key, long now) {
        if (key == null) return 0L;
        MemWindow w = memory.get(key);
        if (w == null || w.blockedUntil == 0L) return 0L;
        if (w.blockedUntil <= now) {
            // Blok doldu — 20.165.1 gibi sayım da sıfırlanır (sonraki deneme yeni pencere açar)
            memory.remove(key, w);
            return 0L;
        }
        return w.blockedUntil;
    }

    private void pruneMemory(long now) {
        // expires_at ≥ blok bitişi (pencere ve bloğun büyüğü) → süresi dolan kayıt hiçbir kararı etkilemez
        memory.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    static long remainingSeconds(long until, long now) {
        if (until <= 0L) return 0L;
        long r = (until - now + 999L) / 1000L;   // yukarı yuvarla
        return r <= 0L ? 0L : r;
    }

    private static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(v.hashCode()) + "0".repeat(40);
        }
    }
}
