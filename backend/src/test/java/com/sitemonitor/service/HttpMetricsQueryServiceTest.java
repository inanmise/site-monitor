package com.sitemonitor.service;

import com.sitemonitor.model.HttpMetricMinute;
import com.sitemonitor.repository.HttpMetricMinuteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(HttpMetricsQueryService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class HttpMetricsQueryServiceTest {

    @Autowired HttpMetricsQueryService service;
    @Autowired HttpMetricMinuteRepository repo;

    // ── Histogram yardımcıları (saf) ────────────────────────────────────────────

    private static String histAt(long ms, long count) {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(ms)] = count;
        return join(h);
    }

    private static String join(long[] h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length; i++) { if (i > 0) sb.append(','); sb.append(h[i]); }
        return sb.toString();
    }

    @Test
    @DisplayName("percentile: tek kovada p50<=p95<=p99, kovanın [alt,üst] sınırı içinde")
    void percentile_singleBucket() {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(75)] = 100;   // hepsi 75ms kovasında (alt 50, üst 75)
        long p50 = HttpMetricsQueryService.percentile(h, 0.50, 75);
        long p95 = HttpMetricsQueryService.percentile(h, 0.95, 75);
        long p99 = HttpMetricsQueryService.percentile(h, 0.99, 75);
        assertThat(p50).isLessThanOrEqualTo(p95);
        assertThat(p95).isLessThanOrEqualTo(p99);
        assertThat(p95).isBetween(50L, 75L);
    }

    @Test
    @DisplayName("percentile: çok-kovalı dağılımda p95 üst kuyruğa düşer")
    void percentile_multiBucket() {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(10)]   = 90;   // %90 hızlı (~10ms)
        h[HttpMetricsService.histIndex(1000)] = 10;   // %10 yavaş (~1000ms, alt 750 üst 1000)
        long p50 = HttpMetricsQueryService.percentile(h, 0.50, 1000);
        long p95 = HttpMetricsQueryService.percentile(h, 0.95, 1000);
        assertThat(p50).isLessThanOrEqualTo(20L);      // ortanca hızlı kovada
        assertThat(p95).isBetween(750L, 1000L);        // p95 yavaş kuyrukta
    }

    @Test
    @DisplayName("percentile: boş histogram → 0")
    void percentile_empty() {
        assertThat(HttpMetricsQueryService.percentile(new long[HttpMetricsService.HIST_LEN], 0.95, 0)).isZero();
    }

    @Test
    @DisplayName("bucketKey: UTC → Europe/Istanbul yerel; gece-yarısı bir sonraki güne kayar")
    void bucketKey_istanbulShift() {
        // 22:30 UTC = 01:30 IST (ertesi gün)
        assertThat(HttpMetricsQueryService.bucketKey("2026-06-18T22:30:00", "minute")).isEqualTo("2026-06-19T01:30:00");
        assertThat(HttpMetricsQueryService.bucketKey("2026-06-18T22:30:00", "hour")).isEqualTo("2026-06-19T01:00:00");
    }

    @Test
    @DisplayName("resolveGranularity: >24 saat → hour, değilse minute (24s dahil dakika)")
    void resolveGranularity_auto() {
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-18T06:00:00")).isEqualTo("minute"); // 6s
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-19T00:00:00")).isEqualTo("minute"); // 24s → dakika
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-20T00:00:00")).isEqualTo("hour");   // 48s → saat
        assertThat(HttpMetricsQueryService.resolveGranularity("hour", "2026-06-18T00:00:00", "2026-06-18T01:00:00")).isEqualTo("hour");
    }

    // ── series / endpoints (DB) ─────────────────────────────────────────────────

    private HttpMetricMinute row(String bucketUtc, String endpoint, long count, long errors, long sumMs, long maxMs, long histMs) {
        HttpMetricMinute m = new HttpMetricMinute();
        m.setBucketMinute(bucketUtc); m.setEndpoint(endpoint);
        m.setReqCount(count); m.setErrorCount(errors); m.setSumMs(sumMs); m.setMaxMs(maxMs); m.setMinMs(1);
        m.setHist(histAt(histMs, count));
        return m;
    }

    @Test
    @DisplayName("series: minute granularite + IST kovalar + özet (count/avg/p95)")
    @SuppressWarnings("unchecked")
    void series_minute() {
        // aynı IST saatinde iki dakika + bir endpoint
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 1, 400, 80, 40));   // IST 13:00
        repo.save(row("2026-06-18T10:01:00", "GET /api/x", 20, 0, 600, 90, 30));   // IST 13:01
        repo.save(row("2026-06-18T10:00:00", "GET /api/y", 5, 0, 100, 20, 20));    // başka endpoint (filtrelenir)

        Map<String, Object> res = service.series("2026-06-18T09:55:00", "2026-06-18T10:10:00", "GET /api/x", "minute");
        var data = (List<Map<String, Object>>) res.get("data");
        assertThat(data).hasSize(16);   // IST 12:55..13:10 = 16 dakika kovası (boşlar dahil)
        var b1300 = data.stream().filter(p -> "2026-06-18T13:00:00".equals(p.get("ts"))).findFirst().orElseThrow();
        var b1301 = data.stream().filter(p -> "2026-06-18T13:01:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) b1300.get("count")).longValue()).isEqualTo(10L);
        assertThat(((Number) b1301.get("count")).longValue()).isEqualTo(20L);
        // boş kova (13:05): count=0, süreler null → grafik boşlukta çizgiyi birleştirmez
        var empty = data.stream().filter(p -> "2026-06-18T13:05:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) empty.get("count")).longValue()).isZero();
        assertThat(empty.get("avg_ms")).isNull();
        assertThat(empty.get("p95_ms")).isNull();

        var summary = (Map<String, Object>) res.get("summary");
        assertThat(((Number) summary.get("total")).longValue()).isEqualTo(30L);   // yalnız x
        assertThat(((Number) summary.get("errors")).longValue()).isEqualTo(1L);
        assertThat(((Number) summary.get("avg_ms")).longValue()).isEqualTo(1000L / 30L);
        assertThat(((Number) summary.get("p95_ms")).longValue()).isGreaterThan(0L);
        assertThat(res.get("granularity")).isEqualTo("minute");
    }

    @Test
    @DisplayName("series: endpoint boş → tüm endpoint'ler birlikte; hour granularite tek kovada toplar")
    @SuppressWarnings("unchecked")
    void series_allEndpoints_hour() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 0, 400, 80, 40));
        repo.save(row("2026-06-18T10:30:00", "GET /api/y", 20, 2, 600, 90, 30));   // aynı IST saati (13:xx)

        Map<String, Object> res = service.series("2026-06-18T09:00:00", "2026-06-18T11:00:00", null, "hour");
        var data = (List<Map<String, Object>>) res.get("data");
        assertThat(data).hasSize(3);                                  // IST 12:00,13:00,14:00 (boşlar dahil)
        var b13 = data.stream().filter(p -> "2026-06-18T13:00:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) b13.get("count")).longValue()).isEqualTo(30L);  // x + y
        assertThat(((Number) b13.get("errors")).longValue()).isEqualTo(2L);
        // boş saat (12:00) → count=0
        var empty12 = data.stream().filter(p -> "2026-06-18T12:00:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) empty12.get("count")).longValue()).isZero();
    }

    @Test
    @DisplayName("endpoints: aralıkta endpoint başına toplam (count'a göre azalan)")
    @SuppressWarnings("unchecked")
    void endpoints_list() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 30, 3, 900, 80, 40));
        repo.save(row("2026-06-18T10:01:00", "GET /api/y", 5, 0, 100, 20, 20));

        List<Map<String, Object>> eps = service.endpoints("2026-06-18T00:00:00", "2026-06-18T23:59:59");
        assertThat(eps).hasSize(2);
        assertThat(eps.get(0).get("endpoint")).isEqualTo("GET /api/x");   // en çok istek başta
        assertThat(((Number) eps.get(0).get("count")).longValue()).isEqualTo(30L);
        assertThat(((Number) eps.get(0).get("avg_ms")).longValue()).isEqualTo(30L);
        assertThat(((Number) eps.get(0).get("error_rate_pct")).doubleValue()).isEqualTo(10.0);
    }
}
