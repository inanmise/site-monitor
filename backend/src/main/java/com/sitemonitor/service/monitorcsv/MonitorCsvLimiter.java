package com.sitemonitor.service.monitorcsv;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * İzleme CSV uçlarının yük sınırı (2026-11-19, pod belleği — güvenlik değil YÜK sınırı: içe aktarım her satırda gerçek
 * kayıt yolunu çalıştırır). Kişi başına aynı anda TEK içe aktarım (önizleme dahil) ve dakikada en çok
 * {@link #EXPORTS_PER_MINUTE} dışa aktarım. Haritalar sınırlı: {@link #MAX_KEYS} kişiyi aşınca eski kayıtlar budanır.
 */
public final class MonitorCsvLimiter {

    public static final int EXPORTS_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_KEYS = 10_000;

    private final LongSupplier clock;
    private final Map<String, Boolean> importing = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> exports = new ConcurrentHashMap<>();

    public MonitorCsvLimiter() {
        this(System::currentTimeMillis);
    }

    MonitorCsvLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** İçe aktarım başlatılabildiyse true — bitince {@link #endImport} ZORUNLU (finally). */
    public boolean tryStartImport(String user) {
        return importing.putIfAbsent(key(user), Boolean.TRUE) == null;
    }

    public void endImport(String user) {
        importing.remove(key(user));
    }

    /** Dışa aktarım hakkı: izin verildiyse 0, değilse yeniden denemeye kalan saniye (≥ 1). */
    public long tryExport(String user) {
        long now = clock.getAsLong();
        if (exports.size() > MAX_KEYS) prune(now);
        Deque<Long> q = exports.computeIfAbsent(key(user), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
            if (q.size() >= EXPORTS_PER_MINUTE) {
                long wait = WINDOW_MS - (now - q.peekFirst());
                return Math.max(1, (wait + 999) / 1000);
            }
            q.addLast(now);
            return 0;
        }
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = exports.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> q = it.next().getValue();
            synchronized (q) {
                Long last = q.peekLast();
                if (last == null || now - last >= WINDOW_MS) it.remove();
            }
        }
    }

    private static String key(String user) {
        return user == null || user.isBlank() ? "-" : user.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
