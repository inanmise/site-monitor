package com.certmonitor.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class HttpMetricsService {

    private static final int MAX_BUCKETS = 1440; // 24 h × 60 min
    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private volatile MinuteBucket current = new MinuteBucket(minuteKey());
    private final Deque<Map<String, Object>> history = new ArrayDeque<>(MAX_BUCKETS + 1);

    // ── Called by interceptor (many threads) ─────────────────────────────────

    public void record(int status, long durationMs) {
        String key = minuteKey();
        MinuteBucket b = current;
        if (!b.key.equals(key)) {
            synchronized (this) {
                if (!current.key.equals(key)) {
                    finalize(current);
                    current = new MinuteBucket(key);
                }
            }
            b = current;
        }
        b.add(status, durationMs);
    }

    // ── Rotate every minute to emit zero-count buckets during quiet periods ──

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public synchronized void rotate() {
        String key = minuteKey();
        if (!current.key.equals(key)) {
            finalize(current);
            current = new MinuteBucket(key);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public synchronized List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> out = new ArrayList<>(history);
        Map<String, Object> cur = current.snapshot();
        if ((long) cur.get("count") > 0) out.add(cur);
        return out;
    }

    public synchronized Map<String, Object> getSummary() {
        List<Map<String, Object>> all = getHistory();
        long totalReqs   = all.stream().mapToLong(b -> num(b, "count")).sum();
        long totalErrors = all.stream().mapToLong(b -> num(b, "errors")).sum();
        long totalMs     = all.stream().mapToLong(b -> num(b, "sum_ms")).sum();
        long maxMs       = all.stream().mapToLong(b -> num(b, "max_ms")).max().orElse(0);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total_requests", totalReqs);
        s.put("total_errors",   totalErrors);
        s.put("error_rate_pct", totalReqs > 0 ? Math.round(totalErrors * 1000.0 / totalReqs) / 10.0 : 0.0);
        s.put("avg_ms",  totalReqs > 0 ? totalMs / totalReqs : 0);
        s.put("max_ms",  maxMs);
        s.put("buckets", all.size());
        return s;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private synchronized void finalize(MinuteBucket b) {
        history.addLast(b.snapshot());
        while (history.size() > MAX_BUCKETS) history.pollFirst();
    }

    private static String minuteKey() {
        // Round down to the current minute
        Instant now = Instant.now();
        long epochSec = now.getEpochSecond();
        return MINUTE_FMT.format(Instant.ofEpochSecond(epochSec - (epochSec % 60)));
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // ── Inner: per-minute accumulator ─────────────────────────────────────────

    private static final class MinuteBucket {
        final String     key;
        final AtomicLong count  = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong sumMs  = new AtomicLong();
        final AtomicLong maxMs  = new AtomicLong();

        MinuteBucket(String key) { this.key = key; }

        void add(int status, long ms) {
            count.incrementAndGet();
            if (status >= 400) errors.incrementAndGet();
            sumMs.addAndGet(ms);
            long prev;
            do { prev = maxMs.get(); } while (ms > prev && !maxMs.compareAndSet(prev, ms));
        }

        Map<String, Object> snapshot() {
            long c = count.get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts",     key);
            m.put("count",  c);
            m.put("errors", errors.get());
            m.put("sum_ms", sumMs.get());
            m.put("avg_ms", c > 0 ? sumMs.get() / c : 0L);
            m.put("max_ms", maxMs.get());
            return m;
        }
    }
}
