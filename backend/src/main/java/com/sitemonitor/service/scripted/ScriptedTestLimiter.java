package com.sitemonitor.service.scripted;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * "Test Çalıştır" ({@code POST /api/monitoring/scripted/test}) kullanıcı başına sınırı (2026-10-31, k6 inceleme A4 / G3):
 * aynı anda EN ÇOK 1 test ve dakikada en çok {@link #PER_MINUTE}. Pod belleğinde tutulur — bu bir YÜK sınırıdır (her test
 * bir k6 süreci başlatır ve elle kotadan bir izin tutar), güvenlik sınırı değil; N pod'da en kötü N kat (CLAUDE.md: yük
 * sınırları pod başına kalabilir).
 *
 * <p>Bellek sınırlı: en çok {@link #MAX_USERS} kullanıcı izlenir; dolunca en eski boş kayıtlar budanır.
 */
public final class ScriptedTestLimiter {

    public static final int PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_USERS = 10_000;

    private final LongSupplier clock;
    private final Map<String, State> users = new ConcurrentHashMap<>();

    private static final class State {
        final Deque<Long> starts = new ArrayDeque<>();
        boolean running;
    }

    public ScriptedTestLimiter() {
        this(System::currentTimeMillis);
    }

    public ScriptedTestLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Sonuç: izin verildi ({@code ticket} ≠ null — iş bitince {@link Ticket#close()}) ya da reddedildi
     * ({@code retryAfterSeconds}, {@code concurrent} = aynı kullanıcının testi hâlâ sürüyor).
     */
    public record Decision(Ticket ticket, long retryAfterSeconds, boolean concurrent) {
        public boolean allowed() { return ticket != null; }
    }

    /** Alınan test hakkı — {@link #close()} eşzamanlılık yuvasını bırakır (birden çok çağrı zararsız). */
    public final class Ticket implements AutoCloseable {
        private final String user;
        private boolean closed;

        private Ticket(String user) { this.user = user; }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            State s = users.get(user);
            if (s != null) synchronized (s) { s.running = false; }
        }
    }

    public Decision tryAcquire(String user) {
        String key = user == null || user.isBlank() ? "?" : user;
        long now = clock.getAsLong();
        if (users.size() >= MAX_USERS) prune(now);
        State s = users.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            while (!s.starts.isEmpty() && now - s.starts.peekFirst() >= WINDOW_MS) s.starts.pollFirst();
            if (s.running) return new Decision(null, 5, true);
            if (s.starts.size() >= PER_MINUTE) {
                long wait = WINDOW_MS - (now - s.starts.peekFirst());
                return new Decision(null, Math.max(1, (wait + 999) / 1000), false);
            }
            s.starts.addLast(now);
            s.running = true;
            return new Decision(new Ticket(key), 0, false);
        }
    }

    private void prune(long now) {
        users.entrySet().removeIf(e -> {
            State s = e.getValue();
            synchronized (s) {
                return !s.running && (s.starts.isEmpty() || now - s.starts.peekLast() >= WINDOW_MS);
            }
        });
    }
}
