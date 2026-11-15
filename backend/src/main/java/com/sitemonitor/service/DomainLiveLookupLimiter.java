package com.sitemonitor.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Alan adı Kayıt sekmesinin CANLI sorgusu ({@code GET /domain/{id}/registration?live=true}) için YÜK sınırı (2026-11-15, A4):
 * kullanıcı başına ve izleme başına dakikada en çok {@link #PER_MINUTE}, pod başına aynı anda en çok {@link #MAX_IN_FLIGHT}.
 * Her canlı sorgu dışarıya RDAP / WHOIS isteği atar (registry'lerin kendi oran sınırı var; 429 alan bir pod zamanlanmış turda
 * da veri alamaz). Pod belleğinde tutulur — güvenlik değil yük sınırıdır, N pod'da en kötü N kat (CLAUDE.md kuralı).
 *
 * <p>Bellek sınırlı: en çok {@link #MAX_KEYS} anahtar; dolunca boş kayıtlar budanır.
 */
public final class DomainLiveLookupLimiter {

    public static final int PER_MINUTE = 6;
    public static final int MAX_IN_FLIGHT = 2;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_KEYS = 20_000;

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
        private boolean closed;

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            inFlight.decrementAndGet();
        }
    }

    /** Kullanıcı VE izleme penceresi birlikte denetlenir; reddedilen istek hiçbir pencereye yazılmaz. */
    public Decision tryAcquire(String user, Long monitorId) {
        long now = clock.getAsLong();
        if (windows.size() >= MAX_KEYS) prune(now);
        String uk = "u:" + (user == null || user.isBlank() ? "?" : user);
        String mk = "m:" + monitorId;
        Deque<Long> u = windows.computeIfAbsent(uk, k -> new ArrayDeque<>());
        Deque<Long> m = windows.computeIfAbsent(mk, k -> new ArrayDeque<>());
        // Kilit sırası sabit (kullanıcı → izleme): iki istek kilitlenmez.
        synchronized (u) {
            synchronized (m) {
                trim(u, now);
                trim(m, now);
                long wait = Math.max(waitMs(u, now), waitMs(m, now));
                if (wait > 0) return new Decision(null, Math.max(1, (wait + 999) / 1000), false);
                if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
                    inFlight.decrementAndGet();
                    return new Decision(null, 5, true);
                }
                u.addLast(now);
                m.addLast(now);
                return new Decision(new Ticket(), 0, false);
            }
        }
    }

    int inFlight() { return inFlight.get(); }

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
