package com.sitemonitor.service;

import com.sitemonitor.model.HttpMetricMinute;
import com.sitemonitor.repository.HttpMetricMinuteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kalıcı HTTP metrik satırlarını ({@link HttpMetricMinute}) zaman-serisi + özet olarak sorgular.
 * Dakika satırları Europe/Istanbul yerel dk/saat kovalarına gruplanır; p50/p95/p99 her kovadaki
 * gecikme histogramları birleştirilerek (Prometheus histogram_quantile yaklaşımı) hesaplanır.
 */
@Service
@RequiredArgsConstructor
public class HttpMetricsQueryService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private final HttpMetricMinuteRepository repo;

    /** Endpoint listesi (seçici + özet): aralıkta endpoint başına toplamlar. */
    public List<Map<String, Object>> endpoints(String from, String to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : repo.aggregateByEndpoint(from, to)) {
            long count = ((Number) r[1]).longValue();
            long errors = ((Number) r[2]).longValue();
            long sumMs = ((Number) r[3]).longValue();
            long maxMs = r[4] != null ? ((Number) r[4]).longValue() : 0L;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", r[0]);
            m.put("count", count);
            m.put("errors", errors);
            m.put("error_rate_pct", count > 0 ? Math.round(errors * 1000.0 / count) / 10.0 : 0.0);
            m.put("avg_ms", count > 0 ? sumMs / count : 0L);
            m.put("max_ms", maxMs);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return out;
    }

    /** Zaman-serisi + aralık özeti. endpoint null/boş → tüm endpoint'ler birlikte ("Tümü"). */
    public Map<String, Object> series(String from, String to, String endpoint, String granularity) {
        String gran = resolveGranularity(granularity, from, to);
        List<HttpMetricMinute> rows = (endpoint != null && !endpoint.isBlank())
                ? repo.findByEndpointAndBucketMinuteBetween(endpoint, from, to)
                : repo.findByBucketMinuteBetween(from, to);

        TreeMap<String, Agg> buckets = new TreeMap<>();
        Agg overall = new Agg();
        for (HttpMetricMinute m : rows) {
            String key = bucketKey(m.getBucketMinute(), gran);
            buckets.computeIfAbsent(key, k -> new Agg()).add(m);
            overall.add(m);
        }

        // Tüm zaman eksenini doldur: istek GELMEYEN kovalar count=0 + null süreler → grafik boşlukları çizgiyle
        // BİRLEŞTİRMEZ (latency çizgileri null'da kırılır; istek alanı 0'a iner → sahte süreklilik olmaz).
        List<Map<String, Object>> data = new ArrayList<>();
        for (String key : bucketKeysInRange(from, to, gran)) {
            Agg a = buckets.get(key);
            data.add(a != null ? a.toPoint(key) : emptyPoint(key));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", overall.count);
        summary.put("errors", overall.errors);
        summary.put("error_rate_pct", overall.count > 0 ? Math.round(overall.errors * 1000.0 / overall.count) / 10.0 : 0.0);
        summary.put("avg_ms", overall.count > 0 ? overall.sumMs / overall.count : 0L);
        summary.put("max_ms", overall.maxMs);
        summary.put("p50_ms", percentile(overall.hist, 0.50, overall.maxMs));
        summary.put("p95_ms", percentile(overall.hist, 0.95, overall.maxMs));
        summary.put("p99_ms", percentile(overall.hist, 0.99, overall.maxMs));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("granularity", gran);
        out.put("data", data);
        out.put("summary", summary);
        return out;
    }

    // ── Granularite + IST kova anahtarı ─────────────────────────────────────────

    static String resolveGranularity(String gran, String from, String to) {
        if ("hour".equalsIgnoreCase(gran)) return "hour";
        if ("minute".equalsIgnoreCase(gran)) return "minute";
        // otomatik: aralık > 24 saat → saat, değilse dakika (Grafana benzeri ince çözünürlük; 24s = dakika).
        try {
            LocalDateTime f = LocalDateTime.parse(from.substring(0, Math.min(19, from.length())));
            LocalDateTime t = LocalDateTime.parse(to.substring(0, Math.min(19, to.length())));
            return java.time.Duration.between(f, t).toHours() > 24 ? "hour" : "minute";
        } catch (Exception e) {
            return "minute";
        }
    }

    /** UTC dakika string'ini Europe/Istanbul yerel dk/saat kova anahtarına çevirir (sıralanabilir). */
    static String bucketKey(String utcMinute, String gran) {
        try {
            LocalDateTime utc = LocalDateTime.parse(utcMinute.substring(0, Math.min(19, utcMinute.length())));
            ZonedDateTime ist = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(IST);
            String d = ist.toLocalDate().toString();
            if ("hour".equals(gran)) return d + "T" + String.format("%02d:00:00", ist.getHour());
            return d + "T" + String.format("%02d:%02d:00", ist.getHour(), ist.getMinute());
        } catch (Exception e) {
            return utcMinute;
        }
    }

    /** [from,to] (UTC ISO) aralığındaki TÜM kova anahtarları (IST yerel, granülarite adımıyla) — boş kovalar dahil. */
    static List<String> bucketKeysInRange(String fromUtc, String toUtc, String gran) {
        List<String> keys = new ArrayList<>();
        try {
            boolean hour = "hour".equals(gran);
            java.time.temporal.ChronoUnit step = hour ? java.time.temporal.ChronoUnit.HOURS : java.time.temporal.ChronoUnit.MINUTES;
            ZonedDateTime f = LocalDateTime.parse(fromUtc.substring(0, Math.min(19, fromUtc.length())))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(IST).truncatedTo(step);
            ZonedDateTime t = LocalDateTime.parse(toUtc.substring(0, Math.min(19, toUtc.length())))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(IST);
            int guard = 0;
            for (ZonedDateTime cur = f; !cur.isAfter(t) && guard < 5000; guard++, cur = cur.plus(1, step)) {
                String d = cur.toLocalDate().toString();
                keys.add(hour ? d + "T" + String.format("%02d:00:00", cur.getHour())
                              : d + "T" + String.format("%02d:%02d:00", cur.getHour(), cur.getMinute()));
            }
        } catch (Exception ignore) { }
        return keys;
    }

    /** İstek gelmeyen kova — count/errors 0, süreler null (grafik boşlukta çizgiyi birleştirmesin). */
    private static Map<String, Object> emptyPoint(String ts) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ts", ts);
        p.put("count", 0L);
        p.put("errors", 0L);
        p.put("avg_ms", null);
        p.put("max_ms", null);
        p.put("min_ms", null);
        p.put("p50_ms", null);
        p.put("p95_ms", null);
        p.put("p99_ms", null);
        return p;
    }

    // ── Histogram yardımcıları (test edilebilir) ────────────────────────────────

    static long[] parseHist(String csv) {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        if (csv == null || csv.isBlank()) return h;
        String[] parts = csv.split(",");
        for (int i = 0; i < h.length && i < parts.length; i++) {
            try { h[i] = Long.parseLong(parts[i].trim()); } catch (Exception ignore) { }
        }
        return h;
    }

    /** Birleşik histogramdan kantil (ms) — kümülatif sayım + kova içi lineer interpolasyon. */
    static long percentile(long[] hist, double q, long maxMs) {
        long total = 0;
        for (long h : hist) total += h;
        if (total == 0) return 0;
        double rank = q * total;
        long cum = 0;
        long[] bounds = HttpMetricsService.HISTOGRAM_BOUNDS;
        for (int i = 0; i < hist.length; i++) {
            long c = hist[i];
            if (c == 0) continue;
            if (cum + c >= rank) {
                long lower = i == 0 ? 0 : bounds[i - 1];
                long upper = i < bounds.length ? bounds[i] : Math.max(maxMs, bounds[bounds.length - 1]);
                double frac = c > 0 ? (rank - cum) / c : 0;            // 0..1 kova içinde
                return Math.round(lower + (upper - lower) * Math.max(0, Math.min(1, frac)));
            }
            cum += c;
        }
        return maxMs;
    }

    // ── Kova akümülatörü ────────────────────────────────────────────────────────

    private static final class Agg {
        long count, errors, sumMs, maxMs, minMs = Long.MAX_VALUE;
        final long[] hist = new long[HttpMetricsService.HIST_LEN];

        void add(HttpMetricMinute m) {
            count += m.getReqCount();
            errors += m.getErrorCount();
            sumMs += m.getSumMs();
            if (m.getMaxMs() > maxMs) maxMs = m.getMaxMs();
            if (m.getReqCount() > 0 && m.getMinMs() < minMs) minMs = m.getMinMs();
            long[] h = parseHist(m.getHist());
            for (int i = 0; i < hist.length; i++) hist[i] += h[i];
        }

        Map<String, Object> toPoint(String ts) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ts", ts);
            p.put("count", count);
            p.put("errors", errors);
            p.put("avg_ms", count > 0 ? sumMs / count : 0L);
            p.put("max_ms", maxMs);
            p.put("min_ms", count > 0 ? minMs : 0L);
            p.put("p50_ms", percentile(hist, 0.50, maxMs));
            p.put("p95_ms", percentile(hist, 0.95, maxMs));
            p.put("p99_ms", percentile(hist, 0.99, maxMs));
            return p;
        }
    }
}
