package com.sitemonitor.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Alan adı kaydının dışarıya RDAP / WHOIS sorgusu yapan elle yolları için YÜK sınırı (2026-11-15, A4 + M1). Pod belleğinde
 * tutulur — güvenlik değil yük sınırıdır, N pod'da en kötü N kat (CLAUDE.md kuralı). Her canlı sorgu dışarıya istek atar
 * (registry'lerin kendi oran sınırı var; 429 alan bir pod zamanlanmış turda da veri alamaz).
 *
 * <ul>
 *   <li>{@link #tryAcquire}: Kayıt sekmesinin CANLI sorgusu ({@code GET /domain/{id}/registration?live=true}) — kullanıcı
 *       başına VE izleme başına dakikada en çok {@link #PER_MINUTE}, pod başına aynı anda en çok {@link #MAX_IN_FLIGHT}.</li>
 *   <li>{@link #tryAcquireMonitor}: "Şimdi kontrol et" ({@code POST /domain/{id}/check}) — YALNIZ izleme penceresi (aynı
 *       pencere, iki yol birlikte sayılır). Kullanıcı penceresi ve eşzamanlılık reddi BURADA YOK: sayfanın toplu "Şimdi kontrol
 *       et"i her izleme için bir istek atar; kullanıcı penceresi 7. izlemeyi 429'a düşürürdü.</li>
 * </ul>
 *
 * <p>Bellek sınırlı: en çok {@link #MAX_KEYS} anahtar; dolunca boş kayıtlar budanır. Budama yarışı (inceleme LOW 5): budama bir
 * kaydı yalnız KENDİ kilidi altında ve boşken siler; istek kilidi aldıktan sonra haritadaki kaydın hâlâ kendisi olduğunu
 * doğrular, değilse (arada budandı) yeniden alır — en çok {@link #RETRIES} kez (sınırlı döngü).
 */
public final class DomainLiveLookupLimiter {

    public static final int PER_MINUTE = 6;
    public static final int MAX_IN_FLIGHT = 2;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_KEYS = 20_000;
    private static final int RETRIES = 3;

    private final LongSupplier clock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    public DomainLiveLookupLimiter() { this(System::currentTimeMillis); }

    public DomainLiveLookupLimiter(LongSupplier clock) { this.clock = clock; }

    /** Sonuç: izin ({@code ticket} ≠ null — iş bitince {@link Ticket#close()}) ya da red ({@code retryAfterSeconds}, {@code busy}). */
    public record Decision(Ticket ticket, long retryAfterSeconds, boolean busy) {
        public boolean allowed() { return ticket != null; }
    }

    /** Alınan sorgu hakkı — {@link #close()} pod yuvasını bırakır (birden çok çağrı zararsız). */
    public final class Ticket implements AutoCloseable {
        private final boolean counted;
        private boolean closed;

        Ticket(boolean counted) { this.counted = counted; }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (counted) inFlight.decrementAndGet();
        }
    }

    /** Kullanıcı VE izleme penceresi birlikte denetlenir + pod eşzamanlılığı; reddedilen istek hiçbir pencereye yazılmaz. */
    public Decision tryAcquire(String user, Long monitorId) {
        return acquire("u:" + (user == null || user.isBlank() ? "?" : user), "m:" + monitorId, true);
    }

    /**
     * Yalnız izleme penceresi ("Şimdi kontrol et", M1) — kullanıcı penceresi ve eşzamanlılık reddi yok.
     *
     * @return 0 = izin (pencereye yazıldı); &gt; 0 = bekleme süresi (sn)
     */
    public long tryAcquireMonitor(Long monitorId) {
        Decision d = acquire(null, "m:" + monitorId, false);
        return d.allowed() ? 0 : d.retryAfterSeconds();
    }

    private Decision acquire(String uk, String mk, boolean capInFlight) {
        long now = clock.getAsLong();
        if (windows.size() >= MAX_KEYS) prune(now);
        for (int attempt = 0; ; attempt++) {
            Deque<Long> u = uk == null ? null : windows.computeIfAbsent(uk, k -> new ArrayDeque<>());
            Deque<Long> m = windows.computeIfAbsent(mk, k -> new ArrayDeque<>());
            // Kilit sırası sabit (kullanıcı → izleme): iki istek kilitlenmez. Kullanıcısız çağrıda ilk kilit izleme kaydı (yeniden giriş).
            Object first = u != null ? u : m;
            synchronized (first) {
                synchronized (m) {
                    // Kilit alınmadan budama bu kaydı silmiş olabilir — yetim kayda yazılan istek sayılmazdı
                    boolean stale = (u != null && windows.get(uk) != u) || windows.get(mk) != m;
                    if (stale && attempt < RETRIES) continue;
                    if (u != null) trim(u, now);
                    trim(m, now);
                    long wait = Math.max(u == null ? 0 : waitMs(u, now), waitMs(m, now));
                    if (wait > 0) return new Decision(null, Math.max(1, (wait + 999) / 1000), false);
                    if (capInFlight) {
                        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
                            inFlight.decrementAndGet();
                            return new Decision(null, 5, true);
                        }
                    }
                    if (u != null) u.addLast(now);
                    m.addLast(now);
                    return new Decision(new Ticket(capInFlight), 0, false);
                }
            }
        }
    }

    int inFlight() { return inFlight.get(); }

    int keys() { return windows.size(); }

    private static void trim(Deque<Long> d, long now) {
        while (!d.isEmpty() && now - d.peekFirst() >= WINDOW_MS) d.pollFirst();
    }

    private static long waitMs(Deque<Long> d, long now) {
        return d.size() >= PER_MINUTE ? WINDOW_MS - (now - d.peekFirst()) : 0;
    }

    private void prune(long now) {
        windows.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                trim(d, now);
                return d.isEmpty();
            }
        });
    }
}
