package com.sitemonitor.service;

import com.sitemonitor.model.HttpMetricMinute;
import com.sitemonitor.repository.HttpMetricMinuteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class HttpMetricsService {

    private static final int MAX_BUCKETS = 1440; // 24 h × 60 min

    /**
     * Bir dakika penceresindeki AYRI endpoint kovası üst sınırı; aşılırsa hepsi tek
     * {@code (overflow)} kovasına katlanır. Yapısal güvence (2026-08-20 bellek denetimi):
     * endpoint anahtarı yüksek kardinaliteli bir kaynaktan beslenirse ne bellek-içi harita
     * ne de {@code http_metric_minute} tablosu sınırsız büyüyebilsin — dakika başına en çok
     * MAX_ENDPOINTS_PER_MINUTE+1 satır. Bugünkü tek besleyici olan interceptor sınırlı rota
     * şablonu kullanıyor (~92 ayrı endpoint ölçüldü), yani tavan pratikte hiç görülmemeli;
     * amaç ileride yanlışlıkla ham URI/ID besleyen bir çağıranın sessizce sızdırmaması.
     */
    private static final int MAX_ENDPOINTS_PER_MINUTE = 500;
    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Gecikme histogramı kova ÜST sınırları (ms, dahil). İndeks {@code length} = taşma (overflow) kovası. */
    static final long[] HISTOGRAM_BOUNDS =
            {1, 2, 5, 10, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2500, 5000, 10000};
    static final int HIST_LEN = HISTOGRAM_BOUNDS.length + 1; // + overflow

    static int histIndex(long ms) {
        for (int i = 0; i < HISTOGRAM_BOUNDS.length; i++) if (ms <= HISTOGRAM_BOUNDS[i]) return i;
        return HISTOGRAM_BOUNDS.length; // overflow
    }

    /** Durum kodu anahtarı: 100–599 olduğu gibi, dışı (0, 999 …) tek {@code 0} kovasına — kardinalite sınırlı. */
    static int statusKey(int status) { return status >= 100 && status <= 599 ? status : 0; }

    private volatile MinuteBucket current = new MinuteBucket(minuteKey());
    private final Deque<Map<String, Object>> history = new ArrayDeque<>(MAX_BUCKETS + 1);
    /**
     * {@link #history} ile HİZALI dakika histogramları + 24 saatlik kayan toplamları (2026-09-28): özet kartının
     * p95/p99'u her istekte 1440 diziyi yeniden toplamasın diye {@code hist24} dakika kapanırken eklenir, pencereden
     * düşen dakika çıkarılır. Bellek: 1440 × 20 long ≈ 230 KB (sabit, büyümez).
     */
    private final Deque<long[]> histHistory = new ArrayDeque<>(MAX_BUCKETS + 1);
    private final long[] hist24 = new long[HIST_LEN];

    // ── Endpoint bazlı kalıcı zaman serisi (yeni) ────────────────────────────────
    /** Repo opsiyonel: birim testte (manuel new) null kalır → kalıcılık atlanır, bellek-içi davranış sürer. */
    @Autowired(required = false)
    private HttpMetricMinuteRepository metricRepo;

    private volatile ConcurrentHashMap<String, EndpointBucket> currentEndpoints = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingMinute> pending = new ConcurrentLinkedQueue<>();

    // ── Called by interceptor (many threads) ─────────────────────────────────

    /** Geriye uyumluluk — endpoint bilinmiyorsa. */
    public void record(int status, long durationMs) { record("(other)", status, durationMs); }

    public void record(String endpoint, int status, long durationMs) {
        String key = minuteKey();
        if (!current.key.equals(key)) {
            synchronized (this) {
                if (!current.key.equals(key)) rotateTo(key);
            }
        }
        current.add(status, durationMs);                         // toplam (mini-grafikler)

        // Endpoint bazlı (kalıcı seri) — dakika içi kardinalite tavanıyla.
        // rotateTo() haritayı takas edebilir; yerel referans al ki sayım ile ekleme aynı
        // pencereye gitsin (takas olursa en fazla bir kova yanlış pencereye düşer, sızıntı olmaz).
        ConcurrentHashMap<String, EndpointBucket> eps = currentEndpoints;
        String epKey = endpoint == null ? "(other)" : endpoint;
        if (!eps.containsKey(epKey) && eps.size() >= MAX_ENDPOINTS_PER_MINUTE) epKey = "(overflow)";
        eps.computeIfAbsent(epKey, k -> new EndpointBucket()).add(status, durationMs);
    }

    // ── Rotate every minute to emit zero-count buckets during quiet periods ──

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void rotate() {
        synchronized (this) {
            String key = minuteKey();
            if (!current.key.equals(key)) rotateTo(key);
        }
        flushPending();   // DB yazımı KİLİT DIŞINDA (scheduler thread'i — istek thread'ini bloke etmez)
    }

    /** synchronized(this) içinde çağrılır: biten dakikayı kapatır, endpoint kovalarını DB kuyruğuna koyar. */
    private void rotateTo(String newKey) {
        MinuteBucket oldAgg = current;
        ConcurrentHashMap<String, EndpointBucket> oldEps = currentEndpoints;
        current = new MinuteBucket(newKey);
        currentEndpoints = new ConcurrentHashMap<>();
        finalize(oldAgg);
        if (!oldEps.isEmpty()) pending.add(new PendingMinute(oldAgg.key, oldEps));
    }

    /** Biten dakikaların endpoint kovalarını DB'ye yazar. Asla istek/scheduler akışını kırmaz. */
    private void flushPending() {
        if (metricRepo == null) { pending.clear(); return; }   // kalıcılık devre dışı (test)
        PendingMinute pm;
        while ((pm = pending.poll()) != null) {
            try {
                List<HttpMetricMinute> rows = new ArrayList<>(pm.endpoints.size());
                for (Map.Entry<String, EndpointBucket> e : pm.endpoints.entrySet()) {
                    rows.add(e.getValue().toEntity(pm.key, e.getKey()));
                }
                if (!rows.isEmpty()) metricRepo.saveAll(rows);
            } catch (Exception ex) {
                // bu dakikayı atla — metrik yazımı uygulamayı etkilemesin. Ama SESSİZ kalmasın (2026-09-28c, B3):
                // kalıcı bir yazım hatası (ör. eksik kolon) İstek Gezgini'ni sinyalsiz boş bırakıyordu → saatte en çok bir WARN.
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastFlushWarnMs >= FLUSH_WARN_INTERVAL_MS) {
                    lastFlushWarnMs = nowMs;
                    org.slf4j.LoggerFactory.getLogger(HttpMetricsService.class).warn(
                            "[HTTP-METRICS] minute rows could not be saved (skipped; next warning in 1 h): {}",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        }
    }

    /** Yutulan yazım hatası için WARN aralığı (log taşmasın). */
    private static final long FLUSH_WARN_INTERVAL_MS = 3_600_000L;
    private volatile long lastFlushWarnMs = Long.MIN_VALUE / 2;

    // ── Queries (toplam, bellek-içi — mini-grafikler) ───────────────────────────

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
        long peak        = all.stream().mapToLong(b -> num(b, "count")).max().orElse(0);

        // 24 saatlik birleşik histogram = kapanmış dakikaların kayan toplamı + süren dakika.
        long[] merged = hist24.clone();
        long[] cur = current.histCopy();
        for (int i = 0; i < merged.length; i++) merged[i] += cur[i];

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total_requests", totalReqs);
        s.put("total_errors",   totalErrors);
        s.put("error_rate_pct", totalReqs > 0 ? Math.round(totalErrors * 1000.0 / totalReqs) / 10.0 : 0.0);
        s.put("avg_ms",  totalReqs > 0 ? totalMs / totalReqs : 0);
        s.put("max_ms",  maxMs);
        s.put("buckets", all.size());
        // Ek alanlar (2026-09-28, geriye uyumlu): yüzdelikler histogramdan (sorgu servisiyle AYNI tanım), istek hızı
        // pencerenin dakika sayısına göre (boş dakikalar da sayılır — "dakikada ortalama"), tepe = en yoğun dakika.
        s.put("p50_ms",  HttpMetricsQueryService.percentile(merged, 0.50, maxMs));
        s.put("p95_ms",  HttpMetricsQueryService.percentile(merged, 0.95, maxMs));
        s.put("p99_ms",  HttpMetricsQueryService.percentile(merged, 0.99, maxMs));
        s.put("req_per_min", all.isEmpty() ? 0.0 : Math.round(totalReqs * 10.0 / all.size()) / 10.0);
        s.put("peak_req_per_min", peak);
        return s;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private synchronized void finalize(MinuteBucket b) {
        history.addLast(b.snapshot());
        long[] h = b.histCopy();
        histHistory.addLast(h);
        for (int i = 0; i < HIST_LEN; i++) hist24[i] += h[i];
        while (history.size() > MAX_BUCKETS) {
            history.pollFirst();
            long[] old = histHistory.pollFirst();
            if (old != null) for (int i = 0; i < HIST_LEN; i++) hist24[i] -= old[i];
        }
    }

    private static String minuteKey() {
        Instant now = Instant.now();
        long epochSec = now.getEpochSecond();
        return MINUTE_FMT.format(Instant.ofEpochSecond(epochSec - (epochSec % 60)));
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // ── Inner: toplam dakika kovası (mini-grafikler için, değişmedi) ────────────

    private static final class MinuteBucket {
        final String     key;
        final AtomicLong count  = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong sumMs  = new AtomicLong();
        final AtomicLong maxMs  = new AtomicLong();
        /** Dakikanın gecikme histogramı (2026-09-28) — dakika p95'i ve 24 saatlik kayan p95/p99 için. */
        final AtomicLong[] hist = new AtomicLong[HIST_LEN];

        MinuteBucket(String key) {
            this.key = key;
            for (int i = 0; i < HIST_LEN; i++) hist[i] = new AtomicLong();
        }

        void add(int status, long ms) {
            count.incrementAndGet();
            if (status >= 400) errors.incrementAndGet();
            sumMs.addAndGet(ms);
            long prev;
            do { prev = maxMs.get(); } while (ms > prev && !maxMs.compareAndSet(prev, ms));
            hist[histIndex(ms)].incrementAndGet();
        }

        long[] histCopy() {
            long[] h = new long[HIST_LEN];
            for (int i = 0; i < HIST_LEN; i++) h[i] = hist[i].get();
            return h;
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
            // Ek (2026-09-28, geriye uyumlu): dakikanın p95'i — boş dakikada avg_ms gibi 0.
            m.put("p95_ms", c > 0 ? HttpMetricsQueryService.percentile(histCopy(), 0.95, maxMs.get()) : 0L);
            return m;
        }
    }

    // ── Inner: endpoint bazlı dakika kovası (histogramlı, kalıcı seri) ──────────

    private static final class EndpointBucket {
        final AtomicLong count  = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong sumMs  = new AtomicLong();
        final AtomicLong maxMs  = new AtomicLong();
        final AtomicLong minMs  = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong[] hist;
        /** Durum kodu sayımı (2026-09-28): kod → adet; anahtar {@link #statusKey} ile ≤ 501 ayrı değer, pratikte 1–4. */
        final ConcurrentHashMap<Integer, LongAdder> codes = new ConcurrentHashMap<>(4);

        EndpointBucket() {
            hist = new AtomicLong[HIST_LEN];
            for (int i = 0; i < HIST_LEN; i++) hist[i] = new AtomicLong();
        }

        void add(int status, long ms) {
            count.incrementAndGet();
            if (status >= 400) errors.incrementAndGet();
            sumMs.addAndGet(ms);
            long p;
            do { p = maxMs.get(); } while (ms > p && !maxMs.compareAndSet(p, ms));
            do { p = minMs.get(); } while (ms < p && !minMs.compareAndSet(p, ms));
            hist[histIndex(ms)].incrementAndGet();
            codes.computeIfAbsent(statusKey(status), k -> new LongAdder()).increment();
        }

        /** "200:118,404:2" — koda göre sıralı (deterministik; aynı dakika iki kez yazılırsa aynı metin). */
        String codesCsv() {
            StringBuilder sb = new StringBuilder();
            for (Integer code : new java.util.TreeSet<>(codes.keySet())) {
                long n = codes.get(code).sum();
                if (n <= 0) continue;
                if (!sb.isEmpty()) sb.append(',');
                sb.append(code).append(':').append(n);
            }
            return sb.toString();
        }

        HttpMetricMinute toEntity(String bucketMinute, String endpoint) {
            HttpMetricMinute m = new HttpMetricMinute();
            m.setBucketMinute(bucketMinute);
            m.setEndpoint(endpoint.length() > 200 ? endpoint.substring(0, 200) : endpoint);
            long c = count.get();
            m.setReqCount(c);
            m.setErrorCount(errors.get());
            m.setSumMs(sumMs.get());
            m.setMaxMs(maxMs.get());
            m.setMinMs(c > 0 ? minMs.get() : 0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HIST_LEN; i++) { if (i > 0) sb.append(','); sb.append(hist[i].get()); }
            m.setHist(sb.toString());
            String csv = codesCsv();
            m.setStatusCodes(csv.isEmpty() ? null : csv);
            return m;
        }
    }

    private static final class PendingMinute {
        final String key;
        final Map<String, EndpointBucket> endpoints;
        PendingMinute(String key, Map<String, EndpointBucket> endpoints) { this.key = key; this.endpoints = endpoints; }
    }
}
