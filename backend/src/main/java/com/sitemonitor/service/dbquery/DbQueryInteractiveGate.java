package com.sitemonitor.service.dbquery;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * Etkileşimli sorgu denetimi uçlarının yük kapısı (dbq2 inceleme O3, 2026-11-05) — {@code POST …/dbquery/sql-check} ve
 * {@code …/sql-fix}. İkisi de veritabanına gitmez ama {@link DbQuerySqlGuard}'ın süreç geneli ayrıştırma iznini
 * ({@code max(2, CPU)}) ve önbelleğini zamanlanmış çalıştırmalarla PAYLAŞIR; zamanlanmış çalıştırma izni 3 sn'de alamazsa
 * {@code DB_BUSY} ile atlanır. Kapı (pod belleği, yük sınırı — güvenlik sınırı değil):
 * <ul>
 *   <li>eşzamanlılık: en çok {@link #capacity()} = ayrıştırma izni − 1 etkileşimli istek aynı anda denetimdedir; her
 *       istek ayrıştırmalarını SIRAYLA yapar, yani en az bir ayrıştırma izni her zaman zamanlanmış çalıştırmalara kalır.
 *       Kapı dolu → beklemeden red ({@code DBQUERY_BUSY});</li>
 *   <li>hız: kullanıcı başına kayan 1 dakikada {@link #CHECK_PER_MINUTE} canlı denetim / {@link #FIX_PER_MINUTE} düzeltme
 *       önerisi (öneri 6'ya kadar ayrıştırma yapar) → {@code RATE_LIMITED} + bekleme saniyesi. Reddedilen istek sayılmaz.</li>
 * </ul>
 * {@link DbQuerySqlGuard} değişmez (yalnız okunur); izin formülü onunkiyle aynı ({@code DbQueryInteractiveGateTest}
 * gerçek izin sayısıyla karşılaştırır).
 */
public final class DbQueryInteractiveGate {

    /** Kullanıcı başına dakikada canlı yazım denetimi (istemci 400 ms geciktirir — yazarken bu sınıra yaklaşılmaz). */
    public static final int CHECK_PER_MINUTE = 60;
    /** Kullanıcı başına dakikada düzeltme önerisi (her biri 6'ya kadar ayrıştırma). */
    public static final int FIX_PER_MINUTE = 20;
    /** Kapı dolu iken önerilen bekleme. */
    public static final long BUSY_RETRY_SECONDS = 1;
    public static final String CODE_RATE_LIMITED = "RATE_LIMITED", CODE_BUSY = "DBQUERY_BUSY";

    static final long WINDOW_MS = 60_000L;
    /** Kullanıcı tablosu üst sınırı (aşılırsa temizlenir — yük sınırı, kalıcı değil). */
    static final int MAX_USERS = 10_000;

    public enum Kind { CHECK, FIX }

    private static final DbQueryInteractiveGate SHARED = new DbQueryInteractiveGate(defaultCapacity(), System::currentTimeMillis);

    private final int capacity;
    private final Semaphore permits;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> starts = new ConcurrentHashMap<>();

    public DbQueryInteractiveGate(int capacity, LongSupplier clock) {
        this.capacity = Math.max(1, capacity);
        this.permits = new Semaphore(this.capacity);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Pod genelinde tek kapı. */
    public static DbQueryInteractiveGate shared() {
        return SHARED;
    }

    /** {@link DbQuerySqlGuard}'ın ayrıştırma izni sayısı (aynı formül). */
    static int guardParsePermits() {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** Etkileşimli eşzamanlılık: ayrıştırma izni − 1 (en az 1). */
    static int defaultCapacity() {
        return Math.max(1, guardParsePermits() - 1);
    }

    public int capacity() {
        return capacity;
    }

    /**
     * @param ticket          kabul edildiyse kapatılacak bilet (try-with-resources), reddedildiyse null
     * @param refusedCode     {@link #CODE_RATE_LIMITED} | {@link #CODE_BUSY}
     * @param retryAfterSeconds önerilen bekleme
     */
    public record Decision(Ticket ticket, String refusedCode, long retryAfterSeconds) {
        public boolean admitted() { return ticket != null; }
    }

    /** Kapı bileti — {@link #close()} izni bırakır (bir kez). */
    public final class Ticket implements AutoCloseable {
        private boolean closed;

        private Ticket() {}

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            permits.release();
        }
    }

    /** Hız sınırı + eşzamanlılık; beklemez. */
    public Decision tryEnter(String user, Kind kind) {
        String key = (user == null || user.isBlank() ? "?" : user) + '|' + (kind == null ? Kind.CHECK : kind);
        int limit = kind == Kind.FIX ? FIX_PER_MINUTE : CHECK_PER_MINUTE;
        long now = clock.getAsLong();
        if (starts.size() > MAX_USERS) starts.clear();
        ArrayDeque<Long> q = starts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
            if (q.size() >= limit) {
                long wait = Math.max(1, (WINDOW_MS - (now - q.peekFirst()) + 999) / 1000);
                return new Decision(null, CODE_RATE_LIMITED, wait);
            }
            if (!permits.tryAcquire()) return new Decision(null, CODE_BUSY, BUSY_RETRY_SECONDS);
            q.addLast(now);
        }
        return new Decision(new Ticket(), null, 0);
    }
}
