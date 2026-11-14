package com.sitemonitor.service.page.scan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * Kaynak analizi görsel uçlarının YÜK sınırı (2026-11-14, görsel aracı) — pod belleğinde; güvenlik sınırı değildir (uçların
 * kapısı izin + takım kapsamıdır; N pod'da en kötü N kat — CLAUDE.md: yük sınırları pod başına kalabilir).
 *
 * <p>Üç sınır tek kararda: kişi başına dakikada en çok {@code perMinute} istek (kayan 60 sn), kişi başına aynı anda en çok
 * {@code perUser} ve pod genelinde aynı anda en çok {@code perPod} iş. Reddedilen istek SAYILMAZ (dakika penceresine ve
 * uçuştaki sayıya yalnız kabul edilen girer). Alınan hak {@link Ticket#close()} ile bırakılır — çağıran {@code finally}'de
 * kapatır; birden çok kapatma zararsızdır.
 *
 * <p>Bellek sınırlı: en çok {@value #MAX_USERS} kişi izlenir; dolunca boşta kalan (uçuşta işi olmayan, penceresi boşalmış)
 * kayıtlar budanır, yine doluysa yeni kişiler tek ortak kovayı paylaşır (harita büyümez). Hak, kişinin durum nesnesine doğrudan
 * bağlıdır (budama uçuştaki bir hakkı sızdıramaz).
 */
public final class ScanImageLimiter {

    static final long WINDOW_MS = 60_000L;
    static final int MAX_USERS = 10_000;
    /** Harita doluyken yeni kişilerin paylaştığı kova (kişi anahtarları {@code u:} / {@code n:} / {@code s:} ile başlar). */
    static final String OVERFLOW_KEY = "*";

    private final LongSupplier clock;
    private final int perMinute;
    private final int perUser;
    private final Semaphore pod;
    private final Map<String, State> users = new ConcurrentHashMap<>();

    private static final class State {
        final Deque<Long> starts = new ArrayDeque<>();
        int inFlight;
    }

    /**
     * @param perMinute kişi başına dakikadaki en çok kabul
     * @param perUser   kişi başına aynı andaki en çok iş (≤ 0 = sınırsız)
     * @param perPod    pod genelinde aynı andaki en çok iş (≤ 0 = sınırsız)
     */
    public ScanImageLimiter(int perMinute, int perUser, int perPod, LongSupplier clock) {
        this.perMinute = Math.max(1, perMinute);
        this.perUser = perUser <= 0 ? Integer.MAX_VALUE : perUser;
        this.pod = perPod <= 0 ? null : new Semaphore(perPod);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * Karar: kabul ({@code ticket} ≠ null), dakika sınırı ({@code retryAfterSeconds} &gt; 0, {@code busy} false) ya da eşzamanlılık
     * sınırı ({@code busy} true — kişinin ya da pod'un uçuştaki işi dolu).
     */
    public record Decision(Ticket ticket, long retryAfterSeconds, boolean busy) {
        public boolean allowed() {
            return ticket != null;
        }
    }

    /** Alınan hak: uçuştaki sayıyı ve pod yuvasını tutar; {@link #close()} bırakır. */
    public final class Ticket implements AutoCloseable {
        private final State state;
        private final long stamp;
        private boolean closed;

        private Ticket(State state, long stamp) {
            this.state = state;
            this.stamp = stamp;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            synchronized (state) {
                if (state.inFlight > 0) state.inFlight--;
            }
            if (pod != null) pod.release();
        }

        /**
         * Kabul GERİ alınır: hak bırakılır VE dakika penceresindeki damgası silinir — sonraki bir denetimin (ör. kaynak
         * sunucu sınırı) reddettiği istek kişinin dakika sayısına girmez. Kapatılmış hakta yalnız ilk çağrı damgayı siler.
         */
        public void refund() {
            boolean first;
            synchronized (this) {
                first = !closed;
            }
            if (first) {
                synchronized (state) {
                    state.starts.removeLastOccurrence(stamp);
                }
            }
            close();
        }
    }

    public Decision tryAcquire(String user) {
        String key = user == null || user.isBlank() ? "?" : user;
        long now = clock.getAsLong();
        if (users.size() >= MAX_USERS && !users.containsKey(key)) {
            prune(now);
            // Budamadan sonra da doluysa (10 000 etkin kişi — beklenmez) yeni kişiler ORTAK kovayı paylaşır: harita büyümez
            if (users.size() >= MAX_USERS) key = OVERFLOW_KEY;
        }
        State s = users.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            // Saat geri alınmışsa (NTP adımı, VM geri yükleme) eski damgalar "gelecekte" kalır: pencere sıfırlanır —
            // kişi saat farkı kadar kilitli kalmaz
            if (!s.starts.isEmpty() && now < s.starts.peekLast()) s.starts.clear();
            while (!s.starts.isEmpty() && now - s.starts.peekFirst() >= WINDOW_MS) s.starts.pollFirst();
            if (s.starts.size() >= perMinute) {
                long wait = Math.min(WINDOW_MS, WINDOW_MS - (now - s.starts.peekFirst()));
                return new Decision(null, Math.max(1, (wait + 999) / 1000), false);
            }
            if (s.inFlight >= perUser) return new Decision(null, 0, true);
            if (pod != null && !pod.tryAcquire()) return new Decision(null, 0, true);
            s.starts.addLast(now);
            s.inFlight++;
            return new Decision(new Ticket(s, now), 0, false);
        }
    }

    /** Testler için: pod'un boştaki yuvası (sınırsızsa -1). */
    int freePodSlots() {
        return pod == null ? -1 : pod.availablePermits();
    }

    /** Testler için: kişinin dakika penceresindeki kabul sayısı (kayıt yoksa 0). */
    int recentStarts(String user) {
        State s = users.get(user);
        if (s == null) return 0;
        synchronized (s) {
            return s.starts.size();
        }
    }

    /** Testler için: izlenen kişi sayısı. */
    int trackedUsers() {
        return users.size();
    }

    private void prune(long now) {
        users.entrySet().removeIf(e -> {
            State s = e.getValue();
            synchronized (s) {
                return s.inFlight == 0 && (s.starts.isEmpty() || now - s.starts.peekLast() >= WINDOW_MS);
            }
        });
    }
}
